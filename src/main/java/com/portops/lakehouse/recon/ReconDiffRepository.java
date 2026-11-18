package com.portops.lakehouse.recon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconDiffRepository extends JpaRepository<ReconDiff, Long> {

    List<ReconDiff> findByRunIdOrderByContainerNoAscIdAsc(Long runId);

    List<ReconDiff> findByStatus(String status);
}
