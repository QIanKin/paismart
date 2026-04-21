package com.yizhaoqi.smartpai.repository.agent;

import com.yizhaoqi.smartpai.model.agent.MemoryItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MemoryItemRepository extends JpaRepository<MemoryItem, Long> {

    List<MemoryItem> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    List<MemoryItem> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<MemoryItem> findByUserIdAndSourceOrderByCreatedAtDesc(Long userId, MemoryItem.Source source);

    Optional<MemoryItem> findByEsDocId(String esDocId);

    /** MemoryRecallService 使用：按 session 内最新优先 */
    List<MemoryItem> findBySessionIdOrderByIdDesc(Long sessionId);

    /** 项目级 user_note/fact/... 不分页，一般数量不大 */
    List<MemoryItem> findByProjectIdAndSourceInOrderByIdDesc(Long projectId, List<MemoryItem.Source> sources);

    /** 分页拉 compaction 摘要 */
    List<MemoryItem> findByProjectIdAndSourceOrderByIdDesc(Long projectId, MemoryItem.Source source, Pageable pageable);

    List<MemoryItem> findByUserIdAndSourceOrderByIdDesc(Long userId, MemoryItem.Source source, Pageable pageable);
}
