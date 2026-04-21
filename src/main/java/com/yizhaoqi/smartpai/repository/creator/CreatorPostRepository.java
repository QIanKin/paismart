package com.yizhaoqi.smartpai.repository.creator;

import com.yizhaoqi.smartpai.model.creator.CreatorPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CreatorPostRepository
        extends JpaRepository<CreatorPost, Long>, JpaSpecificationExecutor<CreatorPost> {

    Optional<CreatorPost> findByPlatformAndPlatformPostId(String platform, String platformPostId);

    Page<CreatorPost> findByAccountId(Long accountId, Pageable pageable);

    Page<CreatorPost> findByAccountIdOrderByPublishedAtDesc(Long accountId, Pageable pageable);

    List<CreatorPost> findTop20ByAccountIdOrderByPublishedAtDesc(Long accountId);

    Page<CreatorPost> findByOwnerOrgTag(String ownerOrgTag, Pageable pageable);
}
