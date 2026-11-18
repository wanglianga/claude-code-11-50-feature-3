package com.portops.lakehouse.manifest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VoyageBookingRepository extends JpaRepository<VoyageBooking, VoyageBookingId> {

    List<VoyageBooking> findByVoyageNo(String voyageNo);
}
