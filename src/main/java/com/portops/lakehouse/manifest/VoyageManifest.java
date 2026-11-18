package com.portops.lakehouse.manifest;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 航次清单（截关快照）：从已提交数据冻结，冻结后不可修改、不可删除。
 * commitFrontier 记录本清单引用的完整提交清单（箱|分区 → 提交ID），
 * 保证清单只引用完整提交，不会发布半个分区。
 */
@Entity
@Table(name = "voyage_manifest")
public class VoyageManifest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voyage_no", nullable = false, length = 32)
    private String voyageNo;

    @Column(name = "cutoff_time", nullable = false)
    private OffsetDateTime cutoffTime;

    /** FROZEN：冻结后结算只能引用该版本 */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "FROZEN";

    /** {"箱号|分区": 提交ID, ...} —— 清单引用的完整提交边界 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "commit_frontier", nullable = false, columnDefinition = "jsonb")
    private String commitFrontier;

    @Column(name = "frozen_by", length = 64)
    private String frozenBy;

    @Column(name = "frozen_at", nullable = false)
    private OffsetDateTime frozenAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVoyageNo() { return voyageNo; }
    public void setVoyageNo(String v) { this.voyageNo = v; }
    public OffsetDateTime getCutoffTime() { return cutoffTime; }
    public void setCutoffTime(OffsetDateTime v) { this.cutoffTime = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getCommitFrontier() { return commitFrontier; }
    public void setCommitFrontier(String v) { this.commitFrontier = v; }
    public String getFrozenBy() { return frozenBy; }
    public void setFrozenBy(String v) { this.frozenBy = v; }
    public OffsetDateTime getFrozenAt() { return frozenAt; }
    public void setFrozenAt(OffsetDateTime v) { this.frozenAt = v; }
}
