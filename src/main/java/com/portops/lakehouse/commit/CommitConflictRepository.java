package com.portops.lakehouse.commit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommitConflictRepository extends JpaRepository<CommitConflict, Long> {

    List<CommitConflict> findByStatusOrderByCreatedAtAsc(String status);

    List<CommitConflict> findByContainerNoOrderByCreatedAtAsc(String containerNo);

    boolean existsByContainerNoAndStatus(String containerNo, String status);
}
