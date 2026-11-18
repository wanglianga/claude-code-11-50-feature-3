package com.portops.lakehouse.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portops.lakehouse.common.BizException;
import com.portops.lakehouse.common.ContainerState;
import com.portops.lakehouse.common.ContainerStateJson;
import com.portops.lakehouse.common.EventStatus;
import com.portops.lakehouse.commit.LakeCommit;
import com.portops.lakehouse.commit.LakeCommitRepository;
import com.portops.lakehouse.ingest.IngestEvent;
import com.portops.lakehouse.ingest.IngestEventRepository;
import com.portops.lakehouse.settlement.FeeSchedule;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 航次清单服务：从已提交数据冻结截关快照。
 *
 * <p>完整性约束：</p>
 * <ul>
 *   <li>清单只引用完整提交清单（commit frontier）：每个 (箱, 分区) 取截关时点前的最新提交；</li>
 *   <li>任一订舱箱存在截关前未提交事件或待裁定事件时，整个冻结失败，
 *       不会用"半个分区"发布航次；</li>
 *   <li>清单与清单行在同一事务写入，要么完整发布，要么什么都不发布；</li>
 *   <li>冻结后清单不可修改、不可删除（数据库触发器保证）。</li>
 * </ul>
 */
@Service
public class ManifestService {

    private static final Logger log = LoggerFactory.getLogger(ManifestService.class);

    private final VoyageManifestRepository manifestRepo;
    private final ManifestItemRepository itemRepo;
    private final VoyageBookingRepository bookingRepo;
    private final LakeCommitRepository commitRepo;
    private final IngestEventRepository eventRepo;
    private final EntityManager em;
    private final ObjectMapper om;

    public ManifestService(VoyageManifestRepository manifestRepo, ManifestItemRepository itemRepo,
                           VoyageBookingRepository bookingRepo, LakeCommitRepository commitRepo,
                           IngestEventRepository eventRepo, EntityManager em, ObjectMapper om) {
        this.manifestRepo = manifestRepo;
        this.itemRepo = itemRepo;
        this.bookingRepo = bookingRepo;
        this.commitRepo = commitRepo;
        this.eventRepo = eventRepo;
        this.em = em;
        this.om = om;
    }

    @Transactional
    public Map<String, Object> registerBookings(String voyageNo, List<String> containerNos) {
        int added = 0;
        for (String c : containerNos) {
            if (bookingRepo.findById(new VoyageBookingId(voyageNo, c)).isEmpty()) {
                bookingRepo.save(new VoyageBooking(voyageNo, c));
                added++;
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("voyageNo", voyageNo);
        r.put("registered", containerNos.size());
        r.put("newlyAdded", added);
        return r;
    }

    /**
     * 冻结截关快照。整个方法在一个事务内：完整性校验失败或写入失败都会整体回滚，
     * 保证清单服务只发布引用完整提交边界的航次清单。
     */
    @Transactional
    public Map<String, Object> freeze(String voyageNo, OffsetDateTime cutoffTime, String operator) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(?1))")
                .setParameter(1, "manifest|" + voyageNo)
                .getSingleResult();

        if (manifestRepo.findByVoyageNoAndStatus(voyageNo, "FROZEN").isPresent()) {
            throw BizException.conflict("航次 " + voyageNo + " 已存在冻结清单，不可重复冻结");
        }
        List<VoyageBooking> bookings = bookingRepo.findByVoyageNo(voyageNo);
        if (bookings.isEmpty()) {
            throw BizException.badRequest("航次 " + voyageNo + " 无订舱记录，无法冻结清单");
        }

        Map<String, Long> frontier = new LinkedHashMap<>();
        List<ManifestItem> items = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        for (VoyageBooking b : bookings) {
            String c = b.getContainerNo();

            // 完整性检查 1：截关前事件必须全部完成提交（分区提交未完成 → 阻塞）
            long uncommitted = eventRepo.countByContainerNoAndStatusAndBizTimeLessThanEqual(
                    c, EventStatus.RECEIVED, cutoffTime);
            if (uncommitted > 0) {
                blockers.add(c + ": " + uncommitted + " 条截关前事件尚未提交（分区提交未完成）");
            }
            // 完整性检查 2：截关前事件不能有待裁定
            long pendingArb = eventRepo.countByContainerNoAndStatusAndBizTimeLessThanEqual(
                    c, EventStatus.PENDING_ARBITRATION, cutoffTime);
            if (pendingArb > 0) {
                blockers.add(c + ": " + pendingArb + " 条截关前事件待裁定，须先裁定");
            }

            // 提交边界：每个分区取截关时点前的最新提交
            List<LakeCommit> commits = commitRepo.findByContainerNoOrderByOpPartitionAscCommitSeqAsc(c);
            if (commits.isEmpty()) {
                blockers.add(c + ": 无任何已提交数据");
                continue;
            }
            Map<String, LakeCommit> latestByPartition = new LinkedHashMap<>();
            for (LakeCommit cm : commits) {
                if (!cm.getMaxBizTime().isAfter(cutoffTime)) {
                    latestByPartition.merge(cm.getOpPartition(), cm,
                            (x, y) -> x.getCommitSeq() >= y.getCommitSeq() ? x : y);
                }
            }
            if (latestByPartition.isEmpty()) {
                blockers.add(c + ": 没有满足截关时点的提交");
                continue;
            }
            List<Long> frontierIds = new ArrayList<>();
            latestByPartition.forEach((p, cm) -> {
                frontier.put(c + "|" + p, cm.getId());
                frontierIds.add(cm.getId());
            });

            // 快照状态：仅由边界内提交包含的事件折叠（清单只引用完整提交清单）
            List<IngestEvent> frontierEvents = frontierIds.isEmpty() ? List.of()
                    : eventRepo.findByCommitIdInOrderByBizTimeAscSourceSystemAscSourceSeqAsc(frontierIds);
            ContainerState s = new ContainerState();
            for (IngestEvent e : frontierEvents) {
                s.apply(toView(e));
            }

            ManifestItem item = new ManifestItem();
            item.setContainerNo(c);
            item.setPosition(s.position);
            item.setOpStatus(s.opStatus);
            item.setVoyageNo(s.voyageNo != null ? s.voyageNo : voyageNo);
            item.setLoaded(s.loaded);
            item.setMoveCount(s.moveCount);
            item.setGateReleased(s.gateReleased);
            item.setCharges(writeJson(FeeSchedule.computeCharges(s)));
            item.setBasisCommitIds(writeJson(frontierIds.stream().sorted().toList()));
            items.add(item);
        }

        if (!blockers.isEmpty()) {
            // 不发布半个分区：任何阻塞都导致整个冻结失败，且本事务不写任何数据
            throw BizException.conflict("清单冻结失败，提交边界不完整: " + String.join("; ", blockers));
        }

        VoyageManifest m = new VoyageManifest();
        m.setVoyageNo(voyageNo);
        m.setCutoffTime(cutoffTime);
        m.setStatus("FROZEN");
        m.setCommitFrontier(writeJson(frontier));
        m.setFrozenBy(operator);
        VoyageManifest saved = manifestRepo.save(m);

        for (ManifestItem item : items) {
            item.setManifestId(saved.getId());
        }
        itemRepo.saveAll(items);

        log.info("manifest frozen: voyage={} id={} items={} frontier={}", voyageNo, saved.getId(), items.size(), frontier);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("manifestId", saved.getId());
        r.put("voyageNo", voyageNo);
        r.put("cutoffTime", cutoffTime.toString());
        r.put("status", saved.getStatus());
        r.put("itemCount", items.size());
        r.put("commitFrontier", frontier);
        return r;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> manifestDetail(Long manifestId) {
        VoyageManifest m = manifestRepo.findById(manifestId)
                .orElseThrow(() -> BizException.notFound("清单不存在: " + manifestId));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("manifest", m);
        r.put("items", itemRepo.findByManifestIdOrderByContainerNoAsc(manifestId));
        return r;
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

    private String writeJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
