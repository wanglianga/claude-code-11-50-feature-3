package com.portops.lakehouse.dwell;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 分析编排：对一次在场风险区间版本，按分组（全部/堆区/航次/箱型）与时间尺度
 * （剩余滞留/总堆存）计算两条路线的统计估计。
 *
 * <ul>
 *   <li>路线一（BOUNDS）：只发布证据支持的可行上下界，无需删失分布假设；</li>
 *   <li>路线二（MODEL）：Weibull 生存模型点估计 + 假设与敏感性披露；
 *       两路线都纳入全部目标箱及其删失状态，不以完整记录子集替代总体。</li>
 * </ul>
 */
@Service
public class DwellAnalysisService {

    /** 模型拟合的最小组样本量（小于则不拟合参数模型，仅发布非参数界限） */
    static final int MIN_MODEL_SAMPLE = 3;

    private final ObjectMapper om;

    public DwellAnalysisService(ObjectMapper om) {
        this.om = om;
    }

    /** 对一批记录（同一 run）计算全部估计行。 */
    public List<DwellEstimate> analyze(DwellStudy study, List<DwellRecord> records) {
        List<Double> thresholds = readDoubleList(study.getThresholdsHours());
        List<Double> quantiles = readDoubleList(study.getQuantiles());
        OffsetDateTime periodStart = study.getPeriodStart();

        Map<String, List<DwellRecord>> groups = new LinkedHashMap<>();
        groups.put("ALL|ALL", new ArrayList<>(records));
        for (DwellRecord r : records) {
            groups.computeIfAbsent("BLOCK|" + key(r.getBlock()), k -> new ArrayList<>()).add(r);
            groups.computeIfAbsent("VOYAGE|" + key(r.getVoyageNo()), k -> new ArrayList<>()).add(r);
            groups.computeIfAbsent("TYPE|" + key(r.getContainerType()), k -> new ArrayList<>()).add(r);
        }

        List<DwellEstimate> out = new ArrayList<>();
        for (Map.Entry<String, List<DwellRecord>> g : groups.entrySet()) {
            String dim = g.getKey().substring(0, g.getKey().indexOf('|'));
            String groupKey = g.getKey().substring(g.getKey().indexOf('|') + 1);
            List<DwellRecord> members = g.getValue();
            for (String scale : List.of("REMAINING", "TOTAL")) {
                out.addAll(analyzeGroup(study, dim, groupKey, members, scale, thresholds, quantiles,
                        periodStart));
            }
        }
        return out;
    }

    private List<DwellEstimate> analyzeGroup(DwellStudy study, String dim, String groupKey,
                                             List<DwellRecord> members, String timeScale,
                                             List<Double> thresholds, List<Double> quantiles,
                                             OffsetDateTime periodStart) {
        List<DwellEstimate> out = new ArrayList<>();
        int n = members.size();
        int conflicts = (int) members.stream().filter(r -> !"NONE".equals(r.getConflictStatus())).count();

        // ---------- 组摘要（覆盖率） ----------
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("exactEntry", members.stream().filter(r -> "EXACT".equals(r.getEntryKind())).count());
        coverage.put("intervalEntry", members.stream().filter(r -> "INTERVAL".equals(r.getEntryKind())).count());
        coverage.put("unboundedEntry", members.stream().filter(r -> r.getEntryLower() == null).count());
        coverage.put("exactExit", members.stream().filter(r -> "EXACT".equals(r.getExitKind())).count());
        coverage.put("intervalExit", members.stream().filter(r -> "INTERVAL".equals(r.getExitKind())).count());
        coverage.put("rightCensored", members.stream().filter(DwellRecord::isRightCensored).count());
        coverage.put("conflictedExit", members.stream().filter(r -> "CONFLICTED".equals(r.getExitKind())).count());
        coverage.put("leftTruncated", members.stream().filter(DwellRecord::isLeftTruncated).count());
        coverage.put("conflicted", conflicts);
        Map<String, Object> summaryDetail = new LinkedHashMap<>();
        summaryDetail.put("coverage", coverage);
        summaryDetail.put("note", "本组全部 " + n + " 只目标箱均纳入估计（含删失与冲突箱），"
                + "不以完整记录子集替代总体；覆盖率为各类证据占比");
        out.add(est("BOUNDS", timeScale, dim, groupKey, n, conflicts, "GROUP_SUMMARY", "-",
                null, null, null, summaryDetail));

        // ---------- 路线一：可行界 ----------
        List<DwellBoundsAnalyzer.Interval> intervals = members.stream()
                .map(r -> intervalOf(r, timeScale)).toList();
        Map<String, double[]> boundsByThreshold = new LinkedHashMap<>();
        for (double tau : thresholds) {
            double[] b = DwellBoundsAnalyzer.overdueProportionBounds(intervals, tau);
            boundsByThreshold.put(arg(tau), b);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("definitelyOverdue", (int) b[2]);
            detail.put("possiblyOverdue", (int) b[3]);
            detail.put("note", "可行界：仅使用证据允许的滞留区间，不猜测区间内时点、不假定删失分布");
            out.add(est("BOUNDS", timeScale, dim, groupKey, n, conflicts,
                    "OVERDUE_PROPORTION", arg(tau), null, b[0], b[1], detail));
        }
        Map<String, Double[]> quantileBounds = new LinkedHashMap<>();
        for (double q : quantiles) {
            Double[] qb = DwellBoundsAnalyzer.quantileBounds(intervals, q);
            quantileBounds.put(arg(q), qb);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("identifiable", qb[1] != null);
            detail.put("note", qb[1] == null
                    ? "分位点上界不可识别：删失/区间证据过多，任何有限上界都与证据相容"
                    : "分位数可识别范围（小时）");
            out.add(est("BOUNDS", timeScale, dim, groupKey, n, conflicts,
                    "QUANTILE", arg(q), null, qb[0], qb[1], detail));
        }

        // ---------- 路线二：生存模型 ----------
        List<DwellModelAnalyzer.ModelObs> modelData = new ArrayList<>();
        int excludedUnverifiable = 0;
        for (DwellRecord r : members) {
            if ("TOTAL".equals(timeScale)) {
                // 总堆存模型：仅纳入进场区间可核实箱（真实进场未知者不伪装已知，排除数披露）
                OffsetDateTime entryUpper = r.getEntryExact() != null ? r.getEntryExact() : r.getEntryUpper();
                boolean verifiable = r.getEntryExact() != null || r.getEntryLower() != null;
                if (!verifiable) {
                    excludedUnverifiable++;
                    continue;
                }
                double truncation = Math.max(0.0,
                        Duration.between(entryUpper, periodStart).toMillis() / 3_600_000.0);
                modelData.add(new DwellModelAnalyzer.ModelObs(
                        r.getTotalLowerHours(), r.getTotalUpperHours(), truncation));
            } else {
                modelData.add(new DwellModelAnalyzer.ModelObs(
                        r.getRemainLowerHours(), r.getRemainUpperHours(), 0.0));
            }
        }

        Map<String, Object> modelSummary = new LinkedHashMap<>();
        if (modelData.size() < MIN_MODEL_SAMPLE) {
            modelSummary.put("status", "INSUFFICIENT_DATA");
            modelSummary.put("reason", "可用于模型拟合的样本 " + modelData.size()
                    + " < " + MIN_MODEL_SAMPLE + "，不拟合参数模型；该组仅以路线一可行界为准");
            out.add(est("MODEL", timeScale, dim, groupKey, n, conflicts, "GROUP_SUMMARY", "-",
                    null, null, null, modelSummary));
            return out;
        }

        DwellModelAnalyzer.FittedModel weibull = DwellModelAnalyzer.fitWeibull(modelData);
        DwellModelAnalyzer.FittedModel exponential = DwellModelAnalyzer.fitExponential(modelData);

        Map<String, Double> tailWeibull = new TreeMap<>();
        Map<String, Double> tailExponential = new TreeMap<>();
        Map<String, Double> tailDelta = new TreeMap<>();
        Map<String, Boolean> containment = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        for (double tau : thresholds) {
            String a = arg(tau);
            double pw = weibull.survival(tau);
            double pe = exponential.survival(tau);
            tailWeibull.put(a, pw);
            tailExponential.put(a, pe);
            tailDelta.put(a, pw - pe);
            double[] b = boundsByThreshold.get(a);
            boolean within = pw >= b[0] - 1e-9 && pw <= b[1] + 1e-9;
            containment.put(a, within);
            if (!within) {
                warnings.add("时限 " + a + "h：模型超期比例 " + round4(pw)
                        + " 超出非参数可行界 [" + round4(b[0]) + ", " + round4(b[1])
                        + "]，分布或删失机制假设可能失配，尾部风险以路线一上界为准");
            }
        }

        Map<String, Object> censoring = new LinkedHashMap<>();
        censoring.put("n", modelData.size());
        censoring.put("exact", modelData.stream()
                .filter(o -> o.upper() != null && Math.abs(o.upper() - o.lower()) < 1e-9).count());
        censoring.put("rightCensored", modelData.stream().filter(o -> o.upper() == null).count());
        censoring.put("intervalCensored", modelData.stream()
                .filter(o -> o.upper() != null && Math.abs(o.upper() - o.lower()) >= 1e-9).count());
        censoring.put("leftTruncated", modelData.stream().filter(o -> o.truncationHours() > 0).count());
        censoring.put("excludedUnverifiableEntry", excludedUnverifiable);

        modelSummary.put("status", weibull.converged() ? "FITTED" : "CONVERGENCE_WARN");
        modelSummary.put("params", Map.of(
                "model", weibull.model(),
                "shape", weibull.shape(),
                "scale", weibull.scale(),
                "logLik", weibull.logLik(),
                "converged", weibull.converged()));
        modelSummary.put("censoring", censoring);
        modelSummary.put("assumptions", assumptions(timeScale));
        Map<String, Object> sensitivity = new LinkedHashMap<>();
        sensitivity.put("exponentialReference", Map.of(
                "scale", exponential.scale(), "logLik", exponential.logLik()));
        sensitivity.put("tailProbWeibull", tailWeibull);
        sensitivity.put("tailProbExponential", tailExponential);
        sensitivity.put("tailDeltaWeibullMinusExponential", tailDelta);
        sensitivity.put("boundsContainment", containment);
        sensitivity.put("warnings", warnings);
        modelSummary.put("sensitivity", sensitivity);
        modelSummary.put("disclosures", disclosures(timeScale, excludedUnverifiable));
        out.add(est("MODEL", timeScale, dim, groupKey, n, conflicts, "GROUP_SUMMARY", "-",
                null, null, null, modelSummary));

        for (double tau : thresholds) {
            String a = arg(tau);
            double[] b = boundsByThreshold.get(a);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("model", "Weibull");
            detail.put("boundsLower", b[0]);
            detail.put("boundsUpper", b[1]);
            detail.put("withinBounds", containment.get(a));
            out.add(est("MODEL", timeScale, dim, groupKey, n, conflicts,
                    "OVERDUE_PROPORTION", a, weibull.survival(tau), null, null, detail));
        }
        for (double q : quantiles) {
            String a = arg(q);
            Double[] qb = quantileBounds.get(a);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("model", "Weibull");
            detail.put("boundsLower", qb[0]);
            detail.put("boundsUpper", qb[1]);
            out.add(est("MODEL", timeScale, dim, groupKey, n, conflicts,
                    "QUANTILE", a, weibull.quantile(q), null, null, detail));
        }
        return out;
    }

    private DwellBoundsAnalyzer.Interval intervalOf(DwellRecord r, String timeScale) {
        if ("TOTAL".equals(timeScale)) {
            return new DwellBoundsAnalyzer.Interval(r.getTotalLowerHours(), r.getTotalUpperHours());
        }
        return new DwellBoundsAnalyzer.Interval(r.getRemainLowerHours(), r.getRemainUpperHours());
    }

    private DwellEstimate est(String route, String timeScale, String dim, String groupKey,
                              int n, int conflicts, String metric, String metricArg,
                              Double point, Double lower, Double upper, Map<String, Object> detail) {
        DwellEstimate e = new DwellEstimate();
        e.setRoute(route);
        e.setTimeScale(timeScale);
        e.setGroupDim(dim);
        e.setGroupKey(groupKey);
        e.setSampleSize(n);
        e.setConflictCount(conflicts);
        e.setMetric(metric);
        e.setMetricArg(metricArg);
        e.setPointEstimate(point);
        e.setLowerBound(lower);
        e.setUpperBound(upper);
        e.setDetail(writeJson(detail));
        return e;
    }

    private static List<String> assumptions(String timeScale) {
        List<String> a = new ArrayList<>();
        a.add("删失与进入机制假设为非信息性：删失/截断时刻与真实滞留时长相互独立；"
                + "若长滞留箱更晚被盘点或闸口覆盖（信息性删失），尾部风险将被低估，最坏情形见路线一上界");
        a.add("分布假设：Weibull 生存分布 S(t)=exp(-(t/λ)^k)；分布形状敏感性见 sensitivity 中指数分布参照与 tailDelta");
        a.add("区间删失按 S(L)−S(R) 计入似然，不把盘点区间中点当作精确时刻；右删失按 S(c) 计入；"
                + "延迟进入按左截断条件似然 1/S(L0) 计入（仅总时长模型）");
        a.add("证据冲突箱以最宽包络区间纳入估计，未剔除、未挑选较短时长");
        if ("TOTAL".equals(timeScale)) {
            a.add("总时长模型仅纳入进场区间可核实箱；真实进场未知箱不排除于剩余滞留模型，"
                    + "但不计入本口径（排除数见 censoring.excludedUnverifiableEntry）");
        }
        return a;
    }

    private static List<String> disclosures(String timeScale, int excludedUnverifiable) {
        List<String> d = new ArrayList<>();
        d.add("模型点估计为参数外推，尾部概率受分布假设影响；与非参数可行界不一致时以路线一为准");
        if ("TOTAL".equals(timeScale)) {
            d.add("左截断时间取证据允许的最小值（保守近似）：真实截断更深时尾部风险为下界；"
                    + "真实进场未知箱不计入本口径（" + excludedUnverifiable + " 只），其总堆存上界见路线一（+∞）");
        } else {
            d.add("剩余滞留口径以各箱风险观察起点为原点（期初在场箱为统计期起点），全部目标箱均纳入");
        }
        return d;
    }

    private static String key(String v) {
        return v == null ? "UNKNOWN" : v;
    }

    /** 指标参数格式化：168 → "168"，0.5 → "0.5" */
    static String arg(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private List<Double> readDoubleList(String json) {
        try {
            List<Double> out = new ArrayList<>();
            for (var n : om.readTree(json)) {
                out.add(n.asDouble());
            }
            return out;
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
