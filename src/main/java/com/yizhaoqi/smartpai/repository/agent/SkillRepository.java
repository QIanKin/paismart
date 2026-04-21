package com.yizhaoqi.smartpai.repository.agent;

import com.yizhaoqi.smartpai.model.agent.Skill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkillRepository extends JpaRepository<Skill, Long> {

    Optional<Skill> findByNameAndOwnerOrgTag(String name, String ownerOrgTag);

    List<Skill> findByEnabledTrueAndOwnerOrgTagIsNull();

    List<Skill> findByEnabledTrueAndOwnerOrgTag(String ownerOrgTag);

    List<Skill> findBySource(Skill.Source source);
}
