package com.portops.lakehouse.dwell;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 滞留分布与超期风险服务接口：
 * 覆盖期初在场（延迟进入）、期末仍在场（右删失）与区间时间证据（区间删失）的
 * 滞留分布两条分析路线（非参数可行界 / Weibull 生存模型），
 * 在场风险区间、统计估计与管理报告分别版本化。
 */
@RestController
@RequestMapping("/api/dwell")
@Tag(name = "08-滞留分析", description = "堆场滞留分布与超期风险：删失/截断证据记录、两路线估计、版本化管理报告")
public class DwellController {

    private final DwellRunService runService;
    private final DwellReportService reportService;

    public DwellController(DwellRunService runService, DwellReportService reportService) {
        this.runService = runService;
        this.reportService = reportService;
    }

    @PostMapping("/studies")
    @Operation(summary = "创建滞留研究", description = "定义统计窗口、超期时限与分位点；配置不可变，调整须新建研究")
    public DwellStudy createStudy(@Valid @RequestBody CreateStudyRequest req, Authentication auth) {
        return runService.createStudy(req, auth == null ? "anonymous" : auth.getName());
    }

    @GetMapping("/studies")
    @Operation(summary = "列出滞留研究")
    public List<DwellStudy> listStudies() {
        return runService.listStudies();
    }

    @GetMapping("/studies/{id}")
    @Operation(summary = "研究详情（含区间版本与报告版本）")
    public Map<String, Object> studyDetail(@PathVariable Long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("study", runService.getStudy(id));
        out.put("runs", runService.listRuns(id));
        out.put("reports", reportService.listReports(id));
        return out;
    }

    @PostMapping("/studies/{id}/runs")
    @Operation(summary = "计算新的在场风险区间版本",
            description = "从事件账抽取证据构造每箱删失/截断记录，并计算两条路线估计；迟到离场事件只产生新版本")
    public Map<String, Object> computeRun(@PathVariable Long id, Authentication auth) {
        return runService.computeRun(id, auth == null ? "anonymous" : auth.getName());
    }

    @GetMapping("/studies/{id}/runs")
    @Operation(summary = "列出区间版本")
    public List<DwellObsRun> listRuns(@PathVariable Long id) {
        return runService.listRuns(id);
    }

    @GetMapping("/runs/{runId}/records")
    @Operation(summary = "每箱删失/截断记录",
            description = "含进场/离场证据区间、延迟进入与右删失标记、剩余滞留与总堆存可行区间、冲突状态")
    public List<DwellRecord> records(@PathVariable Long runId,
                                     @RequestParam(required = false) String containerNo,
                                     @RequestParam(defaultValue = "false") boolean onlyConflicts) {
        return runService.listRecords(runId, containerNo, onlyConflicts);
    }

    @GetMapping("/runs/{runId}/estimates")
    @Operation(summary = "统计估计",
            description = "route=BOUNDS 只发布证据支持的可行界；route=MODEL 为 Weibull 点估计并附假设与敏感性披露")
    public List<DwellEstimate> estimates(@PathVariable Long runId,
                                         @RequestParam(required = false) String route,
                                         @RequestParam(required = false) String groupDim,
                                         @RequestParam(required = false) String timeScale) {
        return runService.listEstimates(runId, route, groupDim, timeScale);
    }

    @PostMapping("/studies/{id}/reports")
    @Operation(summary = "生成管理报告新版本", description = "默认引用最新区间版本；报告不可变、独立版本化")
    public Map<String, Object> generateReport(@PathVariable Long id,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              Authentication auth) {
        Long runId = body == null || body.get("runId") == null
                ? null : ((Number) body.get("runId")).longValue();
        return reportService.generateReport(id, runId, auth == null ? "anonymous" : auth.getName());
    }

    @GetMapping("/studies/{id}/reports")
    @Operation(summary = "列出报告版本")
    public List<DwellReport> listReports(@PathVariable Long id) {
        return reportService.listReports(id);
    }

    @GetMapping("/reports/{reportId}")
    @Operation(summary = "报告内容", description = "分组汇总（两路线×两时间尺度）、未决冲突清单、假设与敏感性披露")
    public Map<String, Object> reportContent(@PathVariable Long reportId) {
        return reportService.reportContent(reportId);
    }
}
