package com.yizhaoqi.smartpai.repository.creator;

import com.yizhaoqi.smartpai.model.creator.CustomFieldDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomFieldDefinitionRepository extends JpaRepository<CustomFieldDefinition, Long> {

    List<CustomFieldDefinition> findByOwnerOrgTagAndEntityTypeOrderByDisplayOrderAsc(
            String ownerOrgTag, String entityType);

    Optional<CustomFieldDefinition> findByOwnerOrgTagAndEntityTypeAndFieldKey(
            String ownerOrgTag, String entityType, String fieldKey);
}
