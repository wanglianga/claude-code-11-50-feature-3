package com.portops.lakehouse.dwell;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 滞留研究：统计窗口与阈值/分位点配置。
 * 配置不可变（数据库触发器兜底）；调整窗口或阈值须新建研究。
 */
@Entity
@Table(name = "dwell_study")
public class DwellStudy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "study_code", nullable = false, length = 40, unique = true)
    private String studyCode;

    /** 统计期起点：期初已在场箱以该时刻作为风险观察起点 */
    @Column(name = "period_start", nullable = false)
    private OffsetDateTime periodStart;

    /** 统计截止：期末仍在场箱在该时刻右删失（不登记为已离场） */
    @Column(name = "period_end", nullable = false)
    private OffsetDateTime periodEnd;

    /** 超期时限（小时）列表，如 [72, 168, 336] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "thresholds_hours", nullable = false, columnDefinition = "jsonb")
    private String thresholdsHours;

    /** 分位点列表，如 [0.5, 0.9] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quantiles", nullable = false, columnDefinition = "jsonb")
    private String quantiles;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStudyCode() { return studyCode; }
    public void setStudyCode(String s) { this.studyCode = s; }
    public OffsetDateTime getPeriodStart() { return periodStart; }
    public void setPeriodStart(OffsetDateTime t) { this.periodStart = t; }
    public OffsetDateTime getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(OffsetDateTime t) { this.periodEnd = t; }
    @com.fasterxml.jackson.annotation.JsonRawValue
    public String getThresholdsHours() { return thresholdsHours; }
    public void setThresholdsHours(String t) { this.thresholdsHours = t; }
    @com.fasterxml.jackson.annotation.JsonRawValue
    public String getQuantiles() { return quantiles; }
    public void setQuantiles(String q) { this.quantiles = q; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String c) { this.createdBy = c; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime t) { this.createdAt = t; }
}
