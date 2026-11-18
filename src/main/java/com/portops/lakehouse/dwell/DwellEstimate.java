package com.portops.lakehouse.dwell;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 统计估计：路线一（非参数可行界，只发布证据支持的界限）与
 * 路线二（Weibull 生存模型点估计 + 假设与敏感性披露），按分组与时间尺度落库，不可变。
 */
@Entity
@Table(name = "dwell_estimate")
public class DwellEstimate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    /** BOUNDS / MODEL */
    @Column(name = "route", nullable = false, length = 16)
    private String route;

    /** REMAINING（自观察起点剩余滞留）/ TOTAL（总堆存） */
    @Column(name = "time_scale", nullable = false, length = 16)
    private String timeScale;

    /** ALL / BLOCK / VOYAGE / TYPE */
    @Column(name = "group_dim", nullable = false, length = 16)
    private String groupDim;

    @Column(name = "group_key", nullable = false, length = 64)
    private String groupKey;

    /** 纳入该组估计的全部目标箱数（含删失与冲突箱，不以完整记录子集替代总体） */
    @Column(name = "sample_size", nullable = false)
    private Integer sampleSize;

    @Column(name = "conflict_count", nullable = false)
    private Integer conflictCount;

    /** OVERDUE_PROPORTION / QUANTILE / GROUP_SUMMARY */
    @Column(name = "metric", nullable = false, length = 24)
    private String metric;

    /** 时限小时数或分位点；GROUP_SUMMARY 为 '-' */
    @Column(name = "metric_arg", nullable = false, length = 16)
    private String metricArg;

    /** 路线二点估计；路线一为 NULL（只发布界限） */
    @Column(name = "point_estimate")
    private Double pointEstimate;

    /** 路线一可行下界 */
    @Column(name = "lower_bound")
    private Double lowerBound;

    /** 路线一可行上界；NULL = 上界不可识别（+∞） */
    @Column(name = "upper_bound")
    private Double upperBound;

    /** 覆盖率、模型参数、假设与敏感性披露 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false, columnDefinition = "jsonb")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long r) { this.runId = r; }
    public String getRoute() { return route; }
    public void setRoute(String r) { this.route = r; }
    public String getTimeScale() { return timeScale; }
    public void setTimeScale(String t) { this.timeScale = t; }
    public String getGroupDim() { return groupDim; }
    public void setGroupDim(String g) { this.groupDim = g; }
    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String g) { this.groupKey = g; }
    public Integer getSampleSize() { return sampleSize; }
    public void setSampleSize(Integer s) { this.sampleSize = s; }
    public Integer getConflictCount() { return conflictCount; }
    public void setConflictCount(Integer c) { this.conflictCount = c; }
    public String getMetric() { return metric; }
    public void setMetric(String m) { this.metric = m; }
    public String getMetricArg() { return metricArg; }
    public void setMetricArg(String a) { this.metricArg = a; }
    public Double getPointEstimate() { return pointEstimate; }
    public void setPointEstimate(Double p) { this.pointEstimate = p; }
    public Double getLowerBound() { return lowerBound; }
    public void setLowerBound(Double l) { this.lowerBound = l; }
    public Double getUpperBound() { return upperBound; }
    public void setUpperBound(Double u) { this.upperBound = u; }
    @com.fasterxml.jackson.annotation.JsonRawValue
    public String getDetail() { return detail; }
    public void setDetail(String d) { this.detail = d; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime t) { this.createdAt = t; }
}
