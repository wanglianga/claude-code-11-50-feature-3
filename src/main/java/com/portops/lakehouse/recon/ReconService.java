package com.portops.lakehouse.recon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portops.lakehouse.common.BizException;
import com.portops.lakehouse.manifest.ManifestItem;
import com.portops.lakehouse.manifest.ManifestItemRepository;
import com.portops.lakehouse.manifest.VoyageManifest;
import com.portops.lakehouse.manifest.VoyageManifestRepository;
import com.portops.lakehouse.settlement.FeeAdjustment;
import com.portops.lakehouse.settlement.FeeAdjustmentRepository;
import com.portops.lakehouse.settlement.FeeSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对账服务：将冻结清单（截关快照）与承运人回执逐项比较，
 * 差异由对账人员决定生成费用调整单或忽略；不直接改写原始结算。
 */
@Service
public class ReconService {

    private static final Logger log = LoggerFactory.getLogger(ReconService.class);

    private final CarrierReceiptRepository receiptRepo;
    private final ReconRunRepository runRepo;
    private final ReconDiffRepository diffRepo;
    private final VoyageManifestRepository manifestRepo;
    private final ManifestItemRepository itemRepo;
    private final FeeAdjustmentRepository adjustmentRepo;
    private final ObjectMapper om;

    public ReconService(CarrierReceiptRepository receiptRepo, ReconRunRepository runRepo,
                        ReconDiffRepository diffRepo, VoyageManifestRepository manifestRepo,
                        ManifestItemRepository itemRepo, FeeAdjustmentRepository adjustmentRepo,
                        ObjectMapper om) {
        this.receiptRepo = receiptRepo;
        this.runRepo = runRepo;
        this.diffRepo = diffRepo;
        this.manifestRepo = manifestRepo;
        this.itemRepo = itemRepo;
        this.adjustmentRepo = adjustmentRepo;
        this.om = om;
    }

    /** 承运人回执入账（幂等：同 carrier/voyage/container/seq 重复忽略）。 */
    @Transactional
    public Map<String, Object> ingestReceipt(String carrier, String voyageNo, String containerNo,
                                             Long receiptSeq, String claimedJson) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (receiptRepo.existsByCarrierAndVoyageNoAndContainerNoAndReceiptSeq(
                carrier, voyageNo, containerNo, receiptSeq)) {
            r.put("result", "DUPLICATE_IGNORED");
            return r;
        }
        try {
            om.readTree(claimedJson);
        } catch (Exception e) {
            throw BizException.badRequest("claimed 不是合法 JSON");
        }
        CarrierReceipt cr = new CarrierReceipt();
        cr.setCarrier(carrier);
        cr.setVoyageNo(voyageNo);
        cr.setContainerNo(containerNo);
        cr.setReceiptSeq(receiptSeq);
        cr.setClaimed(claimedJson);
        receiptRepo.save(cr);
        r.put("result", "RECEIVED");
        r.put("receiptId", cr.getId());
        return r;
    }

    /** 回放本地承运人回执样例。 */
    public List<Map<String, Object>> replayReceipts(String sampleName) {
        try {
            Resource res = new PathMatchingResourcePatternResolver()
                    .getResource("classpath:samples/" + sampleName + ".json");
            if (!res.exists()) {
                throw BizException.notFound("样例不存在: " + sampleName);
            }
            String content = res.getContentAsString(StandardCharsets.UTF_8);
            List<Map<String, Object>> results = new ArrayList<>();
            for (JsonNode n : om.readTree(content)) {
                results.add(ingestReceipt(
                        n.get("carrier").asText(),
                        n.get("voyageNo").asText(),
                        n.get("containerNo").asText(),
                        n.get("receiptSeq").asLong(),
                        om.writeValueAsString(n.get("claimed"))));
            }
            return results;
        } catch (IOException e) {
            throw BizException.badRequest("样例解析失败: " + sampleName);
        }
    }

    /**
     * 逐项比较：冻结清单行 vs 承运人最新回执。
     * 只能对 FROZEN 清单运行（结算只能引用已冻结航次版本）。
     */
    @Transactional
    public Map<String, Object> compare(Long manifestId, String operator) {
        VoyageManifest manifest = manifestRepo.findById(manifestId)
                .orElseThrow(() -> BizException.notFound("清单不存在: " + manifestId));
        if (!"FROZEN".equals(manifest.getStatus())) {
            throw BizException.conflict("清单 " + manifestId + " 未冻结，不能用于对账");
        }
        List<ManifestItem> items = itemRepo.findByManifestIdOrderByContainerNoAsc(manifestId);
        List<CarrierReceipt> receipts = receiptRepo.findLatestByVoyage(manifest.getVoyageNo());
        Map<String, CarrierReceipt> receiptByContainer = receipts.stream()
                .collect(Collectors.toMap(CarrierReceipt::getContainerNo, r -> r, (a, b) -> a));

        ReconRun run = new ReconRun();
        run.setManifestId(manifestId);
        run.setCreatedBy(operator);
        run = runRepo.save(run);

        int matched = 0;
        List<ReconDiff> diffs = new ArrayList<>();
        for (ManifestItem item : items) {
            CarrierReceipt receipt = receiptByContainer.get(item.getContainerNo());
            if (receipt == null) {
                diffs.add(diff(run.getId(), item.getContainerNo(), "MISSING_ON_RECEIPT", null,
                        "snapshot: loaded=" + item.isLoaded() + ", moves=" + item.getMoveCount(), null));
                continue;
            }
            JsonNode claimed = readJson(receipt.getClaimed());
            boolean ok = true;
            boolean claimedLoaded = claimed.path("loaded").asBoolean(false);
            if (claimedLoaded != item.isLoaded()) {
                ok = false;
                diffs.add(diff(run.getId(), item.getContainerNo(), "LOADED_MISMATCH", "loaded",
                        String.valueOf(item.isLoaded()), String.valueOf(claimedLoaded)));
            }
            int claimedMoves = claimed.path("moveCount").asInt(0);
            if (claimedMoves != item.getMoveCount()) {
                ok = false;
                diffs.add(diff(run.getId(), item.getContainerNo(), "MOVES_MISMATCH", "moveCount",
                        String.valueOf(item.getMoveCount()), String.valueOf(claimedMoves)));
            }
            boolean claimedGate = claimed.path("gateReleased").asBoolean(false);
            if (claimedGate != item.isGateReleased()) {
                ok = false;
                diffs.add(diff(run.getId(), item.getContainerNo(), "GATE_MISMATCH", "gateReleased",
                        String.valueOf(item.isGateReleased()), String.valueOf(claimedGate)));
            }
            // 费用逐项比较（按费用类型汇总）
            Map<String, BigDecimal> snapshotFees = sumByFeeType(readCharges(item.getCharges()));
            Map<String, BigDecimal> claimedFees = new HashMap<>();
            for (JsonNode f : claimed.path("fees")) {
                claimedFees.merge(f.path("feeType").asText(),
                        new BigDecimal(f.path("amount").asText("0")), BigDecimal::add);
            }
            for (String type : union(snapshotFees, claimedFees)) {
                BigDecimal s = snapshotFees.getOrDefault(type, BigDecimal.ZERO);
                BigDecimal c = claimedFees.getOrDefault(type, BigDecimal.ZERO);
                if (s.compareTo(c) != 0) {
                    ok = false;
                    diffs.add(diff(run.getId(), item.getContainerNo(), "FEE_MISMATCH", type,
                            s.toPlainString(), c.toPlainString()));
                }
            }
            if (ok) {
                matched++;
            }
        }
        // 回执有而清单没有 → 可能错航次或漏装
        for (CarrierReceipt receipt : receipts) {
            boolean inManifest = items.stream()
                    .anyMatch(i -> i.getContainerNo().equals(receipt.getContainerNo()));
            if (!inManifest) {
                diffs.add(diff(run.getId(), receipt.getContainerNo(), "MISSING_ON_MANIFEST", null,
                        null, "receipt: carrier=" + receipt.getCarrier()));
            }
        }
        diffRepo.saveAll(diffs);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("compared", items.size());
        summary.put("matched", matched);
        summary.put("diffCount", diffs.size());
        run.setSummary(writeJson(summary));
        runRepo.save(run);
        log.info("recon compared: manifest={} run={} matched={}/{} diffs={}",
                manifestId, run.getId(), matched, items.size(), diffs.size());

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runId", run.getId());
        r.put("manifestId", manifestId);
        r.put("summary", summary);
        r.put("diffIds", diffs.stream().map(ReconDiff::getId).toList());
        return r;
    }

    /**
     * 对差异生成费用调整单：金额 = 快照费用 - 回执主张费用（正=向承运人补收，负=退减）。
     * 依据记录清单行、提交 id 与差异 id。
     */
    @Transactional
    public Map<String, Object> adjustDiff(Long diffId, String note, String operator) {
        ReconDiff diff = diffRepo.findById(diffId)
                .orElseThrow(() -> BizException.notFound("差异不存在: " + diffId));
        if (!"OPEN".equals(diff.getStatus())) {
            throw BizException.conflict("差异 " + diffId + " 已处理（" + diff.getStatus() + "）");
        }
        ReconRun run = runRepo.findById(diff.getRunId()).orElseThrow();
        ManifestItem item = itemRepo.findByManifestIdAndContainerNo(run.getManifestId(), diff.getContainerNo())
                .orElse(null);

        FeeAdjustment adj = new FeeAdjustment();
        adj.setSource("RECON");
        adj.setSourceId(diff.getId());
        adj.setManifestId(run.getManifestId());
        adj.setContainerNo(diff.getContainerNo());
        adj.setFeeType(feeTypeOf(diff));
        adj.setDeltaAmount(deltaOf(diff));
        adj.setReason("对账差异调整[" + diff.getDiffType() + "]: 快照=" + diff.getSnapshotValue()
                + " 回执=" + diff.getReceiptValue() + (note == null ? "" : "; " + note));
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("reconDiffId", diff.getId());
        basis.put("reconRunId", run.getId());
        if (item != null) {
            basis.put("manifestItemId", item.getId());
            basis.put("basisCommitIds", readLongList(item.getBasisCommitIds()));
        }
        adj.setBasis(writeJson(basis));
        adj.setCreatedBy(operator);
        FeeAdjustment saved = adjustmentRepo.save(adj);

        diff.setStatus("ADJUSTED");
        diffRepo.save(diff);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("diffId", diffId);
        r.put("feeAdjustmentId", saved.getId());
        r.put("deltaAmount", saved.getDeltaAmount());
        return r;
    }

    /** 忽略差异（确认无需调整）。 */
    @Transactional
    public Map<String, Object> ignoreDiff(Long diffId, String note, String operator) {
        ReconDiff diff = diffRepo.findById(diffId)
                .orElseThrow(() -> BizException.notFound("差异不存在: " + diffId));
        if (!"OPEN".equals(diff.getStatus())) {
            throw BizException.conflict("差异 " + diffId + " 已处理（" + diff.getStatus() + "）");
        }
        diff.setStatus("IGNORED");
        diffRepo.save(diff);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("diffId", diffId);
        r.put("status", "IGNORED");
        r.put("note", note);
        return r;
    }

    private ReconDiff diff(Long runId, String containerNo, String type, String field,
                           String snapshotValue, String receiptValue) {
        ReconDiff d = new ReconDiff();
        d.setRunId(runId);
        d.setContainerNo(containerNo);
        d.setDiffType(type);
        d.setField(field);
        d.setSnapshotValue(snapshotValue);
        d.setReceiptValue(receiptValue);
        return d;
    }

    private String feeTypeOf(ReconDiff diff) {
        return switch (diff.getDiffType()) {
            case "LOADED_MISMATCH" -> "LOAD_FEE";
            case "MOVES_MISMATCH" -> "MOVE_FEE";
            case "GATE_MISMATCH" -> "GATE_FEE";
            case "FEE_MISMATCH" -> diff.getField();
            default -> "MISC";
        };
    }

    /** 差异金额：快照口径 - 回执口径。 */
    private BigDecimal deltaOf(ReconDiff diff) {
        return switch (diff.getDiffType()) {
            case "LOADED_MISMATCH" -> Boolean.parseBoolean(diff.getSnapshotValue())
                    ? FeeSchedule.LOAD_FEE : FeeSchedule.LOAD_FEE.negate();
            case "MOVES_MISMATCH" -> FeeSchedule.MOVE_FEE.multiply(BigDecimal.valueOf(
                    Long.parseLong(diff.getSnapshotValue()) - Long.parseLong(diff.getReceiptValue())));
            case "GATE_MISMATCH" -> Boolean.parseBoolean(diff.getSnapshotValue())
                    ? FeeSchedule.GATE_FEE : FeeSchedule.GATE_FEE.negate();
            case "FEE_MISMATCH" -> new BigDecimal(diff.getSnapshotValue())
                    .subtract(new BigDecimal(diff.getReceiptValue()));
            default -> BigDecimal.ZERO;
        };
    }

    private List<String> union(Map<String, BigDecimal> a, Map<String, BigDecimal> b) {
        List<String> u = new ArrayList<>(a.keySet());
        for (String k : b.keySet()) {
            if (!u.contains(k)) {
                u.add(k);
            }
        }
        return u;
    }

    private JsonNode readJson(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readCharges(String json) {
        try {
            return om.readValue(json, List.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, BigDecimal> sumByFeeType(List<Map<String, Object>> charges) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (Map<String, Object> c : charges) {
            m.merge(String.valueOf(c.get("feeType")),
                    new BigDecimal(String.valueOf(c.get("amount"))), BigDecimal::add);
        }
        return m;
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

    private String writeJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
