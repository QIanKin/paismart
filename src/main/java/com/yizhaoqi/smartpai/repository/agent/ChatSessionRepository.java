package com.yizhaoqi.smartpai.repository.agent;

import com.yizhaoqi.smartpai.model.agent.ChatSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    List<ChatSession> findByUserIdAndStatusOrderByLastActiveAtDesc(Long userId, ChatSession.Status status, Pageable pageable);

    List<ChatSession> findByProjectIdAndStatusOrderByLastActiveAtDesc(Long projectId, ChatSession.Status status);

    List<ChatSession> findByProjectIdAndSessionTypeAndStatusOrderByLastActiveAtDesc(
            Long projectId, ChatSession.SessionType sessionType, ChatSession.Status status);

    List<ChatSession> findByProjectIdAndCreatorIdAndStatusOrderByLastActiveAtDesc(
            Long projectId, Long creatorId, ChatSession.Status status);

    Optional<ChatSession> findByIdAndUserId(Long id, Long userId);
}
