package com.yizhaoqi.smartpai.repository.agent;

import com.yizhaoqi.smartpai.model.agent.ScheduledSkillTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduledSkillTaskRepository extends JpaRepository<ScheduledSkillTask, Long> {

    List<ScheduledSkillTask> findByEnabledTrue();

    List<ScheduledSkillTask> findBySkillName(String skillName);

    Optional<ScheduledSkillTask> findByNameAndOrgTag(String name, String orgTag);

    List<ScheduledSkillTask> findByOrgTagOrderByIdDesc(String orgTag);

    List<ScheduledSkillTask> findByProjectIdOrderByIdDesc(Long projectId);
}
