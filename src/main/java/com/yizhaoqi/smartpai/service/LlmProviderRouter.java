package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class LlmProviderRouter {

    private static final Logger logger = LoggerFactory.getLogger(LlmProviderRouter.class);

    private final AiProperties aiProperties;
    private final RateLimitService rateLimitService;
    private final UsageQuotaService usageQuotaService;
    private final ModelProviderConfigService modelProviderConfigService;
    private final ObjectMapper objectMapper;

    public LlmProviderRouter(AiProperties aiProperties,
                             RateLimitService rateLimitService,
                             UsageQuotaService usageQuotaService,
                             ModelProviderConfigService modelProviderConfigService,
                             ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.rateLimitService = rateLimitService;
        this.usageQuotaService = usageQuotaService;
        this.modelProviderConfigService = modelProviderConfigService;
        this.objectMapper = objectMapper;
    }

    public void streamResponse(String requesterId,
                               String userMessage,
                               String context,
                               List<Map<String, String>> history,
                               Consumer<String> onChunk,
                               Consumer<Throwable> onError) {
        streamResponse(requesterId, userMessage, context, history, onChunk, onError, () -> {});
    }

    /**
     * 带完成回调的流式响应。
     * onComplete 在 LLM 流正常结束时触发，用于驱动 ChatHandler 持久化最终响应——
     * 避免过去基于"2 秒无新 chunk"轮询判定完成时，思考型模型首 token 迟到导致提前 finalize 出空内容的问题。
     */
    public void streamResponse(String requesterId,
                               String userMessage,
                               String context,
                               List<Map<String, String>> history,
                               Consumer<String> onChunk,
                               Consumer<Throwable> onError,
                               Runnable onComplete) {

        ModelProviderConfigService.ActiveProviderView provider = modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);
        Map<String, Object> request = buildRequest(provider.model(), userMessage, context, history);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) request.get("messages");
        int estimatedPromptTokens = usageQuotaService.estimateChatTokens(messages);
        int maxCompletionTokens = aiProperties.getGeneration().getMaxTokens() != null
                ? aiProperties.getGeneration().getMaxTokens()
                : 2000;
        UsageQuotaService.TokenReservationBundle reservation = rateLimitService.reserveLlmUsage(
                requesterId, estimatedPromptTokens, maxCompletionTokens);
        StreamUsageTracker usageTracker = new StreamUsageTracker(reservation, estimatedPromptTokens);

        try {
            buildClient(provider)
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .subscribe(
                            chunk -> processChunk(chunk, usageTracker, onChunk),
                            error -> {
                                settleUsage(usageTracker);
                                onError.accept(error);
                            },
                            () -> {
                                settleUsage(usageTracker);
                                try {
                                    onComplete.run();
                                } catch (Exception ex) {
                                    logger.error("onComplete 回调执行失败: {}", ex.getMessage(), ex);
                                }
                            }
                    );
        } catch (Exception exception) {
            usageQuotaService.abortReservation(reservation);
            throw exception;
        }
    }

    /**
     * Agent 模式下的流式调用：
     * - 调用方自己拼 messages（已包含 system / memory / tool_result 等层）；
     * - 可选传入 tools 清单（OpenAI function calling 格式，见 {@link com.yizhaoqi.smartpai.service.tool.ToolRegistry#toOpenAiManifest}）；
     * - 回调区分 content / tool_call / usage / finish_reason。
     *
     * 和 {@link #streamResponse} 的差异：
     * - 不再在内部拼 system prompt；
     * - 不再做 "单次 RAG + 单轮聊天" 的假设，纯透传；
     * - 额度结算策略相同（同样走 RateLimitService + UsageQuotaService）。
     */
    public void streamChat(String requesterId,
                           List<Map<String, Object>> messages,
                           JsonNode toolsManifest,
                           String toolChoice,
                           LlmStreamCallback callback) {

        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);

        Map<String, Object> request = buildAgentRequest(provider.model(), messages, toolsManifest, toolChoice);

        // 估算 tokens 走已有逻辑：把 role/content 拍平成 Map<String,String> 再给 estimateChatTokens
        int estimatedPromptTokens = estimateAgentPromptTokens(messages);
        int maxCompletionTokens = aiProperties.getGeneration().getMaxTokens() != null
                ? aiProperties.getGeneration().getMaxTokens()
                : 2000;
        UsageQuotaService.TokenReservationBundle reservation = rateLimitService.reserveLlmUsage(
                requesterId, estimatedPromptTokens, maxCompletionTokens);
        AgentStreamTracker tracker = new AgentStreamTracker(reservation, estimatedPromptTokens);

        try {
            buildClient(provider)
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .subscribe(
                            chunk -> processAgentChunk(chunk, tracker, callback),
                            error -> {
                                settleAgentUsage(tracker);
                                safeCall(() -> callback.onError(error));
                            },
                            () -> {
                                settleAgentUsage(tracker);
                                safeCall(callback::onComplete);
                            }
                    );
        } catch (Exception exception) {
            usageQuotaService.abortReservation(reservation);
            throw exception;
        }
    }

    private Map<String, Object> buildAgentRequest(String model,
                                                  List<Map<String, Object>> messages,
                                                  JsonNode toolsManifest,
                                                  String toolChoice) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", true);
        request.put("stream_options", Map.of("include_usage", true));

        if (toolsManifest != null && toolsManifest.isArray() && toolsManifest.size() > 0) {
            request.put("tools", toolsManifest);
            request.put("tool_choice", toolChoice == null ? "auto" : toolChoice);
        }

        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private int estimateAgentPromptTokens(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        List<Map<String, String>> flat = new ArrayList<>(messages.size());
        for (Map<String, Object> m : messages) {
            Map<String, String> mm = new java.util.HashMap<>(2);
            Object role = m.get("role");
            Object content = m.get("content");
            mm.put("role", role == null ? "" : String.valueOf(role));
            if (content instanceof String s) {
                mm.put("content", s);
            } else if (content != null) {
                try {
                    mm.put("content", objectMapper.writeValueAsString(content));
                } catch (Exception e) {
                    mm.put("content", String.valueOf(content));
                }
            } else {
                // tool_calls / name / tool_call_id 等字段也计入，粗略序列化一下
                try {
                    mm.put("content", objectMapper.writeValueAsString(m));
                } catch (Exception e) {
                    mm.put("content", "");
                }
            }
            flat.add(mm);
        }
        return usageQuotaService.estimateChatTokens(flat);
    }

    private void processAgentChunk(String rawChunk, AgentStreamTracker tracker, LlmStreamCallback callback) {
        try {
            for (String chunk : extractPayloads(rawChunk)) {
                if ("[DONE]".equals(chunk)) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(chunk);

                JsonNode usageNode = node.path("usage");
                if (usageNode.isObject()) {
                    tracker.promptTokens = usageNode.path("prompt_tokens").asInt(tracker.promptTokens);
                    tracker.completionTokens = usageNode.path("completion_tokens").asInt(tracker.completionTokens);
                    safeCall(() -> callback.onUsage(tracker.promptTokens, tracker.completionTokens));
                }

                JsonNode choice0 = node.path("choices").path(0);
                JsonNode delta = choice0.path("delta");

                String content = delta.path("content").asText("");
                if (!content.isEmpty()) {
                    tracker.responseContent.append(content);
                    safeCall(() -> callback.onContent(content));
                }

                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (Iterator<JsonNode> it = toolCalls.elements(); it.hasNext(); ) {
                        JsonNode tc = it.next();
                        int index = tc.path("index").asInt(0);
                        String id = tc.path("id").isTextual() ? tc.path("id").asText() : null;
                        JsonNode fn = tc.path("function");
                        String name = fn.path("name").isTextual() ? fn.path("name").asText() : null;
                        String argsDelta = fn.path("arguments").isTextual() ? fn.path("arguments").asText() : "";
                        tracker.trackToolCallDelta(argsDelta);
                        safeCall(() -> callback.onToolCallDelta(index, id, name, argsDelta));
                    }
                }

                String finishReason = choice0.path("finish_reason").asText(null);
                if (finishReason != null && !finishReason.isEmpty() && !"null".equalsIgnoreCase(finishReason)) {
                    safeCall(() -> callback.onFinishReason(finishReason));
                }
            }
        } catch (Exception exception) {
            logger.error("处理 Agent 模型响应数据块失败: {}", exception.getMessage(), exception);
        }
    }

    private void settleAgentUsage(AgentStreamTracker tracker) {
        if (tracker == null || tracker.settled) return;
        tracker.settled = true;
        int actualPromptTokens = tracker.promptTokens > 0
                ? tracker.promptTokens
                : tracker.estimatedPromptTokens;
        int actualCompletionTokens = tracker.completionTokens > 0
                ? tracker.completionTokens
                : usageQuotaService.estimateTextTokens(
                        tracker.responseContent.toString() + tracker.toolCallArgsAccumulator.toString());
        usageQuotaService.settleReservation(tracker.reservation, actualPromptTokens + actualCompletionTokens);
    }

    private void safeCall(Runnable action) {
        try { action.run(); } catch (Exception ex) { logger.warn("Agent stream 回调异常: {}", ex.getMessage(), ex); }
    }

    private static final class AgentStreamTracker {
        private final UsageQuotaService.TokenReservationBundle reservation;
        private final int estimatedPromptTokens;
        private final StringBuilder responseContent = new StringBuilder();
        private final StringBuilder toolCallArgsAccumulator = new StringBuilder();
        private volatile int promptTokens;
        private volatile int completionTokens;
        private volatile boolean settled;

        private AgentStreamTracker(UsageQuotaService.TokenReservationBundle reservation, int estimatedPromptTokens) {
            this.reservation = reservation;
            this.estimatedPromptTokens = estimatedPromptTokens;
        }

        void trackToolCallDelta(String delta) {
            if (delta != null) toolCallArgsAccumulator.append(delta);
        }
    }

    private WebClient buildClient(ModelProviderConfigService.ActiveProviderView provider) {
        WebClient.Builder builder = WebClient.builder().baseUrl(provider.apiBaseUrl());
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
        }
        return builder.build();
    }

    private Map<String, Object> buildRequest(String model,
                                             String userMessage,
                                             String context,
                                             List<Map<String, String>> history) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        request.put("stream_options", Map.of("include_usage", true));

        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private List<Map<String, String>> buildMessages(String userMessage,
                                                    String context,
                                                    List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        StringBuilder sysBuilder = new StringBuilder();
        if (promptCfg.getRules() != null) {
            sysBuilder.append(promptCfg.getRules()).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");
        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            sysBuilder.append(promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）").append("\n");
        }
        sysBuilder.append(refEnd);

        messages.add(Map.of("role", "system", "content", sysBuilder.toString()));
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }

    private void processChunk(String rawChunk, StreamUsageTracker usageTracker, Consumer<String> onChunk) {
        try {
            for (String chunk : extractPayloads(rawChunk)) {
                if ("[DONE]".equals(chunk)) {
                    continue;
                }

                JsonNode node = objectMapper.readTree(chunk);
                JsonNode usageNode = node.path("usage");
                if (usageNode.isObject()) {
                    usageTracker.promptTokens = usageNode.path("prompt_tokens").asInt(usageTracker.promptTokens);
                    usageTracker.completionTokens = usageNode.path("completion_tokens").asInt(usageTracker.completionTokens);
                }

                String content = node.path("choices")
                        .path(0)
                        .path("delta")
                        .path("content")
                        .asText("");
                if (!content.isEmpty()) {
                    usageTracker.responseContent.append(content);
                    onChunk.accept(content);
                }
            }
        } catch (Exception exception) {
            logger.error("处理模型响应数据块失败: {}", exception.getMessage(), exception);
        }
    }

    private List<String> extractPayloads(String rawChunk) {
        List<String> payloads = new ArrayList<>();
        if (rawChunk == null || rawChunk.isBlank()) {
            return payloads;
        }

        String trimmed = rawChunk.trim();
        for (String line : trimmed.split("\\r?\\n")) {
            String payload = line.trim();
            if (payload.isEmpty() || payload.startsWith(":")) {
                continue;
            }
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }
            if (!payload.isEmpty()) {
                payloads.add(payload);
            }
        }

        if (payloads.isEmpty()) {
            payloads.add(trimmed);
        }
        return payloads;
    }

    private void settleUsage(StreamUsageTracker usageTracker) {
        if (usageTracker == null || usageTracker.settled) {
            return;
        }

        usageTracker.settled = true;
        int actualPromptTokens = usageTracker.promptTokens > 0
                ? usageTracker.promptTokens
                : usageTracker.estimatedPromptTokens;
        int actualCompletionTokens = usageTracker.completionTokens > 0
                ? usageTracker.completionTokens
                : usageQuotaService.estimateTextTokens(usageTracker.responseContent.toString());

        usageQuotaService.settleReservation(usageTracker.reservation, actualPromptTokens + actualCompletionTokens);
    }

    private static final class StreamUsageTracker {
        private final UsageQuotaService.TokenReservationBundle reservation;
        private final int estimatedPromptTokens;
        private final StringBuilder responseContent = new StringBuilder();
        private volatile int promptTokens;
        private volatile int completionTokens;
        private volatile boolean settled;

        private StreamUsageTracker(UsageQuotaService.TokenReservationBundle reservation, int estimatedPromptTokens) {
            this.reservation = reservation;
            this.estimatedPromptTokens = estimatedPromptTokens;
        }
    }
}
