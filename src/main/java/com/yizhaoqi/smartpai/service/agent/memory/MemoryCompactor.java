package com.yizhaoqi.smartpai.service.agent.memory;

import com.yizhaoqi.smartpai.model.agent.AgentMessage;
import com.yizhaoqi.smartpai.model.agent.MemoryItem;
import com.yizhaoqi.smartpai.repository.agent.AgentMessageRepository;
import com.yizhaoqi.smartpai.repository.agent.ChatSessionRepository;
import com.yizhaoqi.smartpai.repository.agent.MemoryItemRepository;
import com.yizhaoqi.smartpai.service.LlmProviderRouter;
import com.yizhaoqi.smartpai.service.LlmStreamCallback;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 长期记忆压缩器。
 *
 * 触发时机（由外部调度）：
 *  - 某会话活跃消息数 > 压缩阈值（默认 80 条）；
 *  - 或 AgentRuntime 结束一轮后发现 token 压力大（ContextEngine 产生过多 drop）。
 *
 * 策略：
 *  - 取 [compactedBeforeSeq+1, targetSeq] 区间的消息；
 *  - 调 LLM 一次，按 "decision log" 风格压成摘要（保留：意图、关键事实、待办、决策）；
 *  - 写 MemoryItem（source=compaction）；
 *  - 把这段消息 compacted=true；更新 session.compactedBeforeSeq。
 *
 * 保留尾部消息数量通过 {@code keepTail} 控制（默认 20）——必须确保上下文连贯性。
 */
@Service
public class MemoryCompactor {

    private static final Logger logger = LoggerFactory.getLogger(MemoryCompactor.class);
    private static final long LLM_TIMEOUT_MS = 90_000L;

    private final AgentMessageRepository messageRepository;
    private final MemoryItemRepository memoryItemRepository;
    private final ChatSessionRepository sessionRepository;
    private final MessageStore messageStore;
    private final LlmProviderRouter llmProviderRouter;
    private final UsageQuotaService usageQuotaService;

    public MemoryCompactor(AgentMessageRepository messageRepository,
                           MemoryItemRepository memoryItemRepository,
                           ChatSessionRepository sessionRepository,
                           MessageStore messageStore,
                           LlmProviderRouter llmProviderRouter,
                           UsageQuotaService usageQuotaService) {
        this.messageRepository = messageRepository;
        this.memoryItemRepository = memoryItemRepository;
        this.sessionRepository = sessionRepository;
        this.messageStore = messageStore;
        this.llmProviderRouter = llmProviderRouter;
        this.usageQuotaService = usageQuotaService;
    }

    public record CompactionResult(boolean compacted, Long memoryItemId, int fromSeq, int toSeq, int keptTail) {}

    /**
     * 若 session 消息数超过 totalThreshold，则压缩到只剩最近 keepTail 条非压缩消息。
     * 否则直接返回 compacted=false。
     *
     * @param requesterId LLM 调用的配额 owner（通常就是 userId 字符串）
     */
    @Transactional
    public CompactionResult maybeCompactSession(Long sessionId,
                                                String requesterId,
                                                int totalThreshold,
                                                int keepTail) {
        var sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) return new CompactionResult(false, null, 0, 0, 0);
        var session = sessionOpt.get();

        // 仅处理未压缩的部分
        int compactedBefore = session.getCompactedBeforeSeq() == null ? 0 : session.getCompactedBeforeSeq();
        int nextSeq = session.getNextSeq() == null ? 1 : session.getNextSeq();
        int live = (nextSeq - 1) - compactedBefore;
        if (live < totalThreshold) {
            return new CompactionResult(false, null, 0, 0, 0);
        }

        int toSeq = (nextSeq - 1) - keepTail;
        int fromSeq = compactedBefore + 1;
        if (toSeq < fromSeq) {
            return new CompactionResult(false, null, 0, 0, 0);
        }

        List<AgentMessage> range = messageRepository.findBySessionIdAndSeqBetweenOrderBySeqAsc(sessionId, fromSeq, toSeq);
        if (range.isEmpty()) return new CompactionResult(false, null, 0, 0, 0);

        String summary = summarizeViaLlm(requesterId, range);
        if (summary == null || summary.isBlank()) {
            logger.warn("MemoryCompactor 摘要为空，跳过 sessionId={} range=[{},{}]", sessionId, fromSeq, toSeq);
            return new CompactionResult(false, null, 0, 0, 0);
        }

        MemoryItem item = new MemoryItem();
        item.setUserId(session.getUserId());
        item.setOrgTag(session.getOrgTag());
        item.setSessionId(sessionId);
        item.setProjectId(session.getProjectId());
        item.setSource(MemoryItem.Source.compaction);
        item.setTitle(deriveTitle(summary));
        item.setFullText(summary);
        item.setFromSeq(fromSeq);
        item.setToSeq(toSeq);
        item.setTokenEstimate(usageQuotaService.estimateTextTokens(summary));
        MemoryItem saved = memoryItemRepository.save(item);

        int updated = messageRepository.markCompacted(sessionId, fromSeq, toSeq);
        session.setCompactedBeforeSeq(toSeq);
        sessionRepository.save(session);
        messageStore.invalidateL1(sessionId);

        logger.info("MemoryCompactor 完成 sessionId={} range=[{},{}] marked={} memoryItemId={} tokens={}",
                sessionId, fromSeq, toSeq, updated, saved.getId(), item.getTokenEstimate());

        return new CompactionResult(true, saved.getId(), fromSeq, toSeq, keepTail);
    }

    private String summarizeViaLlm(String requesterId, List<AgentMessage> range) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content",
                "你是对话归档助手。把下面的用户与 agent 的对话片段，压缩成决策日志风格的中文摘要："
                        + "重点保留：用户意图、关键事实、已决定/放弃的方案、剩余待办、引用过的外部资源（URL/文件/博主等）。"
                        + "丢弃：寒暄、重复确认、工具调用细节的噪音、低信息密度的段落。"
                        + "控制在 800 字以内，使用小节标题分隔：意图 / 事实 / 决策 / 待办 / 资源。"));

        StringBuilder flat = new StringBuilder();
        for (AgentMessage m : range) {
            flat.append('[').append(m.getSeq()).append("] ").append(m.getRole().name()).append(": ");
            if (m.getContent() != null) flat.append(m.getContent());
            if (m.getRole() == AgentMessage.Role.assistant && m.getToolCallsJson() != null) {
                flat.append("  <tool_calls> ").append(m.getToolCallsJson());
            }
            if (m.getRole() == AgentMessage.Role.tool) {
                flat.append("  <tool=").append(m.getToolName()).append('>');
            }
            flat.append('\n');
        }
        msgs.add(Map.of("role", "user", "content", flat.toString()));

        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder buf = new StringBuilder();
        llmProviderRouter.streamChat(requesterId, msgs, null, "none", new LlmStreamCallback() {
            @Override public void onContent(String delta) { buf.append(delta); }
            @Override public void onComplete() { future.complete(buf.toString()); }
            @Override public void onError(Throwable error) {
                if (!future.isDone()) future.completeExceptionally(error);
            }
        });
        try {
            return future.get(LLM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.warn("MemoryCompactor 调用 LLM 摘要失败: {}", e.getMessage());
            return null;
        }
    }

    private String deriveTitle(String summary) {
        int end = Math.min(summary.length(), 60);
        String head = summary.substring(0, end).replaceAll("\\s+", " ").trim();
        return head.isEmpty() ? "历史摘要" : head;
    }
}
