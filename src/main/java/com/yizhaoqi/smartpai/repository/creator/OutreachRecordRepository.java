package com.yizhaoqi.smartpai.repository.creator;

import com.yizhaoqi.smartpai.model.creator.OutreachRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OutreachRecordRepository
        extends JpaRepository<OutreachRecord, Long>, JpaSpecificationExecutor<OutreachRecord> {

    Page<OutreachRecord> findByOwnerOrgTag(String ownerOrgTag, Pageable pageable);

    Page<OutreachRecord> findByOwnerOrgTagAndProjectId(String ownerOrgTag, String projectId, Pageable pageable);

    Page<OutreachRecord> findByOwnerOrgTagAndSessionId(String ownerOrgTag, String sessionId, Pageable pageable);

    List<OutreachRecord> findByOwnerOrgTagAndPlatformAndPlatformUserIdOrderByIdDesc(
            String ownerOrgTag, String platform, String platformUserId);

    /** 判断是否已经对同一个博主同一个笔记做过同种动作（重复外联保护） */
    Optional<OutreachRecord> findFirstByPlatformAndPlatformUserIdAndPostIdAndAction(
            String platform, String platformUserId, String postId, OutreachRecord.Action action);

    long countByOwnerOrgTagAndProjectIdAndStatus(
            String ownerOrgTag, String projectId, OutreachRecord.Status status);
}
