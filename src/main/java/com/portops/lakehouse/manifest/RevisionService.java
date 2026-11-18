package com.portops.lakehouse.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portops.lakehouse.common.BizException;
import com.portops.lakehouse.common.ContainerState;
import com.portops.lakehouse.common.ContainerStateJson;
import com.portops.lakehouse.ingest.IngestEvent;
import com.portops.lakehouse.settlement.FeeAdjustment;
import com.portops.lakehouse.settlement.FeeAdjustmentRepository;
import com.portops.lakehouse.settlement.FeeSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 修订候选服务：截关后到达的更正报文在提交入账后，针对已冻结清单生成修订候选；
 * 对账人员裁定接受（生成费用调整单）或拒绝。原始冻结清单永不被改写。
 */
@Service
public class RevisionService {

    private static final Logger log = LoggerFactory.getLogger(RevisionService.class);

    private final VoyageManifestRepository manifestRepo;
    private final ManifestItemRepository itemRepo;
    private final RevisionCandidateRepository revisionRepo;
    private final FeeAdjustmentRepository adjustmentRepo;
    private final ObjectMapper om;

    public RevisionService(VoyageManifestRepository manifestRepo, ManifestItemRepository itemRepo,
                          RevisionCandidateRepository revisionRepo,
                          FeeAdjustmentRepository adjustmentRepo, ObjectMapper om) {
        this.manifestRepo = manifestRepo;
        this.itemRepo = itemRepo;
        this.revisionRepo = revisionRepo;
        this.adjustmentRepo = adjustmentRepo;
        this.om = om;
    }

    /**
     * 提交生成后调用：若该批事件涉及已冻结清单的箱子，且事件业务时点早于等于截关时点
     * （即截关后补传/更正），并且改变了可收费事实，则生成修订候选。
     * 在提交事务内执行（REQUIRED）。
     */
    public List<Long> createCandidatesForCommit(String containerNo, List<IngestEvent> committedEvents,
                                                ContainerState newState) {
        List<VoyageManifest> frozen = manifestRepo.findFrozenByContainer(containerNo);
        if (frozen.isEmpty()) {
            return List.of();
        }
        List<Long> created = new ArrayList<>();
        for (VoyageManifest m : frozen) {
            Optional<IngestEvent> trigger = committedEvents.stream()
                    .filter(e -> !e.getBizTime().isAfter(m.getCutoffTime()))
                    .max(Comparator.comparing(IngestEvent::getBizTime));
            if (trigger.isEmpty()) {
                continue; // 全部为截关后的新业务，与已冻结清单无关
            }
            ManifestItem item = itemRepo.findByManifestIdAndContainerNo(m.getId(), containerNo).orElse(null);
            if (item == null) {
                continue;
            }
            List<String> diffs = new ArrayList<>();
            if (item.isLoaded() != newState.loaded) {
                diffs.add("loaded: " + item.isLoaded() + " → " + newState.loaded);
            }
            if (item.getMoveCount() != newState.moveCount) {
                diffs.add("moveCount: " + item.getMoveCount() + " → " + newState.moveCount);
            }
            if (!Objects.equals(item.getPosition(), newState.position)) {
                diffs.add("position: " + item.getPosition() + " → " + newState.position);
            }
            if (!Objects.equals(item.getVoyageNo(), newState.voyageNo)) {
                diffs.add("voyageNo: " + item.getVoyageNo() + " → " + newState.voyageNo);
            }
            if (item.isGateReleased() != newState.gateReleased) {
                diffs.add("gateReleased: " + item.isGateReleased() + " → " + newState.gateReleased);
            }
            if (diffs.isEmpty()) {
                continue; // 未改变可收费事实，不产生噪音候选
            }
            if (revisionRepo.existsByManifestIdAndContainerNoAndEventIdAndStatus(
                    m.getId(), containerNo, trigger.get().getId(), "PENDING")) {
                continue;
            }
            RevisionCandidate rc = new RevisionCandidate();
            rc.setManifestId(m.getId());
            rc.setContainerNo(containerNo);
            rc.setEventId(trigger.get().getId());
            rc.setProposedState(ContainerStateJson.toJson(newState));
            rc.setDiffSummary(String.join("; ", diffs));
            created.add(revisionRepo.save(rc).getId());
            log.info("revision candidate created: manifest={} container={} event={} diffs={}",
                    m.getId(), containerNo, trigger.get().getId(), diffs);
        }
        return created;
    }

    /**
     * 裁定修订候选。接受时按冻结清单行与修订状态的费用差额生成费用调整单（可能多行），
     * 调整单记录依据（清单行 + 提交 id + 触发事件），原始清单行不变。
     */
    @Transactional
    public Map<String, Object> decide(Long candidateId, boolean accept, String note, String operator) {
        RevisionCandidate rc = revisionRepo.findById(candidateId)
                .orElseThrow(() -> BizException.notFound("修订候选不存在: " + candidateId));
        if (!"PENDING".equals(rc.getStatus())) {
            throw BizException.conflict("修订候选 " + candidateId + " 已裁定，不可重复裁定");
        }
        rc.setStatus(accept ? "ACCEPTED" : "REJECTED");
        rc.setDecidedBy(operator);
        rc.setDecidedAt(OffsetDateTime.now());
        rc.setDecisionNote(note);
        revisionRepo.save(rc);

        List<Long> adjustmentIds = new ArrayList<>();
        if (accept) {
            ManifestItem item = itemRepo.findByManifestIdAndContainerNo(rc.getManifestId(), rc.getContainerNo())
                    .orElseThrow(() -> BizException.notFound(
                            "清单行不存在: manifest=" + rc.getManifestId() + " container=" + rc.getContainerNo()));
            ContainerState proposed = ContainerStateJson.fromJson(rc.getProposedState());
            Map<String, BigDecimal> frozenByType = sumByFeeType(readCharges(item.getCharges()));
            Map<String, BigDecimal> proposedByType = sumByFeeType(FeeSchedule.computeCharges(proposed));

            Set<String> allTypes = new LinkedHashSet<>();
            allTypes.addAll(frozenByType.keySet());
            allTypes.addAll(proposedByType.keySet());
            for (String feeType : allTypes) {
                BigDecimal delta = proposedByType.getOrDefault(feeType, BigDecimal.ZERO)
                        .subtract(frozenByType.getOrDefault(feeType, BigDecimal.ZERO));
                if (delta.compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }
                FeeAdjustment adj = new FeeAdjustment();
                adj.setSource("REVISION");
                adj.setSourceId(rc.getId());
                adj.setManifestId(rc.getManifestId());
                adj.setContainerNo(rc.getContainerNo());
                adj.setFeeType(feeType);
                adj.setDeltaAmount(delta);
                adj.setReason("截关后更正被接受: " + rc.getDiffSummary());
                Map<String, Object> basis = new LinkedHashMap<>();
                basis.put("manifestItemId", item.getId());
                basis.put("basisCommitIds", readLongList(item.getBasisCommitIds()));
                basis.put("triggerEventId", rc.getEventId());
                basis.put("frozenCharges", readCharges(item.getCharges()));
                basis.put("proposedCharges", FeeSchedule.computeCharges(proposed));
                adj.setBasis(writeJson(basis));
                adj.setCreatedBy(operator);
                adjustmentIds.add(adjustmentRepo.save(adj).getId());
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("candidateId", candidateId);
        r.put("status", rc.getStatus());
        r.put("feeAdjustmentIds", adjustmentIds);
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

    private Map<String, BigDecimal> sumByFeeType(List<Map<String, Object>> charges) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (Map<String, Object> c : charges) {
            String type = String.valueOf(c.get("feeType"));
            BigDecimal amount = new BigDecimal(String.valueOf(c.get("amount")));
            m.merge(type, amount, BigDecimal::add);
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
