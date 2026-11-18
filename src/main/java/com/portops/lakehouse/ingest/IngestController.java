package com.portops.lakehouse.ingest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 接入服务接口：闸口/场桥/盘点/装船系统的事件上报与本地样例回放。
 */
@RestController
@RequestMapping("/api/ingest")
@Tag(name = "01-事件接入", description = "保存来源事件及其顺序标识（sourceSystem + sourceSeq 幂等）")
public class IngestController {

    private final IngestService service;

    public IngestController(IngestService service) {
        this.service = service;
    }

    @PostMapping("/events")
    @Operation(summary = "上报事件（支持批量）", description = "同一来源系统 sourceSeq 重复时幂等忽略；事件入账后不可删除")
    public List<Map<String, Object>> ingest(@Valid @RequestBody List<EventIngestRequest> events) {
        return service.ingestBatch(events);
    }

    @PostMapping("/replay/{sampleName}")
    @Operation(summary = "回放本地样例", description = "加载 classpath:samples/{sampleName}.json 并逐条入账；可用样例见 GET /api/ingest/samples")
    public List<Map<String, Object>> replay(@PathVariable String sampleName) {
        return service.replaySample(sampleName);
    }

    @GetMapping("/samples")
    @Operation(summary = "列出可用本地样例")
    public List<String> samples() {
        return service.listSamples();
    }

    @GetMapping("/events")
    @Operation(summary = "查询事件账", description = "可按箱号与状态过滤；状态含 RECEIVED/COMMITTED/PENDING_ARBITRATION/SUPERSEDED")
    public List<IngestEvent> events(@RequestParam(required = false) String containerNo,
                                    @RequestParam(required = false) String status) {
        return service.listEvents(containerNo, status);
    }
}
