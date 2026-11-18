package com.portops.lakehouse.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portops.lakehouse.common.BizException;
import com.portops.lakehouse.common.ContainerState;
import com.portops.lakehouse.common.EventStatus;
import com.portops.lakehouse.commit.CommitConflictRepository;
import com.portops.lakehouse.commit.LakeCommit;
import com.portops.lakehouse.commit.LakeCommitRepository;
import com.portops.lakehouse.ingest.IngestEvent;
import com.portops.lakehouse.ingest.IngestEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度查询：从完整提交合并推导箱区当前状态。
 * 平台选择事件账+提交清单路线，调度视图在读时合并（承担合并延迟），
 * 换取补传更正不改写已锁定航次收费事实的能力。
 */
@Service
public class DispatchService {

    private final IngestEventRepository eventRepo;
    private final LakeCommitRepository commitRepo;
    private final CommitConflictRepository conflictRepo;
    private final ObjectMapper om;

    public DispatchService(IngestEventRepository eventRepo, LakeCommitRepository commitRepo,
                           CommitConflictRepository conflictRepo, ObjectMapper om) {
        this.eventRepo = eventRepo;
        this.commitRepo = commitRepo;
        this.conflictRepo = conflictRepo;
        this.om = om;
    }

    /** 单箱当前视图：合并全部已提交事件，逐字段给出来源（哪条闸口/场桥/装船记录）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> containerView(String containerNo) {
        List<IngestEvent> committed = eventRepo.findCommitted(containerNo);
        ContainerState state = new ContainerState();
        for (IngestEvent e : committed) {
            state.apply(toView(e));
        }
        List<LakeCommit> commits = commitRepo.findByContainerNoOrderByOpPartitionAscCommitSeqAsc(containerNo);
        long uncommitted = eventRepo.findByContainerNoAndStatus(containerNo, EventStatus.RECEIVED).size();
        long pendingArb = eventRepo.findByContainerNoAndStatus(containerNo, EventStatus.PENDING_ARBITRATION).size();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("containerNo", containerNo);
        r.put("currentState", state.toMap());
        r.put("committedEventCount", committed.size());
        r.put("commitCount", commits.size());
        r.put("uncommittedEventCount", uncommitted);
        r.put("pendingArbitrationCount", pendingArb);
        r.put("conflicts", conflictRepo.findByContainerNoOrderByCreatedAtAsc(containerNo));
        return r;
    }

    /** 单箱溯源：全部事件及其处理状态、所属提交，说明每次更正来自哪条来源记录。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> lineage(String containerNo) {
        List<IngestEvent> events = eventRepo
                .findByContainerNoOrderByBizTimeAscSourceSystemAscSourceSeqAsc(containerNo);
        List<Map<String, Object>> out = new ArrayList<>();
        for (IngestEvent e : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventId", e.getId());
            m.put("sourceSystem", e.getSourceSystem());
            m.put("sourceSeq", e.getSourceSeq());
            m.put("eventType", e.getEventType());
            m.put("opPartition", e.getOpPartition());
            m.put("bizTime", e.getBizTime());
            m.put("status", e.getStatus());
            m.put("commitId", e.getCommitId());
            m.put("payload", readJson(e.getPayload()));
            out.add(m);
        }
        return out;
    }

    /** 分区当前堆位视图：该分区有过提交的全部箱子的当前状态（跨分区合并）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> partitionView(String opPartition) {
        List<LakeCommit> commits = commitRepo.findAll().stream()
                .filter(c -> c.getOpPartition().equals(opPartition))
                .toList();
        List<String> containers = commits.stream().map(LakeCommit::getContainerNo)
                .distinct().sorted().toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : containers) {
            Map<String, Object> view = containerView(c);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("containerNo", c);
            row.put("currentState", view.get("currentState"));
            row.put("pendingArbitrationCount", view.get("pendingArbitrationCount"));
            out.add(row);
        }
        return out;
    }

    private ContainerState.EventView toView(IngestEvent e) {
        return new ContainerState.EventView(e.getId(), e.getCommitId(), e.getSourceSystem(),
                e.getSourceSeq(), e.getEventType(), e.getBizTime(), e.getVoyageNo(), readJson(e.getPayload()));
    }

    private JsonNode readJson(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
