package com.portops.lakehouse.dwell;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 在场风险区间版本：一次证据抽取 + 两条路线估计构成一个版本。
 * 迟到离场事件入账后重新抽取只产生新版本，旧版本不可改写（触发器兜底）。
 */
@Entity
@Table(name = "dwell_obs_run",
        uniqueConstraints = @UniqueConstraint(columnNames = {"study_id", "version"}))
public class DwellObsRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "study_id", nullable = false)
    private Long studyId;

    /** 版本号：study 内单调递增 */
    @Column(name = "version", nullable = false)
    private Integer version;

    /** 本次抽取覆盖到的 ingest_event 最大 id：作业事件版本标识 */
    @Column(name = "event_watermark", nullable = false)
    private Long eventWatermark;

    @Column(name = "committed_event_count", nullable = false)
    private Integer committedEventCount;

    @Column(name = "container_count", nullable = false)
    private Integer containerCount;

    /** 被排除箱及原因（如仅有未提交事件），披露而非静默丢弃 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "excluded_containers", nullable = false, columnDefinition = "jsonb")
    private String excludedContainers;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "COMPUTED";

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStudyId() { return studyId; }
    public void setStudyId(Long s) { this.studyId = s; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer v) { this.version = v; }
    public Long getEventWatermark() { return eventWatermark; }
    public void setEventWatermark(Long w) { this.eventWatermark = w; }
    public Integer getCommittedEventCount() { return committedEventCount; }
    public void setCommittedEventCount(Integer c) { this.committedEventCount = c; }
    public Integer getContainerCount() { return containerCount; }
    public void setContainerCount(Integer c) { this.containerCount = c; }
    @com.fasterxml.jackson.annotation.JsonRawValue
    public String getExcludedContainers() { return excludedContainers; }
    public void setExcludedContainers(String e) { this.excludedContainers = e; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String c) { this.createdBy = c; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime t) { this.createdAt = t; }
}
