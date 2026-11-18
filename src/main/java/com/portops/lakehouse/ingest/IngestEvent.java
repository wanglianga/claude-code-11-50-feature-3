package com.portops.lakehouse.ingest;

import com.portops.lakehouse.common.EventStatus;
import com.portops.lakehouse.common.EventType;
import com.portops.lakehouse.common.SourceSystem;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 事件账：接入服务保存的来源事件及其顺序标识。
 * (source_system, source_seq) 唯一，保证同一来源的报文幂等。
 */
@Entity
@Table(name = "ingest_event",
        uniqueConstraints = @UniqueConstraint(columnNames = {"source_system", "source_seq"}))
public class IngestEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 16)
    private SourceSystem sourceSystem;

    /** 来源序号：来源系统自己的单调序号 */
    @Column(name = "source_seq", nullable = false)
    private Long sourceSeq;

    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    /** 作业分区（闸口/堆场区/泊位），提交按 箱号+分区 组织 */
    @Column(name = "op_partition", nullable = false, length = 32)
    private String opPartition;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private EventType eventType;

    /** 业务时点：事件在来源现场实际发生的时间 */
    @Column(name = "biz_time", nullable = false)
    private OffsetDateTime bizTime;

    @Column(name = "voyage_no", length = 32)
    private String voyageNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private EventStatus status = EventStatus.RECEIVED;

    @Column(name = "commit_id")
    private Long commitId;

    @Column(name = "ingested_at", nullable = false)
    private OffsetDateTime ingestedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public SourceSystem getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(SourceSystem s) { this.sourceSystem = s; }
    public Long getSourceSeq() { return sourceSeq; }
    public void setSourceSeq(Long s) { this.sourceSeq = s; }
    public String getContainerNo() { return containerNo; }
    public void setContainerNo(String c) { this.containerNo = c; }
    public String getOpPartition() { return opPartition; }
    public void setOpPartition(String p) { this.opPartition = p; }
    public EventType getEventType() { return eventType; }
    public void setEventType(EventType t) { this.eventType = t; }
    public OffsetDateTime getBizTime() { return bizTime; }
    public void setBizTime(OffsetDateTime t) { this.bizTime = t; }
    public String getVoyageNo() { return voyageNo; }
    public void setVoyageNo(String v) { this.voyageNo = v; }
    public String getPayload() { return payload; }
    public void setPayload(String p) { this.payload = p; }
    public EventStatus getStatus() { return status; }
    public void setStatus(EventStatus s) { this.status = s; }
    public Long getCommitId() { return commitId; }
    public void setCommitId(Long c) { this.commitId = c; }
    public OffsetDateTime getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(OffsetDateTime t) { this.ingestedAt = t; }
}
