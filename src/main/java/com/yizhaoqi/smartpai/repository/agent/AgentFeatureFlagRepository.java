package com.yizhaoqi.smartpai.repository.agent;

import com.yizhaoqi.smartpai.model.agent.AgentFeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentFeatureFlagRepository extends JpaRepository<AgentFeatureFlag, Long> {

    Optional<AgentFeatureFlag> findByKey(String key);
}
