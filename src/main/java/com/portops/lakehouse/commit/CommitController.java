package com.portops.lakehouse.commit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 湖仓写入服务接口：提交生成、提交查询、冲突裁定。
 */
@RestController
@RequestMapping("/api/commits")
@Tag(name = "02-湖仓提交", description = "按箱号+作业分区生成不可变提交；冲突进入待裁定")
public class CommitController {

    private final CommitService commitService;
    private final LakeCommitRepository commitRepo;
    private final CommitConflictRepository conflictRepo;

    public CommitController(CommitService commitService, LakeCommitRepository commitRepo,
                            CommitConflictRepository conflictRepo) {
        this.commitService = commitService;
        this.commitRepo = commitRepo;
        this.conflictRepo = conflictRepo;
    }

    @PostMapping("/generate")
    @Operation(summary = "为指定箱号生成提交", description = "跨分区冲突检测，按分区落不可变提交")
    public Map<String, Object> generate(@RequestParam String containerNo) {
        return commitService.generate(containerNo);
    }

    @PostMapping("/generate-all")
    @Operation(summary = "为所有待提交箱号生成提交")
    public List<Map<String, Object>> generateAll() {
        // 逐箱调用事务性的 generate，避免自调用绕过事务代理
        List<Map<String, Object>> results = new ArrayList<>();
        for (String containerNo : commitService.listPendingContainers()) {
            results.add(commitService.generate(containerNo));
        }
        return results;
    }

    @GetMapping
    @Operation(summary = "查询提交", description = "按箱号查询全部不可变提交（按分区+提交序号排序）")
    public List<LakeCommit> commits(@RequestParam String containerNo) {
        return commitRepo.findByContainerNoOrderByOpPartitionAscCommitSeqAsc(containerNo);
    }

    @GetMapping("/conflicts")
    @Operation(summary = "查询冲突", description = "status=PENDING 查看待裁定冲突")
    public List<CommitConflict> conflicts(@RequestParam(defaultValue = "PENDING") String status) {
        return conflictRepo.findByStatusOrderByCreatedAtAsc(status);
    }

    @PostMapping("/conflicts/{conflictId}/resolve")
    @Operation(summary = "裁定冲突", description = "指定胜选事件，其余事件标记为被取代；胜选事件重新进入提交流程")
    public Map<String, Object> resolve(@PathVariable Long conflictId,
                                       @RequestBody ResolveRequest req,
                                       Authentication auth) {
        return commitService.resolve(conflictId, req.winnerEventId(), req.note(),
                auth == null ? "anonymous" : auth.getName());
    }

    public record ResolveRequest(Long winnerEventId, String note) {
    }
}
