package com.portops.lakehouse.manifest;

import java.io.Serializable;
import java.util.Objects;

public class VoyageBookingId implements Serializable {

    private String voyageNo;
    private String containerNo;

    public VoyageBookingId() {
    }

    public VoyageBookingId(String voyageNo, String containerNo) {
        this.voyageNo = voyageNo;
        this.containerNo = containerNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VoyageBookingId that)) return false;
        return Objects.equals(voyageNo, that.voyageNo) && Objects.equals(containerNo, that.containerNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(voyageNo, containerNo);
    }
}
