package com.portops.lakehouse.recon;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * 对账差异：快照与承运人回执逐项比较发现的差异。
 * 差异可生成费用调整单（ADJUSTED）或人工忽略（IGNORED）。
 */
@Entity
@Table(name = "recon_diff")
public class ReconDiff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    /** LOADED_MISMATCH / MOVES_MISMATCH / GATE_MISMATCH / FEE_MISMATCH / MISSING_ON_RECEIPT / MISSING_ON_MANIFEST */
    @Column(name = "diff_type", nullable = false, length = 32)
    private String diffType;

    @Column(name = "field", length = 40)
    private String field;

    @Column(name = "snapshot_value", columnDefinition = "text")
    private String snapshotValue;

    @Column(name = "receipt_value", columnDefinition = "text")
    private String receiptValue;

    /** OPEN / ADJUSTED / IGNORED */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "OPEN";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long v) { this.runId = v; }
    public String getContainerNo() { return containerNo; }
    public void setContainerNo(String v) { this.containerNo = v; }
    public String getDiffType() { return diffType; }
    public void setDiffType(String v) { this.diffType = v; }
    public String getField() { return field; }
    public void setField(String v) { this.field = v; }
    public String getSnapshotValue() { return snapshotValue; }
    public void setSnapshotValue(String v) { this.snapshotValue = v; }
    public String getReceiptValue() { return receiptValue; }
    public void setReceiptValue(String v) { this.receiptValue = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
