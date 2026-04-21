package com.yizhaoqi.smartpai.repository.agent;

import com.yizhaoqi.smartpai.model.agent.AgentMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    /** 按 seq 升序拉完整历史（用于落库后完整取回） */
    List<AgentMessage> findBySessionIdOrderBySeqAsc(Long sessionId);

    /** 按 seq 降序拉最近 N 条（Pageable with limit），供短期记忆回填 */
    List<AgentMessage> findBySessionIdAndCompactedFalseOrderBySeqDesc(Long sessionId, Pageable pageable);

    /** 某 session 在 [fromSeq, toSeq] 之间的所有消息（供 compactor 批量处理） */
    List<AgentMessage> findBySessionIdAndSeqBetweenOrderBySeqAsc(Long sessionId, Integer fromSeq, Integer toSeq);

    @Modifying
    @Query("update AgentMessage m set m.compacted = true where m.sessionId = :sessionId and m.seq between :fromSeq and :toSeq")
    int markCompacted(@Param("sessionId") Long sessionId,
                      @Param("fromSeq") Integer fromSeq,
                      @Param("toSeq") Integer toSeq);
}
