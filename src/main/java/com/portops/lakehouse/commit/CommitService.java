package com.portops.lakehouse.commit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portops.lakehouse.common.BizException;
import com.portops.lakehouse.common.ContainerState;
import com.portops.lakehouse.common.ContainerStateJson;
import com.portops.lakehouse.common.EventStatus;
import com.portops.lakehouse.ingest.IngestEvent;
import com.portops.lakehouse.ingest.IngestEventRepository;
import com.portops.lakehouse.manifest.RevisionService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 湖仓写入服务：按箱号把已入账事件折叠为按作业分区组织的不可变提交。
 *
 * <p>并发语义：</p>
 * <ul>
 *   <li>同一箱号的提交生成用 PostgreSQL 咨询锁串行化；</li>
 *   <li>事件排序键为 (业务时点, 来源系统, 来源序号)，与入库先后无关，
 *       因此"后到的消息"无法覆盖先到的装船确认；</li>
 *   <li>两条链路同时写同一箱子时：业务时点并列且效果冲突、或来源序号与业务时点矛盾的，
 *       相关事件进入待裁定，不产生提交，箱子状态不由数据库最后写入者决定；</li>
 *   <li>冲突检测在箱号维度跨分区进行（装船确认在泊位分区、场桥移动在堆场分区，
 *       但它们争的是同一箱子的状态）。</li>
 * </ul>
 */
@Service
public class CommitService {

    private static final Logger log = LoggerFactory.getLogger(CommitService.class);

    private final IngestEventRepository eventRepo;
    private final LakeCommitRepository commitRepo;
    private final CommitConflictRepository conflictRepo;
    private final RevisionService revisionService;
    private final EntityManager em;
    private final ObjectMapper om;

    public CommitService(IngestEventRepository eventRepo,
                         LakeCommitRepository commitRepo,
                         CommitConflictRepository conflictRepo,
                         RevisionService revisionService,
                         EntityManager em,
                         ObjectMapper om) {
        this.eventRepo = eventRepo;
        this.commitRepo = commitRepo;
        this.conflictRepo = conflictRepo;
        this.revisionService = revisionService;
        this.em = em;
        this.om = om;
    }

    private record ConflictDraft(String reason, List<Long> eventIds, String detail) {
    }

    /** 为指定箱号生成提交（跨分区冲突检测，按分区落不可变提交）。 */
    @Transactional
    public Map<String, Object> generate(String containerNo) {
        // 串行化同一箱号的提交生成，防止并发事务产出冲突提交
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(?1))")
                .setParameter(1, "commit|" + containerNo)
                .getSingleResult();

        List<IngestEvent> pending = eventRepo
                .findByContainerNoAndStatusOrderByBizTimeAscSourceSystemAscSourceSeqAsc(
                        containerNo, EventStatus.RECEIVED);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("containerNo", containerNo);
        if (pending.isEmpty()) {
            result.put("result", "NO_PENDING_EVENTS");
            return result;
        }

        // 基准状态 = 全部已提交事件折叠（事件账是事实源）
        List<IngestEvent> committed = eventRepo.findCommitted(containerNo);
        ContainerState baseState = new ContainerState();
        Map<String, Long> maxSeqs = new HashMap<>();
        OffsetDateTime frontierBiz = null;
        for (IngestEvent e : committed) {
            baseState.apply(toView(e));
            maxSeqs.merge(e.getSourceSystem().name(), e.getSourceSeq(), Math::max);
            frontierBiz = frontierBiz == null || e.getBizTime().isAfter(frontierBiz) ? e.getBizTime() : frontierBiz;
        }

        // ---------- 冲突检测（箱号维度，跨分区） ----------
        Set<Long> conflictedIds = new LinkedHashSet<>();
        List<ConflictDraft> drafts = new ArrayList<>();

        // 规则一：来源序号回退（小于已提交水位）却声称更晚的业务时点 —— 来源顺序与业务顺序矛盾
        for (IngestEvent e : pending) {
            Long maxSeq = maxSeqs.get(e.getSourceSystem().name());
            if (maxSeq != null && e.getSourceSeq() < maxSeq
                    && frontierBiz != null && e.getBizTime().isAfter(frontierBiz)) {
                conflictedIds.add(e.getId());
                drafts.add(new ConflictDraft("SOURCE_SEQ_REGRESSION", List.of(e.getId()),
                        "来源序号 " + e.getSourceSeq() + " 小于已提交水位 " + maxSeq
                                + "，但业务时点 " + e.getBizTime() + " 晚于已提交水位 " + frontierBiz
                                + "，来源顺序与业务顺序矛盾，无法自动排序"));
            }
        }

        // 规则二：业务时点并列（跨来源）且对同一状态字段产生不同效果
        Map<OffsetDateTime, List<IngestEvent>> byBiz = pending.stream()
                .filter(e -> !conflictedIds.contains(e.getId()))
                .collect(Collectors.groupingBy(IngestEvent::getBizTime, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<OffsetDateTime, List<IngestEvent>> entry : byBiz.entrySet()) {
            List<IngestEvent> tie = entry.getValue();
            if (tie.size() < 2) {
                continue;
            }
            Map<Long, Map<String, Object>> effects = new HashMap<>();
            for (IngestEvent e : tie) {
                effects.put(e.getId(), effectOf(baseState, e));
            }
            Set<Long> tieConflicted = new LinkedHashSet<>();
            StringBuilder detail = new StringBuilder();
            for (int i = 0; i < tie.size(); i++) {
                for (int j = i + 1; j < tie.size(); j++) {
                    IngestEvent a = tie.get(i);
                    IngestEvent b = tie.get(j);
                    if (a.getSourceSystem() == b.getSourceSystem()) {
                        continue; // 同源事件按来源序号排序，天然有序
                    }
                    Map<String, Object> ea = effects.get(a.getId());
                    Map<String, Object> eb = effects.get(b.getId());
                    for (Map.Entry<String, Object> f : ea.entrySet()) {
                        Object vb = eb.get(f.getKey());
                        if (vb != null && !f.getValue().equals(vb)) {
                            tieConflicted.add(a.getId());
                            tieConflicted.add(b.getId());
                            detail.append(String.format("字段[%s]: %s#%d→%s 与 %s#%d→%s; ",
                                    f.getKey(), a.getSourceSystem(), a.getSourceSeq(), f.getValue(),
                                    b.getSourceSystem(), b.getSourceSeq(), vb));
                        }
                    }
                }
            }
            if (!tieConflicted.isEmpty()) {
                conflictedIds.addAll(tieConflicted);
                drafts.add(new ConflictDraft("BIZ_TIME_TIE", List.copyOf(tieConflicted),
                        "业务时点 " + entry.getKey() + " 并列且效果冲突: " + detail));
            }
        }

        // ---------- 可排序事件按全局顺序折叠，按分区落提交 ----------
        List<IngestEvent> orderable = pending.stream()
                .filter(e -> !conflictedIds.contains(e.getId()))
                .sorted(Comparator.comparing(IngestEvent::getBizTime)
                        .thenComparing(e -> e.getSourceSystem().ordinal())
                        .thenComparing(IngestEvent::getSourceSeq))
                .toList();

        List<Map<String, Object>> commitSummaries = new ArrayList<>();
        ContainerState newState = baseState.copy();
        if (!orderable.isEmpty()) {
            // 每个分区的最后一个事件在全局序列中的位置（该分区的提交在此刻落盘，状态为全局当前状态）
            Map<String, Integer> lastIdxByPartition = new HashMap<>();
            for (int i = 0; i < orderable.size(); i++) {
                lastIdxByPartition.put(orderable.get(i).getOpPartition(), i);
            }
            Map<String, List<IngestEvent>> eventsByPartition = new LinkedHashMap<>();
            Map<String, Long> newMaxSeqs = new HashMap<>(maxSeqs);
            for (int i = 0; i < orderable.size(); i++) {
                IngestEvent e = orderable.get(i);
                newState.apply(toView(e));
                newMaxSeqs.merge(e.getSourceSystem().name(), e.getSourceSeq(), Math::max);
                eventsByPartition.computeIfAbsent(e.getOpPartition(), k -> new ArrayList<>()).add(e);

                if (lastIdxByPartition.get(e.getOpPartition()) == i) {
                    // 落该分区的不可变提交
                    String partition = e.getOpPartition();
                    List<IngestEvent> partEvents = eventsByPartition.get(partition);
                    long nextSeq = commitRepo
                            .findTopByContainerNoAndOpPartitionOrderByCommitSeqDesc(containerNo, partition)
                            .map(LakeCommit::getCommitSeq).orElse(0L) + 1;
                    LakeCommit c = new LakeCommit();
                    c.setContainerNo(containerNo);
                    c.setOpPartition(partition);
                    c.setCommitSeq(nextSeq);
                    c.setEventIds(writeJson(partEvents.stream().map(IngestEvent::getId).toList()));
                    c.setMaxSourceSeqs(writeJson(newMaxSeqs));
                    c.setState(ContainerStateJson.toJson(newState));
                    c.setMaxBizTime(partEvents.stream().map(IngestEvent::getBizTime)
                            .max(OffsetDateTime::compareTo).orElseThrow());
                    Long commitId = commitRepo.save(c).getId();
                    for (IngestEvent pe : partEvents) {
                        pe.setStatus(EventStatus.COMMITTED);
                        pe.setCommitId(commitId);
                    }
                    eventRepo.saveAll(partEvents);
                    Map<String, Object> cs = new LinkedHashMap<>();
                    cs.put("commitId", commitId);
                    cs.put("opPartition", partition);
                    cs.put("commitSeq", nextSeq);
                    cs.put("eventIds", partEvents.stream().map(IngestEvent::getId).toList());
                    commitSummaries.add(cs);
                }
            }
        }

        // ---------- 冲突落账（待裁定） ----------
        List<Long> conflictIds = new ArrayList<>();
        Map<Long, IngestEvent> byId = pending.stream().collect(Collectors.toMap(IngestEvent::getId, e -> e));
        for (ConflictDraft d : drafts) {
            CommitConflict cc = new CommitConflict();
            cc.setContainerNo(containerNo);
            cc.setOpPartition("MULTI"); // 冲突跨分区发生，分区维度见各事件
            cc.setEventIds(writeJson(d.eventIds()));
            cc.setReason(d.reason());
            cc.setDetail(d.detail());
            conflictIds.add(conflictRepo.save(cc).getId());
            for (Long eid : d.eventIds()) {
                IngestEvent e = byId.get(eid);
                if (e != null) {
                    e.setStatus(EventStatus.PENDING_ARBITRATION);
                    eventRepo.save(e);
                }
            }
        }

        // ---------- 截关后更正 → 修订候选 ----------
        List<Long> revisionIds = new ArrayList<>();
        if (!orderable.isEmpty()) {
            revisionIds = revisionService.createCandidatesForCommit(containerNo, orderable, newState);
        }

        result.put("result", "OK");
        result.put("commits", commitSummaries);
        result.put("conflictIds", conflictIds);
        result.put("revisionCandidateIds", revisionIds);
        log.info("commits generated: container={} commits={} conflicts={} revisions={}",
                containerNo, commitSummaries.size(), conflictIds.size(), revisionIds.size());
        return result;
    }

    /** 列出存在待提交事件的箱号。 */
    @Transactional(readOnly = true)
    public List<String> listPendingContainers() {
        return eventRepo.findPendingContainers();
    }

    /**
     * 裁定冲突：指定胜选事件，其余事件标记为被取代（保留在账上可追溯），
     * 胜选事件回到待提交状态并立即重新生成提交（同一事务，咨询锁在 generate 内获取）。
     */
    @Transactional
    public Map<String, Object> resolve(Long conflictId, Long winnerEventId, String note, String operator) {
        CommitConflict cc = conflictRepo.findById(conflictId)
                .orElseThrow(() -> BizException.notFound("冲突不存在: " + conflictId));
        if (!"PENDING".equals(cc.getStatus())) {
            throw BizException.conflict("冲突 " + conflictId + " 已裁定，不可重复裁定");
        }
        List<Long> ids = readLongList(cc.getEventIds());
        if (!ids.contains(winnerEventId)) {
            throw BizException.badRequest("胜选事件 " + winnerEventId + " 不在冲突集合 " + ids + " 内");
        }
        List<IngestEvent> events = eventRepo.findAllById(ids);
        for (IngestEvent e : events) {
            e.setStatus(e.getId().equals(winnerEventId) ? EventStatus.RECEIVED : EventStatus.SUPERSEDED);
        }
        eventRepo.saveAll(events);

        cc.setStatus("RESOLVED");
        cc.setResolvedBy(operator);
        cc.setResolvedAt(OffsetDateTime.now());
        cc.setResolution("winnerEventId=" + winnerEventId + (note == null ? "" : "; " + note));
        conflictRepo.save(cc);

        Map<String, Object> regen = generate(cc.getContainerNo());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conflictId", conflictId);
        result.put("status", "RESOLVED");
        result.put("winnerEventId", winnerEventId);
        result.put("supersededEventIds", ids.stream().filter(i -> !i.equals(winnerEventId)).toList());
        result.put("regeneratedCommits", regen);
        return result;
    }

    /** 计算事件相对基准状态的效果（变更字段 → 新值）。 */
    private Map<String, Object> effectOf(ContainerState base, IngestEvent e) {
        ContainerState after = base.copy();
        after.apply(toView(e));
        Map<String, Object> changed = new LinkedHashMap<>();
        if (!Objects.equals(base.position, after.position)) {
            changed.put("position", after.position);
        }
        if (!Objects.equals(base.opStatus, after.opStatus)) {
            changed.put("opStatus", after.opStatus);
        }
        if (!Objects.equals(base.voyageNo, after.voyageNo)) {
            changed.put("voyageNo", after.voyageNo);
        }
        if (base.loaded != after.loaded) {
            changed.put("loaded", after.loaded);
        }
        if (base.gateReleased != after.gateReleased) {
            changed.put("gateReleased", after.gateReleased);
        }
        if (base.moveCount != after.moveCount) {
            changed.put("moveCount", after.moveCount);
        }
        return changed;
    }

    private ContainerState.EventView toView(IngestEvent e) {
        JsonNode payload;
        try {
            payload = om.readTree(e.getPayload());
        } catch (Exception ex) {
            throw new IllegalStateException("事件 payload 无法解析: " + e.getId(), ex);
        }
        return new ContainerState.EventView(e.getId(), e.getCommitId(), e.getSourceSystem(),
                e.getSourceSeq(), e.getEventType(), e.getBizTime(), e.getVoyageNo(), payload);
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
