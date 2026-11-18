package com.portops.lakehouse.dwell;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 管理报告：引用某次在场风险区间版本的估计快照，独立版本化，不可变。
 */
@Entity
@Table(name = "dwell_report",
        uniqueConstraints = @UniqueConstraint(columnNames = {"study_id", "version"}))
public class DwellReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "study_id", nullable = false)
    private Long studyId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    /** 报告快照：分组汇总、两路线对比、未决冲突清单、假设与敏感性披露 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false, columnDefinition = "jsonb")
    private String content;

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
    public Long getRunId() { return runId; }
    public void setRunId(Long r) { this.runId = r; }
    @com.fasterxml.jackson.annotation.JsonRawValue
    public String getContent() { return content; }
    public void setContent(String c) { this.content = c; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String c) { this.createdBy = c; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime t) { this.createdAt = t; }
}
