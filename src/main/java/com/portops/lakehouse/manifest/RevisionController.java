package com.portops.lakehouse.manifest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 修订候选接口：截关后更正报文形成的候选，由对账人员裁定。
 */
@RestController
@RequestMapping("/api/revisions")
@Tag(name = "04-修订候选", description = "截关后更正形成修订候选；裁定接受生成费用调整单，不改写原始结算")
public class RevisionController {

    private final RevisionService revisionService;
    private final RevisionCandidateRepository revisionRepo;

    public RevisionController(RevisionService revisionService, RevisionCandidateRepository revisionRepo) {
        this.revisionService = revisionService;
        this.revisionRepo = revisionRepo;
    }

    @GetMapping
    @Operation(summary = "查询修订候选", description = "可按清单与状态过滤；status=PENDING 为待裁定")
    public List<RevisionCandidate> list(@RequestParam(required = false) Long manifestId,
                                        @RequestParam(required = false) String status) {
        if (manifestId != null) {
            return revisionRepo.findByManifestIdOrderByCreatedAtAsc(manifestId);
        }
        return revisionRepo.findByStatusOrderByCreatedAtAsc(status == null ? "PENDING" : status);
    }

    @PostMapping("/{candidateId}/decide")
    @Operation(summary = "裁定修订候选", description = "accept=true 生成费用调整单（依据含清单行与提交 id）；accept=false 关闭候选")
    public Map<String, Object> decide(@PathVariable Long candidateId,
                                      @RequestBody DecideRequest req,
                                      Authentication auth) {
        return revisionService.decide(candidateId, req.accept(), req.note(),
                auth == null ? "anonymous" : auth.getName());
    }

    public record DecideRequest(boolean accept, String note) {
    }
}
