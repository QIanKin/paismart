package com.yizhaoqi.smartpai.service.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.config.AsrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * DashScope paraformer-v2 异步音频转写适配。实现 {@link AsrService}。
 *
 * <p>调用流程：
 * <ol>
 *   <li>{@link #submitTask(String)} POST {@code /api/v1/services/audio/asr/transcription} +
 *       {@code X-DashScope-Async: enable} → 拿 task_id</li>
 *   <li>{@link #pollTask(String)} 周期性 GET {@code /api/v1/tasks/{task_id}} → SUCCEEDED 后拿 transcription_url</li>
 *   <li>{@link #fetchTranscriptionResult(String)} GET transcription_url 下载具体 JSON，提取 transcripts[*].text</li>
 *   <li>{@link #transcribe(Path, String)} 一站式封装上面三步（仅依赖 publicUrl，本地文件被忽略）</li>
 * </ol>
 *
 * <p>错误归一化（以 {@link AsrService.AsrException#code()} 暴露给调用方）：
 * <ul>
 *   <li>{@code provider_disabled}：smartpai.asr.enabled=false / provider 不匹配 / apiKey 缺失</li>
 *   <li>{@code submit_failed}：任务提交 4xx/5xx</li>
 *   <li>{@code poll_failed}：轮询 4xx/5xx</li>
 *   <li>{@code asr_failed}：任务 task_status=FAILED 或 results 全失败</li>
 *   <li>{@code asr_timeout}：超过 maxPollSeconds 仍未 SUCCEEDED</li>
 *   <li>{@code result_fetch_failed}：transcription_url 下载失败</li>
 * </ul>
 */
@Service
public class DashScopeAsrService implements AsrService {

    public static final String PROVIDER_ID = "dashscope-paraformer-v2";

    private static final Logger log = LoggerFactory.getLogger(DashScopeAsrService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AsrProperties props;
    private final String fallbackEmbeddingKey;
    private final HttpClient httpClient;

    public DashScopeAsrService(AsrProperties props,
                               @Value("${embedding.api.key:}") String fallbackEmbeddingKey) {
        this.props = props;
        this.fallbackEmbeddingKey = fallbackEmbeddingKey == null ? "" : fallbackEmbeddingKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, props.getHttpTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String provider() { return PROVIDER_ID; }

    /**
     * 是否可用：开关 + provider 匹配 + 拼出的最终 key 非空。
     */
    @Override
    public boolean configured() {
        if (!props.isEnabled()) return false;
        if (!PROVIDER_ID.equalsIgnoreCase(props.getProvider())) return false;
        return !resolvedApiKey().isBlank();
    }

    /**
     * 一站式：提交任务 → 轮询 → 拿 transcription_url → 下载结果 → 拼 transcript。
     * DashScope 只能用 URL，{@code localFile} 被忽略；调用方需保证 {@code publicUrl} 公网可达。
     */
    @Override
    public AsrResult transcribe(Path localFile, String publicUrl) throws AsrException {
        if (!configured()) {
            throw new AsrException("provider_disabled",
                    "DashScope ASR 未启用或 key 缺失（smartpai.asr.api-key / DASHSCOPE_ASR_API_KEY / EMBEDDING_API_KEY）");
        }
        if (publicUrl == null || publicUrl.isBlank()) {
            throw new AsrException("bad_input",
                    "DashScope ASR 必须用公网可达的音频 URL（请检查 MINIO_PUBLIC_URL 是否能被外网访问）");
        }
        String taskId = submitTask(publicUrl);
        log.info("DashScope ASR 任务已提交 task_id={} audioUrl={}", taskId, mask(publicUrl));
        TaskOutcome outcome = pollTask(taskId);
        if (!"SUCCEEDED".equalsIgnoreCase(outcome.status)) {
            throw new AsrException("asr_failed",
                    "ASR 任务终态 " + outcome.status + (outcome.errorMessage == null ? "" : "：" + outcome.errorMessage));
        }
        if (outcome.transcriptionUrls.isEmpty()) {
            throw new AsrException("asr_failed", "ASR 任务成功但未返回 transcription_url");
        }
        StringBuilder textBuilder = new StringBuilder();
        List<Sentence> sentences = new ArrayList<>();
        long totalDurationMs = 0;
        String detectedLanguages = null;
        for (String url : outcome.transcriptionUrls) {
            JsonNode result = fetchTranscriptionResult(url);
            JsonNode transcripts = result.path("transcripts");
            if (transcripts.isArray()) {
                for (JsonNode tr : transcripts) {
                    String text = tr.path("text").asText("");
                    if (!text.isBlank()) {
                        if (textBuilder.length() > 0) textBuilder.append('\n');
                        textBuilder.append(text);
                    }
                    long contentMs = tr.path("content_duration_in_milliseconds").asLong(0);
                    totalDurationMs = Math.max(totalDurationMs, contentMs);
                    JsonNode arr = tr.path("sentences");
                    if (arr.isArray()) {
                        for (JsonNode s : arr) {
                            sentences.add(new Sentence(
                                    s.path("begin_time").asLong(0),
                                    s.path("end_time").asLong(0),
                                    s.path("text").asText(""),
                                    s.path("speaker_id").isMissingNode() ? null : s.path("speaker_id").asText(null)
                            ));
                        }
                    }
                }
            }
            JsonNode propsNode = result.path("properties");
            if (propsNode.has("audio_format")) {
                long ms = propsNode.path("original_duration_in_milliseconds").asLong(0);
                if (ms > 0 && totalDurationMs == 0) totalDurationMs = ms;
            }
            JsonNode lang = result.path("language");
            if (!lang.isMissingNode() && !lang.isNull()) detectedLanguages = lang.asText(null);
        }
        return new AsrResult(textBuilder.toString().trim(), sentences, totalDurationMs, detectedLanguages, taskId);
    }

    /**
     * POST 提交任务，返回 task_id。
     */
    public String submitTask(String audioUrl) throws AsrException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", props.getModel());
        ObjectNode input = body.putObject("input");
        ArrayNode files = input.putArray("file_urls");
        files.add(audioUrl);
        ObjectNode parameters = body.putObject("parameters");
        parameters.putArray("channel_id").add(0);
        ArrayNode hints = parameters.putArray("language_hints");
        for (String h : splitHints(props.getLanguageHints())) hints.add(h);
        if (props.isPunctuation()) parameters.put("enable_punctuation_prediction", true);
        if (props.isItn()) parameters.put("enable_inverse_text_normalization", true);

        String url = joinUrl(props.getBaseUrl(), "/api/v1/services/audio/asr/transcription");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(props.getHttpTimeoutSeconds()))
                .header("Authorization", "Bearer " + resolvedApiKey())
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode parsed = parseJson(resp.body());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new AsrException("submit_failed",
                        "DashScope 提交任务失败 HTTP=" + resp.statusCode()
                                + " body=" + truncate(resp.body(), 240));
            }
            String taskId = parsed.path("output").path("task_id").asText("");
            if (taskId.isBlank()) {
                throw new AsrException("submit_failed",
                        "DashScope 返回 200 但缺 task_id：" + truncate(resp.body(), 240));
            }
            return taskId;
        } catch (IOException ioe) {
            throw new AsrException("network", "DashScope 提交任务 IO: " + ioe.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AsrException("interrupted", ie.getMessage());
        }
    }

    /**
     * 轮询直到 SUCCEEDED / FAILED / 超时。
     */
    public TaskOutcome pollTask(String taskId) throws AsrException {
        long deadline = System.currentTimeMillis() + props.getMaxPollSeconds() * 1000L;
        long interval = Math.max(1L, props.getPollIntervalSeconds()) * 1000L;
        String url = joinUrl(props.getBaseUrl(), "/api/v1/tasks/" + taskId);

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AsrException("interrupted", "轮询线程被打断");
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getHttpTimeoutSeconds()))
                    .header("Authorization", "Bearer " + resolvedApiKey())
                    .GET()
                    .build();
            try {
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonNode parsed = parseJson(resp.body());
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    throw new AsrException("poll_failed",
                            "DashScope 轮询失败 HTTP=" + resp.statusCode()
                                    + " body=" + truncate(resp.body(), 240));
                }
                String status = parsed.path("output").path("task_status").asText("");
                if ("SUCCEEDED".equalsIgnoreCase(status)) {
                    List<String> urls = new ArrayList<>();
                    JsonNode results = parsed.path("output").path("results");
                    if (results.isArray()) {
                        for (JsonNode r : results) {
                            String tu = r.path("transcription_url").asText("");
                            if (!tu.isBlank()) urls.add(tu);
                        }
                    }
                    return new TaskOutcome(status, urls, null);
                }
                if ("FAILED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status)) {
                    String message = parsed.path("output").path("message").asText(
                            parsed.path("message").asText("DashScope 任务终态 " + status));
                    return new TaskOutcome(status, List.of(), message);
                }
            } catch (IOException ioe) {
                throw new AsrException("network", "DashScope 轮询 IO: " + ioe.getMessage());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AsrException("interrupted", ie.getMessage());
            }
        }
        throw new AsrException("asr_timeout",
                "DashScope ASR 等待超 " + props.getMaxPollSeconds() + "s 仍未 SUCCEEDED");
    }

    /**
     * 下载 transcription_url 拿具体识别结果 JSON。
     */
    public JsonNode fetchTranscriptionResult(String transcriptionUrl) throws AsrException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(transcriptionUrl))
                .timeout(Duration.ofSeconds(props.getHttpTimeoutSeconds()))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new AsrException("result_fetch_failed",
                        "下载 transcription 失败 HTTP=" + resp.statusCode());
            }
            return parseJson(resp.body());
        } catch (IOException ioe) {
            throw new AsrException("network", "下载 transcription IO: " + ioe.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AsrException("interrupted", ie.getMessage());
        }
    }

    // --------------- helpers ---------------

    private String resolvedApiKey() {
        String k = props.getApiKey();
        if (k != null && !k.isBlank()) return k.trim();
        return fallbackEmbeddingKey == null ? "" : fallbackEmbeddingKey.trim();
    }

    private static List<String> splitHints(String s) {
        if (s == null || s.isBlank()) return List.of("zh", "en");
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(x -> !x.isBlank())
                .toList();
    }

    private static JsonNode parseJson(String body) throws AsrException {
        if (body == null) return MAPPER.createObjectNode();
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AsrException("parse_error", "DashScope 返回非 JSON: " + truncate(body, 200));
        }
    }

    private static String joinUrl(String base, String path) {
        if (base.endsWith("/") && path.startsWith("/")) return base.substring(0, base.length() - 1) + path;
        if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
        return base + path;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String mask(String url) {
        if (url == null) return "";
        int qm = url.indexOf('?');
        return qm > 0 ? url.substring(0, qm) + "?<sig...>" : url;
    }

    // --------------- DTOs ---------------

    /** ASR 任务一次轮询结果。 */
    public static final class TaskOutcome {
        public final String status;
        public final List<String> transcriptionUrls;
        public final String errorMessage;

        public TaskOutcome(String status, List<String> transcriptionUrls, String errorMessage) {
            this.status = status;
            this.transcriptionUrls = transcriptionUrls;
            this.errorMessage = errorMessage;
        }
    }
}
