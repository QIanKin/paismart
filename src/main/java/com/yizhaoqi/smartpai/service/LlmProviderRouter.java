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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class LlmProviderRouter {

    private static final Logger logger = LoggerFactory.getLogger(LlmProviderRouter.class);
    private static final String API_STYLE_RESPONSES = "responses";

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
            WebClient client = buildClient(provider);
            if (isResponsesProvider(provider)) {
                Map<String, Object> responsesRequest = buildResponsesRequest(provider.model(), userMessage, context, history);
                client.post()
                        .uri("/responses")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(responsesRequest)
                        .retrieve()
                        .bodyToFlux(String.class)
                        .subscribe(
                                chunk -> processResponsesTextChunk(chunk, usageTracker, onChunk),
                                error -> {
                                    settleUsage(usageTracker);
                                    onError.accept(error);
                                },
                                () -> finishResponsesTextStream(usageTracker, onChunk, onError, onComplete)
                        );
            } else {
                client.post()
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
            }
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

        Map<String, Object> request = isResponsesProvider(provider)
                ? buildResponsesAgentRequest(provider.model(), messages, toolsManifest, toolChoice)
                : buildAgentRequest(provider.model(), messages, toolsManifest, toolChoice);

        // 估算 tokens 走已有逻辑：把 role/content 拍平成 Map<String,String> 再给 estimateChatTokens
        int estimatedPromptTokens = estimateAgentPromptTokens(messages);
        int maxCompletionTokens = aiProperties.getGeneration().getMaxTokens() != null
                ? aiProperties.getGeneration().getMaxTokens()
                : 2000;
        UsageQuotaService.TokenReservationBundle reservation = rateLimitService.reserveLlmUsage(
                requesterId, estimatedPromptTokens, maxCompletionTokens);
        AgentStreamTracker tracker = new AgentStreamTracker(reservation, estimatedPromptTokens);

        try {
            WebClient client = buildClient(provider);
            if (isResponsesProvider(provider)) {
                client.post()
                        .uri("/responses")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(request)
                        .retrieve()
                        .bodyToFlux(String.class)
                        .subscribe(
                                chunk -> processResponsesAgentChunk(chunk, tracker, callback),
                                error -> {
                                    settleAgentUsage(tracker);
                                    safeCall(() -> callback.onError(error));
                                },
                                () -> finishResponsesAgentStream(tracker, callback)
                        );
            } else {
                client.post()
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
            }
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

    private Map<String, Object> buildResponsesRequest(String model,
                                                      String userMessage,
                                                      String context,
                                                      List<Map<String, String>> history) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("model", model);

        List<Map<String, String>> messages = buildMessages(userMessage, context, history);
        List<Object> input = new ArrayList<>(messages.size());
        for (Map<String, String> message : messages) {
            java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("role", message.get("role"));
            item.put("content", message.get("content"));
            input.add(item);
        }
        request.put("input", input);
        request.put("stream", true);

        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_output_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private Map<String, Object> buildResponsesAgentRequest(String model,
                                                           List<Map<String, Object>> messages,
                                                           JsonNode toolsManifest,
                                                           String toolChoice) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("model", model);
        request.put("input", toResponsesInput(messages));
        request.put("stream", true);

        List<Map<String, Object>> tools = toResponsesTools(toolsManifest);
        if (!tools.isEmpty()) {
            request.put("tools", tools);
            if (toolChoice != null && !toolChoice.isBlank()) {
                request.put("tool_choice", toolChoice);
            }
        }

        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_output_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private int estimateAgentPromptTokens(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Map<String, Object> message : messages) {
            if (message == null || message.isEmpty()) {
                continue;
            }
            total += 8;
            total += usageQuotaService.estimateTextTokens(stringValue(message.get("role")));
            if (message.containsKey("content") && message.get("content") != null) {
                total += estimateAgentContentTokens(message.get("content"));
                continue;
            }
            total += estimateAgentMessageMetadataTokens(message);
        }
        return total;
    }

    private int estimateAgentMessageMetadataTokens(Map<String, Object> message) {
        int total = 0;
        for (Map.Entry<String, Object> entry : message.entrySet()) {
            String key = entry.getKey();
            if ("role".equals(key) || "content".equals(key)) {
                continue;
            }
            total += estimateAgentContentTokens(entry.getValue());
        }
        return total;
    }

    private int estimateAgentContentTokens(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof String s) {
            return usageQuotaService.estimateTextTokens(s);
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return usageQuotaService.estimateTextTokens(String.valueOf(value));
        }
        if (value instanceof Iterable<?> iterable) {
            int total = 0;
            for (Object item : iterable) {
                total += estimateAgentContentTokens(item);
            }
            return total;
        }
        if (value instanceof Map<?, ?> map) {
            String type = stringValue(map.get("type"));
            if ("image_url".equals(type) || "input_image".equals(type)) {
                return com.yizhaoqi.smartpai.service.agent.AgentMessageContentService.TOKENS_PER_IMAGE;
            }
            if ("text".equals(type) || "input_text".equals(type)) {
                return usageQuotaService.estimateTextTokens(stringValue(map.get("text")));
            }

            int total = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = stringValue(entry.getKey());
                if ("image_url".equals(key)) {
                    total += com.yizhaoqi.smartpai.service.agent.AgentMessageContentService.TOKENS_PER_IMAGE;
                    continue;
                }
                if ("type".equals(key)) {
                    continue;
                }
                total += estimateAgentContentTokens(entry.getValue());
            }
            return total;
        }

        try {
            return usageQuotaService.estimateTextTokens(objectMapper.writeValueAsString(value));
        } catch (Exception e) {
            return usageQuotaService.estimateTextTokens(String.valueOf(value));
        }
    }

    private void processResponsesTextChunk(String rawChunk,
                                           StreamUsageTracker usageTracker,
                                           Consumer<String> onChunk) {
        try {
            processResponsesEvents(rawChunk, usageTracker.sseBuffer, false,
                    event -> handleResponsesTextEvent(event, usageTracker, onChunk));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void finishResponsesTextStream(StreamUsageTracker usageTracker,
                                           Consumer<String> onChunk,
                                           Consumer<Throwable> onError,
                                           Runnable onComplete) {
        try {
            processResponsesEvents("", usageTracker.sseBuffer, true,
                    event -> handleResponsesTextEvent(event, usageTracker, onChunk));
            settleUsage(usageTracker);
            try {
                onComplete.run();
            } catch (Exception ex) {
                logger.error("onComplete 回调执行失败: {}", ex.getMessage(), ex);
            }
        } catch (Exception ex) {
            settleUsage(usageTracker);
            onError.accept(ex);
        }
    }

    private void processResponsesAgentChunk(String rawChunk,
                                            AgentStreamTracker tracker,
                                            LlmStreamCallback callback) {
        try {
            processResponsesEvents(rawChunk, tracker.sseBuffer, false,
                    event -> handleResponsesAgentEvent(event, tracker, callback));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void finishResponsesAgentStream(AgentStreamTracker tracker,
                                            LlmStreamCallback callback) {
        try {
            processResponsesEvents("", tracker.sseBuffer, true,
                    event -> handleResponsesAgentEvent(event, tracker, callback));
            settleAgentUsage(tracker);
            safeCall(callback::onComplete);
        } catch (Exception ex) {
            settleAgentUsage(tracker);
            safeCall(() -> callback.onError(ex));
        }
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
        private final StringBuilder sseBuffer = new StringBuilder();
        private final Map<String, ResponsesToolCallState> responsesToolCallsByItemId = new HashMap<>();
        private volatile boolean sawResponsesToolCall;
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

    private boolean isResponsesProvider(ModelProviderConfigService.ActiveProviderView provider) {
        return provider != null && API_STYLE_RESPONSES.equalsIgnoreCase(provider.apiStyle());
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

    private void handleResponsesTextEvent(ResponsesStreamEvent event,
                                          StreamUsageTracker usageTracker,
                                          Consumer<String> onChunk) throws Exception {
        JsonNode node = parseResponsesEventNode(event);
        String eventType = resolveResponsesEventType(event, node);
        if ("response.output_text.delta".equals(eventType)) {
            String delta = node.path("delta").asText("");
            if (!delta.isEmpty()) {
                usageTracker.responseContent.append(delta);
                onChunk.accept(delta);
            }
            return;
        }

        if ("response.completed".equals(eventType)) {
            applyResponsesUsage(node.path("response").path("usage"), usageTracker);
            return;
        }

        if (isResponsesTerminalError(eventType, node)) {
            throw buildResponsesError(eventType, node);
        }

        if (looksLikeNonStreamingResponsesBody(node)) {
            applyNonStreamingResponsesResult(node, usageTracker, onChunk);
        }
    }

    private void handleResponsesAgentEvent(ResponsesStreamEvent event,
                                           AgentStreamTracker tracker,
                                           LlmStreamCallback callback) throws Exception {
        JsonNode node = parseResponsesEventNode(event);
        String eventType = resolveResponsesEventType(event, node);
        if ("response.output_text.delta".equals(eventType)) {
            String delta = node.path("delta").asText("");
            if (!delta.isEmpty()) {
                tracker.responseContent.append(delta);
                safeCall(() -> callback.onContent(delta));
            }
            return;
        }

        if ("response.output_item.added".equals(eventType)) {
            JsonNode item = node.path("item");
            if ("function_call".equals(item.path("type").asText(""))) {
                int index = node.path("output_index").asInt(0);
                String itemId = item.path("id").asText(null);
                String callId = item.path("call_id").asText(null);
                String name = item.path("name").asText(null);
                if (itemId != null && !itemId.isBlank()) {
                    tracker.responsesToolCallsByItemId.put(itemId, new ResponsesToolCallState(index, callId, name));
                }
                tracker.sawResponsesToolCall = true;
                safeCall(() -> callback.onToolCallDelta(index, callId, name, ""));
            }
            return;
        }

        if ("response.function_call_arguments.delta".equals(eventType)) {
            String itemId = node.path("item_id").asText(null);
            String delta = node.path("delta").asText("");
            ResponsesToolCallState state = itemId == null ? null : tracker.responsesToolCallsByItemId.get(itemId);
            int index = state == null ? node.path("output_index").asInt(0) : state.index();
            tracker.trackToolCallDelta(delta);
            safeCall(() -> callback.onToolCallDelta(index, null, null, delta));
            return;
        }

        if ("response.completed".equals(eventType)) {
            applyResponsesUsage(node.path("response").path("usage"), tracker);
            if (tracker.promptTokens > 0 || tracker.completionTokens > 0) {
                safeCall(() -> callback.onUsage(tracker.promptTokens, tracker.completionTokens));
            }
            safeCall(() -> callback.onFinishReason(tracker.sawResponsesToolCall ? "tool_calls" : "stop"));
            return;
        }

        if (isResponsesTerminalError(eventType, node)) {
            throw buildResponsesError(eventType, node);
        }

        if (looksLikeNonStreamingResponsesBody(node)) {
            ResponsesResult result = parseResponsesResultNode(node);
            tracker.promptTokens = result.promptTokens();
            tracker.completionTokens = result.completionTokens();
            if (tracker.promptTokens > 0 || tracker.completionTokens > 0) {
                safeCall(() -> callback.onUsage(tracker.promptTokens, tracker.completionTokens));
            }
            if (!result.text().isEmpty()) {
                tracker.responseContent.append(result.text());
                safeCall(() -> callback.onContent(result.text()));
            }
            List<ResponsesToolCall> toolCalls = result.toolCalls();
            for (int i = 0; i < toolCalls.size(); i++) {
                ResponsesToolCall call = toolCalls.get(i);
                tracker.trackToolCallDelta(call.arguments());
                int index = i;
                safeCall(() -> callback.onToolCallDelta(index, call.callId(), call.name(), call.arguments()));
            }
            tracker.sawResponsesToolCall = !toolCalls.isEmpty();
            safeCall(() -> callback.onFinishReason(toolCalls.isEmpty() ? "stop" : "tool_calls"));
        }
    }

    private void processResponsesEvents(String rawChunk,
                                        StringBuilder sseBuffer,
                                        boolean flushRemainder,
                                        ResponsesEventHandler handler) throws Exception {
        if (rawChunk != null && !rawChunk.isEmpty()) {
            String normalized = normalizeSseChunk(rawChunk);
            ResponsesStreamEvent directEvent = tryParseDirectResponsesJson(normalized);
            if (sseBuffer.length() == 0 && directEvent != null) {
                handler.accept(directEvent);
                return;
            }
            sseBuffer.append(normalized);
        }

        int delimiterIndex;
        while ((delimiterIndex = sseBuffer.indexOf("\n\n")) >= 0) {
            String block = sseBuffer.substring(0, delimiterIndex);
            sseBuffer.delete(0, delimiterIndex + 2);
            ResponsesStreamEvent event = parseResponsesSseBlock(block);
            if (event != null) {
                handler.accept(event);
            }
        }

        if (flushRemainder && sseBuffer.length() > 0) {
            String block = sseBuffer.toString();
            sseBuffer.setLength(0);
            ResponsesStreamEvent event = parseResponsesSseBlock(block);
            if (event != null) {
                handler.accept(event);
            }
        }
    }

    private String normalizeSseChunk(String rawChunk) {
        return rawChunk.replace("\r\n", "\n").replace('\r', '\n');
    }

    private ResponsesStreamEvent tryParseDirectResponsesJson(String rawChunk) {
        if (rawChunk == null) {
            return null;
        }
        String trimmed = rawChunk.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return new ResponsesStreamEvent(null, trimmed);
        }
        return null;
    }

    private ResponsesStreamEvent parseResponsesSseBlock(String block) {
        if (block == null) {
            return null;
        }
        String trimmed = block.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String eventName = null;
        StringBuilder data = new StringBuilder();
        boolean sawField = false;
        for (String line : trimmed.split("\n")) {
            String item = line.trim();
            if (item.isEmpty() || item.startsWith(":")) {
                continue;
            }
            if (item.startsWith("event:")) {
                eventName = item.substring(6).trim();
                sawField = true;
                continue;
            }
            if (item.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(item.substring(5).trim());
                sawField = true;
            }
        }

        if (!sawField && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return new ResponsesStreamEvent(null, trimmed);
        }
        if (data.length() == 0) {
            return null;
        }
        return new ResponsesStreamEvent(eventName, data.toString());
    }

    private JsonNode parseResponsesEventNode(ResponsesStreamEvent event) throws Exception {
        return objectMapper.readTree(event.data());
    }

    private String resolveResponsesEventType(ResponsesStreamEvent event, JsonNode node) {
        if (event.eventName() != null && !event.eventName().isBlank()) {
            return event.eventName();
        }
        return node.path("type").asText("");
    }

    private boolean isResponsesTerminalError(String eventType, JsonNode node) {
        if (eventType != null && (eventType.endsWith(".failed") || eventType.endsWith(".error") || "error".equals(eventType))) {
            return true;
        }
        JsonNode errorNode = node.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            return true;
        }
        JsonNode responseError = node.path("response").path("error");
        return !responseError.isMissingNode() && !responseError.isNull();
    }

    private RuntimeException buildResponsesError(String eventType, JsonNode node) {
        JsonNode errorNode = node.path("error");
        if (errorNode.isMissingNode() || errorNode.isNull()) {
            errorNode = node.path("response").path("error");
        }
        String message = errorNode.isMissingNode() || errorNode.isNull()
                ? eventType
                : errorNode.toString();
        return new IllegalStateException(message);
    }

    private void applyResponsesUsage(JsonNode usageNode, StreamUsageTracker usageTracker) {
        if (usageNode == null || !usageNode.isObject()) {
            return;
        }
        usageTracker.promptTokens = usageNode.path("input_tokens").asInt(usageTracker.promptTokens);
        usageTracker.completionTokens = usageNode.path("output_tokens").asInt(usageTracker.completionTokens);
    }

    private void applyResponsesUsage(JsonNode usageNode, AgentStreamTracker tracker) {
        if (usageNode == null || !usageNode.isObject()) {
            return;
        }
        tracker.promptTokens = usageNode.path("input_tokens").asInt(tracker.promptTokens);
        tracker.completionTokens = usageNode.path("output_tokens").asInt(tracker.completionTokens);
    }

    private boolean looksLikeNonStreamingResponsesBody(JsonNode node) {
        return node != null && node.isObject()
                && (node.has("output") || node.has("output_text"))
                && !node.has("type");
    }

    private void applyNonStreamingResponsesResult(JsonNode node,
                                                  StreamUsageTracker usageTracker,
                                                  Consumer<String> onChunk) throws Exception {
        ResponsesResult result = parseResponsesResultNode(node);
        usageTracker.promptTokens = result.promptTokens();
        usageTracker.completionTokens = result.completionTokens();
        if (!result.text().isEmpty()) {
            usageTracker.responseContent.append(result.text());
            onChunk.accept(result.text());
        }
    }

    private List<Object> toResponsesInput(List<Map<String, Object>> messages) {
        List<Object> input = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return input;
        }

        for (Map<String, Object> message : messages) {
            if (message == null || message.isEmpty()) {
                continue;
            }
            String role = message.get("role") == null ? "" : String.valueOf(message.get("role"));
            switch (role) {
                case "assistant" -> appendResponsesAssistantInput(input, message);
                case "tool" -> {
                    Map<String, Object> item = toResponsesToolOutput(message);
                    if (item != null) {
                        input.add(item);
                    }
                }
                default -> {
                    Map<String, Object> item = toResponsesRoleMessage(role, message.get("content"));
                    if (item != null) {
                        input.add(item);
                    }
                }
            }
        }
        return input;
    }

    private void appendResponsesAssistantInput(List<Object> input, Map<String, Object> message) {
        String assistantText = extractAssistantText(message.get("content"));
        if (assistantText != null && !assistantText.isBlank()) {
            java.util.LinkedHashMap<String, Object> assistant = new java.util.LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", assistantText);
            input.add(assistant);
        }

        for (Map<String, Object> toolCall : normalizeToolCalls(message.get("tool_calls"))) {
            String callId = stringValue(toolCall.get("id"));
            String name = null;
            String arguments = null;

            Object function = toolCall.get("function");
            if (function instanceof Map<?, ?> functionMap) {
                name = stringValue(functionMap.get("name"));
                arguments = stringValue(functionMap.get("arguments"));
            }

            if (callId == null || callId.isBlank()) {
                callId = "call_" + Math.abs(toolCall.hashCode());
            }
            if (arguments == null || arguments.isBlank()) {
                arguments = "{}";
            }

            java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("type", "function_call");
            item.put("call_id", callId);
            item.put("name", name);
            item.put("arguments", arguments);
            input.add(item);
        }
    }

    private Map<String, Object> toResponsesRoleMessage(String role, Object content) {
        if (content == null) {
            return null;
        }

        java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("role", role);

        if ("user".equals(role) && content instanceof List<?> contentList) {
            List<Map<String, Object>> parts = toResponsesUserContent(contentList);
            if (!parts.isEmpty()) {
                item.put("content", parts);
                return item;
            }
        }

        item.put("content", stringifyContent(content));
        return item;
    }

    private Map<String, Object> toResponsesToolOutput(Map<String, Object> message) {
        String callId = stringValue(message.get("tool_call_id"));
        if (callId == null || callId.isBlank()) {
            return null;
        }

        java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("type", "function_call_output");
        item.put("call_id", callId);
        item.put("output", stringifyContent(message.get("content")));
        return item;
    }

    private List<Map<String, Object>> toResponsesUserContent(List<?> contentList) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Object rawPart : contentList) {
            if (!(rawPart instanceof Map<?, ?> part)) {
                continue;
            }
            String type = stringValue(part.get("type"));
            if ("text".equals(type) || "input_text".equals(type)) {
                String text = stringValue(part.get("text"));
                if (text == null || text.isBlank()) {
                    continue;
                }
                java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("type", "input_text");
                item.put("text", text);
                parts.add(item);
                continue;
            }
            if ("image_url".equals(type) || "input_image".equals(type)) {
                String imageUrl = extractImageUrl(part);
                if (imageUrl == null || imageUrl.isBlank()) {
                    continue;
                }
                java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("type", "input_image");
                item.put("image_url", imageUrl);
                parts.add(item);
            }
        }
        return parts;
    }

    private String extractImageUrl(Map<?, ?> part) {
        Object imageUrl = part.get("image_url");
        if (imageUrl instanceof Map<?, ?> imageUrlMap) {
            return stringValue(imageUrlMap.get("url"));
        }
        return stringValue(imageUrl);
    }

    private List<Map<String, Object>> normalizeToolCalls(Object toolCallsRaw) {
        if (toolCallsRaw == null) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.valueToTree(toolCallsRaw);
            if (!node.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                out.add(objectMapper.convertValue(item, Map.class));
            }
            return out;
        } catch (Exception ex) {
            logger.warn("tool_calls 转换为 Responses 输入失败: {}", ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> toResponsesTools(JsonNode toolsManifest) {
        if (toolsManifest == null || !toolsManifest.isArray() || toolsManifest.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> tools = new ArrayList<>(toolsManifest.size());
        for (JsonNode item : toolsManifest) {
            JsonNode function = item.path("function");
            if (function.isMissingNode() || function.isNull()) {
                continue;
            }
            String name = function.path("name").asText(null);
            if (name == null || name.isBlank()) {
                continue;
            }
            java.util.LinkedHashMap<String, Object> tool = new java.util.LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("name", name);
            String description = function.path("description").asText(null);
            if (description != null && !description.isBlank()) {
                tool.put("description", description);
            }
            JsonNode parameters = function.path("parameters");
            if (!parameters.isMissingNode() && !parameters.isNull()) {
                tool.put("parameters", objectMapper.convertValue(parameters, Object.class));
            }
            tools.add(tool);
        }
        return tools;
    }

    private String extractAssistantText(Object content) {
        if (content == null) {
            return null;
        }
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> contentList) {
            StringBuilder buf = new StringBuilder();
            for (Object rawPart : contentList) {
                if (!(rawPart instanceof Map<?, ?> part)) {
                    continue;
                }
                String type = stringValue(part.get("type"));
                if (!"output_text".equals(type) && !"text".equals(type) && !"input_text".equals(type)) {
                    continue;
                }
                String text = stringValue(part.get("text"));
                if (text != null) {
                    buf.append(text);
                }
            }
            return buf.toString();
        }
        return stringifyContent(content);
    }

    private String stringifyContent(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception ex) {
            return String.valueOf(content);
        }
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return "null".equalsIgnoreCase(text) ? null : text;
    }

    private ResponsesResult parseResponsesResult(String body) throws Exception {
        return parseResponsesResultNode(objectMapper.readTree(body));
    }

    private ResponsesResult parseResponsesResultNode(JsonNode node) throws Exception {
        JsonNode errorNode = node.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            throw new IllegalStateException(errorNode.toString());
        }

        int promptTokens = node.path("usage").path("input_tokens").asInt(0);
        int completionTokens = node.path("usage").path("output_tokens").asInt(0);

        StringBuilder text = new StringBuilder();
        List<ResponsesToolCall> toolCalls = new ArrayList<>();
        JsonNode output = node.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                String type = item.path("type").asText("");
                if ("message".equals(type)) {
                    JsonNode content = item.path("content");
                    if (content.isArray()) {
                        for (JsonNode part : content) {
                            if ("output_text".equals(part.path("type").asText(""))) {
                                text.append(part.path("text").asText(""));
                            }
                        }
                    }
                    continue;
                }
                if ("function_call".equals(type)) {
                    toolCalls.add(new ResponsesToolCall(
                            item.path("call_id").asText(null),
                            item.path("name").asText(null),
                            item.path("arguments").asText("")
                    ));
                }
            }
        }

        if (text.length() == 0) {
            String topLevelOutputText = node.path("output_text").asText("");
            if (!topLevelOutputText.isEmpty()) {
                text.append(topLevelOutputText);
            }
        }

        return new ResponsesResult(text.toString(), toolCalls, promptTokens, completionTokens);
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
        private final StringBuilder sseBuffer = new StringBuilder();
        private volatile int promptTokens;
        private volatile int completionTokens;
        private volatile boolean settled;

        private StreamUsageTracker(UsageQuotaService.TokenReservationBundle reservation, int estimatedPromptTokens) {
            this.reservation = reservation;
            this.estimatedPromptTokens = estimatedPromptTokens;
        }
    }

    private record ResponsesResult(
            String text,
            List<ResponsesToolCall> toolCalls,
            int promptTokens,
            int completionTokens
    ) {}

    @FunctionalInterface
    private interface ResponsesEventHandler {
        void accept(ResponsesStreamEvent event) throws Exception;
    }

    private record ResponsesStreamEvent(
            String eventName,
            String data
    ) {}

    private record ResponsesToolCall(
            String callId,
            String name,
            String arguments
    ) {}

    private record ResponsesToolCallState(
            int index,
            String callId,
            String name
    ) {}
}
