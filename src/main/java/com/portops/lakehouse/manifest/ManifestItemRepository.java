package com.portops.lakehouse.manifest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ManifestItemRepository extends JpaRepository<ManifestItem, Long> {

    List<ManifestItem> findByManifestIdOrderByContainerNoAsc(Long manifestId);

    Optional<ManifestItem> findByManifestIdAndContainerNo(Long manifestId, String containerNo);
}
