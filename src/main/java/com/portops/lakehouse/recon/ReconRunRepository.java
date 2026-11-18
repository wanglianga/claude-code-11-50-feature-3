package com.portops.lakehouse.recon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconRunRepository extends JpaRepository<ReconRun, Long> {

    List<ReconRun> findByManifestIdOrderByCreatedAtDesc(Long manifestId);
}
