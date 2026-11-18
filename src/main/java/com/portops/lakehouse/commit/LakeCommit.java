package com.portops.lakehouse.commit;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 不可变提交：按 (箱号, 作业分区) 单调递增的提交序列。
 * 数据库触发器禁止 UPDATE/DELETE，保证提交不可变。
 */
@Entity
@Table(name = "lake_commit",
        uniqueConstraints = @UniqueConstraint(columnNames = {"container_no", "op_partition", "commit_seq"}))
public class LakeCommit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    @Column(name = "op_partition", nullable = false, length = 32)
    private String opPartition;

    /** 同一 (箱号, 分区) 内单调递增的提交序号 */
    @Column(name = "commit_seq", nullable = false)
    private Long commitSeq;

    /** 本提交包含的事件 id 列表（JSON 数组） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_ids", nullable = false, columnDefinition = "jsonb")
    private String eventIds;

    /** 提交后来源序号水位 {GATE: 12, CRANE: 5, ...} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "max_source_seqs", nullable = false, columnDefinition = "jsonb")
    private String maxSourceSeqs;

    /** 提交后推导出的箱子状态（位置/作业状态/航次等） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state", nullable = false, columnDefinition = "jsonb")
    private String state;

    /** 本提交覆盖的最大业务时点 */
    @Column(name = "max_biz_time", nullable = false)
    private OffsetDateTime maxBizTime;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getContainerNo() { return containerNo; }
    public void setContainerNo(String v) { this.containerNo = v; }
    public String getOpPartition() { return opPartition; }
    public void setOpPartition(String v) { this.opPartition = v; }
    public Long getCommitSeq() { return commitSeq; }
    public void setCommitSeq(Long v) { this.commitSeq = v; }
    public String getEventIds() { return eventIds; }
    public void setEventIds(String v) { this.eventIds = v; }
    public String getMaxSourceSeqs() { return maxSourceSeqs; }
    public void setMaxSourceSeqs(String v) { this.maxSourceSeqs = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public OffsetDateTime getMaxBizTime() { return maxBizTime; }
    public void setMaxBizTime(OffsetDateTime v) { this.maxBizTime = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
