package com.yizhaoqi.smartpai.repository.agent;

import com.yizhaoqi.smartpai.model.agent.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByOwnerUserIdAndStatusOrderByUpdatedAtDesc(Long ownerUserId, Project.Status status);

    List<Project> findByOrgTagAndStatusOrderByUpdatedAtDesc(String orgTag, Project.Status status);

    Optional<Project> findByIdAndOwnerUserId(Long id, Long ownerUserId);
}
