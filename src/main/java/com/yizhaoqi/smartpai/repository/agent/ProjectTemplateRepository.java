package com.yizhaoqi.smartpai.repository.agent;

import com.yizhaoqi.smartpai.model.agent.ProjectTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectTemplateRepository extends JpaRepository<ProjectTemplate, Long> {

    Optional<ProjectTemplate> findByCode(String code);

    List<ProjectTemplate> findByEnabledTrueAndOwnerOrgTagIsNullOrderByDisplayOrderAsc();

    List<ProjectTemplate> findByEnabledTrueAndOwnerOrgTagOrderByDisplayOrderAsc(String ownerOrgTag);
}
