package com.portops.lakehouse.ingest;

import com.portops.lakehouse.common.EventType;
import com.portops.lakehouse.common.SourceSystem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/** 事件上报请求。 */
public record EventIngestRequest(
        @NotNull SourceSystem sourceSystem,
        @NotNull Long sourceSeq,
        @NotBlank String containerNo,
        @NotBlank String opPartition,
        @NotNull EventType eventType,
        @NotNull OffsetDateTime bizTime,
        String voyageNo,
        @NotBlank String payload   // JSON 字符串
) {
}
