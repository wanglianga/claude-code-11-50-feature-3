package com.portops.lakehouse.dispatch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 调度查询接口：读取当前提交合并视图（非冻结版本，仅供调度）。
 */
@RestController
@RequestMapping("/api/dispatch")
@Tag(name = "06-调度视图", description = "从完整提交合并当前箱区状态；读时合并，可承受合并延迟")
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @GetMapping("/containers/{containerNo}")
    @Operation(summary = "单箱当前视图",
            description = "合并全部已提交事件；currentState.lineage 逐字段标明来自哪条闸口/场桥/装船记录及提交")
    public Map<String, Object> container(@PathVariable String containerNo) {
        return dispatchService.containerView(containerNo);
    }

    @GetMapping("/containers/{containerNo}/lineage")
    @Operation(summary = "单箱事件溯源", description = "全部事件及处理状态、所属提交；含被取代与待裁定事件")
    public List<Map<String, Object>> lineage(@PathVariable String containerNo) {
        return dispatchService.lineage(containerNo);
    }

    @GetMapping("/partitions/{opPartition}/containers")
    @Operation(summary = "分区当前堆位视图")
    public List<Map<String, Object>> partition(@PathVariable String opPartition) {
        return dispatchService.partitionView(opPartition);
    }
}
