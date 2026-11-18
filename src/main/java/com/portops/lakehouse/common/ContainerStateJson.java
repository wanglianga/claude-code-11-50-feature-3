package com.portops.lakehouse.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;

/** ContainerState 与 JSON 之间的转换（提交物中持久化的状态快照）。 */
public final class ContainerStateJson {

    private static final ObjectMapper OM = new ObjectMapper();

    private ContainerStateJson() {
    }

    public static String toJson(ContainerState s) {
        ObjectNode n = OM.createObjectNode();
        n.put("position", s.position);
        n.put("opStatus", s.opStatus);
        n.put("voyageNo", s.voyageNo);
        n.put("loaded", s.loaded);
        n.put("gateReleased", s.gateReleased);
        n.put("moveCount", s.moveCount);
        ObjectNode lin = OM.createObjectNode();
        s.lineage.forEach((k, v) -> {
            ObjectNode f = OM.createObjectNode();
            f.put("eventId", v.eventId());
            f.put("commitId", v.commitId());
            f.put("sourceSystem", v.sourceSystem());
            f.put("sourceSeq", v.sourceSeq());
            f.put("bizTime", v.bizTime() == null ? null : v.bizTime().toString());
            lin.set(k, f);
        });
        n.set("lineage", lin);
        try {
            return OM.writeValueAsString(n);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ContainerState fromJson(String json) {
        ContainerState s = new ContainerState();
        if (json == null || json.isBlank()) {
            return s;
        }
        try {
            JsonNode n = OM.readTree(json);
            s.position = textOrNull(n, "position");
            s.opStatus = textOrNull(n, "opStatus");
            s.voyageNo = textOrNull(n, "voyageNo");
            s.loaded = n.path("loaded").asBoolean(false);
            s.gateReleased = n.path("gateReleased").asBoolean(false);
            s.moveCount = n.path("moveCount").asInt(0);
            JsonNode lin = n.path("lineage");
            lin.fields().forEachRemaining(e -> {
                JsonNode f = e.getValue();
                String bt = f.path("bizTime").isTextual() ? f.path("bizTime").asText() : null;
                s.lineage.put(e.getKey(), new ContainerState.FieldLineage(
                        f.path("eventId").isNumber() ? f.path("eventId").asLong() : null,
                        f.path("commitId").isNumber() ? f.path("commitId").asLong() : null,
                        f.path("sourceSystem").isTextual() ? f.path("sourceSystem").asText() : null,
                        f.path("sourceSeq").isNumber() ? f.path("sourceSeq").asLong() : null,
                        bt == null ? null : OffsetDateTime.parse(bt)));
            });
            return s;
        } catch (Exception e) {
            throw new IllegalStateException("无法解析提交状态 JSON", e);
        }
    }

    private static String textOrNull(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }
}
