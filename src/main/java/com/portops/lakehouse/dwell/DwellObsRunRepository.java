package com.portops.lakehouse.dwell;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DwellObsRunRepository extends JpaRepository<DwellObsRun, Long> {

    List<DwellObsRun> findByStudyIdOrderByVersionAsc(Long studyId);

    Optional<DwellObsRun> findTopByStudyIdOrderByVersionDesc(Long studyId);
}
