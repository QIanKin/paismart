package com.yizhaoqi.smartpai.repository.creator;

import com.yizhaoqi.smartpai.model.creator.CreatorSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CreatorSnapshotRepository extends JpaRepository<CreatorSnapshot, Long> {

    List<CreatorSnapshot> findByAccountIdOrderBySnapshotAtDesc(Long accountId);

    Optional<CreatorSnapshot> findTopByAccountIdOrderBySnapshotAtDesc(Long accountId);
}
