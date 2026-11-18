package com.portops.lakehouse.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeeAdjustmentRepository extends JpaRepository<FeeAdjustment, Long> {

    List<FeeAdjustment> findByManifestIdOrderByCreatedAtAsc(Long manifestId);

    List<FeeAdjustment> findByManifestIdAndContainerNo(Long manifestId, String containerNo);
}
