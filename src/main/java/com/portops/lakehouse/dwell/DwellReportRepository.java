package com.portops.lakehouse.dwell;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DwellReportRepository extends JpaRepository<DwellReport, Long> {

    List<DwellReport> findByStudyIdOrderByVersionAsc(Long studyId);

    Optional<DwellReport> findTopByStudyIdOrderByVersionDesc(Long studyId);
}
