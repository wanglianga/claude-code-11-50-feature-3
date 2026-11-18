package com.portops.lakehouse.dwell;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DwellStudyRepository extends JpaRepository<DwellStudy, Long> {

    boolean existsByStudyCode(String studyCode);

    Optional<DwellStudy> findByStudyCode(String studyCode);
}
