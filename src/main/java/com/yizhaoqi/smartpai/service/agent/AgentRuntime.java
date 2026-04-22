package com.yizhaoqi.smartpai.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.exception.RateLimitExceededException;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.model.agent.ChatSession;
import com.yizhaoqi.smartpai.model.agent.Project;
import com.yizhaoqi.smartpai.model.creator.Creator;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.model.creator.CreatorPost;
import com.yizhaoqi.smartpai.repository.creator.CreatorRepository;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import com.yizhaoqi.smartpai.service.LlmProviderRouter;
import com.yizhaoqi.smartpai.service.LlmStreamCallback;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import com.yizhaoqi.smartpai.service.agent.context.ContextEngine;
import com.yizhaoqi.smartpai.service.agent.context.ContextRequest;
import com.yizhaoqi.smartpai.service.agent.memory.MemoryCompactor;
import com.yizhaoqi.smartpai.service.agent.memory.MessageStore;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolExecutor;
import com.yizhaoqi.smartpai.service.tool.ToolRegistry;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 主运行时。对标 claude-code packages/agent-core 的 agent loop。
 *
 * Phase 2 与 Phase 1 的差异：
 *  1. 历史改走 MySQL + Redis L1（{@link MessageStore}），不再使用 {@link AgentHistoryStore}；
 *  2. prompt 组装改走 {@link ContextEngine}（system/live/history/memory 分层 + token 预算）；
 *  3. 一轮结束后异步触发 {@link MemoryCompactor} 做长期记忆压缩。
 *  4. 工具白名单顺序：项目级 > 请求级 > 全局。
 *
 * 单轮步骤循环保留 ReAct 风格（LLM 产出 tool_calls → 工具执行 → 结果注入 → 再 LLM）。
 */
@Service
public class AgentRuntime {

    private static final Logger logger = LoggerFactory.getLogger(AgentRuntime.class);

    /** 模型 prompt 预算：与 ai.generation.max-tokens 组合使用，剩余即 prompt 可用 */
    private static final int MODEL_CONTEXT_WINDOW = 128_000;
    /** 压缩阈值：未压缩消息数超过此值 → 触发 MemoryCompactor */
    private static final int COMPACTION_THRESHOLD = 60;
    /** 压缩后保留的尾部消息数（保证上下文连贯） */
    private static final int COMPACTION_KEEP_TAIL = 20;

    private final LlmProviderRouter llmProviderRouter;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final AgentEventPublisher events;
    private final AgentCancellationRegistry cancellationRegistry;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    private final AgentUserResolver userResolver;
    private final ChatSessionService chatSessionService;
    private final ProjectService projectService;
    private final MessageStore messageStore;
    private final ContextEngine contextEngine;
    private final MemoryCompactor memoryCompactor;
    private final UsageQuotaService usageQuotaService;
    private final ThreadPoolTaskExecutor backgroundExecutor;
    private final CreatorRepository creatorRepository;
    private final CreatorService creatorService;
    private final ProjectCreatorService projectCreatorService;

    public AgentRuntime(LlmProviderRouter llmProviderRouter,
                        ToolRegistry toolRegistry,
                        ToolExecutor toolExecutor,
                        AgentEventPublisher events,
                        AgentCancellationRegistry cancellationRegistry,
                        AiProperties aiProperties,
                        ObjectMapper objectMapper,
                        AgentUserResolver userResolver,
                        ChatSessionService chatSessionService,
                        ProjectService projectService,
                        MessageStore messageStore,
                        ContextEngine contextEngine,
                        MemoryCompactor memoryCompactor,
                        UsageQuotaService usageQuotaService,
                        @Qualifier("chatMonitorExecutor") ThreadPoolTaskExecutor backgroundExecutor,
                        CreatorRepository creatorRepository,
                        CreatorService creatorService,
                        ProjectCreatorService projectCreatorService) {
        this.llmProviderRouter = llmProviderRouter;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.events = events;
        this.cancellationRegistry = cancellationRegistry;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.userResolver = userResolver;
        this.chatSessionService = chatSessionService;
        this.projectService = projectService;
        this.messageStore = messageStore;
        this.contextEngine = contextEngine;
        this.memoryCompactor = memoryCompactor;
        this.usageQuotaService = usageQuotaService;
        this.backgroundExecutor = backgroundExecutor;
        this.creatorRepository = creatorRepository;
        this.creatorService = creatorService;
        this.projectCreatorService = projectCreatorService;
    }

    public void runTurn(AgentRequest req, WebSocketSession session) {
        String messageId = UUID.randomUUID().toString();
        long turnStart = System.currentTimeMillis();
        AtomicBoolean cancelFlag = cancellationRegistry.obtainForSession(session.getId());
        cancelFlag.set(false);

        events.publishStart(session, messageId);

        // 1. 解析用户 / session / project
        TurnScope scope;
        try {
            scope = resolveScope(req);
        } catch (Exception e) {
            logger.warn("解析 turn scope 失败 userId={} sessionId={} err={}",
                    req.userId(), req.sessionId(), e.getMessage());
            events.publishError(session, messageId, "scope_resolve_failed: " + e.getMessage());
            events.publishCompletion(session, messageId, "error", "");
            return;
        }

        // 2. 工具选择：项目级 ∩ 请求级 ∩ 全局
        List<String> whitelist = mergeWhitelists(scope.projectEnabledTools,
                req.enabledTools(), aiProperties.getAgent().getEnabledTools());
        List<Tool> tools = toolRegistry.subset(whitelist);
        ArrayNode manifest = toolRegistry.toOpenAiManifest(tools);

        // 3. 预算参数
        int configuredMaxSteps = aiProperties.getAgent().getMaxSteps();
        long configuredTimeoutMs = aiProperties.getAgent().getTurnTimeoutMs();
        int effectiveMaxSteps = req.maxSteps() > 0 ? Math.min(req.maxSteps(), configuredMaxSteps) : configuredMaxSteps;
        long effectiveTimeoutMs = req.turnTimeoutMs() > 0 ? Math.min(req.turnTimeoutMs(), configuredTimeoutMs) : configuredTimeoutMs;
        int maxCompletionTokens = aiProperties.getGeneration().getMaxTokens() != null
                ? aiProperties.getGeneration().getMaxTokens() : 2000;
        int totalBudgetTokens = Math.max(4000, MODEL_CONTEXT_WINDOW - maxCompletionTokens - 1000);

        // 4. 本 turn 的 live messages —— 初始只有 user
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", req.userMessage());
        List<Map<String, Object>> liveTurn = new ArrayList<>();
        liveTurn.add(userMsg);

        // 5. 记录本 turn 要持久化的消息
        String groupId = UUID.randomUUID().toString();
        int userTokens = usageQuotaService.estimateTextTokens(req.userMessage());
        List<MessageStore.NewMessage> toPersist = new ArrayList<>();
        toPersist.add(MessageStore.NewMessage.user(req.userMessage(), userTokens));

        StringBuilder assistantFinalContent = new StringBuilder();
        int step = 0;
        String finishReason = "stop";
        Throwable fatal = null;
        String systemPrompt = buildSystemPrompt(req, scope, tools);

        outer:
        while (step < effectiveMaxSteps) {
            step++;
            if (cancelFlag.get()) { finishReason = "cancelled"; break; }
            if (System.currentTimeMillis() - turnStart > effectiveTimeoutMs) { finishReason = "timeout"; break; }

            events.publishStepStart(session, messageId, step);

            ContextRequest ctxReq = new ContextRequest(
                    scope.session.getId(),
                    scope.session.getProjectId(),
                    scope.user.getId(),
                    scope.user.getPrimaryOrg(),
                    req.userMessage(),
                    new ArrayList<>(liveTurn),
                    totalBudgetTokens,
                    systemPrompt,
                    tools.stream().map(Tool::name).toList(),
                    true
            );
            ContextEngine.AssembledContext ctx = contextEngine.assemble(ctxReq);

            StepOutcome outcome;
            try {
                outcome = runStep(req, session, messageId, ctx.messages(), manifest,
                        assistantFinalContent, effectiveTimeoutMs);
            } catch (RateLimitExceededException rle) {
                events.publishRateLimit(session, messageId, rle.getMessage(), rle.getRetryAfterSeconds());
                finishReason = "rate_limited";
                events.publishStepEnd(session, messageId, step);
                break;
            } catch (Throwable ex) {
                fatal = ex;
                finishReason = "error";
                events.publishStepEnd(session, messageId, step);
                break;
            }
            events.publishStepEnd(session, messageId, step);

            if (outcome == null) { finishReason = "stop"; break; }

            // 记录 assistant（可能带 tool_calls）
            liveTurn.add(outcome.assistantMessage);
            String assistantText = outcome.assistantMessage.get("content") instanceof String s ? s : null;
            String toolCallsJson = serializeToolCalls(outcome.assistantMessage.get("tool_calls"));
            int asstTokens = (assistantText == null ? 0 : usageQuotaService.estimateTextTokens(assistantText))
                    + (toolCallsJson == null ? 0 : usageQuotaService.estimateTextTokens(toolCallsJson) / 4);
            toPersist.add(MessageStore.NewMessage.assistant(assistantText, toolCallsJson, asstTokens));

            if (outcome.toolCalls.isEmpty()) {
                finishReason = outcome.finishReason == null ? "stop" : outcome.finishReason;
                break;
            }

            for (ToolCallAccumulator.PendingToolCall tc : outcome.toolCalls) {
                if (cancelFlag.get()) { finishReason = "cancelled"; break outer; }
                runOneToolCall(req, scope, session, messageId, tc, liveTurn, toPersist, cancelFlag);
            }
        }

        if (step >= effectiveMaxSteps && "stop".equals(finishReason)) {
            finishReason = "max_steps";
        }

        // 持久化本轮（user + assistants + tools）
        MessageStore.TurnWriteResult writeResult = null;
        try {
            writeResult = messageStore.appendTurn(scope.session.getId(), groupId, toPersist);
        } catch (Exception e) {
            logger.warn("MessageStore.appendTurn 失败 sessionId={} err={}", scope.session.getId(), e.getMessage(), e);
        }

        if (fatal != null) {
            events.publishError(session, messageId,
                    fatal.getMessage() == null ? fatal.getClass().getSimpleName() : fatal.getMessage());
        }
        events.publishCompletion(session, messageId, finishReason, assistantFinalContent.toString());
        cancellationRegistry.release(session.getId());

        logger.info("Agent 轮次结束 user={} session={} project={} step={} reason={} cost={}ms finalLen={} persisted={}",
                req.userId(), scope.session.getId(), scope.session.getProjectId(), step, finishReason,
                System.currentTimeMillis() - turnStart, assistantFinalContent.length(),
                writeResult == null ? 0 : writeResult.persisted().size());

        // 后台触发记忆压缩，不阻塞当前响应
        final ChatSession sessionSnap = writeResult != null ? writeResult.session() : scope.session;
        final String requester = req.userId();
        backgroundExecutor.execute(() -> {
            try {
                memoryCompactor.maybeCompactSession(sessionSnap.getId(), requester,
                        COMPACTION_THRESHOLD, COMPACTION_KEEP_TAIL);
            } catch (Exception e) {
                logger.warn("MemoryCompactor 异步执行失败 sessionId={} err={}",
                        sessionSnap.getId(), e.getMessage());
            }
        });
    }

    private TurnScope resolveScope(AgentRequest req) {
        User user = userResolver.resolve(req.userId());
        ChatSession session;
        Long sessionId = req.sessionIdNumeric();
        if (sessionId != null) {
            session = chatSessionService.getOwned(sessionId, user.getId());
        } else {
            String orgTag = req.orgTag() != null ? req.orgTag() : user.getPrimaryOrg();
            Long projectId = req.projectIdNumeric();
            if (projectId != null) {
                session = chatSessionService.createSession(user.getId(), orgTag, projectId, null);
            } else {
                session = chatSessionService.getOrCreateDefaultSession(user.getId(), orgTag);
            }
        }
        Project project = chatSessionService.resolveProject(session);
        List<String> projectTools = project == null ? List.of() : projectService.parseEnabledTools(project);
        return new TurnScope(user, session, project, projectTools);
    }

    /**
     * 工具白名单合并：
     * - 若某层为空 → 透明（不过滤）
     * - 非空 → 与上一层取交集
     * 返回 null 表示 "全量使用"，ToolRegistry.subset(null) 语义要求支持。
     */
    private List<String> mergeWhitelists(List<String> project, List<String> request, List<String> global) {
        List<String> current = null;
        for (List<String> layer : List.of(
                project == null ? List.<String>of() : project,
                request == null ? List.<String>of() : request,
                global == null ? List.<String>of() : global)) {
            if (layer.isEmpty()) continue;
            if (current == null) current = new ArrayList<>(layer);
            else current.retainAll(layer);
        }
        if (current == null || current.isEmpty()) return null;
        return current;
    }

    private StepOutcome runStep(AgentRequest req,
                                WebSocketSession session,
                                String messageId,
                                List<Map<String, Object>> messages,
                                ArrayNode manifest,
                                StringBuilder assistantFinalContent,
                                long effectiveTimeoutMs) throws Exception {

        StringBuilder contentBuf = new StringBuilder();
        ToolCallAccumulator acc = new ToolCallAccumulator();
        CompletableFuture<String> finishFuture = new CompletableFuture<>();
        final String[] finishReasonHolder = new String[1];

        LlmStreamCallback cb = new LlmStreamCallback() {
            @Override public void onContent(String delta) {
                contentBuf.append(delta);
                assistantFinalContent.append(delta);
                events.publishChunk(session, messageId, delta);
            }
            @Override public void onToolCallDelta(int index, String id, String name, String argumentsDelta) {
                acc.accept(index, id, name, argumentsDelta);
            }
            @Override public void onFinishReason(String reason) { finishReasonHolder[0] = reason; }
            @Override public void onComplete() {
                if (!finishFuture.isDone()) finishFuture.complete(finishReasonHolder[0]);
            }
            @Override public void onError(Throwable error) {
                if (!finishFuture.isDone()) finishFuture.completeExceptionally(error);
            }
        };

        llmProviderRouter.streamChat(req.userId(), messages, manifest, "auto", cb);

        try {
            finishFuture.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            throw new RuntimeException("LLM 流式响应超时", te);
        }

        List<ToolCallAccumulator.PendingToolCall> toolCalls = acc.drain(objectMapper);

        Map<String, Object> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", contentBuf.length() == 0 ? null : contentBuf.toString());
        if (!toolCalls.isEmpty()) {
            List<Map<String, Object>> tcArray = new ArrayList<>(toolCalls.size());
            for (ToolCallAccumulator.PendingToolCall tc : toolCalls) {
                Map<String, Object> tcMap = new HashMap<>();
                tcMap.put("id", tc.id() != null ? tc.id() : "call_" + UUID.randomUUID());
                tcMap.put("type", "function");
                Map<String, Object> fn = new HashMap<>();
                fn.put("name", tc.name());
                fn.put("arguments", tc.rawArguments() == null ? "{}" : tc.rawArguments());
                tcMap.put("function", fn);
                tcArray.add(tcMap);
            }
            assistantMsg.put("tool_calls", tcArray);
        }

        return new StepOutcome(assistantMsg, toolCalls, finishReasonHolder[0]);
    }

    private void runOneToolCall(AgentRequest req,
                                TurnScope scope,
                                WebSocketSession session,
                                String messageId,
                                ToolCallAccumulator.PendingToolCall tc,
                                List<Map<String, Object>> liveTurn,
                                List<MessageStore.NewMessage> toPersist,
                                AtomicBoolean cancelFlag) {
        String toolUseId = tc.id() != null ? tc.id() : "call_" + UUID.randomUUID();
        String toolName = tc.name();
        if (toolName == null || toolName.isBlank()) {
            logger.warn("LLM 返回的 tool_call 缺少 name，忽略");
            appendToolMessage(liveTurn, toolUseId, "error: missing tool name");
            toPersist.add(MessageStore.NewMessage.tool(toolUseId, null, "error: missing tool name", 0L, 8));
            return;
        }

        Tool tool = toolRegistry.find(toolName).orElse(null);
        if (tool == null) {
            logger.warn("工具不存在: {}", toolName);
            events.publishToolCall(session, messageId, toolUseId, new FakeUnknownTool(toolName), tc.arguments());
            events.publishToolResult(session, messageId, toolUseId, toolName,
                    ToolResult.error("unknown_tool: " + toolName), 0L);
            String payload = toolExecutor.resultToLlmPayload(ToolResult.error("unknown_tool: " + toolName));
            appendToolMessage(liveTurn, toolUseId, payload);
            toPersist.add(MessageStore.NewMessage.tool(toolUseId, toolName, payload, 0L,
                    usageQuotaService.estimateTextTokens(payload)));
            return;
        }

        events.publishToolCall(session, messageId, toolUseId, tool, tc.arguments());

        ToolContext ctx = ToolContext.builder()
                .userId(req.userId())
                .orgTag(scope.user.getPrimaryOrg())
                .role(req.role())
                .projectId(scope.session.getProjectId() == null ? null : String.valueOf(scope.session.getProjectId()))
                .sessionId(String.valueOf(scope.session.getId()))
                .messageId(messageId)
                .toolUseId(toolUseId)
                .cancelled(cancelFlag)
                .progressSink(progress -> events.publishToolProgress(session, messageId, progress))
                .askUserSink((question, options) -> events.publishAskUser(session, messageId, question, options))
                .todoSink(todos -> events.publishTodo(session, messageId, todos))
                .build();

        long start = System.currentTimeMillis();
        ToolExecutor.ToolExecution exec = toolExecutor.execute(tool, tc.arguments(), ctx);
        long cost = System.currentTimeMillis() - start;

        events.publishToolResult(session, messageId, toolUseId, toolName, exec.result(), cost);
        String payload = toolExecutor.resultToLlmPayload(exec.result());
        appendToolMessage(liveTurn, toolUseId, payload);
        toPersist.add(MessageStore.NewMessage.tool(toolUseId, toolName, payload, cost,
                usageQuotaService.estimateTextTokens(payload)));
    }

    private void appendToolMessage(List<Map<String, Object>> liveTurn, String toolUseId, String content) {
        Map<String, Object> toolMsg = new HashMap<>();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", toolUseId);
        toolMsg.put("content", content == null ? "" : content);
        liveTurn.add(toolMsg);
    }

    private String serializeToolCalls(Object toolCalls) {
        if (toolCalls == null) return null;
        try {
            return objectMapper.writeValueAsString(toolCalls);
        } catch (Exception e) {
            logger.warn("序列化 tool_calls 失败: {}", e.getMessage());
            return null;
        }
    }

    private String buildSystemPrompt(AgentRequest req, TurnScope scope, List<Tool> tools) {
        if (req.systemPromptOverride() != null && !req.systemPromptOverride().isBlank()) {
            return req.systemPromptOverride();
        }
        // 会话级 override > 项目级 systemPrompt > 默认
        if (scope.session.getSystemPromptOverride() != null && !scope.session.getSystemPromptOverride().isBlank()) {
            return scope.session.getSystemPromptOverride();
        }
        StringBuilder sb = new StringBuilder();
        if (scope.project != null && scope.project.getSystemPrompt() != null
                && !scope.project.getSystemPrompt().isBlank()) {
            sb.append(scope.project.getSystemPrompt().trim()).append("\n\n");
        } else {
            sb.append("你是 PaiSmart 企业提效 Agent。默认面向 MCN 业务场景。\n");
            sb.append("工作原则：\n");
            sb.append("1. 优先从企业内部资产（知识库 / 博主库 / 赛道库 / 爆款结构库 / 人设标签库）中查找，而不是直接外网抓取。\n");
            sb.append("2. 只有当内部资产命中为空或明显过时时，才通过爬虫/搜索类 skill/tool 获取外部数据，并把有价值的结果沉淀回内部资产。\n");
            sb.append("3. 充分使用提供的 tools；一次回答不要虚构 tool 调用的结果。\n");
            sb.append("4. 当信息不足，向用户发起 ask_user 反问，而不是硬猜。\n");
            sb.append("5. 输出中文；引用知识库片段时尽量带来源编号。\n");
        }

        // 项目级上下文（带项目信息、名册 top N）
        appendProjectContext(sb, scope);

        // 会话类型专属 prompt
        appendSessionTypePrompt(sb, scope);

        if (!tools.isEmpty()) {
            sb.append("\n可用工具（会通过 OpenAI function calling 协议暴露）：\n");
            for (Tool t : tools) {
                sb.append("- ").append(t.name()).append(": ").append(t.description()).append('\n');
            }
        }
        String extra = aiProperties.getAgent().getBehaviorContract();
        if (extra != null && !extra.isBlank()) {
            sb.append("\n# 企业行为契约（租户定制）\n").append(extra.trim()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 注入"本项目上下文"：项目基本信息 + 名册 top N 概览。<br>
     * 目的是让 AI 即便没点开任何工具，也知道"这个项目是谁在做、目标是什么、已经敲定了哪些博主"，<br>
     * 避免每轮都重复调 project_roster_list / project_get_detail。<br>
     *
     * <p>只有 scope.project != null 时才会注入。单个会话绑定某个 creator 时也不重复；creator 档案由
     * {@link #appendSessionTypePrompt} 再追加，彼此不冲突。
     *
     * <p>上限：roster 最多展示 15 行，每行简短（名字 / 赛道 / stage / priority / 粉丝数），
     * 避免吃掉 token 预算。用户真正要分析某个博主时会落到 BLOGGER_BRIEF 会话，那里才注入完整档案。
     */
    private void appendProjectContext(StringBuilder sb, TurnScope scope) {
        Project project = scope.project;
        if (project == null) return;

        sb.append("\n# 本项目上下文\n");
        sb.append("- 项目: ").append(nullToDash(project.getName()))
                .append(" (id=").append(project.getId()).append(")\n");
        if (project.getDescription() != null && !project.getDescription().isBlank()) {
            sb.append("- 描述: ").append(truncate(project.getDescription().trim(), 300)).append('\n');
        }
        if (project.getTemplateCode() != null && !project.getTemplateCode().isBlank()) {
            sb.append("- 项目模板: ").append(project.getTemplateCode()).append('\n');
        }
        if (project.getCustomFieldsJson() != null && !project.getCustomFieldsJson().isBlank()) {
            sb.append("- 自定义字段: ").append(truncate(project.getCustomFieldsJson(), 400)).append('\n');
        }

        // 名册 top N
        try {
            List<ProjectCreatorService.RosterEntryView> roster =
                    projectCreatorService.listRoster(project.getId(), project.getOwnerUserId());
            if (roster != null && !roster.isEmpty()) {
                int total = roster.size();
                int cap = Math.min(15, total);
                sb.append("- 项目名册 (共 ").append(total).append(" 人").append(
                        total > cap ? "，以下按 priority/id 展示 top " + cap : "").append("):\n");
                for (int i = 0; i < cap; i++) {
                    ProjectCreatorService.RosterEntryView v = roster.get(i);
                    sb.append("  * [stage=").append(v.entry().getStage())
                            .append(", priority=").append(v.entry().getPriority()).append("] ");
                    Creator c = v.creator();
                    if (c != null) {
                        sb.append(nullToDash(c.getDisplayName()))
                                .append(" (creatorId=").append(c.getId()).append(")");
                        if (c.getTrackTagsJson() != null && !c.getTrackTagsJson().isBlank()) {
                            sb.append(" 赛道=").append(truncate(c.getTrackTagsJson(), 48));
                        }
                    } else {
                        sb.append("creatorId=").append(v.entry().getCreatorId()).append(" (档案已被删除)");
                    }
                    sb.append('\n');
                }
                sb.append("- 要查看名册完整信息或操作 stage / priority，使用 project_roster_list / project_roster_add 工具。\n");
            } else {
                sb.append("- 项目名册当前为空——可先用 creator_search 找候选并用 project_roster_add 入册。\n");
            }
        } catch (Exception e) {
            logger.debug("appendProjectContext listRoster 失败 projectId={} err={}", project.getId(), e.getMessage());
        }

        sb.append("- 博主全库对所有 session 可见：用 creator_search / creator_get / creator_get_posts 按需查询。\n");
    }

    /**
     * 按会话类型追加"角色指引"和（若有）被绑定 Creator 的核心档案。<br>
     * 关键设计：这里只注入"结构化摘要"（名字 / 人设 / 平台账号 / 最近 3 条爆款 metrics），<br>
     * 具体笔记内容让 Agent 通过 creator_get_posts 等工具按需拉取，不吃 prompt token。
     */
    private void appendSessionTypePrompt(StringBuilder sb, TurnScope scope) {
        ChatSession.SessionType type = scope.session.getSessionType();
        if (type == null || type == ChatSession.SessionType.GENERAL) return;

        sb.append("\n# 本会话类型：").append(type.name()).append('\n');
        switch (type) {
            case ALLOCATION -> sb.append("""
                    你正在协助用户为本项目挑选 & 分配博主。工作步骤建议：
                    1. 先用 creator_search 在本企业博主库里按 赛道/人设/互动 过滤出候选；
                    2. 对每个候选，调用 creator_get_posts 看最近笔记是否匹配投放诉求；
                    3. 给出「推荐入围 / 需谈 / 不匹配」三档结论，并解释原因；
                    4. 用户确认后，使用 project_roster_* 工具把入围博主写入项目名册。
                    绝不要基于想象推荐博主，所有博主都必须能在内部库里查到。
                    """);
            case BLOGGER_BRIEF -> sb.append("""
                    你正在为一个具体博主在本项目下做内容方案 / 选题 / 脚本。
                    产出结构默认：选题方向 → 钩子 / 标题 → 开头 3 秒 → 正文结构 → CTA。
                    要结合该博主的人设标签、爆款结构，避免「把别人的模板硬套给这个博主」。
                    """);
            case CONTENT_REVIEW -> sb.append("""
                    你正在对该博主的交付稿做审阅。先指出必须改（合规/事实错误/与人设偏离），再给建议改。
                    输出「必须改 X 条 / 建议改 Y 条」的结构化清单。
                    """);
            case DATA_TRACK -> sb.append("""
                    你正在帮用户追踪该博主的数据表现。优先展示：新增互动、爆款 top3、互动率环比。
                    """);
            default -> { /* no extra guidance */ }
        }

        // 若绑定了 Creator，注入结构化档案
        if (scope.session.getCreatorId() != null) {
            String brief = buildCreatorBrief(scope.session.getCreatorId(), scope.user.getPrimaryOrg());
            if (brief != null) {
                sb.append("\n# 当前会话锁定的博主档案\n").append(brief);
            }
        }
    }

    private String buildCreatorBrief(Long creatorId, String orgTag) {
        try {
            Creator creator = creatorRepository.findById(creatorId)
                    .filter(c -> orgTag != null && orgTag.equals(c.getOwnerOrgTag()))
                    .orElse(null);
            if (creator == null) return null;

            StringBuilder sb = new StringBuilder();
            sb.append("- Creator ID: ").append(creator.getId()).append('\n');
            sb.append("- 名字: ").append(nullToDash(creator.getDisplayName()));
            if (creator.getRealName() != null && !creator.getRealName().isBlank()) {
                sb.append("（真名 ").append(creator.getRealName()).append("）");
            }
            sb.append('\n');
            if (creator.getPersonaTagsJson() != null && !creator.getPersonaTagsJson().isBlank()) {
                sb.append("- 人设标签: ").append(creator.getPersonaTagsJson()).append('\n');
            }
            if (creator.getTrackTagsJson() != null && !creator.getTrackTagsJson().isBlank()) {
                sb.append("- 赛道: ").append(creator.getTrackTagsJson()).append('\n');
            }
            if (creator.getPriceNote() != null && !creator.getPriceNote().isBlank()) {
                sb.append("- 公司对外报价备注: ").append(creator.getPriceNote()).append('\n');
            }
            if (creator.getCooperationStatus() != null) {
                sb.append("- 当前合作状态: ").append(creator.getCooperationStatus()).append('\n');
            }
            List<CreatorAccount> accounts = creatorService.getAccountsByCreator(creatorId, orgTag);
            if (!accounts.isEmpty()) {
                sb.append("- 平台账号:\n");
                for (CreatorAccount a : accounts) {
                    sb.append("  * ").append(a.getPlatform()).append(" @").append(nullToDash(a.getHandle()));
                    if (a.getFollowers() != null) sb.append(" 粉丝=").append(a.getFollowers());
                    if (a.getAvgLikes() != null) sb.append(" 均赞=").append(a.getAvgLikes());
                    if (a.getEngagementRate() != null) sb.append(" 互动率=").append(a.getEngagementRate());
                    sb.append(" (accountId=").append(a.getId()).append(")\n");
                }
                CreatorAccount main = accounts.get(0);
                List<CreatorPost> posts = creatorService.latestPostsOf(main.getId());
                if (!posts.isEmpty()) {
                    sb.append("- 最近笔记（取近 3 条做 metrics 摘要；详细内容请用 creator_get_posts）:\n");
                    int cap = Math.min(3, posts.size());
                    for (int i = 0; i < cap; i++) {
                        CreatorPost p = posts.get(i);
                        sb.append("  * ").append(truncate(p.getTitle(), 40));
                        if (p.getLikes() != null) sb.append(" 赞=").append(p.getLikes());
                        if (p.getComments() != null) sb.append(" 评=").append(p.getComments());
                        if (p.getPublishedAt() != null) sb.append(" @").append(p.getPublishedAt());
                        sb.append('\n');
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("buildCreatorBrief 失败 creatorId={} err={}", creatorId, e.getMessage());
            return null;
        }
    }

    private static String nullToDash(String s) { return s == null || s.isBlank() ? "-" : s; }

    private static String truncate(String s, int max) {
        if (s == null) return "-";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static final class FakeUnknownTool implements Tool {
        private final String name;
        private final JsonNode schema = new ObjectMapper().createObjectNode().put("type", "object");
        FakeUnknownTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "(unknown tool)"; }
        @Override public JsonNode inputSchema() { return schema; }
        @Override public ToolResult call(ToolContext ctx, JsonNode input) { return ToolResult.error("unknown_tool"); }
        @Override public boolean isReadOnly(JsonNode input) { return true; }
    }

    private record StepOutcome(Map<String, Object> assistantMessage,
                               List<ToolCallAccumulator.PendingToolCall> toolCalls,
                               String finishReason) {}

    private record TurnScope(User user, ChatSession session, Project project,
                             List<String> projectEnabledTools) {}
}
