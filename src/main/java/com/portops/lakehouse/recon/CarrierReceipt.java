package com.portops.lakehouse.recon;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 承运人回执：航运公司回传的装卸事实与费用主张。
 * (carrier, voyageNo, containerNo, receiptSeq) 唯一，支持补发更新版本回执。
 */
@Entity
@Table(name = "carrier_receipt",
        uniqueConstraints = @UniqueConstraint(columnNames = {"carrier", "voyage_no", "container_no", "receipt_seq"}))
public class CarrierReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "carrier", nullable = false, length = 64)
    private String carrier;

    @Column(name = "voyage_no", nullable = false, length = 32)
    private String voyageNo;

    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    /** 回执序号：同一箱多版回执时取最大者 */
    @Column(name = "receipt_seq", nullable = false)
    private Long receiptSeq;

    /** 承运人主张的事实与费用 {loaded, moveCount, gateReleased, fees:[{feeType, amount}]} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "claimed", nullable = false, columnDefinition = "jsonb")
    private String claimed;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCarrier() { return carrier; }
    public void setCarrier(String v) { this.carrier = v; }
    public String getVoyageNo() { return voyageNo; }
    public void setVoyageNo(String v) { this.voyageNo = v; }
    public String getContainerNo() { return containerNo; }
    public void setContainerNo(String v) { this.containerNo = v; }
    public Long getReceiptSeq() { return receiptSeq; }
    public void setReceiptSeq(Long v) { this.receiptSeq = v; }
    public String getClaimed() { return claimed; }
    public void setClaimed(String v) { this.claimed = v; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime v) { this.receivedAt = v; }
}
