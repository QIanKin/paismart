package com.yizhaoqi.smartpai.repository.creator;

import com.yizhaoqi.smartpai.model.creator.Creator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreatorRepository
        extends JpaRepository<Creator, Long>, JpaSpecificationExecutor<Creator> {

    Page<Creator> findByOwnerOrgTag(String ownerOrgTag, Pageable pageable);

    List<Creator> findByOwnerOrgTagAndDisplayName(String ownerOrgTag, String displayName);

    /** 用于后台定时任务遍历所有租户。结果通常 < 数百，直接内存迭代即可。 */
    @Query("SELECT DISTINCT c.ownerOrgTag FROM Creator c WHERE c.ownerOrgTag IS NOT NULL")
    List<String> findDistinctOwnerOrgTag();
}
