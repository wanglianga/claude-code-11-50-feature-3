package com.portops.lakehouse.ingest;

import com.portops.lakehouse.common.EventStatus;
import com.portops.lakehouse.common.SourceSystem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface IngestEventRepository extends JpaRepository<IngestEvent, Long> {

    boolean existsBySourceSystemAndSourceSeq(SourceSystem sourceSystem, Long sourceSeq);

    Optional<IngestEvent> findBySourceSystemAndSourceSeq(SourceSystem sourceSystem, Long sourceSeq);

    List<IngestEvent> findByContainerNoOrderByBizTimeAscSourceSystemAscSourceSeqAsc(String containerNo);

    List<IngestEvent> findByContainerNoAndStatusOrderByBizTimeAscSourceSystemAscSourceSeqAsc(
            String containerNo, EventStatus status);

    List<IngestEvent> findByContainerNoAndStatus(String containerNo, EventStatus status);

    @Query("select e from IngestEvent e where e.status = :status order by e.containerNo, e.opPartition, e.bizTime, e.sourceSystem, e.sourceSeq")
    List<IngestEvent> findByStatusOrdered(@Param("status") EventStatus status);

    @Query("select distinct e.containerNo from IngestEvent e where e.status = 'RECEIVED'")
    List<String> findPendingContainers();

    @Query("select e from IngestEvent e where e.containerNo = :c and e.status = 'COMMITTED' " +
            "order by e.bizTime, e.sourceSystem, e.sourceSeq")
    List<IngestEvent> findCommitted(@Param("c") String containerNo);

    @Query("select e from IngestEvent e where e.containerNo = :c and e.status = 'COMMITTED' and e.bizTime <= :cutoff " +
            "order by e.bizTime, e.sourceSystem, e.sourceSeq")
    List<IngestEvent> findCommittedUpTo(@Param("c") String containerNo, @Param("cutoff") OffsetDateTime cutoff);

    /** 指定提交集合包含的全部事件（按排序键），用于从完整提交边界推导快照 */
    List<IngestEvent> findByCommitIdInOrderByBizTimeAscSourceSystemAscSourceSeqAsc(java.util.Collection<Long> commitIds);

    long countByContainerNoAndStatusAndBizTimeLessThanEqual(String containerNo, EventStatus status, OffsetDateTime cutoff);

    /** 全部出现过的箱号（滞留分析的目标总体枚举） */
    @Query("select distinct e.containerNo from IngestEvent e order by e.containerNo")
    List<String> findAllContainerNos();

    /** 事件账水位：最大事件 id（滞留分析 run 的作业事件版本标识） */
    @Query("select coalesce(max(e.id), 0) from IngestEvent e")
    long maxEventId();

    long countByStatus(EventStatus status);
}
