package com.portops.lakehouse.dwell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portops.lakehouse.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理报告：引用某次在场风险区间版本的估计，组装管理层视图并独立版本化。
 * 报告不可变；新证据（如迟到离场事件）产生新的区间版本后，可再生成新报告版本。
 */
@Service
public class DwellReportService {

    private final DwellStudyRepository studyRepo;
    private final DwellObsRunRepository runRepo;
    private final DwellRecordRepository recordRepo;
    private final DwellEstimateRepository estimateRepo;
    private final DwellReportRepository reportRepo;
    private final ObjectMapper om;

    public DwellReportService(DwellStudyRepository studyRepo, DwellObsRunRepository runRepo,
                              DwellRecordRepository recordRepo, DwellEstimateRepository estimateRepo,
                              DwellReportRepository reportRepo, ObjectMapper om) {
        this.studyRepo = studyRepo;
        this.runRepo = runRepo;
        this.recordRepo = recordRepo;
        this.estimateRepo = estimateRepo;
        this.reportRepo = reportRepo;
        this.om = om;
    }

    /** 生成管理报告新版本（默认引用最新区间版本）。 */
    @Transactional
    public Map<String, Object> generateReport(Long studyId, Long runId, String operator) {
        DwellStudy study = studyRepo.findById(studyId)
                .orElseThrow(() -> BizException.notFound("滞留研究不存在: " + studyId));
        DwellObsRun run = runId == null
                ? runRepo.findTopByStudyIdOrderByVersionDesc(studyId)
                .orElseThrow(() -> BizException.badRequest("该研究尚无区间版本，请先计算"))
                : runRepo.findById(runId)
                .filter(r -> r.getStudyId().equals(studyId))
                .orElseThrow(() -> BizException.notFound("区间版本不属于该研究: " + runId));

        List<DwellRecord> records = recordRepo.findByRunIdOrderByContainerNoAsc(run.getId());
        List<DwellEstimate> estimates = estimateRepo
                .findByRunIdOrderByRouteAscTimeScaleAscGroupDimAscGroupKeyAscMetricAscMetricArgAsc(run.getId());

        int version = reportRepo.findTopByStudyIdOrderByVersionDesc(studyId)
                .map(DwellReport::getVersion).orElse(0) + 1;

        DwellReport report = new DwellReport();
        report.setStudyId(studyId);
        report.setVersion(version);
        report.setRunId(run.getId());
        report.setCreatedBy(operator);
        report.setContent(writeJson(assembleContent(study, run, records, estimates)));
        DwellReport saved = reportRepo.save(report);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", saved.getId());
        result.put("studyId", studyId);
        result.put("version", version);
        result.put("runId", run.getId());
        result.put("runVersion", run.getVersion());
        return result;
    }

    @Transactional(readOnly = true)
    public List<DwellReport> listReports(Long studyId) {
        return reportRepo.findByStudyIdOrderByVersionAsc(studyId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> reportContent(Long reportId) {
        DwellReport r = reportRepo.findById(reportId)
                .orElseThrow(() -> BizException.notFound("报告不存在: " + reportId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportId", r.getId());
        out.put("studyId", r.getStudyId());
        out.put("version", r.getVersion());
        out.put("runId", r.getRunId());
        out.put("createdBy", r.getCreatedBy());
        out.put("createdAt", r.getCreatedAt());
        out.put("content", readJson(r.getContent()));
        return out;
    }

    /** 组装报告内容：分组汇总（两路线 × 两时间尺度）、未决冲突、假设与披露。 */
    private Map<String, Object> assembleContent(DwellStudy study, DwellObsRun run,
                                                List<DwellRecord> records,
                                                List<DwellEstimate> estimates) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("study", Map.of(
                "studyCode", study.getStudyCode(),
                "periodStart", study.getPeriodStart().toString(),
                "periodEnd", study.getPeriodEnd().toString(),
                "thresholdsHours", readJson(study.getThresholdsHours()),
                "quantiles", readJson(study.getQuantiles())));
        content.put("run", Map.of(
                "runId", run.getId(),
                "version", run.getVersion(),
                "eventWatermark", run.getEventWatermark(),
                "committedEventCount", run.getCommittedEventCount(),
                "containerCount", run.getContainerCount(),
                "excludedContainers", readJson(run.getExcludedContainers())));

        // 分组：dim|key|scale → 汇总
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        List<String> assumptions = null;
        List<String> disclosures = null;
        for (DwellEstimate e : estimates) {
            String gk = e.getGroupDim() + "|" + e.getGroupKey();
            Map<String, Object> group = groups.computeIfAbsent(gk, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("dim", e.getGroupDim());
                g.put("key", e.getGroupKey());
                g.put("sampleSize", e.getSampleSize());
                g.put("conflictCount", e.getConflictCount());
                return g;
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> scale = (Map<String, Object>) group.computeIfAbsent(
                    e.getTimeScale().toLowerCase(), k -> new LinkedHashMap<String, Object>());
            JsonNode detail = readJson(e.getDetail());
            if ("GROUP_SUMMARY".equals(e.getMetric()) && "BOUNDS".equals(e.getRoute())) {
                scale.put("coverage", detail.get("coverage"));
                continue;
            }
            if ("GROUP_SUMMARY".equals(e.getMetric()) && "MODEL".equals(e.getRoute())) {
                Map<String, Object> model = new LinkedHashMap<>();
                model.put("status", detail.get("status"));
                if (detail.hasNonNull("params")) {
                    model.put("params", detail.get("params"));
                }
                if (detail.hasNonNull("censoring")) {
                    model.put("censoring", detail.get("censoring"));
                }
                if (detail.hasNonNull("sensitivity")) {
                    model.put("sensitivity", detail.get("sensitivity"));
                }
                scale.put("model", model);
                if ("ALL".equals(e.getGroupDim()) && assumptions == null && detail.hasNonNull("assumptions")) {
                    assumptions = toTextList(detail.get("assumptions"));
                    disclosures = toTextList(detail.get("disclosures"));
                }
                continue;
            }
            String routeKey = "BOUNDS".equals(e.getRoute()) ? "bounds" : "model";
            @SuppressWarnings("unchecked")
            Map<String, Object> route = (Map<String, Object>) scale.computeIfAbsent(
                    routeKey, k -> new LinkedHashMap<String, Object>());
            String metricKey = "OVERDUE_PROPORTION".equals(e.getMetric())
                    ? "overdueProportion" : "quantiles";
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> metricList = (List<Map<String, Object>>) route
                    .computeIfAbsent(metricKey, k -> new ArrayList<Map<String, Object>>());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("arg", e.getMetricArg());
            if (e.getPointEstimate() != null) {
                item.put("point", e.getPointEstimate());
            }
            if (e.getLowerBound() != null) {
                item.put("lower", e.getLowerBound());
            }
            item.put("upper", e.getUpperBound()); // null = 上界不可识别（+∞）
            if ("MODEL".equals(e.getRoute())) {
                // 模型行附非参数界限对照（界限为 null 表示不可识别）
                if (detail.has("boundsLower")) {
                    item.put("boundsLower", detail.get("boundsLower"));
                }
                if (detail.has("boundsUpper")) {
                    item.put("boundsUpper", detail.get("boundsUpper"));
                }
                if (detail.has("withinBounds")) {
                    item.put("withinBounds", detail.get("withinBounds"));
                }
            }
            metricList.add(item);
        }
        content.put("groups", new ArrayList<>(groups.values()));

        // 未决冲突清单（分层保留，不挑选较短时长）
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (DwellRecord r : records) {
            if (!"NONE".equals(r.getConflictStatus())) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("containerNo", r.getContainerNo());
                c.put("conflictStatus", r.getConflictStatus());
                c.put("conflictDetail", r.getConflictDetail());
                c.put("evidenceEventIds", readJson(r.getEvidenceEventIds()));
                c.put("pendingEventIds", readJson(r.getPendingEventIds()));
                c.put("remainHoursRange", List.of(r.getRemainLowerHours(),
                        r.getRemainUpperHours() == null ? "+∞" : r.getRemainUpperHours()));
                conflicts.add(c);
            }
        }
        content.put("unresolvedConflicts", conflicts);
        content.put("assumptions", assumptions == null ? List.of() : assumptions);
        content.put("disclosures", disclosures == null ? List.of() : disclosures);
        content.put("routeComparisonNote",
                "路线一（bounds）只发布证据支持的可行上下界，无需删失分布假设，记录不确定时范围较宽；"
                        + "路线二（model）为 Weibull 参数外推，范围更集中，但依赖非信息性删失与分布假设，"
                        + "模型失配对尾部风险的影响见 sensitivity.boundsContainment 与 disclosures");
        return content;
    }

    private List<String> toTextList(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    private JsonNode readJson(String json) {
        try {
            return om.readTree(json);
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
