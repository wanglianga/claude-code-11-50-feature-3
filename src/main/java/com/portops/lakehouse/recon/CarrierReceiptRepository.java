package com.portops.lakehouse.recon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CarrierReceiptRepository extends JpaRepository<CarrierReceipt, Long> {

    boolean existsByCarrierAndVoyageNoAndContainerNoAndReceiptSeq(
            String carrier, String voyageNo, String containerNo, Long receiptSeq);

    List<CarrierReceipt> findByVoyageNoOrderByContainerNoAscReceiptSeqAsc(String voyageNo);

    /** 每箱最新一版回执 */
    @Query("select r from CarrierReceipt r where r.voyageNo = :voyageNo and r.receiptSeq = " +
            "(select max(r2.receiptSeq) from CarrierReceipt r2 " +
            " where r2.voyageNo = r.voyageNo and r2.containerNo = r.containerNo and r2.carrier = r.carrier)")
    List<CarrierReceipt> findLatestByVoyage(@Param("voyageNo") String voyageNo);
}
