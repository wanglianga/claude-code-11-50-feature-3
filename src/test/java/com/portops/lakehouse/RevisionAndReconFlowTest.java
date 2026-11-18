package com.portops.lakehouse;

import com.portops.lakehouse.common.BizException;
import com.portops.lakehouse.common.EventType;
import com.portops.lakehouse.common.SourceSystem;
import com.portops.lakehouse.commit.CommitService;
import com.portops.lakehouse.ingest.IngestService;
import com.portops.lakehouse.manifest.ManifestService;
import com.portops.lakehouse.manifest.RevisionCandidateRepository;
import com.portops.lakehouse.manifest.RevisionService;
import com.portops.lakehouse.recon.ReconDiffRepository;
import com.portops.lakehouse.recon.ReconService;
import com.portops.lakehouse.settlement.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 截关后更正与承运人回执对账：
 * - 截关后更正 → 修订候选；接受 → 费用调整单；原始冻结清单不改写；
 * - 回执差异逐项比对 → 调整或忽略；结算只能引用已冻结版本。
 */
class RevisionAndReconFlowTest extends BaseIntegrationTest {

    private static final String VOYAGE = "V-RECON-1";
    private static final String CUTOFF = "2026-11-20T18:00:00+08:00";

    @Autowired
    private IngestService ingestService;
    @Autowired
    private CommitService commitService;
    @Autowired
    private ManifestService manifestService;
    @Autowired
    private RevisionService revisionService;
    @Autowired
    private RevisionCandidateRepository revisionRepo;
    @Autowired
    private ReconService reconService;
    @Autowired
    private ReconDiffRepository diffRepo;
    @Autowired
    private SettlementService settlementService;

    private Long setupFrozenManifest() {
        // CNU8001：两次移箱 + 装船（之后会有截关后更正：漏报一次移箱）
        // CNU8002：装船（承运人回执声称未装船）
        // CNU8003：装船（承运人无回执）
        manifestService.registerBookings(VOYAGE, List.of("CNU8001", "CNU8002", "CNU8003"));
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.GATE, 1, "CNU8001", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-20T09:00:00+08:00", VOYAGE, "{\"direction\":\"IN\"}"),
                ev(SourceSystem.CRANE, 1, "CNU8001", "YARD-A", EventType.CRANE_MOVE,
                        "2026-11-20T10:00:00+08:00", VOYAGE,
                        "{\"fromBlock\":\"S\",\"fromSlot\":\"1\",\"toBlock\":\"A\",\"toSlot\":\"05-02\"}"),
                ev(SourceSystem.CRANE, 2, "CNU8001", "YARD-A", EventType.CRANE_MOVE,
                        "2026-11-20T11:00:00+08:00", VOYAGE,
                        "{\"fromBlock\":\"A\",\"fromSlot\":\"05-02\",\"toBlock\":\"A\",\"toSlot\":\"05-07\"}"),
                ev(SourceSystem.VESSEL, 1, "CNU8001", "BERTH-02", EventType.LOAD_CONFIRM,
                        "2026-11-20T15:00:00+08:00", VOYAGE, "{\"voyageNo\":\"" + VOYAGE + "\"}"),
                ev(SourceSystem.VESSEL, 2, "CNU8002", "BERTH-02", EventType.LOAD_CONFIRM,
                        "2026-11-20T15:30:00+08:00", VOYAGE, "{\"voyageNo\":\"" + VOYAGE + "\"}"),
                ev(SourceSystem.VESSEL, 3, "CNU8003", "BERTH-02", EventType.LOAD_CONFIRM,
                        "2026-11-20T16:00:00+08:00", VOYAGE, "{\"voyageNo\":\"" + VOYAGE + "\"}")));
        for (String c : List.of("CNU8001", "CNU8002", "CNU8003")) {
            commitService.generate(c);
        }
        Map<String, Object> frozen = manifestService.freeze(VOYAGE, OffsetDateTime.parse(CUTOFF), "settlement");
        return ((Number) frozen.get("manifestId")).longValue();
    }

    @Test
    void postCutoffCorrectionBecomesRevisionCandidateAndAdjustmentKeepsOriginalFrozen() {
        Long manifestId = setupFrozenManifest();

        // 截关后到达的更正报文（业务时点在截关前）：CNU8001 漏报一次移箱
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.CRANE, 3, "CNU8001", "YARD-A", EventType.CORRECTION,
                        "2026-11-20T11:30:00+08:00", VOYAGE,
                        "{\"position\":\"A-05-09\",\"moveCount\":3,\"note\":\"盘点发现漏报移箱\"}")));
        Map<String, Object> gen = commitService.generate("CNU8001");
        assertThat((List<?>) gen.get("revisionCandidateIds")).hasSize(1);

        var candidate = revisionRepo.findByManifestIdOrderByCreatedAtAsc(manifestId).get(0);
        assertThat(candidate.getStatus()).isEqualTo("PENDING");
        assertThat(candidate.getDiffSummary()).contains("moveCount: 2 → 3");

        // 冻结清单行不被改写
        Integer frozenMoves = jdbc.queryForObject(
                "select move_count from manifest_item where manifest_id=? and container_no='CNU8001'",
                Integer.class, manifestId);
        assertThat(frozenMoves).isEqualTo(2);

        // 接受候选 → 生成费用调整单（+1 次移箱 × 80）
        Map<String, Object> decided = revisionService.decide(candidate.getId(), true, "确认漏报", "settlement");
        assertThat(decided.get("status")).isEqualTo("ACCEPTED");
        assertThat((List<?>) decided.get("feeAdjustmentIds")).hasSize(1);

        Map<String, Object> charges = settlementService.charges(manifestId);
        // 原始结算 = CNU8001(450+160) + CNU8002(450) + CNU8003(450) = 1510
        assertThat((BigDecimal) charges.get("originalTotal")).isEqualByComparingTo("1510.00");
        assertThat((BigDecimal) charges.get("adjustmentTotal")).isEqualByComparingTo("80.00");
        assertThat((BigDecimal) charges.get("settledTotal")).isEqualByComparingTo("1590.00");
        // 原始清单行仍然未变
        frozenMoves = jdbc.queryForObject(
                "select move_count from manifest_item where manifest_id=? and container_no='CNU8001'",
                Integer.class, manifestId);
        assertThat(frozenMoves).isEqualTo(2);
    }

    @Test
    void carrierReceiptDiffsProduceAdjustmentsWithoutTouchingSnapshot() {
        Long manifestId = setupFrozenManifest();

        reconService.ingestReceipt("MASK-LINE", VOYAGE, "CNU8001", 1L,
                "{\"loaded\":true,\"moveCount\":2,\"gateReleased\":false,\"fees\":[{\"feeType\":\"LOAD_FEE\",\"amount\":\"450.00\"},{\"feeType\":\"MOVE_FEE\",\"amount\":\"160.00\"}]}");
        reconService.ingestReceipt("MASK-LINE", VOYAGE, "CNU8002", 1L,
                "{\"loaded\":false,\"moveCount\":0,\"gateReleased\":false,\"fees\":[]}");
        // CNU8003 无回执；CNU8099 不在清单中
        reconService.ingestReceipt("MASK-LINE", VOYAGE, "CNU8099", 1L,
                "{\"loaded\":true,\"moveCount\":1,\"gateReleased\":false,\"fees\":[{\"feeType\":\"LOAD_FEE\",\"amount\":\"450.00\"}]}");

        Map<String, Object> run = reconService.compare(manifestId, "settlement");
        Long runId = ((Number) run.get("runId")).longValue();
        var diffs = diffRepo.findByRunIdOrderByContainerNoAscIdAsc(runId);
        // CNU8002: LOADED_MISMATCH + MOVES_MISMATCH + FEE_MISMATCH(LOAD_FEE)；CNU8003: MISSING_ON_RECEIPT；CNU8099: MISSING_ON_MANIFEST
        assertThat(diffs).anyMatch(d -> d.getContainerNo().equals("CNU8002") && d.getDiffType().equals("LOADED_MISMATCH"));
        assertThat(diffs).anyMatch(d -> d.getContainerNo().equals("CNU8003") && d.getDiffType().equals("MISSING_ON_RECEIPT"));
        assertThat(diffs).anyMatch(d -> d.getContainerNo().equals("CNU8099") && d.getDiffType().equals("MISSING_ON_MANIFEST"));

        // 对 LOADED_MISMATCH 生成调整单：快照 loaded=true → 补收 450
        var loadedDiff = diffs.stream()
                .filter(d -> d.getDiffType().equals("LOADED_MISMATCH")).findFirst().orElseThrow();
        Map<String, Object> adj = reconService.adjustDiff(loadedDiff.getId(), "按快照补收", "settlement");
        assertThat(adj.get("deltaAmount")).isEqualTo(new BigDecimal("450.00"));

        // 忽略 MISSING_ON_RECEIPT
        var missingDiff = diffs.stream()
                .filter(d -> d.getDiffType().equals("MISSING_ON_RECEIPT")).findFirst().orElseThrow();
        reconService.ignoreDiff(missingDiff.getId(), "承运人下周补回执", "settlement");
        assertThat(diffRepo.findById(missingDiff.getId()).orElseThrow().getStatus()).isEqualTo("IGNORED");

        // 结算视图：调整单与原始结算分列
        Map<String, Object> charges = settlementService.charges(manifestId);
        assertThat((BigDecimal) charges.get("adjustmentTotal")).isEqualByComparingTo("450.00");
        assertThat((BigDecimal) charges.get("settledTotal")).isEqualByComparingTo("1960.00");
    }

    @Test
    void settlementCannotBypassFrozenManifest() {
        assertThatThrownBy(() -> settlementService.charges(999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("清单不存在");
    }
}
