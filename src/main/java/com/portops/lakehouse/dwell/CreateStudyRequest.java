package com.portops.lakehouse.dwell;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/** 创建滞留研究请求。 */
public record CreateStudyRequest(
        @NotBlank String studyCode,
        @NotNull OffsetDateTime periodStart,
        @NotNull OffsetDateTime periodEnd,
        @NotEmpty List<Double> thresholdsHours,
        @NotEmpty List<Double> quantiles
) {
}
