package com.yizhaoqi.smartpai.repository.creator;

import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CreatorAccountRepository
        extends JpaRepository<CreatorAccount, Long>, JpaSpecificationExecutor<CreatorAccount> {

    Optional<CreatorAccount> findByPlatformAndPlatformUserId(String platform, String platformUserId);

    List<CreatorAccount> findByCreatorId(Long creatorId);

    Page<CreatorAccount> findByOwnerOrgTag(String ownerOrgTag, Pageable pageable);

    Page<CreatorAccount> findByOwnerOrgTagAndPlatform(String ownerOrgTag, String platform, Pageable pageable);
}
