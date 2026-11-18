package com.portops.lakehouse.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 箱子状态：由不可变作业事件账按 (业务时点, 来源系统, 来源序号) 顺序折叠推导。
 * 每个字段都记录溯源信息（来自哪条闸口/场桥/盘点/装船记录、哪个提交）。
 */
public class ContainerState {

    /** 单个字段的溯源信息。 */
    public record FieldLineage(Long eventId, Long commitId, String sourceSystem,
                               Long sourceSeq, OffsetDateTime bizTime) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventId", eventId);
            m.put("commitId", commitId);
            m.put("sourceSystem", sourceSystem);
            m.put("sourceSeq", sourceSeq);
            m.put("bizTime", bizTime == null ? null : bizTime.toString());
            return m;
        }
    }

    public String position;          // 当前位置：堆位 / VESSEL:航次 / GATE-OUT
    public String opStatus;          // ARRIVED / IN_YARD / LOADED / RELEASED
    public String voyageNo;          // 所属航次
    public boolean loaded;           // 是否已装船
    public boolean gateReleased;     // 是否闸口放行出场
    public int moveCount;            // 场桥移动次数
    public final Map<String, FieldLineage> lineage = new LinkedHashMap<>();

    public ContainerState copy() {
        ContainerState c = new ContainerState();
        c.position = position;
        c.opStatus = opStatus;
        c.voyageNo = voyageNo;
        c.loaded = loaded;
        c.gateReleased = gateReleased;
        c.moveCount = moveCount;
        c.lineage.putAll(lineage);
        return c;
    }

    /** 将一条事件折叠进状态。event 参数为折叠所需的轻量视图。 */
    public void apply(EventView e) {
        JsonNode p = e.payload();
        switch (e.eventType()) {
            case GATE_RELEASE -> {
                String direction = text(p, "direction", "IN");
                if ("OUT".equalsIgnoreCase(direction)) {
                    set("position", "GATE-OUT", e);
                    this.gateReleased = true;
                    set("gateReleased", null, e);
                    set("opStatus", "RELEASED", e);
                    this.opStatus = "RELEASED";
                } else {
                    set("position", "GATE-IN:" + text(p, "gate", "?"), e);
                    set("opStatus", "ARRIVED", e);
                    this.opStatus = "ARRIVED";
                }
            }
            case CRANE_MOVE -> {
                String to = text(p, "toBlock", "?") + "-" + text(p, "toSlot", "?");
                set("position", to, e);
                this.moveCount++;
                set("moveCount", null, e);
                if (!this.loaded) {
                    set("opStatus", "IN_YARD", e);
                    this.opStatus = "IN_YARD";
                }
            }
            case YARD_CHECK -> {
                String pos = text(p, "block", "?") + "-" + text(p, "slot", "?");
                set("position", pos, e);
            }
            case LOAD_CONFIRM -> {
                String voyage = text(p, "voyageNo", e.voyageNo() == null ? "?" : e.voyageNo());
                this.loaded = true;
                set("loaded", null, e);
                this.voyageNo = voyage;
                set("voyageNo", voyage, e);
                this.opStatus = "LOADED";
                set("opStatus", "LOADED", e);
                this.position = "VESSEL:" + voyage;
                set("position", this.position, e);
            }
            case CORRECTION -> {
                // 更正报文：显式字段覆盖，并记录溯源
                if (p.hasNonNull("position")) {
                    this.position = p.get("position").asText();
                    set("position", this.position, e);
                }
                if (p.hasNonNull("opStatus")) {
                    this.opStatus = p.get("opStatus").asText();
                    set("opStatus", this.opStatus, e);
                }
                if (p.hasNonNull("voyageNo")) {
                    this.voyageNo = p.get("voyageNo").asText();
                    set("voyageNo", this.voyageNo, e);
                }
                if (p.hasNonNull("loaded")) {
                    this.loaded = p.get("loaded").asBoolean();
                    set("loaded", null, e);
                }
                if (p.hasNonNull("moveCount")) {
                    this.moveCount = p.get("moveCount").asInt();
                    set("moveCount", null, e);
                }
            }
        }
    }

    private void set(String field, String value, EventView e) {
        lineage.put(field, new FieldLineage(e.id(), e.commitId(), e.sourceSystem().name(),
                e.sourceSeq(), e.bizTime()));
    }

    private static String text(JsonNode n, String field, String dft) {
        return n != null && n.hasNonNull(field) ? n.get(field).asText() : dft;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("position", position);
        m.put("opStatus", opStatus);
        m.put("voyageNo", voyageNo);
        m.put("loaded", loaded);
        m.put("gateReleased", gateReleased);
        m.put("moveCount", moveCount);
        Map<String, Object> lin = new LinkedHashMap<>();
        lineage.forEach((k, v) -> lin.put(k, v.toMap()));
        m.put("lineage", lin);
        return m;
    }

    /** 折叠所需的事件轻量视图。 */
    public record EventView(Long id, Long commitId, SourceSystem sourceSystem, Long sourceSeq,
                            EventType eventType, OffsetDateTime bizTime, String voyageNo,
                            JsonNode payload) {
    }
}
