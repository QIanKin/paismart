package com.yizhaoqi.smartpai.service.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AsrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Whisper 协议兼容的本地/自建 ASR 适配。
 *
 * <p>支持三种模式（按 {@code smartpai.asr.whisper.mode} 切换）：
 * <ul>
 *   <li><b>local-faster-whisper</b>（默认）：同容器 {@code python3 + faster-whisper} 子进程转写，
 *       不依赖外网 Docker 镜像，与「本地 whisper」诉求一致；</li>
 *   <li><b>asr-webservice</b>：POST {@code /asr?...}，multipart 字段 {@code audio_file}；</li>
 *   <li><b>openai-compat</b>：POST {@code /v1/audio/transcriptions}，multipart 字段 {@code file}。</li>
 * </ul>
 *
 * <p>区别于 DashScope：直接 multipart 上传 mp3 binary，不需要音频公网可达，
 * MinIO 私有也无所谓 —— 这正是用户选"本地 whisper"想要的。
 *
 * <p>错误码：
 * <ul>
 *   <li>{@code provider_disabled}：开关关 / provider 不匹配 / baseUrl 空</li>
 *   <li>{@code bad_input}：本地文件不存在 / 超大小上限</li>
 *   <li>{@code submit_failed}：HTTP 4xx/5xx</li>
 *   <li>{@code asr_failed}：响应缺 text 字段</li>
 *   <li>{@code asr_timeout}：超时（请求阶段已包含轮询全部时间）</li>
 *   <li>{@code parse_error}：返回非预期 JSON</li>
 * </ul>
 */
@Service
public class WhisperCompatibleAsrService implements AsrService {

    public static final String PROVIDER_ID = "whisper-compatible";

    private static final Logger log = LoggerFactory.getLogger(WhisperCompatibleAsrService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AsrProperties props;
    private final HttpClient httpClient;

    public WhisperCompatibleAsrService(AsrProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String provider() { return PROVIDER_ID; }

    @Override
    public boolean configured() {
        if (!props.isEnabled()) return false;
        if (!PROVIDER_ID.equalsIgnoreCase(props.getProvider())) return false;
        AsrProperties.Whisper w = props.getWhisper();
        if (w == null) return false;
        if (isLocalMode(w)) {
            String p = w.getLocalScriptPath();
            if (p == null || p.isBlank()) return false;
            return Files.isRegularFile(Path.of(p));
        }
        return w.getBaseUrl() != null && !w.getBaseUrl().isBlank();
    }

    @Override
    public AsrResult transcribe(Path localFile, String publicUrl) throws AsrException {
        if (!configured()) {
            AsrProperties.Whisper w0 = props.getWhisper();
            String hint = (w0 != null && isLocalMode(w0))
                    ? "检查 smartpai.asr.whisper.local-script-path 指向的脚本是否在镜像内（默认 /app/scripts/local_whisper_transcribe.py）"
                    : "检查 smartpai.asr.provider=whisper-compatible 与 smartpai.asr.whisper.base-url";
            throw new AsrException("provider_disabled", "Whisper ASR 未就绪：" + hint);
        }
        if (localFile == null || !Files.exists(localFile)) {
            throw new AsrException("bad_input", "Whisper 本地音频文件不存在：" + localFile);
        }
        AsrProperties.Whisper w = props.getWhisper();
        long size;
        try {
            size = Files.size(localFile);
        } catch (IOException e) {
            throw new AsrException("bad_input", "读取本地音频 size 失败: " + e.getMessage());
        }
        if (size <= 0) {
            throw new AsrException("bad_input", "本地音频为空文件");
        }
        if (w.getMaxAudioBytes() > 0 && size > w.getMaxAudioBytes()) {
            throw new AsrException("bad_input",
                    "本地音频 " + size + "B 超出上限 " + w.getMaxAudioBytes() + "B；请压缩或加大 smartpai.asr.whisper.max-audio-bytes");
        }

        if (isLocalMode(w)) {
            return transcribeLocal(localFile, w, size);
        }

        boolean openaiCompat = "openai-compat".equalsIgnoreCase(w.getMode());
        String url = buildEndpointUrl(w, openaiCompat);
        String boundary = "----paismart-" + UUID.randomUUID().toString().replace("-", "");
        byte[] audioBytes;
        try {
            audioBytes = Files.readAllBytes(localFile);
        } catch (IOException e) {
            throw new AsrException("bad_input", "读取本地音频失败: " + e.getMessage());
        }
        byte[] body = buildMultipartBody(boundary, openaiCompat, w, localFile, audioBytes);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(30, w.getHttpTimeoutSeconds())))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (openaiCompat && w.getApiKey() != null && !w.getApiKey().isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + w.getApiKey().trim());
        }

        log.info("Whisper ASR 提交 endpoint={} mode={} size={}KB language={} task={}",
                url, w.getMode(), size / 1024, w.getLanguage(), w.getTask());
        HttpResponse<String> resp;
        try {
            resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException te) {
            throw new AsrException("asr_timeout",
                    "Whisper ASR 超时 " + w.getHttpTimeoutSeconds() + "s（CPU 跑大模型时正常，可上调 timeout 或换更小模型）");
        } catch (IOException ioe) {
            throw new AsrException("network", "Whisper ASR 网络异常: " + ioe.getMessage()
                    + "（HTTP 模式请确认 base-url 可达；本地模式请用 mode=local-faster-whisper）");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AsrException("interrupted", ie.getMessage());
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new AsrException("submit_failed",
                    "Whisper HTTP=" + resp.statusCode() + " body=" + truncate(resp.body(), 240));
        }
        return parseResponse(resp.body(), w, size);
    }

    private static boolean isLocalMode(AsrProperties.Whisper w) {
        return w.getMode() != null && "local-faster-whisper".equalsIgnoreCase(w.getMode().trim());
    }

    /**
     * 同容器 python faster-whisper，stdout 一行 JSON。
     */
    private AsrResult transcribeLocal(Path localFile, AsrProperties.Whisper w, long audioBytes) throws AsrException {
        String scriptPath = w.getLocalScriptPath();
        if (scriptPath == null || scriptPath.isBlank()) {
            scriptPath = "/app/scripts/local_whisper_transcribe.py";
        }
        Path script = Path.of(scriptPath);
        if (!Files.isRegularFile(script)) {
            throw new AsrException("provider_disabled", "本地 ASR 脚本不存在: " + script);
        }
        String model = w.getModel();
        if (model == null || model.isBlank()) {
            model = "base";
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("python3");
        cmd.add(script.toAbsolutePath().toString());
        cmd.add(localFile.toAbsolutePath().toString());
        cmd.add("--model");
        cmd.add(model);
        String task = w.getTask() == null || w.getTask().isBlank() ? "transcribe" : w.getTask();
        cmd.add("--task");
        cmd.add(task);
        if (w.getLanguage() != null && !w.getLanguage().isBlank()) {
            cmd.add("--language");
            cmd.add(w.getLanguage());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        int timeoutSec = Math.max(30, w.getHttpTimeoutSeconds());
        log.info("Whisper local ASR script={} model={} lang={} timeout={}s size={}KB",
                script.getFileName(), model, w.getLanguage(), timeoutSec, audioBytes / 1024);
        try {
            Process p = pb.start();
            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new AsrException("asr_timeout", "本地 Whisper 超时 " + timeoutSec + "s（可上调 smartpai.asr.whisper.http-timeout-seconds）");
            }
            byte[] outBytes = p.getInputStream().readAllBytes();
            byte[] errBytes = p.getErrorStream().readAllBytes();
            String stdout = new String(outBytes, StandardCharsets.UTF_8).trim();
            String stderr = new String(errBytes, StandardCharsets.UTF_8).trim();
            if (p.exitValue() != 0) {
                throw new AsrException("asr_failed",
                        "本地 Whisper 退出码=" + p.exitValue()
                                + (stderr.isBlank() ? "" : " stderr=" + truncate(stderr, 500))
                                + (stdout.isBlank() ? "" : " stdout=" + truncate(stdout, 200)));
            }
            if (stdout.isBlank()) {
                throw new AsrException("asr_failed", "本地 Whisper 无 stdout 输出");
            }
            return parseResponse(stdout, w, audioBytes);
        } catch (AsrException e) {
            throw e;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AsrException("interrupted", ie.getMessage());
        } catch (IOException ioe) {
            throw new AsrException("network", "启动本地 Whisper 子进程失败: " + ioe.getMessage());
        }
    }

    // --------------- internals ---------------

    private static String buildEndpointUrl(AsrProperties.Whisper w, boolean openaiCompat) {
        String base = w.getBaseUrl();
        String path = w.getPath();
        if (path == null || path.isBlank()) path = openaiCompat ? "/v1/audio/transcriptions" : "/asr";
        String full = joinUrl(base, path);
        if (!openaiCompat) {
            // ASR-webservice 的查询参数走 URL
            StringBuilder qs = new StringBuilder();
            if (w.getTask() != null && !w.getTask().isBlank()) {
                appendQuery(qs, "task", w.getTask());
            }
            if (w.getLanguage() != null && !w.getLanguage().isBlank()) {
                appendQuery(qs, "language", w.getLanguage());
            }
            // output=json 才能拿到 segments；text 直接是 plain text
            String output = w.getOutput() == null || w.getOutput().isBlank() ? "json" : w.getOutput();
            appendQuery(qs, "output", output);
            // 强制 word_timestamps 关闭以加速；需要时 future 再开
            appendQuery(qs, "word_timestamps", "false");
            full = full + (qs.length() > 0 ? "?" + qs : "");
        }
        return full;
    }

    private static byte[] buildMultipartBody(String boundary, boolean openaiCompat,
                                             AsrProperties.Whisper w,
                                             Path localFile, byte[] audioBytes) throws AsrException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            String fileFieldName = openaiCompat ? "file" : "audio_file";
            String contentType = guessContentType(localFile.toString());
            String fileName = localFile.getFileName() == null ? "audio.mp3" : localFile.getFileName().toString();
            // file part
            writeAscii(out, "--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"" + fileFieldName
                    + "\"; filename=\"" + fileName + "\"\r\n");
            writeAscii(out, "Content-Type: " + contentType + "\r\n\r\n");
            out.write(audioBytes);
            writeAscii(out, "\r\n");
            if (openaiCompat) {
                // OpenAI 兼容需要 model + response_format + 可选 language / temperature
                writePart(out, boundary, "model",
                        w.getModel() == null || w.getModel().isBlank()
                                ? "whisper-1" : w.getModel());
                String outFormat = w.getOutput() == null || w.getOutput().isBlank() ? "json" : w.getOutput();
                writePart(out, boundary, "response_format", outFormat);
                if (w.getLanguage() != null && !w.getLanguage().isBlank()) {
                    writePart(out, boundary, "language", w.getLanguage());
                }
                if (w.getTask() != null && "translate".equalsIgnoreCase(w.getTask())) {
                    // OpenAI 用单独 endpoint /v1/audio/translations，但 vLLM/faster-whisper 接受 task 字段
                    writePart(out, boundary, "task", "translate");
                }
            }
            writeAscii(out, "--" + boundary + "--\r\n");
            return out.toByteArray();
        } catch (IOException ioe) {
            throw new AsrException("bad_input", "构造 multipart body 失败: " + ioe.getMessage());
        }
    }

    private static void writePart(ByteArrayOutputStream out, String boundary,
                                  String name, String value) throws IOException {
        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeAscii(out, value);
        writeAscii(out, "\r\n");
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private AsrResult parseResponse(String body, AsrProperties.Whisper w, long audioBytes) throws AsrException {
        String output = w.getOutput() == null ? "json" : w.getOutput().toLowerCase();
        if ("text".equals(output) || "txt".equals(output)) {
            return new AsrResult(body == null ? "" : body.trim(), List.of(),
                    estimateDurationMs(audioBytes), w.getLanguage(), null);
        }
        // 默认 json 解析
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AsrException("parse_error",
                    "Whisper 返回非 JSON（可能误用 output=text，请把 smartpai.asr.whisper.output 设回 json）："
                            + truncate(body, 200));
        }
        // 兼容字段：onerahmet 直接 {"text":..., "segments":[...]}；
        // OpenAI 兼容也是 {"text":...}；vLLM whisper 也是 {"text":...}
        String text = root.path("text").asText("");
        if (text.isBlank()) {
            throw new AsrException("asr_failed",
                    "Whisper 响应缺 text 字段：" + truncate(body, 200));
        }
        List<Sentence> sentences = new java.util.ArrayList<>();
        long durationMs = 0;
        JsonNode segments = root.path("segments");
        if (segments.isArray()) {
            for (JsonNode s : segments) {
                long beginMs = (long) (s.path("start").asDouble(0d) * 1000);
                long endMs = (long) (s.path("end").asDouble(0d) * 1000);
                String segText = s.path("text").asText("").trim();
                if (!segText.isEmpty()) {
                    sentences.add(new Sentence(beginMs, endMs, segText, null));
                }
                if (endMs > durationMs) durationMs = endMs;
            }
        }
        if (durationMs == 0) durationMs = estimateDurationMs(audioBytes);
        String detectedLang = root.has("language") ? root.path("language").asText(null)
                : (root.has("detected_language") ? root.path("detected_language").asText(null) : null);
        return new AsrResult(text.trim(), sentences, durationMs,
                detectedLang == null ? w.getLanguage() : detectedLang, null);
    }

    /** 16kHz mono 16-bit ≈ 32KB/s；mp3 128k ≈ 16KB/s。给兜底估算。 */
    private static long estimateDurationMs(long audioBytes) {
        if (audioBytes <= 0) return 0;
        return audioBytes / 16L; // 16KB/s ≈ 1ms/16B → 1B = 1/16ms ≈ 0.0625ms；audioBytes/16 → ms
    }

    private static String guessContentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".webm")) return "audio/webm";
        return "application/octet-stream";
    }

    private static void appendQuery(StringBuilder qs, String k, String v) {
        if (qs.length() > 0) qs.append('&');
        qs.append(urlEncode(k)).append('=').append(urlEncode(v));
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
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
}
