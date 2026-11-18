package com.portops.lakehouse.commit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface LakeCommitRepository extends JpaRepository<LakeCommit, Long> {

    Optional<LakeCommit> findTopByContainerNoAndOpPartitionOrderByCommitSeqDesc(String containerNo, String opPartition);

    List<LakeCommit> findByContainerNoOrderByOpPartitionAscCommitSeqAsc(String containerNo);

    List<LakeCommit> findByContainerNoAndOpPartitionOrderByCommitSeqAsc(String containerNo, String opPartition);

    /** 某 (箱,分区) 在指定业务时点水位下的最新提交 */
    Optional<LakeCommit> findTopByContainerNoAndOpPartitionAndMaxBizTimeLessThanEqualOrderByCommitSeqDesc(
            String containerNo, String opPartition, OffsetDateTime cutoff);
}
