package com.portops.lakehouse;

import com.portops.lakehouse.common.EventType;
import com.portops.lakehouse.common.SourceSystem;
import com.portops.lakehouse.commit.CommitConflictRepository;
import com.portops.lakehouse.commit.CommitService;
import com.portops.lakehouse.dispatch.DispatchService;
import com.portops.lakehouse.ingest.IngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发写入与冲突检测：
 * - 业务时点并列且效果冲突 → 待裁定，箱子状态不由最后写入者决定；
 * - 裁定后胜选事件生效，其余被取代但可追溯；
 * - 入库乱序不影响业务时点排序（后到的场桥报文不能覆盖先到的装船确认）；
 * - 来源序号与业务时点矛盾 → 待裁定。
 */
class CommitConflictFlowTest extends BaseIntegrationTest {

    @Autowired
    private IngestService ingestService;
    @Autowired
    private CommitService commitService;
    @Autowired
    private CommitConflictRepository conflictRepo;
    @Autowired
    private DispatchService dispatchService;

    @Test
    void bizTimeTieGoesToPendingArbitrationAndDoesNotDecideLoaded() {
        String c = "CNU9001";
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.GATE, 1, c, "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-20T09:00:00+08:00", "V1", "{\"direction\":\"IN\",\"gate\":\"G3\"}"),
                ev(SourceSystem.CRANE, 1, c, "YARD-A", EventType.CRANE_MOVE,
                        "2026-11-20T14:00:00+08:00", "V1",
                        "{\"fromBlock\":\"A\",\"fromSlot\":\"01\",\"toBlock\":\"A\",\"toSlot\":\"09-09\"}"),
                ev(SourceSystem.VESSEL, 1, c, "BERTH-02", EventType.LOAD_CONFIRM,
                        "2026-11-20T14:00:00+08:00", "V1",
                        "{\"voyageNo\":\"V1\",\"berth\":\"B2\"}")));

        Map<String, Object> result = commitService.generate(c);

        assertThat(result.get("result")).isEqualTo("OK");
        assertThat((List<?>) result.get("conflictIds")).hasSize(1);
        assertThat(conflictRepo.findByStatusOrderByCreatedAtAsc("PENDING")).hasSize(1);
        // 关键断言：装船确认与场桥移动都未生效，loaded 不由最后写入者决定
        Map<String, Object> view = dispatchService.containerView(c);
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) view.get("currentState");
        assertThat(state.get("loaded")).isEqualTo(false);
        assertThat(state.get("opStatus")).isNotEqualTo("LOADED");
        // 两条冲突事件（场桥移动 + 装船确认）都在待裁定
        assertThat(view.get("pendingArbitrationCount")).isEqualTo(2L);
    }

    @Test
    void resolveConflictMakesWinnerEffective() {
        String c = "CNU9002";
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.CRANE, 1, c, "YARD-A", EventType.CRANE_MOVE,
                        "2026-11-20T14:00:00+08:00", "V1",
                        "{\"fromBlock\":\"A\",\"fromSlot\":\"01\",\"toBlock\":\"A\",\"toSlot\":\"09-09\"}"),
                ev(SourceSystem.VESSEL, 1, c, "BERTH-02", EventType.LOAD_CONFIRM,
                        "2026-11-20T14:00:00+08:00", "V1", "{\"voyageNo\":\"V1\"}")));
        commitService.generate(c);
        var conflict = conflictRepo.findByStatusOrderByCreatedAtAsc("PENDING").get(0);

        // 胜选事件 = 装船确认（eventType=LOAD_CONFIRM 的那条）
        Long vesselEventId = jdbc.queryForObject(
                "select id from ingest_event where container_no=? and event_type='LOAD_CONFIRM'",
                Long.class, c);
        Map<String, Object> resolved = commitService.resolve(conflict.getId(), vesselEventId,
                "场桥14:00报文为迟到重复上报，装船确认有效", "dispatcher");

        assertThat(resolved.get("status")).isEqualTo("RESOLVED");
        Map<String, Object> view = dispatchService.containerView(c);
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) view.get("currentState");
        assertThat(state.get("loaded")).isEqualTo(true);
        assertThat(state.get("opStatus")).isEqualTo("LOADED");
        // 被取代的场桥报文仍可在溯源中看到
        List<Map<String, Object>> lineage = dispatchService.lineage(c);
        assertThat(lineage).anyMatch(e -> "SUPERSEDED".equals(String.valueOf(e.get("status"))));
    }

    @Test
    void outOfOrderIngestionDoesNotOverrideLoadConfirm() {
        String c = "CNU9003";
        // 装船确认先入库；场桥报文后入库但业务时点更早
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.VESSEL, 1, c, "BERTH-02", EventType.LOAD_CONFIRM,
                        "2026-11-20T13:30:00+08:00", "V1", "{\"voyageNo\":\"V1\"}")));
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.CRANE, 1, c, "YARD-A", EventType.CRANE_MOVE,
                        "2026-11-20T12:00:00+08:00", "V1",
                        "{\"fromBlock\":\"S\",\"fromSlot\":\"1\",\"toBlock\":\"A\",\"toSlot\":\"03-02\"}")));
        commitService.generate(c);

        Map<String, Object> view = dispatchService.containerView(c);
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) view.get("currentState");
        // 业务时点 13:30 的装船确认晚于 12:00 的场桥移动 → 最终状态为已装船
        assertThat(state.get("loaded")).isEqualTo(true);
        assertThat(state.get("position")).isEqualTo("VESSEL:V1");
    }

    @Test
    void sourceSeqRegressionWithNewerBizTimeGoesToPending() {
        String c = "CNU9004";
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.GATE, 5, c, "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-20T09:00:00+08:00", "V1", "{\"direction\":\"IN\",\"gate\":\"G1\"}")));
        commitService.generate(c);

        // 同一来源更小的序号（3 < 5）却声称更晚的业务时点 → 矛盾，待裁定
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.GATE, 3, c, "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-20T10:00:00+08:00", "V1", "{\"direction\":\"OUT\",\"gate\":\"G1\"}")));
        Map<String, Object> result = commitService.generate(c);

        assertThat((List<?>) result.get("conflictIds")).hasSize(1);
        var conflict = conflictRepo.findByStatusOrderByCreatedAtAsc("PENDING").get(0);
        assertThat(conflict.getReason()).isEqualTo("SOURCE_SEQ_REGRESSION");
    }

    @Test
    void duplicateSourceSeqIsIdempotent() {
        String c = "CNU9005";
        var req = ev(SourceSystem.GATE, 1, c, "GATE-NORTH", EventType.GATE_RELEASE,
                "2026-11-20T09:00:00+08:00", "V1", "{\"direction\":\"IN\",\"gate\":\"G1\"}");
        var r1 = ingestService.ingestBatch(List.of(req)).get(0);
        var r2 = ingestService.ingestBatch(List.of(req)).get(0);
        assertThat(r1.get("result")).isEqualTo("RECEIVED");
        assertThat(r2.get("result")).isEqualTo("DUPLICATE_IGNORED");
        Integer count = jdbc.queryForObject(
                "select count(*) from ingest_event where container_no=?", Integer.class, c);
        assertThat(count).isEqualTo(1);
    }
}
