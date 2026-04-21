package com.yizhaoqi.smartpai.repository.agent;

import com.yizhaoqi.smartpai.model.agent.ProjectCreator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectCreatorRepository
        extends JpaRepository<ProjectCreator, Long>, JpaSpecificationExecutor<ProjectCreator> {

    List<ProjectCreator> findByProjectIdOrderByPriorityDescIdDesc(Long projectId);

    Optional<ProjectCreator> findByProjectIdAndCreatorId(Long projectId, Long creatorId);

    List<ProjectCreator> findByCreatorId(Long creatorId);

    /** 被至少一个 active 项目绑着的博主，用于后台定时刷新。 */
    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT pc.creatorId FROM ProjectCreator pc " +
            "WHERE pc.ownerOrgTag = :orgTag AND pc.stage IN :stages")
    List<Long> findDistinctCreatorIdByOrgTagAndStages(String orgTag, List<ProjectCreator.Stage> stages);

    long countByProjectId(Long projectId);
}
