package com.portops.lakehouse.manifest;

import jakarta.persistence.*;

/** 航次订舱：清单冻结的范围依据（哪些箱子属于该航次）。 */
@Entity
@Table(name = "voyage_booking")
@IdClass(VoyageBookingId.class)
public class VoyageBooking {

    @Id
    @Column(name = "voyage_no", nullable = false, length = 32)
    private String voyageNo;

    @Id
    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    public VoyageBooking() {
    }

    public VoyageBooking(String voyageNo, String containerNo) {
        this.voyageNo = voyageNo;
        this.containerNo = containerNo;
    }

    public String getVoyageNo() { return voyageNo; }
    public void setVoyageNo(String v) { this.voyageNo = v; }
    public String getContainerNo() { return containerNo; }
    public void setContainerNo(String v) { this.containerNo = v; }
}
