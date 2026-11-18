package com.portops.lakehouse.dwell;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DwellEstimateRepository extends JpaRepository<DwellEstimate, Long> {

    List<DwellEstimate> findByRunIdOrderByRouteAscTimeScaleAscGroupDimAscGroupKeyAscMetricAscMetricArgAsc(Long runId);
}
