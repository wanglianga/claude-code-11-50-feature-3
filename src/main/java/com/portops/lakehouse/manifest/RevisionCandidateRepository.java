package com.portops.lakehouse.manifest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RevisionCandidateRepository extends JpaRepository<RevisionCandidate, Long> {

    List<RevisionCandidate> findByManifestIdOrderByCreatedAtAsc(Long manifestId);

    List<RevisionCandidate> findByStatusOrderByCreatedAtAsc(String status);

    boolean existsByManifestIdAndContainerNoAndEventIdAndStatus(
            Long manifestId, String containerNo, Long eventId, String status);
}
