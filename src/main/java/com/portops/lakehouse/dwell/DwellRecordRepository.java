package com.portops.lakehouse.dwell;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DwellRecordRepository extends JpaRepository<DwellRecord, Long> {

    List<DwellRecord> findByRunIdOrderByContainerNoAsc(Long runId);

    Optional<DwellRecord> findByRunIdAndContainerNo(Long runId, String containerNo);
}
