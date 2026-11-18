package com.portops.lakehouse.manifest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VoyageManifestRepository extends JpaRepository<VoyageManifest, Long> {

    Optional<VoyageManifest> findByVoyageNoAndStatus(String voyageNo, String status);

    List<VoyageManifest> findByVoyageNo(String voyageNo);

    @Query("select m from VoyageManifest m, ManifestItem i " +
            "where i.manifestId = m.id and i.containerNo = :containerNo and m.status = 'FROZEN'")
    List<VoyageManifest> findFrozenByContainer(@Param("containerNo") String containerNo);
}
