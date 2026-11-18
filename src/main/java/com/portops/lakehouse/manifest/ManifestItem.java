package com.portops.lakehouse.manifest;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 清单行：某箱子在截关快照中的可收费装卸事实。
 * basisCommitIds 记录该行费用事实基于哪几版提交（对账可解释性）。
 * 数据库触发器禁止 UPDATE/DELETE。
 */
@Entity
@Table(name = "manifest_item",
        uniqueConstraints = @UniqueConstraint(columnNames = {"manifest_id", "container_no"}))
public class ManifestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false)
    private Long manifestId;

    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    @Column(name = "position", length = 64)
    private String position;

    @Column(name = "op_status", length = 24)
    private String opStatus;

    @Column(name = "voyage_no", length = 32)
    private String voyageNo;

    @Column(name = "loaded", nullable = false)
    private boolean loaded;

    @Column(name = "move_count", nullable = false)
    private int moveCount;

    @Column(name = "gate_released", nullable = false)
    private boolean gateReleased;

    /** 费用明细 [{feeType, quantity, unitPrice, amount}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "charges", nullable = false, columnDefinition = "jsonb")
    private String charges;

    /** 本行事实来源的提交 id 列表 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "basis_commit_ids", nullable = false, columnDefinition = "jsonb")
    private String basisCommitIds;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getManifestId() { return manifestId; }
    public void setManifestId(Long v) { this.manifestId = v; }
    public String getContainerNo() { return containerNo; }
    public void setContainerNo(String v) { this.containerNo = v; }
    public String getPosition() { return position; }
    public void setPosition(String v) { this.position = v; }
    public String getOpStatus() { return opStatus; }
    public void setOpStatus(String v) { this.opStatus = v; }
    public String getVoyageNo() { return voyageNo; }
    public void setVoyageNo(String v) { this.voyageNo = v; }
    public boolean isLoaded() { return loaded; }
    public void setLoaded(boolean v) { this.loaded = v; }
    public int getMoveCount() { return moveCount; }
    public void setMoveCount(int v) { this.moveCount = v; }
    public boolean isGateReleased() { return gateReleased; }
    public void setGateReleased(boolean v) { this.gateReleased = v; }
    public String getCharges() { return charges; }
    public void setCharges(String v) { this.charges = v; }
    public String getBasisCommitIds() { return basisCommitIds; }
    public void setBasisCommitIds(String v) { this.basisCommitIds = v; }
}
