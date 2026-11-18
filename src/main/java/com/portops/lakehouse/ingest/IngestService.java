package com.portops.lakehouse.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portops.lakehouse.common.BizException;
import com.portops.lakehouse.common.EventStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接入服务：保存来源事件及其顺序标识。
 * 同一来源系统的 sourceSeq 唯一，重复上报幂等拒绝；
 * 事件一旦入账不可删除（数据库触发器保证）。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final IngestEventRepository repo;
    private final ObjectMapper om;

    public IngestService(IngestEventRepository repo, ObjectMapper om) {
        this.repo = repo;
        this.om = om;
    }

    /** 批量上报。每条独立校验与落账，返回逐条结果。 */
    public List<Map<String, Object>> ingestBatch(List<EventIngestRequest> requests) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (EventIngestRequest req : requests) {
            results.add(ingestOne(req));
        }
        return results;
    }

    /** 单条上报（独立事务，避免批量中一条失败拖垮全部）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> ingestOne(EventIngestRequest req) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sourceSystem", req.sourceSystem());
        r.put("sourceSeq", req.sourceSeq());
        r.put("containerNo", req.containerNo());

        // 校验 payload 是合法 JSON
        try {
            om.readTree(req.payload());
        } catch (Exception e) {
            throw BizException.badRequest("payload 不是合法 JSON: " + req.sourceSystem() + "#" + req.sourceSeq());
        }

        if (repo.existsBySourceSystemAndSourceSeq(req.sourceSystem(), req.sourceSeq())) {
            // 幂等：同一来源同一序号重复上报，不重复入账
            r.put("result", "DUPLICATE_IGNORED");
            return r;
        }

        IngestEvent e = new IngestEvent();
        e.setSourceSystem(req.sourceSystem());
        e.setSourceSeq(req.sourceSeq());
        e.setContainerNo(req.containerNo());
        e.setOpPartition(req.opPartition());
        e.setEventType(req.eventType());
        e.setBizTime(req.bizTime());
        e.setVoyageNo(req.voyageNo());
        e.setPayload(req.payload());
        e.setStatus(EventStatus.RECEIVED);
        IngestEvent saved = repo.save(e);

        r.put("result", "RECEIVED");
        r.put("eventId", saved.getId());
        return r;
    }

    /** 回放本地样例文件（classpath:samples/）。 */
    public List<Map<String, Object>> replaySample(String sampleName) {
        String content = loadSample(sampleName);
        try {
            List<EventIngestRequest> reqs = new ArrayList<>();
            for (JsonNode n : om.readTree(content)) {
                reqs.add(new EventIngestRequest(
                        com.portops.lakehouse.common.SourceSystem.valueOf(n.get("sourceSystem").asText()),
                        n.get("sourceSeq").asLong(),
                        n.get("containerNo").asText(),
                        n.get("opPartition").asText(),
                        com.portops.lakehouse.common.EventType.valueOf(n.get("eventType").asText()),
                        java.time.OffsetDateTime.parse(n.get("bizTime").asText()),
                        n.hasNonNull("voyageNo") ? n.get("voyageNo").asText() : null,
                        om.writeValueAsString(n.get("payload"))));
            }
            return ingestBatch(reqs);
        } catch (IOException e) {
            throw BizException.badRequest("样例文件解析失败: " + sampleName + " - " + e.getMessage());
        }
    }

    public List<String> listSamples() {
        try {
            List<String> names = new ArrayList<>();
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:samples/*.json");
            for (Resource r : resources) {
                String fn = r.getFilename();
                if (fn != null) names.add(fn.replace(".json", ""));
            }
            return names.stream().sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String loadSample(String name) {
        try {
            Resource r = new PathMatchingResourcePatternResolver()
                    .getResource("classpath:samples/" + name + ".json");
            if (!r.exists()) {
                throw BizException.notFound("样例不存在: " + name + "，可用样例: " + listSamples());
            }
            return r.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw BizException.notFound("样例不存在: " + name);
        }
    }

    @Transactional(readOnly = true)
    public List<IngestEvent> listEvents(String containerNo, String status) {
        if (containerNo != null && status != null) {
            return repo.findByContainerNoAndStatus(containerNo, EventStatus.valueOf(status));
        }
        if (containerNo != null) {
            return repo.findByContainerNoOrderByBizTimeAscSourceSystemAscSourceSeqAsc(containerNo);
        }
        return repo.findAll();
    }
}
