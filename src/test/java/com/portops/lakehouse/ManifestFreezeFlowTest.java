package com.portops.lakehouse;

import com.portops.lakehouse.common.BizException;
import com.portops.lakehouse.common.EventType;
import com.portops.lakehouse.common.SourceSystem;
import com.portops.lakehouse.commit.CommitService;
import com.portops.lakehouse.ingest.IngestService;
import com.portops.lakehouse.manifest.ManifestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.UncategorizedDataAccessException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 截关快照冻结：
 * - 存在未提交事件或待裁定事件时冻结失败，且不产生任何清单数据（不发布半个分区）；
 * - 冻结成功后 commitFrontier 覆盖全部分区，清单行费用可由提交解释；
 * - 重复冻结拒绝；提交与清单行不可变（数据库触发器）。
 */
class ManifestFreezeFlowTest extends BaseIntegrationTest {

    private static final String VOYAGE = "V-FREEZE-1";
    private static final String CUTOFF = "2026-11-20T18:00:00+08:00";

    @Autowired
    private IngestService ingestService;
    @Autowired
    private CommitService commitService;
    @Autowired
    private ManifestService manifestService;

    private void ingestAndBook(String c) {
        manifestService.registerBookings(VOYAGE, List.of(c));
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.GATE, 1, c, "GATE-NORTH", EventType.GATE_RELEASE,
                        "2026-11-20T09:00:00+08:00", VOYAGE, "{\"direction\":\"IN\",\"gate\":\"G1\"}"),
                ev(SourceSystem.CRANE, 1, c, "YARD-A", EventType.CRANE_MOVE,
                        "2026-11-20T10:00:00+08:00", VOYAGE,
                        "{\"fromBlock\":\"S\",\"fromSlot\":\"1\",\"toBlock\":\"A\",\"toSlot\":\"01-01\"}"),
                ev(SourceSystem.VESSEL, 1, c, "BERTH-02", EventType.LOAD_CONFIRM,
                        "2026-11-20T15:00:00+08:00", VOYAGE, "{\"voyageNo\":\"" + VOYAGE + "\"}")));
    }

    @Test
    void freezeFailsWhenEventsUncommittedAndPublishesNothing() {
        String c = "CNU7001";
        ingestAndBook(c);
        // 不生成提交，直接冻结 → 必须失败且不留任何清单数据
        assertThatThrownBy(() -> manifestService.freeze(VOYAGE, OffsetDateTime.parse(CUTOFF), "settlement"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未提交");
        Integer manifests = jdbc.queryForObject("select count(*) from voyage_manifest", Integer.class);
        Integer items = jdbc.queryForObject("select count(*) from manifest_item", Integer.class);
        assertThat(manifests).isZero();
        assertThat(items).isZero();
    }

    @Test
    void freezeFailsWhileConflictPendingThenSucceedsAfterResolve() {
        String c = "CNU7002";
        manifestService.registerBookings(VOYAGE, List.of(c));
        ingestService.ingestBatch(List.of(
                ev(SourceSystem.CRANE, 1, c, "YARD-A", EventType.CRANE_MOVE,
                        "2026-11-20T14:00:00+08:00", VOYAGE,
                        "{\"fromBlock\":\"A\",\"fromSlot\":\"1\",\"toBlock\":\"A\",\"toSlot\":\"09-09\"}"),
                ev(SourceSystem.VESSEL, 1, c, "BERTH-02", EventType.LOAD_CONFIRM,
                        "2026-11-20T14:00:00+08:00", VOYAGE, "{\"voyageNo\":\"" + VOYAGE + "\"}")));
        commitService.generate(c);

        // 待裁定存在 → 冻结失败，不能用半个分区发布航次
        assertThatThrownBy(() -> manifestService.freeze(VOYAGE, OffsetDateTime.parse(CUTOFF), "settlement"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("待裁定");
        assertThat(jdbc.queryForObject("select count(*) from voyage_manifest", Integer.class)).isZero();

        // 裁定装船确认有效后 → 冻结成功
        Long vesselEventId = jdbc.queryForObject(
                "select id from ingest_event where container_no=? and event_type='LOAD_CONFIRM'", Long.class, c);
        Long conflictId = jdbc.queryForObject(
                "select id from commit_conflict where container_no=?", Long.class, c);
        commitService.resolve(conflictId, vesselEventId, "装船确认有效", "dispatcher");

        Map<String, Object> frozen = manifestService.freeze(VOYAGE, OffsetDateTime.parse(CUTOFF), "settlement");
        assertThat(frozen.get("status")).isEqualTo("FROZEN");
        @SuppressWarnings("unchecked")
        Map<String, Long> frontier = (Map<String, Long>) frozen.get("commitFrontier");
        assertThat(frontier.keySet()).anyMatch(k -> k.contains("BERTH-02"));
    }

    @Test
    void frozenManifestHasCompleteFrontierAndExplainableCharges() {
        String c = "CNU7003";
        ingestAndBook(c);
        commitService.generate(c);

        Map<String, Object> frozen = manifestService.freeze(VOYAGE, OffsetDateTime.parse(CUTOFF), "settlement");
        Long manifestId = ((Number) frozen.get("manifestId")).longValue();

        @SuppressWarnings("unchecked")
        Map<String, Long> frontier = (Map<String, Long>) frozen.get("commitFrontier");
        // 完整提交边界：三个分区都有提交被引用
        assertThat(frontier.keySet()).containsExactlyInAnyOrder(
                c + "|GATE-NORTH", c + "|YARD-A", c + "|BERTH-02");

        Map<String, Object> detail = manifestService.manifestDetail(manifestId);
        @SuppressWarnings("unchecked")
        List<com.portops.lakehouse.manifest.ManifestItem> items =
                (List<com.portops.lakehouse.manifest.ManifestItem>) detail.get("items");
        assertThat(items).hasSize(1);
        var item = items.get(0);
        assertThat(item.isLoaded()).isTrue();
        assertThat(item.getMoveCount()).isEqualTo(1);
        // 费用 = 装船 450 + 移箱 80 = 530，且依据提交可解释
        assertThat(item.getCharges()).contains("LOAD_FEE").contains("450.00")
                .contains("MOVE_FEE").contains("80.00");
        assertThat(item.getBasisCommitIds()).isNotBlank();

        // 重复冻结 → 拒绝
        assertThatThrownBy(() -> manifestService.freeze(VOYAGE, OffsetDateTime.parse(CUTOFF), "settlement"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可重复冻结");
    }

    @Test
    void commitsAndManifestItemsAreImmutable() {
        String c = "CNU7004";
        ingestAndBook(c);
        commitService.generate(c);
        manifestService.freeze(VOYAGE, OffsetDateTime.parse(CUTOFF), "settlement");

        assertThatThrownBy(() -> jdbc.update("update lake_commit set state='{}' where container_no=?", c))
                .isInstanceOf(UncategorizedDataAccessException.class)
                .hasMessageContaining("不可变");
        assertThatThrownBy(() -> jdbc.update("delete from manifest_item"))
                .isInstanceOf(UncategorizedDataAccessException.class)
                .hasMessageContaining("不可变");
        assertThatThrownBy(() -> jdbc.update("delete from ingest_event"))
                .isInstanceOf(UncategorizedDataAccessException.class)
                .hasMessageContaining("不可变");
    }
}
