package com.yizhaoqi.smartpai.repository.xhs;

import com.yizhaoqi.smartpai.model.xhs.XhsLoginSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface XhsLoginSessionRepository extends JpaRepository<XhsLoginSession, Long> {

    Optional<XhsLoginSession> findBySessionId(String sessionId);

    List<XhsLoginSession> findByOwnerOrgTagOrderByIdDesc(String ownerOrgTag);

    /** 用于定时回收：取所有超时但仍在非终态的会话。 */
    List<XhsLoginSession> findByStatusInAndExpiresAtBefore(
            List<XhsLoginSession.Status> statuses, LocalDateTime cutoff);
}
