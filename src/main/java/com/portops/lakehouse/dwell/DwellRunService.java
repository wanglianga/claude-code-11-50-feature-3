package com.portops.lakehouse.dwell;

import com.portops.lakehouse.common.BizException;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行编排：创建滞留研究、计算在场风险区间版本（证据抽取 → 两路线估计落库）。
 * 同一研究的版本生成用 PostgreSQL 咨询锁串行化；全部产物不可变，
 * 迟到离场事件入账后重新计算只产生新版本。
 */
@Service
public class DwellRunService {

    private static final Logger log = LoggerFactory.getLogger(DwellRunService.class);

    private final DwellStudyRepository studyRepo;
    private final DwellObsRunRepository runRepo;
    private final DwellRecordRepository recordRepo;
    private final DwellEstimateRepository estimateRepo;
    private final DwellExtractionService extractionService;
    private final DwellAnalysisService analysisService;
    private final EntityManager em;
    private final com.fasterxml.jackson.databind.ObjectMapper om;

    public DwellRunService(DwellStudyRepository studyRepo, DwellObsRunRepository runRepo,
                           DwellRecordRepository recordRepo, DwellEstimateRepository estimateRepo,
                           DwellExtractionService extractionService, DwellAnalysisService analysisService,
                           EntityManager em, com.fasterxml.jackson.databind.ObjectMapper om) {
        this.studyRepo = studyRepo;
        this.runRepo = runRepo;
        this.recordRepo = recordRepo;
        this.estimateRepo = estimateRepo;
        this.extractionService = extractionService;
        this.analysisService = analysisService;
        this.em = em;
        this.om = om;
    }

    /** 创建滞留研究（窗口与阈值配置；配置不可变，调整须新建研究）。 */
    @Transactional
    public DwellStudy createStudy(CreateStudyRequest req, String operator) {
        if (!req.periodEnd().isAfter(req.periodStart())) {
            throw BizException.badRequest("统计截止必须晚于统计起点");
        }
        if (studyRepo.existsByStudyCode(req.studyCode())) {
            throw BizException.conflict("研究编码已存在（研究配置不可变，请使用新编码）: " + req.studyCode());
        }
        DwellStudy s = new DwellStudy();
        s.setStudyCode(req.studyCode());
        s.setPeriodStart(req.periodStart());
        s.setPeriodEnd(req.periodEnd());
        s.setThresholdsHours(writeJson(req.thresholdsHours()));
        s.setQuantiles(writeJson(req.quantiles()));
        s.setCreatedBy(operator);
        return studyRepo.save(s);
    }

    @Transactional(readOnly = true)
    public List<DwellStudy> listStudies() {
        return studyRepo.findAll();
    }

    @Transactional(readOnly = true)
    public DwellStudy getStudy(Long id) {
        return studyRepo.findById(id).orElseThrow(() -> BizException.notFound("滞留研究不存在: " + id));
    }

    /**
     * 计算新的在场风险区间版本：从事件账抽取证据 → 每箱删失/截断记录 → 两路线估计。
     * 返回版本摘要；旧版本不受影响（不可变）。
     */
    @Transactional
    public Map<String, Object> computeRun(Long studyId, String operator) {
        // 串行化同一研究的版本生成
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(?1))")
                .setParameter(1, "dwell-run|" + studyId)
                .getSingleResult();

        DwellStudy study = getStudy(studyId);
        DwellExtractionService.ExtractionResult extraction =
                extractionService.extract(study.getPeriodStart(), study.getPeriodEnd());

        int version = runRepo.findTopByStudyIdOrderByVersionDesc(studyId)
                .map(DwellObsRun::getVersion).orElse(0) + 1;

        DwellObsRun run = new DwellObsRun();
        run.setStudyId(studyId);
        run.setVersion(version);
        run.setEventWatermark(extraction.eventWatermark());
        run.setCommittedEventCount(extraction.committedEventCount());
        run.setContainerCount(extraction.records().size());
        run.setExcludedContainers(writeJson(extraction.excluded()));
        run.setCreatedBy(operator);
        Long runId = runRepo.save(run).getId();

        for (DwellRecord rec : extraction.records()) {
            rec.setRunId(runId);
        }
        recordRepo.saveAll(extraction.records());

        List<DwellEstimate> estimates = analysisService.analyze(study, extraction.records());
        for (DwellEstimate e : estimates) {
            e.setRunId(runId);
        }
        estimateRepo.saveAll(estimates);

        log.info("dwell run computed: study={} version={} containers={} estimates={}",
                study.getStudyCode(), version, extraction.records().size(), estimates.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("studyId", studyId);
        result.put("studyCode", study.getStudyCode());
        result.put("version", version);
        result.put("eventWatermark", extraction.eventWatermark());
        result.put("committedEventCount", extraction.committedEventCount());
        result.put("containerCount", extraction.records().size());
        result.put("excludedContainers", extraction.excluded());
        result.put("estimateCount", estimates.size());
        return result;
    }

    @Transactional(readOnly = true)
    public List<DwellObsRun> listRuns(Long studyId) {
        return runRepo.findByStudyIdOrderByVersionAsc(studyId);
    }

    @Transactional(readOnly = true)
    public List<DwellRecord> listRecords(Long runId, String containerNo, boolean onlyConflicts) {
        List<DwellRecord> all = recordRepo.findByRunIdOrderByContainerNoAsc(runId);
        List<DwellRecord> out = new ArrayList<>();
        for (DwellRecord r : all) {
            if (containerNo != null && !r.getContainerNo().equals(containerNo)) {
                continue;
            }
            if (onlyConflicts && "NONE".equals(r.getConflictStatus())) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<DwellEstimate> listEstimates(Long runId, String route, String groupDim, String timeScale) {
        List<DwellEstimate> all = estimateRepo
                .findByRunIdOrderByRouteAscTimeScaleAscGroupDimAscGroupKeyAscMetricAscMetricArgAsc(runId);
        List<DwellEstimate> out = new ArrayList<>();
        for (DwellEstimate e : all) {
            if (route != null && !e.getRoute().equals(route)) {
                continue;
            }
            if (groupDim != null && !e.getGroupDim().equals(groupDim)) {
                continue;
            }
            if (timeScale != null && !e.getTimeScale().equals(timeScale)) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private String writeJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
