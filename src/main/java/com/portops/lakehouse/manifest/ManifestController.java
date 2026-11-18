package com.portops.lakehouse.manifest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 航次清单服务接口：订舱登记、截关快照冻结、清单查询。
 */
@RestController
@RequestMapping("/api/manifests")
@Tag(name = "03-航次清单", description = "从已提交数据冻结截关快照；只引用完整提交清单")
public class ManifestController {

    private final ManifestService manifestService;
    private final VoyageManifestRepository manifestRepo;
    private final ManifestItemRepository itemRepo;

    public ManifestController(ManifestService manifestService, VoyageManifestRepository manifestRepo,
                              ManifestItemRepository itemRepo) {
        this.manifestService = manifestService;
        this.manifestRepo = manifestRepo;
        this.itemRepo = itemRepo;
    }

    @PostMapping("/bookings")
    @Operation(summary = "登记航次订舱", description = "声明哪些箱子属于该航次（清单冻结的范围依据）")
    public Map<String, Object> bookings(@Valid @RequestBody BookingRequest req) {
        return manifestService.registerBookings(req.voyageNo(), req.containerNos());
    }

    @PostMapping("/freeze")
    @Operation(summary = "冻结截关快照",
            description = "完整性校验（无未提交/待裁定的截关前事件）后，在同一事务内发布清单与清单行；失败则整体回滚，不发布半个分区")
    public Map<String, Object> freeze(@Valid @RequestBody FreezeRequest req, Authentication auth) {
        return manifestService.freeze(req.voyageNo(), req.cutoffTime(),
                auth == null ? "anonymous" : auth.getName());
    }

    @GetMapping
    @Operation(summary = "按航次查询清单")
    public List<VoyageManifest> byVoyage(@RequestParam String voyageNo) {
        return manifestRepo.findByVoyageNo(voyageNo);
    }

    @GetMapping("/{manifestId}")
    @Operation(summary = "清单明细", description = "含 commitFrontier（引用的完整提交清单）与逐箱清单行（含费用依据提交）")
    public Map<String, Object> detail(@PathVariable Long manifestId) {
        return manifestService.manifestDetail(manifestId);
    }

    @GetMapping("/{manifestId}/items")
    @Operation(summary = "清单行列表")
    public List<ManifestItem> items(@PathVariable Long manifestId) {
        return itemRepo.findByManifestIdOrderByContainerNoAsc(manifestId);
    }

    public record BookingRequest(@NotBlank String voyageNo, @NotEmpty List<String> containerNos) {
    }

    public record FreezeRequest(@NotBlank String voyageNo, @NotNull OffsetDateTime cutoffTime) {
    }
}
