package com.portops.lakehouse.dwell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portops.lakehouse.common.EventStatus;
import com.portops.lakehouse.common.EventType;
import com.portops.lakehouse.ingest.IngestEvent;
import com.portops.lakehouse.ingest.IngestEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 证据抽取：从已提交作业事件为每只目标箱构造删失/截断记录。
 *
 * <p>构造规则（只使用证据支持的区间，不把盘点区间中点当作精确时刻）：</p>
 * <ul>
 *   <li>进场：闸口进场（GATE_RELEASE/IN）为精确进场；无闸口记录时以
 *       "最后一次不在场证明, 首次在场证明]" 为进场区间，无不在场证明则下界无界；</li>
 *   <li>离场：闸口出场（GATE_RELEASE/OUT）或装船确认（LOAD_CONFIRM）为精确离场；
 *       无离场事件时以 "最后在场证明, 首次不在场证明]" 为离场区间；
 *       期末仍在场仅在最后在场证明时刻右删失，不登记为已离场；</li>
 *   <li>延迟进入：进场证据上界 ≤ 统计期起点 → 期初已在场，以统计期起点为风险观察起点，
 *       报告自该时点起的剩余滞留；真实进场未知时总堆存上界为 +∞，不伪装成已知；</li>
 *   <li>证据冲突（盘点与闸口/装船矛盾、重复进场、在场证明断裂）：取各解释的最宽包络区间，
 *       分层保留冲突标记，不挑选较短时长；</li>
 *   <li>业务时点晚于统计截止的事件不属于本窗口证据（迟到离场事件由新版本吸收）。</li>
 * </ul>
 */
@Service
public class DwellExtractionService {

    private final IngestEventRepository eventRepo;
    private final ObjectMapper om;

    public DwellExtractionService(IngestEventRepository eventRepo, ObjectMapper om) {
        this.eventRepo = eventRepo;
        this.om = om;
    }

    /** 一次抽取的结果：每箱记录 + 被排除箱（披露原因）。 */
    public record ExtractionResult(List<DwellRecord> records,
                                   List<ExcludedContainer> excluded,
                                   long eventWatermark,
                                   int committedEventCount) {
    }

    /** 被排除的目标箱及原因（不静默丢弃）。 */
    public record ExcludedContainer(String containerNo, String reason, List<Long> pendingEventIds) {
    }

    private record TimedEvent(IngestEvent e) {
        OffsetDateTime t() { return e.getBizTime(); }
    }

    /** 为研究窗口 [periodStart, periodEnd] 抽取全部目标箱的删失/截断记录。 */
    @Transactional(readOnly = true)
    public ExtractionResult extract(OffsetDateTime periodStart, OffsetDateTime periodEnd) {
        List<DwellRecord> records = new ArrayList<>();
        List<ExcludedContainer> excluded = new ArrayList<>();
        for (String containerNo : eventRepo.findAllContainerNos()) {
            List<IngestEvent> all = eventRepo
                    .findByContainerNoOrderByBizTimeAscSourceSystemAscSourceSeqAsc(containerNo);
            List<IngestEvent> committed = all.stream()
                    .filter(e -> e.getStatus() == EventStatus.COMMITTED)
                    .filter(e -> !e.getBizTime().isAfter(periodEnd))
                    .toList();
            List<Long> pendingIds = all.stream()
                    .filter(e -> e.getStatus() == EventStatus.RECEIVED
                            || e.getStatus() == EventStatus.PENDING_ARBITRATION)
                    .map(IngestEvent::getId).toList();
            boolean hasPendingArbitration = all.stream()
                    .anyMatch(e -> e.getStatus() == EventStatus.PENDING_ARBITRATION);
            if (committed.isEmpty()) {
                excluded.add(new ExcludedContainer(containerNo,
                        "无统计窗口内的已提交事件（仅有未提交/待裁定事件），无法构造在场证据", pendingIds));
                continue;
            }
            DwellRecord rec = buildRecord(containerNo, committed, pendingIds, hasPendingArbitration,
                    periodStart, periodEnd);
            if (rec == null) {
                excluded.add(new ExcludedContainer(containerNo,
                        "已提交事件均不构成在场证据（如仅盘点不在场记录），或离场证据上界早于统计期起点",
                        pendingIds));
                continue;
            }
            records.add(rec);
        }
        long watermark = eventRepo.maxEventId();
        int committedCount = (int) eventRepo.countByStatus(EventStatus.COMMITTED);
        return new ExtractionResult(records, excluded, watermark, committedCount);
    }

    /** 单箱记录构造；返回 null 表示该箱不属于本窗口目标总体。 */
    private DwellRecord buildRecord(String containerNo, List<IngestEvent> committed,
                                    List<Long> pendingIds, boolean hasPendingArbitration,
                                    OffsetDateTime periodStart, OffsetDateTime periodEnd) {
        List<IngestEvent> gateIns = new ArrayList<>();
        List<IngestEvent> gateOuts = new ArrayList<>();
        List<IngestEvent> loads = new ArrayList<>();
        List<IngestEvent> presences = new ArrayList<>();   // 在场证明：场桥移动 + 盘点在场
        List<IngestEvent> absences = new ArrayList<>();    // 不在场证明：盘点不在场
        String containerType = null;
        String voyageNo = null;
        String block = null;

        for (IngestEvent e : committed) {
            JsonNode p = payload(e);
            if (containerType == null && p.hasNonNull("containerType")) {
                containerType = p.get("containerType").asText();
            }
            if (e.getVoyageNo() != null) {
                voyageNo = e.getVoyageNo();
            }
            switch (e.getEventType()) {
                case GATE_RELEASE -> {
                    if ("OUT".equalsIgnoreCase(text(p, "direction", "IN"))) {
                        gateOuts.add(e);
                    } else {
                        gateIns.add(e);
                    }
                }
                case CRANE_MOVE -> {
                    presences.add(e);
                    block = text(p, "toBlock", block);
                }
                case YARD_CHECK -> {
                    if (p.hasNonNull("present") && !p.get("present").asBoolean()) {
                        absences.add(e);
                    } else {
                        presences.add(e);
                        block = text(p, "block", block);
                    }
                }
                case LOAD_CONFIRM -> loads.add(e);
                case CORRECTION -> { /* 状态更正报文不构成在场/离场时间证据 */ }
            }
        }

        List<String> conflicts = new ArrayList<>();
        List<String> details = new ArrayList<>();

        // ---------- 进场证据 ----------
        String entryKind;
        OffsetDateTime entryExact = null;
        OffsetDateTime entryLower = null;   // null = 下界无界
        OffsetDateTime entryUpper = null;
        if (gateIns.size() >= 2) {
            conflicts.add("DUPLICATE_ENTRY");
            OffsetDateTime first = gateIns.get(0).getBizTime();
            OffsetDateTime last = gateIns.get(gateIns.size() - 1).getBizTime();
            entryKind = "INTERVAL";
            entryLower = first;
            entryUpper = last;
            details.add("同一箱出现 " + gateIns.size() + " 次闸口进场且无中间离场（疑似跨航次转场或身份不稳），"
                    + "进场取最宽包络 [" + first + ", " + last + "]，不挑选较短时长");
        } else if (gateIns.size() == 1) {
            OffsetDateTime gateIn = gateIns.get(0).getBizTime();
            OffsetDateTime firstPresenceBefore = presences.stream().map(IngestEvent::getBizTime)
                    .filter(t -> t.isBefore(gateIn)).min(OffsetDateTime::compareTo).orElse(null);
            if (firstPresenceBefore != null) {
                conflicts.add("ENTRY_AFTER_PRESENCE");
                entryKind = "INTERVAL";
                entryLower = lastAbsenceBefore(absences, firstPresenceBefore);
                entryUpper = gateIn;
                details.add("在场证明 " + firstPresenceBefore + " 早于闸口进场 " + gateIn
                        + "，进场取包络 (-∞或不在场证明, " + gateIn + "]");
            } else {
                entryKind = "EXACT";
                entryExact = gateIn;
            }
        } else {
            // 无闸口进场：进场区间 = (最后不在场证明, 首次在场证明]
            OffsetDateTime firstPresence = presences.stream().map(IngestEvent::getBizTime)
                    .min(OffsetDateTime::compareTo)
                    .orElse(loads.stream().map(IngestEvent::getBizTime)
                            .min(OffsetDateTime::compareTo).orElse(null));
            if (firstPresence == null) {
                return null; // 已提交事件不构成在场证据
            }
            entryKind = "INTERVAL";
            entryLower = lastAbsenceBefore(absences, firstPresence);
            entryUpper = firstPresence;
        }

        // 精确进场：进场区间退化为点（下界=上界=精确时刻），
        // 否则观察起点下界与总堆存上界会错误地按"进场未知"放宽
        if (entryExact != null) {
            entryLower = entryExact;
            entryUpper = entryExact;
        }

        // ---------- 离场证据 ----------
        List<TimedEvent> exitCandidates = new ArrayList<>();
        gateOuts.forEach(e -> exitCandidates.add(new TimedEvent(e)));
        loads.forEach(e -> exitCandidates.add(new TimedEvent(e)));
        exitCandidates.sort(Comparator.comparing(TimedEvent::t));

        String exitKind;
        OffsetDateTime exitExact = null;
        OffsetDateTime exitLower = null;
        OffsetDateTime exitUpper = null;    // null = 上界无界
        OffsetDateTime entryUpperBound = entryExact != null ? entryExact : entryUpper;
        if (!exitCandidates.isEmpty()) {
            OffsetDateTime firstExit = exitCandidates.get(0).t();
            if (firstExit.isBefore(entryUpperBound)) {
                conflicts.add("EXIT_BEFORE_ENTRY");
                exitKind = "CONFLICTED";
                exitLower = firstExit;
                exitUpper = null;
                // 进离场均不可信：进场包络放宽为下界无界，不挑选较短时长
                entryUpper = firstExit;
                entryLower = null;
                entryExact = null;
                entryKind = "INTERVAL";
                entryUpperBound = firstExit;
                details.add("最早离场证据 " + firstExit + " 早于进场证据上界，进离场均取最宽包络");
            } else {
                OffsetDateTime firstExitFinal = firstExit;
                OffsetDateTime laterPresence = presences.stream().map(IngestEvent::getBizTime)
                        .filter(t -> t.isAfter(firstExitFinal)).max(OffsetDateTime::compareTo).orElse(null);
                if (laterPresence != null) {
                    conflicts.add("PRESENCE_AFTER_EXIT");
                    exitKind = "CONFLICTED";
                    exitLower = firstExit;
                    exitUpper = null;
                    details.add("离场证据 " + firstExit + " 与之后在场证明 " + laterPresence
                            + " 矛盾（盘点与闸口/装船冲突），离场取包络 [" + firstExit + ", +∞)，不挑选较短时长");
                } else {
                    exitKind = "EXACT";
                    exitExact = firstExit;
                }
            }
        } else {
            // 无离场事件：最后在场证明（闸口进场时刻亦视为在场）
            List<OffsetDateTime> presenceTimes = new ArrayList<>();
            presences.forEach(e -> presenceTimes.add(e.getBizTime()));
            gateIns.forEach(e -> presenceTimes.add(e.getBizTime()));
            OffsetDateTime lastPresence = presenceTimes.stream().max(OffsetDateTime::compareTo).orElse(null);
            if (lastPresence == null) {
                return null;
            }
            // 在场证明断裂：不在场证明前后均有在场证明 → 取连续在场包络
            OffsetDateTime finalLastPresence = lastPresence;
            boolean gap = absences.stream().map(IngestEvent::getBizTime).anyMatch(t ->
                    t.isAfter(presenceTimes.stream().min(OffsetDateTime::compareTo).orElse(t))
                            && t.isBefore(finalLastPresence));
            if (gap) {
                conflicts.add("PRESENCE_GAP");
                exitKind = "CONFLICTED";
                exitLower = lastPresence;
                exitUpper = null;
                details.add("在场证明存在断裂（不在场证明前后均有在场证明），按连续在场最宽包络右删失处理");
            } else {
                OffsetDateTime firstAbsenceAfter = absences.stream().map(IngestEvent::getBizTime)
                        .filter(t -> t.isAfter(finalLastPresence))
                        .min(OffsetDateTime::compareTo).orElse(null);
                if (firstAbsenceAfter != null) {
                    exitKind = "INTERVAL";
                    exitLower = lastPresence;
                    exitUpper = firstAbsenceAfter;
                } else {
                    exitKind = "RIGHT_CENSORED";
                    exitLower = lastPresence;
                    exitUpper = null;
                }
            }
        }

        // ---------- 组合观察区间与滞留可行区间 ----------
        boolean leftTruncated = !entryUpperBound.isAfter(periodStart);
        OffsetDateTime obsStartLower = entryLower != null && entryLower.isAfter(periodStart)
                ? entryLower : periodStart;
        OffsetDateTime obsStartUpper = entryUpperBound.isAfter(periodStart)
                ? entryUpperBound : periodStart;
        OffsetDateTime obsEndLower = exitExact != null ? exitExact : exitLower;
        OffsetDateTime obsEndUpper = exitExact != null ? exitExact : exitUpper; // null = +∞

        if (obsEndUpper != null && obsEndUpper.isBefore(periodStart)) {
            return null; // 确定在统计期前已离场，不属于本窗口目标总体
        }

        double remainLower = Math.max(0.0, hours(obsStartUpper, obsEndLower));
        Double remainUpper = obsEndUpper == null ? null : Math.max(0.0, hours(obsStartLower, obsEndUpper));
        double totalLower = Math.max(0.0, hours(entryUpperBound, obsEndLower));
        Double totalUpper = (obsEndUpper == null || entryLower == null)
                ? null : Math.max(0.0, hours(entryLower, obsEndUpper));

        boolean rightCensored = "RIGHT_CENSORED".equals(exitKind);
        boolean intervalCensored = "INTERVAL".equals(entryKind)
                || "INTERVAL".equals(exitKind) || "CONFLICTED".equals(exitKind);
        if (hasPendingArbitration) {
            conflicts.add("EVENTS_PENDING_ARBITRATION");
            details.add("存在待裁定事件（id 见 pendingEventIds），本记录仅基于已提交证据，裁定后需重算版本");
        }

        DwellRecord rec = new DwellRecord();
        rec.setContainerNo(containerNo);
        rec.setVoyageNo(voyageNo);
        rec.setContainerType(containerType);
        rec.setBlock(block);
        rec.setEntryKind(entryKind);
        rec.setEntryExact(entryExact);
        rec.setEntryLower(entryLower);
        rec.setEntryUpper(entryUpperBound);
        rec.setExitKind(exitKind);
        rec.setExitExact(exitExact);
        rec.setExitLower(exitLower);
        rec.setExitUpper(exitUpper);
        rec.setLeftTruncated(leftTruncated);
        rec.setRightCensored(rightCensored);
        rec.setIntervalCensored(intervalCensored);
        rec.setObsStartLower(obsStartLower);
        rec.setObsStartUpper(obsStartUpper);
        rec.setRemainLowerHours(remainLower);
        rec.setRemainUpperHours(remainUpper);
        rec.setTotalLowerHours(totalLower);
        rec.setTotalUpperHours(totalUpper);
        rec.setConflictStatus(conflicts.isEmpty() ? "NONE" : String.join("+", conflicts));
        rec.setConflictDetail(details.isEmpty() ? null : String.join("；", details));
        rec.setEvidenceEventIds(writeJson(committed.stream().map(IngestEvent::getId).toList()));
        rec.setPendingEventIds(writeJson(pendingIds));
        return rec;
    }

    private OffsetDateTime lastAbsenceBefore(List<IngestEvent> absences, OffsetDateTime t) {
        return absences.stream().map(IngestEvent::getBizTime)
                .filter(x -> x.isBefore(t)).max(OffsetDateTime::compareTo).orElse(null);
    }

    private static double hours(OffsetDateTime from, OffsetDateTime to) {
        return Duration.between(from, to).toMillis() / 3_600_000.0;
    }

    private JsonNode payload(IngestEvent e) {
        try {
            return om.readTree(e.getPayload());
        } catch (Exception ex) {
            throw new IllegalStateException("事件 payload 无法解析: " + e.getId(), ex);
        }
    }

    private static String text(JsonNode n, String field, String dft) {
        return n != null && n.hasNonNull(field) ? n.get(field).asText() : dft;
    }

    private String writeJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
