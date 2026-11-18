package com.portops.lakehouse.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portops.lakehouse.common.BizException;
import com.portops.lakehouse.manifest.ManifestItem;
import com.portops.lakehouse.manifest.ManifestItemRepository;
import com.portops.lakehouse.manifest.VoyageManifest;
import com.portops.lakehouse.manifest.VoyageManifestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结算服务：只能引用已冻结航次版本（FROZEN 清单）。
 * 原始结算 = 冻结清单行费用；截关后更正与对账差异只通过费用调整单体现，
 * 原始清单行永不被改写。
 */
@Service
public class SettlementService {

    private final VoyageManifestRepository manifestRepo;
    private final ManifestItemRepository itemRepo;
    private final FeeAdjustmentRepository adjustmentRepo;
    private final ObjectMapper om;

    public SettlementService(VoyageManifestRepository manifestRepo, ManifestItemRepository itemRepo,
                             FeeAdjustmentRepository adjustmentRepo, ObjectMapper om) {
        this.manifestRepo = manifestRepo;
        this.itemRepo = itemRepo;
        this.adjustmentRepo = adjustmentRepo;
        this.om = om;
    }

    /** 结算视图：冻结清单费用 + 费用调整单（分列，不改写原始结算）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> charges(Long manifestId) {
        VoyageManifest manifest = manifestRepo.findById(manifestId)
                .orElseThrow(() -> BizException.notFound("清单不存在: " + manifestId));
        if (!"FROZEN".equals(manifest.getStatus())) {
            throw BizException.conflict("清单 " + manifestId + " 未冻结，结算只能引用已冻结航次版本");
        }
        List<ManifestItem> items = itemRepo.findByManifestIdOrderByContainerNoAsc(manifestId);
        List<FeeAdjustment> adjustments = adjustmentRepo.findByManifestIdOrderByCreatedAtAsc(manifestId);

        BigDecimal originalTotal = BigDecimal.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ManifestItem item : items) {
            List<Map<String, Object>> charges = readCharges(item.getCharges());
            BigDecimal itemTotal = charges.stream()
                    .map(c -> new BigDecimal(String.valueOf(c.get("amount"))))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            originalTotal = originalTotal.add(itemTotal);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("containerNo", item.getContainerNo());
            line.put("loaded", item.isLoaded());
            line.put("moveCount", item.getMoveCount());
            line.put("charges", charges);
            line.put("itemTotal", itemTotal);
            line.put("basisCommitIds", readLongList(item.getBasisCommitIds()));
            lines.add(line);
        }
        BigDecimal adjustmentTotal = adjustments.stream()
                .map(FeeAdjustment::getDeltaAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("manifestId", manifestId);
        r.put("voyageNo", manifest.getVoyageNo());
        r.put("cutoffTime", manifest.getCutoffTime());
        r.put("commitFrontier", readJson(manifest.getCommitFrontier()));
        r.put("originalLines", lines);
        r.put("originalTotal", originalTotal);
        r.put("feeAdjustments", adjustments);
        r.put("adjustmentTotal", adjustmentTotal);
        r.put("settledTotal", originalTotal.add(adjustmentTotal));
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readCharges(String json) {
        try {
            return om.readValue(json, List.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> readLongList(String json) {
        try {
            List<Number> m = om.readValue(json, List.class);
            return m.stream().map(Number::longValue).toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object readJson(String json) {
        try {
            return om.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
