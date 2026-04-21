package com.yizhaoqi.smartpai.service.agent;

import com.yizhaoqi.smartpai.service.RateLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 对外的单一入口：把 WebSocket 收到的一条用户消息投递到 AgentRuntime。
 * 负责：
 *  - 限流（checkChatByUser）
 *  - 异步执行（避免阻塞 WebSocket IO 线程，复用已有的 chatMonitorExecutor）
 *  - 取消路由（用户点 stop 时 → AgentCancellationRegistry.cancel(sessionId)）
 *
 * ChatWebSocketHandler 继续调用 ChatHandler 即可（ChatHandler.processMessage 会退化成本类的委托）。
 */
@Component
public class AgentMessageDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(AgentMessageDispatcher.class);

    private final AgentRuntime agentRuntime;
    private final AgentHistoryStore historyStore;
    private final AgentCancellationRegistry cancellationRegistry;
    private final AgentEventPublisher eventPublisher;
    private final RateLimitService rateLimitService;
    private final ThreadPoolTaskExecutor executor;

    public AgentMessageDispatcher(AgentRuntime agentRuntime,
                                  AgentHistoryStore historyStore,
                                  AgentCancellationRegistry cancellationRegistry,
                                  AgentEventPublisher eventPublisher,
                                  RateLimitService rateLimitService,
                                  @Qualifier("chatMonitorExecutor") ThreadPoolTaskExecutor executor) {
        this.agentRuntime = agentRuntime;
        this.historyStore = historyStore;
        this.cancellationRegistry = cancellationRegistry;
        this.eventPublisher = eventPublisher;
        this.rateLimitService = rateLimitService;
        this.executor = executor;
    }

    public void dispatch(String userId, String userMessage, WebSocketSession session) {
        dispatch(userId, userMessage, null, null, session);
    }

    /**
     * 携带项目/会话 id 的 dispatch。sessionId/projectId 来自前端 WS frame 的 JSON 扩展字段；
     * 均可为空（为空时 AgentRuntime 自动走"默认会话"分支）。
     */
    public void dispatch(String userId, String userMessage, String sessionId, String projectId,
                         WebSocketSession session) {
        try {
            rateLimitService.checkChatByUser(userId);
        } catch (com.yizhaoqi.smartpai.exception.RateLimitExceededException e) {
            eventPublisher.publishRateLimit(session, null, e.getMessage(), e.getRetryAfterSeconds());
            return;
        }

        String conversationId = historyStore.getOrCreateConversationId(userId);
        AgentRequest req = AgentRequest.builder()
                .userId(userId)
                .conversationId(conversationId)
                .sessionId(sessionId)
                .projectId(projectId)
                .userMessage(userMessage)
                .build();

        executor.execute(() -> {
            try {
                agentRuntime.runTurn(req, session);
            } catch (Throwable t) {
                logger.error("Agent 轮次异常 user={} session={}", userId, session.getId(), t);
                eventPublisher.publishError(session, null,
                        t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
        });
    }

    public void stop(String userId, WebSocketSession session) {
        logger.info("收到 stop 指令 user={} session={}", userId, session.getId());
        cancellationRegistry.cancel(session.getId());
        eventPublisher.publishStopped(session, null);
    }
}
