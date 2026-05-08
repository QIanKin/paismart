package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 同步 single-shot LLM 调用工具。
 *
 * <p>{@link LlmProviderRouter} 当前只暴露流式接口（用于 chat/agent runtime 的 token-by-token 推送）。
 * 一些后端工具（如 {@code xhs_video_analyze} 的"爆款拆解"步骤）只想得到一段完整文本，不需要流式。
 * 这种场景没必要走 reactor + WebClient 的复杂链路，单独提供一个基于 {@link HttpClient} 的同步实现。
 *
 * <p>支持两种 API 风格（与 {@link ModelProviderConfigService.ActiveProviderView#apiStyle()} 对齐）：
 * <ul>
 *   <li>{@code openai-compatible}：POST {@code /chat/completions}，body 含 messages 数组；</li>
 *   <li>{@code responses}：POST {@code /responses}，body 含 input 数组（OpenAI Responses 协议）。</li>
 * </ul>
 *
 * <p>不在本服务里强行做配额扣减——工具层使用前应通过 RateLimitService 自己做并发限制。
 */
@Service
public class LlmSyncCompletionService {

    private static final Logger log = LoggerFactory.getLogger(LlmSyncCompletionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ModelProviderConfigService modelProviderConfigService;
    private final HttpClient httpClient;

    public LlmSyncCompletionService(ModelProviderConfigService modelProviderConfigService) {
        this.modelProviderConfigService = modelProviderConfigService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 阻塞直到 LLM 返回完整文本。
     *
     * @param systemPrompt   system role 文本，可空
     * @param userPrompt     user role 文本
     * @param maxTokens      最大输出 tokens（responses 风格映射到 max_output_tokens）
     * @param timeoutSeconds 单次调用最长等待秒
     * @return 模型输出文本（已去掉首尾空白）
     */
    public String complete(String systemPrompt, String userPrompt,
                           Integer maxTokens, int timeoutSeconds) throws LlmException {
        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);
        String style = provider.apiStyle();
        String baseUrl = provider.apiBaseUrl();
        String apiKey = provider.apiKey();
        String model = provider.model();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("provider_disabled", "LLM api key 缺失，请检查 model-provider 配置");
        }

        boolean responsesStyle = "responses".equalsIgnoreCase(style);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        if (maxTokens != null && maxTokens > 0) {
            body.put(responsesStyle ? "max_output_tokens" : "max_tokens", maxTokens);
        }
        if (responsesStyle) {
            ArrayNode input = body.putArray("input");
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                ObjectNode sys = input.addObject();
                sys.put("role", "system");
                sys.put("content", systemPrompt);
            }
            ObjectNode user = input.addObject();
            user.put("role", "user");
            user.put("content", userPrompt == null ? "" : userPrompt);
        } else {
            ArrayNode messages = body.putArray("messages");
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                ObjectNode sys = messages.addObject();
                sys.put("role", "system");
                sys.put("content", systemPrompt);
            }
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userPrompt == null ? "" : userPrompt);
        }

        String url = joinUrl(baseUrl, responsesStyle ? "/responses" : "/chat/completions");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new LlmException("llm_failed",
                        "LLM 同步调用失败 HTTP=" + resp.statusCode() + " body=" + truncate(resp.body(), 240));
            }
            JsonNode json;
            try {
                json = MAPPER.readTree(resp.body());
            } catch (Exception parseErr) {
                throw new LlmException("parse_error", "LLM 返回非 JSON：" + truncate(resp.body(), 200));
            }
            String text = responsesStyle ? extractResponsesText(json) : extractChatCompletionsText(json);
            if (text == null) text = "";
            return text.trim();
        } catch (IOException ioe) {
            throw new LlmException("network", "LLM 调用 IO: " + ioe.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", ie.getMessage());
        }
    }

    private static String extractChatCompletionsText(JsonNode json) {
        JsonNode choices = json.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return null;
        JsonNode message = choices.get(0).path("message");
        return message.path("content").asText("");
    }

    private static String extractResponsesText(JsonNode json) {
        // OpenAI Responses 风格：output[*].content[*].text 拼接。
        // 也可能直接出现 output_text 字段。
        if (json.has("output_text")) return json.path("output_text").asText("");
        JsonNode output = json.path("output");
        if (!output.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : output) {
            if (!"message".equalsIgnoreCase(item.path("type").asText("message"))) continue;
            JsonNode contents = item.path("content");
            if (!contents.isArray()) continue;
            for (JsonNode c : contents) {
                String t = c.path("type").asText("");
                if ("output_text".equalsIgnoreCase(t) || "text".equalsIgnoreCase(t)) {
                    sb.append(c.path("text").asText(""));
                }
            }
        }
        return sb.toString();
    }

    private static String joinUrl(String base, String path) {
        if (base == null) base = "";
        if (base.endsWith("/") && path.startsWith("/")) return base.substring(0, base.length() - 1) + path;
        if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
        return base + path;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 多模态同步调用：把若干 image_url（http(s) 直链或 data: 形式）一起送给 LLM。
     *
     * <p>之所以单独开这个 API：
     * <ul>
     *   <li>visual 视频拆解需要把 ffmpeg 抽出来的多帧一起喂给 vision-capable 模型，
     *       与单纯文本 prompt 共用一个 endpoint，但 content 必须是结构化数组；</li>
     *   <li>调用方可以传 {@code modelOverride} 临时切到 vision 专用 model（同 base_url、同 api_key），
     *       这样即使主 LLM 是文本模型，视频拆解也可以独立切到 GPT-4o / qwen-vl-plus / glm-4v。</li>
     * </ul>
     *
     * @param modelOverride 非空时覆盖 provider 默认 model；如 {@code "qwen-vl-plus"} / {@code "gpt-4o"} / {@code "glm-4v"}
     * @param imageUrls     图片 URL 列表，{@code data:} 直接内嵌 / {@code http(s)} 远程都可
     */
    public String completeMultimodal(String systemPrompt,
                                     String userText,
                                     List<String> imageUrls,
                                     String modelOverride,
                                     Integer maxTokens,
                                     int timeoutSeconds) throws LlmException {
        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);
        String style = provider.apiStyle();
        String baseUrl = provider.apiBaseUrl();
        String apiKey = provider.apiKey();
        String effectiveModel = (modelOverride == null || modelOverride.isBlank())
                ? provider.model() : modelOverride.trim();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("provider_disabled", "LLM api key 缺失，请检查 model-provider 配置");
        }

        boolean responsesStyle = "responses".equalsIgnoreCase(style);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", effectiveModel);
        body.put("stream", false);
        if (maxTokens != null && maxTokens > 0) {
            body.put(responsesStyle ? "max_output_tokens" : "max_tokens", maxTokens);
        }

        if (responsesStyle) {
            ArrayNode input = body.putArray("input");
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                ObjectNode sys = input.addObject();
                sys.put("role", "system");
                sys.put("content", systemPrompt);
            }
            ObjectNode user = input.addObject();
            user.put("role", "user");
            ArrayNode parts = user.putArray("content");
            ObjectNode txt = parts.addObject();
            txt.put("type", "input_text");
            txt.put("text", userText == null ? "" : userText);
            if (imageUrls != null) {
                for (String imgUrl : imageUrls) {
                    if (imgUrl == null || imgUrl.isBlank()) continue;
                    ObjectNode imgPart = parts.addObject();
                    imgPart.put("type", "input_image");
                    imgPart.put("image_url", imgUrl);
                }
            }
        } else {
            ArrayNode messages = body.putArray("messages");
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                ObjectNode sys = messages.addObject();
                sys.put("role", "system");
                sys.put("content", systemPrompt);
            }
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            ArrayNode parts = user.putArray("content");
            ObjectNode txt = parts.addObject();
            txt.put("type", "text");
            txt.put("text", userText == null ? "" : userText);
            if (imageUrls != null) {
                for (String imgUrl : imageUrls) {
                    if (imgUrl == null || imgUrl.isBlank()) continue;
                    ObjectNode imgPart = parts.addObject();
                    imgPart.put("type", "image_url");
                    ObjectNode imgRef = imgPart.putObject("image_url");
                    imgRef.put("url", imgUrl);
                }
            }
        }

        String url = joinUrl(baseUrl, responsesStyle ? "/responses" : "/chat/completions");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new LlmException("llm_failed",
                        "LLM 多模态调用失败 HTTP=" + resp.statusCode() + " body=" + truncate(resp.body(), 240));
            }
            JsonNode json;
            try {
                json = MAPPER.readTree(resp.body());
            } catch (Exception parseErr) {
                throw new LlmException("parse_error", "LLM 返回非 JSON：" + truncate(resp.body(), 200));
            }
            String text = responsesStyle ? extractResponsesText(json) : extractChatCompletionsText(json);
            return text == null ? "" : text.trim();
        } catch (IOException ioe) {
            throw new LlmException("network", "LLM 调用 IO: " + ioe.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", ie.getMessage());
        }
    }

    /** 与 ASR/Tikhub 异常风格对齐的 LLM 异常。 */
    public static class LlmException extends Exception {
        private final String code;

        public LlmException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }
}
