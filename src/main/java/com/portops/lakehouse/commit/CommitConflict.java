package com.portops.lakehouse.commit;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 待裁定冲突：多条来源链路对同一箱子的状态无法排序时产生。
 * 在人工裁定前，相关事件不进入任何提交，箱子状态不由"最后写入者"决定。
 */
@Entity
@Table(name = "commit_conflict")
public class CommitConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    @Column(name = "op_partition", nullable = false, length = 32)
    private String opPartition;

    /** 卷入冲突的事件 id 列表（JSON 数组） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_ids", nullable = false, columnDefinition = "jsonb")
    private String eventIds;

    /** BIZ_TIME_TIE=业务时点并列且效果冲突；SOURCE_SEQ_REGRESSION=来源序号与业务时点矛盾 */
    @Column(name = "reason", nullable = false, length = 40)
    private String reason;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    /** PENDING / RESOLVED */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolution", columnDefinition = "text")
    private String resolution;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getContainerNo() { return containerNo; }
    public void setContainerNo(String v) { this.containerNo = v; }
    public String getOpPartition() { return opPartition; }
    public void setOpPartition(String v) { this.opPartition = v; }
    public String getEventIds() { return eventIds; }
    public void setEventIds(String v) { this.eventIds = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String v) { this.resolvedBy = v; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime v) { this.resolvedAt = v; }
    public String getResolution() { return resolution; }
    public void setResolution(String v) { this.resolution = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
