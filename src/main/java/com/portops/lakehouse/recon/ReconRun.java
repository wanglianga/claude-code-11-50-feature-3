package com.portops.lakehouse.recon;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** 对账运行：一次快照与承运人回执的逐项比较。 */
@Entity
@Table(name = "recon_run")
public class ReconRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false)
    private Long manifestId;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    /** {compared, matched, mismatched, missingOnReceipt, missingOnManifest} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", columnDefinition = "jsonb")
    private String summary;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getManifestId() { return manifestId; }
    public void setManifestId(Long v) { this.manifestId = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }
}
