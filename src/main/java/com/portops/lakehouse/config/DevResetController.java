package com.portops.lakehouse.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 仅限本地开发/验收：清空全部业务表，使验收回放脚本可重复执行。
 * 由 app.dev.reset-enabled=true 显式开启（默认关闭）；生产部署不得开启。
 */
@RestController
@RequestMapping("/api/dev")
@ConditionalOnProperty(name = "app.dev.reset-enabled", havingValue = "true")
@Tag(name = "90-开发工具", description = "仅本地开发：app.dev.reset-enabled=true 时生效")
public class DevResetController {

    private final JdbcTemplate jdbc;

    public DevResetController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/reset")
    @Operation(summary = "清空全部业务表（仅本地验收用）")
    public Map<String, Object> reset() {
        jdbc.execute("""
                TRUNCATE TABLE ingest_event, lake_commit, commit_conflict, voyage_booking,
                    voyage_manifest, manifest_item, revision_candidate, carrier_receipt,
                    recon_run, recon_diff, fee_adjustment,
                    dwell_study, dwell_obs_run, dwell_record, dwell_estimate, dwell_report
                RESTART IDENTITY CASCADE
                """);
        return Map.of("result", "RESET_OK");
    }
}
