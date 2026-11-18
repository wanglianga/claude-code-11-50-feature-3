package com.portops.lakehouse.settlement;

import com.portops.lakehouse.common.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 结算接口：只能引用已冻结航次版本。
 */
@RestController
@RequestMapping("/api/settlement")
@Tag(name = "07-结算", description = "只能引用已冻结航次版本；调整单与原始结算分列")
public class SettlementController {

    private final SettlementService settlementService;
    private final FeeAdjustmentRepository adjustmentRepo;

    public SettlementController(SettlementService settlementService, FeeAdjustmentRepository adjustmentRepo) {
        this.settlementService = settlementService;
        this.adjustmentRepo = adjustmentRepo;
    }

    @GetMapping("/manifests/{manifestId}/charges")
    @Operation(summary = "结算视图",
            description = "原始结算（冻结清单行费用）与费用调整单分列；原始清单行不被改写")
    public Map<String, Object> charges(@PathVariable Long manifestId) {
        return settlementService.charges(manifestId);
    }

    @GetMapping("/manifests/{manifestId}/adjustments")
    @Operation(summary = "费用调整单列表", description = "每张调整单的 basis 字段说明费用调整依据（清单行/提交/事件/差异）")
    public List<FeeAdjustment> adjustments(@PathVariable Long manifestId) {
        return adjustmentRepo.findByManifestIdOrderByCreatedAtAsc(manifestId);
    }

    @GetMapping("/quote")
    @Operation(summary = "按航次直接结算（拒绝）",
            description = "演示约束：结算不能绕过冻结清单直接引用当前数据，必须提供 manifestId")
    public Map<String, Object> quoteByVoyage(@RequestParam String voyageNo) {
        throw BizException.badRequest(
                "结算必须引用已冻结航次版本：请先对航次 " + voyageNo
                        + " 执行 POST /api/manifests/freeze，再以 GET /api/settlement/manifests/{manifestId}/charges 结算");
    }
}
