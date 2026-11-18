package com.portops.lakehouse.settlement;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 费用调整单：对冻结清单的费用修正，永不改写原始清单行。
 * source=REVISION（截关后更正被接受）或 RECON（承运人回执差异）。
 * basis 记录调整依据（清单行、提交 id、触发事件/差异），保证可解释。
 */
@Entity
@Table(name = "fee_adjustment")
public class FeeAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** REVISION / RECON */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    /** revision_candidate.id 或 recon_diff.id */
    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "manifest_id", nullable = false)
    private Long manifestId;

    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    @Column(name = "fee_type", nullable = false, length = 32)
    private String feeType;

    /** 调整金额：正=补收，负=退减 */
    @Column(name = "delta_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal deltaAmount;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    /** 调整依据：{manifestItemId, basisCommitIds, triggerEventId / reconDiffId, ...} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "basis", nullable = false, columnDefinition = "jsonb")
    private String basis;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "APPROVED";

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long v) { this.sourceId = v; }
    public Long getManifestId() { return manifestId; }
    public void setManifestId(Long v) { this.manifestId = v; }
    public String getContainerNo() { return containerNo; }
    public void setContainerNo(String v) { this.containerNo = v; }
    public String getFeeType() { return feeType; }
    public void setFeeType(String v) { this.feeType = v; }
    public BigDecimal getDeltaAmount() { return deltaAmount; }
    public void setDeltaAmount(BigDecimal v) { this.deltaAmount = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getBasis() { return basis; }
    public void setBasis(String v) { this.basis = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public String getContainerNoSafe() { return containerNo; }
}
