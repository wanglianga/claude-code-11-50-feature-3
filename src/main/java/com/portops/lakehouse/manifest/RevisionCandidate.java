package com.portops.lakehouse.manifest;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 修订候选：截关后到达的更正报文，提交入账后针对已冻结清单生成。
 * 对账人员裁定接受（生成费用调整单）或拒绝；原始结算不被改写。
 */
@Entity
@Table(name = "revision_candidate")
public class RevisionCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false)
    private Long manifestId;

    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    /** 触发本候选的更正事件 */
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    /** 更正后的事实状态（当前已提交数据推导） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_state", nullable = false, columnDefinition = "jsonb")
    private String proposedState;

    /** 与冻结清单行的差异描述 */
    @Column(name = "diff_summary", columnDefinition = "text")
    private String diffSummary;

    /** PENDING / ACCEPTED / REJECTED */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_note", columnDefinition = "text")
    private String decisionNote;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getManifestId() { return manifestId; }
    public void setManifestId(Long v) { this.manifestId = v; }
    public String getContainerNo() { return containerNo; }
    public void setContainerNo(String v) { this.containerNo = v; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long v) { this.eventId = v; }
    public String getProposedState() { return proposedState; }
    public void setProposedState(String v) { this.proposedState = v; }
    public String getDiffSummary() { return diffSummary; }
    public void setDiffSummary(String v) { this.diffSummary = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String v) { this.decidedBy = v; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(OffsetDateTime v) { this.decidedAt = v; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String v) { this.decisionNote = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
