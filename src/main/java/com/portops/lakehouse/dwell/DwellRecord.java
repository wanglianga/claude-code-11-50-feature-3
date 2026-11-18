package com.portops.lakehouse.dwell;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 每箱删失/截断记录：由已提交事件证据构造，属于某个在场风险区间版本，不可变。
 *
 * <p>时间证据一律以区间表达，不把盘点区间中点当作精确时刻：</p>
 * <ul>
 *   <li>进场：精确闸口进场（EXACT）或证据区间 [entryLower, entryUpper]，
 *       entryLower 为 NULL 表示真实进场早于全部证据（下界无界）；</li>
 *   <li>离场：精确离场（EXACT）、区间离场（INTERVAL）、期末仍在场右删失（RIGHT_CENSORED）、
 *       证据冲突包络（CONFLICTED，取各解释的最宽并集，不挑选较短时长）；</li>
 *   <li>剩余滞留 = 观察终点 − 风险观察起点（期初在场箱观察起点为统计期起点）；</li>
 *   <li>总堆存 = 离场 − 真实进场；真实进场未知时上界为 NULL（+∞），不伪装成已知。</li>
 * </ul>
 */
@Entity
@Table(name = "dwell_record",
        uniqueConstraints = @UniqueConstraint(columnNames = {"run_id", "container_no"}))
public class DwellRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    @Column(name = "voyage_no", length = 32)
    private String voyageNo;

    @Column(name = "container_type", length = 16)
    private String containerType;

    @Column(name = "block", length = 16)
    private String block;

    /** EXACT / INTERVAL（含下界无界与冲突包络） */
    @Column(name = "entry_kind", nullable = false, length = 24)
    private String entryKind;

    @Column(name = "entry_exact")
    private OffsetDateTime entryExact;

    /** 进场证据下界；NULL = 真实进场早于全部证据（下界无界） */
    @Column(name = "entry_lower")
    private OffsetDateTime entryLower;

    /** 进场证据上界（进场不晚于该时刻） */
    @Column(name = "entry_upper")
    private OffsetDateTime entryUpper;

    /** EXACT / INTERVAL / RIGHT_CENSORED / CONFLICTED */
    @Column(name = "exit_kind", nullable = false, length = 24)
    private String exitKind;

    @Column(name = "exit_exact")
    private OffsetDateTime exitExact;

    @Column(name = "exit_lower")
    private OffsetDateTime exitLower;

    /** NULL = 上界无界（仍在场或冲突包络） */
    @Column(name = "exit_upper")
    private OffsetDateTime exitUpper;

    /** 期初已在场（延迟进入）：以统计期起点作为风险观察起点 */
    @Column(name = "left_truncated", nullable = false)
    private boolean leftTruncated;

    /** 期末/最后在场证明时仍在场：仅右删失，不登记为已离场 */
    @Column(name = "right_censored", nullable = false)
    private boolean rightCensored;

    /** 进场或离场仅有区间证据 */
    @Column(name = "interval_censored", nullable = false)
    private boolean intervalCensored;

    /** 风险观察起点区间下界 = max(统计期起点, 进场下界) */
    @Column(name = "obs_start_lower", nullable = false)
    private OffsetDateTime obsStartLower;

    /** 风险观察起点区间上界 = max(统计期起点, 进场上界) */
    @Column(name = "obs_start_upper", nullable = false)
    private OffsetDateTime obsStartUpper;

    /** 自观察起点剩余滞留可行下界（小时） */
    @Column(name = "remain_lower_hours", nullable = false)
    private Double remainLowerHours;

    /** 剩余滞留可行上界（小时）；NULL = +∞ */
    @Column(name = "remain_upper_hours")
    private Double remainUpperHours;

    /** 总堆存可行下界（小时），仅用可核实进场区间 */
    @Column(name = "total_lower_hours", nullable = false)
    private Double totalLowerHours;

    /** 总堆存可行上界（小时）；NULL = +∞（真实进场未知，不伪装已知） */
    @Column(name = "total_upper_hours")
    private Double totalUpperHours;

    /** NONE / DUPLICATE_ENTRY / ENTRY_AFTER_PRESENCE / PRESENCE_AFTER_EXIT /
     *  EXIT_BEFORE_ENTRY / PRESENCE_GAP / EVENTS_PENDING_ARBITRATION */
    @Column(name = "conflict_status", nullable = false, length = 32)
    private String conflictStatus = "NONE";

    @Column(name = "conflict_detail", columnDefinition = "text")
    private String conflictDetail;

    /** 支撑该记录的已提交事件 id（可溯源） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_event_ids", nullable = false, columnDefinition = "jsonb")
    private String evidenceEventIds;

    /** 未提交/待裁定事件 id（披露，不纳入证据） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_event_ids", nullable = false, columnDefinition = "jsonb")
    private String pendingEventIds;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long r) { this.runId = r; }
    public String getContainerNo() { return containerNo; }
    public void setContainerNo(String c) { this.containerNo = c; }
    public String getVoyageNo() { return voyageNo; }
    public void setVoyageNo(String v) { this.voyageNo = v; }
    public String getContainerType() { return containerType; }
    public void setContainerType(String t) { this.containerType = t; }
    public String getBlock() { return block; }
    public void setBlock(String b) { this.block = b; }
    public String getEntryKind() { return entryKind; }
    public void setEntryKind(String k) { this.entryKind = k; }
    public OffsetDateTime getEntryExact() { return entryExact; }
    public void setEntryExact(OffsetDateTime t) { this.entryExact = t; }
    public OffsetDateTime getEntryLower() { return entryLower; }
    public void setEntryLower(OffsetDateTime t) { this.entryLower = t; }
    public OffsetDateTime getEntryUpper() { return entryUpper; }
    public void setEntryUpper(OffsetDateTime t) { this.entryUpper = t; }
    public String getExitKind() { return exitKind; }
    public void setExitKind(String k) { this.exitKind = k; }
    public OffsetDateTime getExitExact() { return exitExact; }
    public void setExitExact(OffsetDateTime t) { this.exitExact = t; }
    public OffsetDateTime getExitLower() { return exitLower; }
    public void setExitLower(OffsetDateTime t) { this.exitLower = t; }
    public OffsetDateTime getExitUpper() { return exitUpper; }
    public void setExitUpper(OffsetDateTime t) { this.exitUpper = t; }
    public boolean isLeftTruncated() { return leftTruncated; }
    public void setLeftTruncated(boolean v) { this.leftTruncated = v; }
    public boolean isRightCensored() { return rightCensored; }
    public void setRightCensored(boolean v) { this.rightCensored = v; }
    public boolean isIntervalCensored() { return intervalCensored; }
    public void setIntervalCensored(boolean v) { this.intervalCensored = v; }
    public OffsetDateTime getObsStartLower() { return obsStartLower; }
    public void setObsStartLower(OffsetDateTime t) { this.obsStartLower = t; }
    public OffsetDateTime getObsStartUpper() { return obsStartUpper; }
    public void setObsStartUpper(OffsetDateTime t) { this.obsStartUpper = t; }
    public Double getRemainLowerHours() { return remainLowerHours; }
    public void setRemainLowerHours(Double v) { this.remainLowerHours = v; }
    public Double getRemainUpperHours() { return remainUpperHours; }
    public void setRemainUpperHours(Double v) { this.remainUpperHours = v; }
    public Double getTotalLowerHours() { return totalLowerHours; }
    public void setTotalLowerHours(Double v) { this.totalLowerHours = v; }
    public Double getTotalUpperHours() { return totalUpperHours; }
    public void setTotalUpperHours(Double v) { this.totalUpperHours = v; }
    public String getConflictStatus() { return conflictStatus; }
    public void setConflictStatus(String s) { this.conflictStatus = s; }
    public String getConflictDetail() { return conflictDetail; }
    public void setConflictDetail(String d) { this.conflictDetail = d; }
    @com.fasterxml.jackson.annotation.JsonRawValue
    public String getEvidenceEventIds() { return evidenceEventIds; }
    public void setEvidenceEventIds(String e) { this.evidenceEventIds = e; }
    @com.fasterxml.jackson.annotation.JsonRawValue
    public String getPendingEventIds() { return pendingEventIds; }
    public void setPendingEventIds(String p) { this.pendingEventIds = p; }
}
