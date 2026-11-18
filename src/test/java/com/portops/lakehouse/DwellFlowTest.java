package com.portops.lakehouse;

import com.portops.lakehouse.common.EventType;
import com.portops.lakehouse.common.SourceSystem;
import com.portops.lakehouse.commit.CommitService;
import com.portops.lakehouse.dwell.CreateStudyRequest;
import com.portops.lakehouse.dwell.DwellEstimate;
import com.portops.lakehouse.dwell.DwellRecord;
import com.portops.lakehouse.dwell.DwellReportRepository;
import com.portops.lakehouse.dwell.DwellReportService;
import com.portops.lakehouse.dwell.DwellRunService;
import com.portops.lakehouse.dwell.DwellStudy;
import com.portops.lakehouse.ingest.IngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.UncategorizedDataAccessException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 滞留分布与超期风险验收：
 * - 期初在场（延迟进入）、期末仍在场（右删失）、区间盘点（区间删失）、进出均不确知、
 *   出场证据冲突五类样例均纳入两条路线；
 * - 路线一只发布证据支持的可行界；路线二披露假设与敏感性；
 * - 迟到离场事件只产生新版本；dwell 各表不可变。
 */
class DwellFlowTest extends BaseIntegrationTest {

    private static final String S = "2026-11-01T00:00:00+08:00";
    private static final String T = "2026-12-01T00:00:00+08:00";

    @Autowired
    private IngestService ingestService;
    @Autowired
    private CommitService commitService;
    @Autowired
    private DwellRunService runService;
    @Autowired
    private DwellReportService reportService;
    @Autowired
    private DwellReportRepository reportRepo;

    private DwellStudy newStudy(String code) {
        return runService.createStudy(new CreateStudyRequest(code,
                OffsetDateTime.parse(S), OffsetDateTime.parse(T),
                List.of(72.0, 168.0, 336.0), List.of(0.5, 0.9)), "tester");
    }

    /** 五类验收样例 + 完整记录对照 + 期初精确进场（左截断）对照。 */
    private void ingestStandardBoxes() {
        ingestService.ingestBatch(List.of(
                // T001 期初已在场且长期未离场：仅盘点在场证明（进场下界无界），期末仍在场
                ev(SourceSystem.YARD, 1, "T001", "YARD-B", EventType.YARD_CHECK,
                        "2026-10-28T08:00:00+08:00", "V-Z", "{\"block\":\"B\",\"present\":true,\"containerType\":\"40HQ\"}"),
                ev(SourceSystem.YARD, 2, "T001", "YARD-B", EventType.YARD_CHECK,
                        "2026-11-30T23:00:00+08:00", "V-Z", "{\"block\":\"B\",\"present\":true,\"containerType\":\"40HQ\"}"),
                // T002 统计期内在场：精确进场，最后在场证明后无离场（右删失）
                ev(SourceSystem.GATE, 1, "T002", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-10T08:00:00+08:00", "V-D", "{\"direction\":\"IN\",\"containerType\":\"40HQ\"}"),
                ev(SourceSystem.CRANE, 1, "T002", "YARD-B", EventType.CRANE_MOVE,
                        "2026-11-15T10:00:00+08:00", "V-D", "{\"toBlock\":\"B\",\"containerType\":\"40HQ\"}"),
                // T003 仅有区间盘点：进场 (11-05,11-12]，离场 (11-12,11-20]
                ev(SourceSystem.YARD, 3, "T003", "YARD-A", EventType.YARD_CHECK,
                        "2026-11-05T09:00:00+08:00", "V-C", "{\"block\":\"A\",\"present\":false,\"containerType\":\"20GP\"}"),
                ev(SourceSystem.YARD, 4, "T003", "YARD-A", EventType.YARD_CHECK,
                        "2026-11-12T09:00:00+08:00", "V-C", "{\"block\":\"A\",\"present\":true,\"containerType\":\"20GP\"}"),
                ev(SourceSystem.YARD, 5, "T003", "YARD-A", EventType.YARD_CHECK,
                        "2026-11-20T09:00:00+08:00", "V-C", "{\"block\":\"A\",\"present\":false,\"containerType\":\"20GP\"}"),
                // T004 区间内进出均不确知：进场 (-∞,11-08]，离场 (11-16,11-22]
                ev(SourceSystem.YARD, 6, "T004", "YARD-A", EventType.YARD_CHECK,
                        "2026-11-08T09:00:00+08:00", "V-C", "{\"block\":\"A\",\"present\":true,\"containerType\":\"20GP\"}"),
                ev(SourceSystem.YARD, 7, "T004", "YARD-A", EventType.YARD_CHECK,
                        "2026-11-16T09:00:00+08:00", "V-C", "{\"block\":\"A\",\"present\":true,\"containerType\":\"20GP\"}"),
                ev(SourceSystem.YARD, 8, "T004", "YARD-A", EventType.YARD_CHECK,
                        "2026-11-22T09:00:00+08:00", "V-C", "{\"block\":\"A\",\"present\":false,\"containerType\":\"20GP\"}"),
                // T005 出场证据冲突：闸口出场后盘点仍在场
                ev(SourceSystem.GATE, 2, "T005", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-03T08:00:00+08:00", "V-D", "{\"direction\":\"IN\",\"containerType\":\"40GP\"}"),
                ev(SourceSystem.GATE, 3, "T005", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-11T08:00:00+08:00", "V-D", "{\"direction\":\"OUT\",\"containerType\":\"40GP\"}"),
                ev(SourceSystem.YARD, 9, "T005", "YARD-A", EventType.YARD_CHECK,
                        "2026-11-18T08:00:00+08:00", "V-D", "{\"block\":\"A\",\"present\":true,\"containerType\":\"40GP\"}"),
                // T006 完整进出场对照：精确 144h
                ev(SourceSystem.GATE, 4, "T006", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-02T08:00:00+08:00", "V-C", "{\"direction\":\"IN\",\"containerType\":\"20GP\"}"),
                ev(SourceSystem.GATE, 5, "T006", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-08T08:00:00+08:00", "V-C", "{\"direction\":\"OUT\",\"containerType\":\"20GP\"}"),
                // T007 跨航次转场重复进场（身份不稳）：两次进场无中间离场
                ev(SourceSystem.GATE, 6, "T007", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-04T08:00:00+08:00", "V-C", "{\"direction\":\"IN\",\"containerType\":\"20GP\"}"),
                ev(SourceSystem.GATE, 7, "T007", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-06T08:00:00+08:00", "V-D", "{\"direction\":\"IN\",\"containerType\":\"20GP\"}"),
                ev(SourceSystem.YARD, 10, "T007", "YARD-C", EventType.YARD_CHECK,
                        "2026-11-29T08:00:00+08:00", "V-D", "{\"block\":\"C\",\"present\":true,\"containerType\":\"20GP\"}"),
                // T008 期初精确进场（左截断对照）：10-25 进场，11-10 离场
                ev(SourceSystem.GATE, 8, "T008", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-10-25T08:00:00+08:00", "V-Z", "{\"direction\":\"IN\",\"containerType\":\"40HQ\"}"),
                ev(SourceSystem.GATE, 9, "T008", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-10T08:00:00+08:00", "V-Z", "{\"direction\":\"OUT\",\"containerType\":\"40HQ\"}")));
        for (String c : List.of("T001", "T002", "T003", "T004", "T005", "T006", "T007", "T008")) {
            commitService.generate(c);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** detail 列为 jsonb（数据库会重排格式），断言一律解析后取值。 */
    private static com.fasterxml.jackson.databind.JsonNode json(String raw) {
        try {
            return OM.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private DwellRecord recordOf(Long runId, String containerNo) {
        return runService.listRecords(runId, containerNo, false).stream()
                .filter(r -> r.getContainerNo().equals(containerNo)).findFirst().orElseThrow();
    }

    private DwellEstimate estimateOf(Long runId, String route, String timeScale, String groupDim,
                                     String metric, String arg) {
        return estimateOf(runId, route, timeScale, groupDim, "ALL", metric, arg);
    }

    private DwellEstimate estimateOf(Long runId, String route, String timeScale, String groupDim,
                                     String groupKey, String metric, String arg) {
        return runService.listEstimates(runId, route, groupDim, timeScale).stream()
                .filter(e -> e.getGroupKey().equals(groupKey)
                        && e.getMetric().equals(metric) && e.getMetricArg().equals(arg))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "estimate not found: " + route + " " + groupDim + " " + groupKey
                                + " " + metric + " " + arg));
    }

    @Test
    void extractionBuildsCensoringTruncationAndConflictRecords() {
        ingestStandardBoxes();
        DwellStudy study = newStudy("DWELL-EXTRACT");
        Map<String, Object> run = runService.computeRun(study.getId(), "tester");
        Long runId = ((Number) run.get("runId")).longValue();
        assertThat(run.get("version")).isEqualTo(1);
        assertThat(run.get("containerCount")).isEqualTo(8);

        // T001 期初在场（延迟进入）：以统计起点为观察起点；真实进场未知 → 总堆存上界 +∞，不伪装已知
        DwellRecord t1 = recordOf(runId, "T001");
        assertThat(t1.isLeftTruncated()).isTrue();
        assertThat(t1.isRightCensored()).isTrue();
        assertThat(t1.getEntryKind()).isEqualTo("INTERVAL");
        assertThat(t1.getEntryLower()).isNull();          // 真实进场早于全部证据
        assertThat(t1.getExitKind()).isEqualTo("RIGHT_CENSORED");
        assertThat(t1.getObsStartLower().toInstant()).isEqualTo(OffsetDateTime.parse(S).toInstant());
        assertThat(t1.getObsStartUpper().toInstant()).isEqualTo(OffsetDateTime.parse(S).toInstant());
        assertThat(t1.getRemainLowerHours()).isEqualTo(719.0);   // 11-01 00:00 → 11-30 23:00
        assertThat(t1.getRemainUpperHours()).isNull();           // 仍在场：上界 +∞
        assertThat(t1.getTotalUpperHours()).isNull();            // 真实进场未知：不当作零

        // T002 期末前在场：精确进场 + 右删失（不登记为已离场）
        DwellRecord t2 = recordOf(runId, "T002");
        assertThat(t2.getEntryKind()).isEqualTo("EXACT");
        assertThat(t2.isRightCensored()).isTrue();
        assertThat(t2.getRemainLowerHours()).isEqualTo(122.0);   // 11-10 08:00 → 11-15 10:00
        assertThat(t2.getRemainUpperHours()).isNull();

        // T003 仅有区间盘点：进出均为区间，不取中点
        DwellRecord t3 = recordOf(runId, "T003");
        assertThat(t3.isIntervalCensored()).isTrue();
        assertThat(t3.getExitKind()).isEqualTo("INTERVAL");
        assertThat(t3.getRemainLowerHours()).isEqualTo(0.0);
        assertThat(t3.getRemainUpperHours()).isEqualTo(360.0);   // 11-05 09:00 → 11-20 09:00

        // T004 进出均不确知：进场下界无界，总堆存上界 +∞
        DwellRecord t4 = recordOf(runId, "T004");
        assertThat(t4.getEntryLower()).isNull();
        assertThat(t4.getTotalUpperHours()).isNull();
        assertThat(t4.getRemainLowerHours()).isEqualTo(192.0);   // 11-08 09:00 → 11-16 09:00
        assertThat(t4.getRemainUpperHours()).isEqualTo(513.0);   // 11-01 00:00 → 11-22 09:00

        // T005 出场证据冲突：取最宽包络 [192h, +∞)，不挑选较短时长
        DwellRecord t5 = recordOf(runId, "T005");
        assertThat(t5.getConflictStatus()).contains("PRESENCE_AFTER_EXIT");
        assertThat(t5.getExitKind()).isEqualTo("CONFLICTED");
        assertThat(t5.getRemainLowerHours()).isEqualTo(192.0);   // 11-03 08:00 → 11-11 08:00
        assertThat(t5.getRemainUpperHours()).isNull();

        // T006 完整对照：精确 144h
        DwellRecord t6 = recordOf(runId, "T006");
        assertThat(t6.getExitKind()).isEqualTo("EXACT");
        assertThat(t6.isIntervalCensored()).isFalse();
        assertThat(t6.getRemainLowerHours()).isEqualTo(144.0);
        assertThat(t6.getRemainUpperHours()).isEqualTo(144.0);

        // T007 重复进场冲突：进场包络 [11-04, 11-06]，最后在场证明时刻右删失（上界 +∞）
        DwellRecord t7 = recordOf(runId, "T007");
        assertThat(t7.getConflictStatus()).contains("DUPLICATE_ENTRY");
        assertThat(t7.getRemainLowerHours()).isEqualTo(552.0);   // 11-29 08:00 − 11-06 08:00
        assertThat(t7.getRemainUpperHours()).isNull();           // 仍在场：上界 +∞，不挑选较短时长
        assertThat(t7.getTotalLowerHours()).isEqualTo(552.0);

        // T008 期初精确进场：剩余滞留自统计起点计 224h；总堆存 384h（可核实进场）
        DwellRecord t8 = recordOf(runId, "T008");
        assertThat(t8.isLeftTruncated()).isTrue();
        assertThat(t8.getRemainLowerHours()).isEqualTo(224.0);   // 11-01 00:00 → 11-10 08:00
        assertThat(t8.getRemainUpperHours()).isEqualTo(224.0);
        assertThat(t8.getTotalLowerHours()).isEqualTo(384.0);    // 10-25 08:00 → 11-10 08:00
        assertThat(t8.getTotalUpperHours()).isEqualTo(384.0);
    }

    @Test
    void boundsRoutePublishesOnlyEvidenceSupportedBounds() {
        ingestStandardBoxes();
        Long runId = ((Number) runService.computeRun(newStudy("DWELL-BOUNDS").getId(), "tester")
                .get("runId")).longValue();

        // 超期比例可行界（N=8，含删失与冲突箱，不以完整记录子集替代总体）
        DwellEstimate p168 = estimateOf(runId, "BOUNDS", "REMAINING", "ALL", "OVERDUE_PROPORTION", "168");
        assertThat(p168.getSampleSize()).isEqualTo(8);
        assertThat(p168.getLowerBound()).isEqualTo(0.625);   // 5/8 确定超期
        assertThat(p168.getUpperBound()).isEqualTo(0.875);   // 7/8 可能超期
        assertThat(p168.getPointEstimate()).isNull();        // 路线一只发布界限
        DwellEstimate p336 = estimateOf(runId, "BOUNDS", "REMAINING", "ALL", "OVERDUE_PROPORTION", "336");
        assertThat(p336.getLowerBound()).isEqualTo(0.25);   // 2/8 确定超期
        assertThat(p336.getUpperBound()).isEqualTo(0.75);   // 6/8 可能超期

        // 分位数可识别范围：中位数 [192h, 513h]；q0.9 上界不可识别
        DwellEstimate med = estimateOf(runId, "BOUNDS", "REMAINING", "ALL", "QUANTILE", "0.5");
        assertThat(med.getLowerBound()).isEqualTo(192.0);
        assertThat(med.getUpperBound()).isEqualTo(513.0);
        DwellEstimate q90 = estimateOf(runId, "BOUNDS", "REMAINING", "ALL", "QUANTILE", "0.9");
        assertThat(q90.getLowerBound()).isEqualTo(719.0);
        assertThat(q90.getUpperBound()).isNull();            // 上界不可识别

        // 覆盖率：各类证据计数披露
        DwellEstimate summary = estimateOf(runId, "BOUNDS", "REMAINING", "ALL", "GROUP_SUMMARY", "-");
        var coverage = json(summary.getDetail()).get("coverage");
        assertThat(coverage.get("rightCensored").asInt()).isEqualTo(3);   // T001/T002/T007
        assertThat(coverage.get("unboundedEntry").asInt()).isEqualTo(2);  // T001/T004
        assertThat(coverage.get("conflicted").asInt()).isEqualTo(2);      // T005/T007
        assertThat(coverage.get("exactEntry").asInt()).isEqualTo(4);      // T002/T005/T006/T008
        assertThat(coverage.get("leftTruncated").asInt()).isEqualTo(2);   // T001/T008

        // 小样本分组（BLOCK B 仅 T001/T002）：界限仍发布，模型不拟合
        DwellEstimate blockB = estimateOf(runId, "BOUNDS", "REMAINING", "BLOCK", "B",
                "OVERDUE_PROPORTION", "72");
        assertThat(blockB.getLowerBound()).isEqualTo(1.0);
        DwellEstimate blockBModel = estimateOf(runId, "MODEL", "REMAINING", "BLOCK", "B",
                "GROUP_SUMMARY", "-");
        assertThat(blockBModel.getDetail()).contains("INSUFFICIENT_DATA");
    }

    @Test
    void modelRouteIncludesCensoredContainersAndDisclosesAssumptions() {
        ingestStandardBoxes();
        Long runId = ((Number) runService.computeRun(newStudy("DWELL-MODEL").getId(), "tester")
                .get("runId")).longValue();

        // 路线二纳入全部目标箱（含删失箱），不以完整记录子集替代总体
        DwellEstimate summary = estimateOf(runId, "MODEL", "REMAINING", "ALL", "GROUP_SUMMARY", "-");
        assertThat(summary.getSampleSize()).isEqualTo(8);
        var detail = json(summary.getDetail());
        var censoring = detail.get("censoring");
        assertThat(censoring.get("n").asInt()).isEqualTo(8);
        assertThat(censoring.get("rightCensored").asInt()).isEqualTo(4);   // T001/T002/T005/T007
        assertThat(censoring.get("intervalCensored").asInt()).isEqualTo(2); // T003/T004
        assertThat(censoring.get("exact").asInt()).isEqualTo(2);            // T006/T008
        // 假设与敏感性披露
        assertThat(detail.get("assumptions").size()).isGreaterThanOrEqualTo(3);
        assertThat(detail.get("assumptions").toString()).contains("非信息性");
        assertThat(detail.get("assumptions").toString()).contains("Weibull");
        assertThat(detail.get("sensitivity").get("boundsContainment").size()).isEqualTo(3);
        assertThat(detail.get("sensitivity").has("tailDeltaWeibullMinusExponential")).isTrue();
        assertThat(detail.get("disclosures").size()).isGreaterThanOrEqualTo(1);

        // 模型超期概率为合法概率值
        DwellEstimate p168 = estimateOf(runId, "MODEL", "REMAINING", "ALL", "OVERDUE_PROPORTION", "168");
        assertThat(p168.getPointEstimate()).isBetween(0.0, 1.0);

        // 总堆存模型：真实进场未知箱（T001/T004）不计入该口径并披露；左截断计入（T008）
        DwellEstimate totalSummary = estimateOf(runId, "MODEL", "TOTAL", "ALL", "GROUP_SUMMARY", "-");
        var totalCensoring = json(totalSummary.getDetail()).get("censoring");
        assertThat(totalCensoring.get("n").asInt()).isEqualTo(6);
        assertThat(totalCensoring.get("excludedUnverifiableEntry").asInt()).isEqualTo(2);
        assertThat(totalCensoring.get("leftTruncated").asInt()).isEqualTo(1);
    }

    @Test
    void lateExitEventOnlyCreatesNewVersion() {
        // V001：精确进场 + 右删失
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.GATE, 1, "V001", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-10T08:00:00+08:00", "V-D", "{\"direction\":\"IN\"}"),
                ev(SourceSystem.CRANE, 1, "V001", "YARD-B", EventType.CRANE_MOVE,
                        "2026-11-15T10:00:00+08:00", "V-D", "{\"toBlock\":\"B\"}")));
        commitService.generate("V001");
        DwellStudy study = newStudy("DWELL-VERSION");

        Map<String, Object> run1 = runService.computeRun(study.getId(), "tester");
        Long runId1 = ((Number) run1.get("runId")).longValue();
        assertThat(recordOf(runId1, "V001").isRightCensored()).isTrue();
        assertThat(recordOf(runId1, "V001").getRemainLowerHours()).isEqualTo(122.0);

        Map<String, Object> report1 = reportService.generateReport(study.getId(), null, "tester");
        assertThat(report1.get("version")).isEqualTo(1);
        assertThat(report1.get("runId")).isEqualTo(runId1);

        // 迟到离场事件入账并提交
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.GATE, 2, "V001", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-28T09:00:00+08:00", "V-D", "{\"direction\":\"OUT\"}")));
        commitService.generate("V001");

        Map<String, Object> run2 = runService.computeRun(study.getId(), "tester");
        Long runId2 = ((Number) run2.get("runId")).longValue();
        assertThat(run2.get("version")).isEqualTo(2);
        // 新版本：精确离场 433h
        DwellRecord v2 = recordOf(runId2, "V001");
        assertThat(v2.getExitKind()).isEqualTo("EXACT");
        assertThat(v2.isRightCensored()).isFalse();
        assertThat(v2.getRemainLowerHours()).isEqualTo(433.0);  // 11-10 08:00 → 11-28 09:00
        assertThat(v2.getRemainUpperHours()).isEqualTo(433.0);
        // 旧版本不可改写：v1 记录仍为右删失
        assertThat(recordOf(runId1, "V001").isRightCensored()).isTrue();
        assertThat(recordOf(runId1, "V001").getRemainLowerHours()).isEqualTo(122.0);

        Map<String, Object> report2 = reportService.generateReport(study.getId(), null, "tester");
        assertThat(report2.get("version")).isEqualTo(2);
        assertThat(report2.get("runId")).isEqualTo(runId2);
        assertThat(reportRepo.findByStudyIdOrderByVersionAsc(study.getId())).hasSize(2);
    }

    @Test
    void dwellTablesAreImmutable() {
        ingestStandardBoxes();
        DwellStudy study = newStudy("DWELL-IMMUTABLE");
        Long runId = ((Number) runService.computeRun(study.getId(), "tester").get("runId")).longValue();
        reportService.generateReport(study.getId(), null, "tester");

        assertThatThrownBy(() -> jdbc.execute(
                "UPDATE dwell_record SET remain_lower_hours = 0 WHERE run_id = " + runId))
                .isInstanceOf(UncategorizedDataAccessException.class)
                .hasMessageContaining("不可变");
        assertThatThrownBy(() -> jdbc.execute(
                "UPDATE dwell_estimate SET lower_bound = 0 WHERE run_id = " + runId))
                .isInstanceOf(UncategorizedDataAccessException.class)
                .hasMessageContaining("不可变");
        assertThatThrownBy(() -> jdbc.execute(
                "DELETE FROM dwell_obs_run WHERE id = " + runId))
                .isInstanceOf(UncategorizedDataAccessException.class)
                .hasMessageContaining("不可变");
        assertThatThrownBy(() -> jdbc.execute(
                "DELETE FROM dwell_report WHERE study_id = " + study.getId()))
                .isInstanceOf(UncategorizedDataAccessException.class)
                .hasMessageContaining("不可变");
        assertThatThrownBy(() -> jdbc.execute(
                "DELETE FROM dwell_study WHERE id = " + study.getId()))
                .isInstanceOf(UncategorizedDataAccessException.class)
                .hasMessageContaining("不可变");
    }

    @Test
    void conflictsAndExcludedContainersAreDisclosedNotDropped() {
        // T009：先进场提交，再有业务时点并列冲突（场桥 vs 装船）→ 待裁定披露
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.GATE, 1, "T009", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-10T08:00:00+08:00", "V-C", "{\"direction\":\"IN\"}")));
        commitService.generate("T009");
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.CRANE, 1, "T009", "YARD-A", EventType.CRANE_MOVE,
                        "2026-11-20T14:00:00+08:00", "V-C", "{\"toBlock\":\"A\"}"),
                ev(SourceSystem.VESSEL, 1, "T009", "BERTH-02", EventType.LOAD_CONFIRM,
                        "2026-11-20T14:00:00+08:00", "V-C", "{\"voyageNo\":\"V-C\"}")));
        commitService.generate("T009");   // 并列冲突 → 待裁定
        // T010：仅有未提交事件 → 列入排除清单（披露，不静默丢弃）
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.GATE, 2, "T010", "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-12T08:00:00+08:00", "V-C", "{\"direction\":\"IN\"}")));

        Map<String, Object> run = runService.computeRun(newStudy("DWELL-CONFLICT").getId(), "tester");
        Long runId = ((Number) run.get("runId")).longValue();

        DwellRecord t9 = recordOf(runId, "T009");
        assertThat(t9.getConflictStatus()).contains("EVENTS_PENDING_ARBITRATION");
        assertThat(t9.getPendingEventIds()).contains("2", "3");   // 两条待裁定事件 id 披露

        @SuppressWarnings("unchecked")
        List<com.portops.lakehouse.dwell.DwellExtractionService.ExcludedContainer> excluded =
                (List<com.portops.lakehouse.dwell.DwellExtractionService.ExcludedContainer>)
                        run.get("excludedContainers");
        assertThat(excluded).anyMatch(e -> "T010".equals(e.containerNo()));
    }
}
