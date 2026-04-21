package com.yizhaoqi.smartpai.service.agent;

import com.yizhaoqi.smartpai.model.agent.ChatSession;
import com.yizhaoqi.smartpai.model.agent.Project;
import com.yizhaoqi.smartpai.repository.agent.ChatSessionRepository;
import com.yizhaoqi.smartpai.repository.agent.ProjectRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话生命周期管理：获取/创建默认会话、按用户/项目拉列表、改标题、归档。
 */
@Service
public class ChatSessionService {

    private static final int DEFAULT_PAGE = 50;

    private final ChatSessionRepository sessionRepository;
    private final ProjectRepository projectRepository;

    public ChatSessionService(ChatSessionRepository sessionRepository, ProjectRepository projectRepository) {
        this.sessionRepository = sessionRepository;
        this.projectRepository = projectRepository;
    }

    /**
     * 为 userId 取一个"默认当前活跃会话"。逻辑：
     *  - 拉最近一条 active 会话；若 24h 内有活跃，沿用；
     *  - 否则新建一条默认会话（projectId=null）。
     * 这样旧用户（无项目）首次上来也能对接上 Agent Runtime。
     */
    @Transactional
    public ChatSession getOrCreateDefaultSession(Long userId, String orgTag) {
        List<ChatSession> recent = sessionRepository.findByUserIdAndStatusOrderByLastActiveAtDesc(
                userId, ChatSession.Status.active, PageRequest.of(0, 1));
        if (!recent.isEmpty()) {
            ChatSession s = recent.get(0);
            LocalDateTime last = s.getLastActiveAt() == null ? s.getUpdatedAt() : s.getLastActiveAt();
            if (last != null && last.isAfter(LocalDateTime.now().minusHours(24))) {
                return s;
            }
        }
        return createSession(userId, orgTag, null, "新对话");
    }

    @Transactional
    public ChatSession createSession(Long userId, String orgTag, Long projectId, String title) {
        return createSession(userId, orgTag, projectId, title, ChatSession.SessionType.GENERAL, null);
    }

    /**
     * 创建会话（扩展版）。会在保存前根据 sessionType + creatorId 做基本校验：
     *  - BLOGGER_BRIEF / CONTENT_REVIEW / DATA_TRACK 需要指定 creatorId；
     *  - ALLOCATION 不需要 creatorId；
     *  - GENERAL 允许但不依赖 creatorId。
     */
    @Transactional
    public ChatSession createSession(Long userId, String orgTag, Long projectId, String title,
                                     ChatSession.SessionType sessionType, Long creatorId) {
        ChatSession.SessionType type = sessionType == null ? ChatSession.SessionType.GENERAL : sessionType;
        if (requiresCreator(type) && creatorId == null) {
            throw new IllegalArgumentException("该会话类型必须绑定 creatorId: " + type);
        }
        ChatSession s = new ChatSession();
        s.setUserId(userId);
        s.setOrgTag(orgTag);
        s.setProjectId(projectId);
        s.setSessionType(type);
        s.setCreatorId(creatorId);
        s.setTitle(title == null || title.isBlank() ? defaultTitle(type) : title);
        s.setMessageCount(0);
        s.setNextSeq(1);
        s.setCompactedBeforeSeq(0);
        s.setStatus(ChatSession.Status.active);
        s.setLastActiveAt(LocalDateTime.now());
        return sessionRepository.save(s);
    }

    private static boolean requiresCreator(ChatSession.SessionType type) {
        return type == ChatSession.SessionType.BLOGGER_BRIEF
                || type == ChatSession.SessionType.CONTENT_REVIEW
                || type == ChatSession.SessionType.DATA_TRACK;
    }

    private static String defaultTitle(ChatSession.SessionType type) {
        return switch (type) {
            case ALLOCATION -> "博主分配";
            case BLOGGER_BRIEF -> "博主方案";
            case CONTENT_REVIEW -> "内容审稿";
            case DATA_TRACK -> "数据追踪";
            default -> "新对话";
        };
    }

    public List<ChatSession> listForUser(Long userId, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return sessionRepository.findByUserIdAndStatusOrderByLastActiveAtDesc(
                userId, ChatSession.Status.active, PageRequest.of(0, capped));
    }

    public List<ChatSession> listForProject(Long projectId) {
        return sessionRepository.findByProjectIdAndStatusOrderByLastActiveAtDesc(
                projectId, ChatSession.Status.active);
    }

    public List<ChatSession> listForProjectAndType(Long projectId, ChatSession.SessionType type) {
        return sessionRepository.findByProjectIdAndSessionTypeAndStatusOrderByLastActiveAtDesc(
                projectId, type, ChatSession.Status.active);
    }

    public List<ChatSession> listForProjectAndCreator(Long projectId, Long creatorId) {
        return sessionRepository.findByProjectIdAndCreatorIdAndStatusOrderByLastActiveAtDesc(
                projectId, creatorId, ChatSession.Status.active);
    }

    public ChatSession getOwned(Long sessionId, Long userId) {
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("session 不存在或无权限"));
    }

    @Transactional
    public ChatSession rename(Long sessionId, Long userId, String newTitle) {
        ChatSession s = getOwned(sessionId, userId);
        s.setTitle(newTitle == null ? "新对话" : newTitle);
        return sessionRepository.save(s);
    }

    @Transactional
    public void archive(Long sessionId, Long userId) {
        ChatSession s = getOwned(sessionId, userId);
        s.setStatus(ChatSession.Status.archived);
        sessionRepository.save(s);
    }

    /** 根据会话 id 反查 project，支持跨服务调用快速拿到 systemPrompt/tools 白名单 */
    public Project resolveProject(ChatSession session) {
        if (session.getProjectId() == null) return null;
        return projectRepository.findById(session.getProjectId()).orElse(null);
    }
}
