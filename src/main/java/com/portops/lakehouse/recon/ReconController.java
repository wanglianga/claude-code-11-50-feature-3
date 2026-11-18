package com.portops.lakehouse.recon;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 对账服务接口：承运人回执入账、快照比对、差异处理。
 */
@RestController
@RequestMapping("/api/recon")
@Tag(name = "05-对账", description = "冻结清单与承运人回执逐项比较；差异生成费用调整单")
public class ReconController {

    private final ReconService reconService;
    private final CarrierReceiptRepository receiptRepo;
    private final ReconRunRepository runRepo;
    private final ReconDiffRepository diffRepo;

    public ReconController(ReconService reconService, CarrierReceiptRepository receiptRepo,
                           ReconRunRepository runRepo, ReconDiffRepository diffRepo) {
        this.reconService = reconService;
        this.receiptRepo = receiptRepo;
        this.runRepo = runRepo;
        this.diffRepo = diffRepo;
    }

    @PostMapping("/receipts")
    @Operation(summary = "承运人回执入账", description = "同 carrier/voyage/container/seq 幂等；同箱多版回执取最大 seq 比对")
    public Map<String, Object> ingestReceipt(@Valid @RequestBody ReceiptRequest req) {
        return reconService.ingestReceipt(req.carrier(), req.voyageNo(), req.containerNo(),
                req.receiptSeq(), req.claimed());
    }

    @PostMapping("/receipts/replay/{sampleName}")
    @Operation(summary = "回放承运人回执样例")
    public List<Map<String, Object>> replayReceipts(@PathVariable String sampleName) {
        return reconService.replayReceipts(sampleName);
    }

    @GetMapping("/receipts")
    @Operation(summary = "查询回执")
    public List<CarrierReceipt> receipts(@RequestParam String voyageNo) {
        return receiptRepo.findByVoyageNoOrderByContainerNoAscReceiptSeqAsc(voyageNo);
    }

    @PostMapping("/compare")
    @Operation(summary = "执行对账", description = "仅接受 FROZEN 清单；逐项比较快照与最新回执，生成差异")
    public Map<String, Object> compare(@RequestParam Long manifestId, Authentication auth) {
        return reconService.compare(manifestId, auth == null ? "anonymous" : auth.getName());
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "对账运行明细（含全部差异）")
    public Map<String, Object> runDetail(@PathVariable Long runId) {
        ReconRun run = runRepo.findById(runId)
                .orElseThrow(() -> com.portops.lakehouse.common.BizException.notFound("对账运行不存在: " + runId));
        return Map.of("run", run, "diffs", diffRepo.findByRunIdOrderByContainerNoAscIdAsc(runId));
    }

    @GetMapping("/diffs")
    @Operation(summary = "查询差异", description = "status=OPEN 为待处理")
    public List<ReconDiff> diffs(@RequestParam(defaultValue = "OPEN") String status) {
        return diffRepo.findByStatus(status);
    }

    @PostMapping("/diffs/{diffId}/adjust")
    @Operation(summary = "对差异生成费用调整单", description = "金额=快照口径-回执口径；依据含清单行与提交 id")
    public Map<String, Object> adjust(@PathVariable Long diffId,
                                      @RequestBody(required = false) NoteRequest req,
                                      Authentication auth) {
        return reconService.adjustDiff(diffId, req == null ? null : req.note(),
                auth == null ? "anonymous" : auth.getName());
    }

    @PostMapping("/diffs/{diffId}/ignore")
    @Operation(summary = "忽略差异")
    public Map<String, Object> ignore(@PathVariable Long diffId,
                                      @RequestBody(required = false) NoteRequest req,
                                      Authentication auth) {
        return reconService.ignoreDiff(diffId, req == null ? null : req.note(),
                auth == null ? "anonymous" : auth.getName());
    }

    public record ReceiptRequest(@NotBlank String carrier, @NotBlank String voyageNo,
                                 @NotBlank String containerNo, @NotNull Long receiptSeq,
                                 @NotBlank String claimed) {
    }

    public record NoteRequest(String note) {
    }
}
