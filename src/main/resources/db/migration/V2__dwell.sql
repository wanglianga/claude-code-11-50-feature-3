-- 堆场滞留分布与超期风险服务：在场风险区间 + 统计估计 + 管理报告（分别版本化）
-- V2 在 V1 事件湖仓之上新增 dwell 域；全部表不可变，迟到离场事件只产生新版本。

-- ============ 滞留研究（统计窗口与阈值配置；配置本身不可变，改窗口=新研究） ============
CREATE TABLE dwell_study (
    id               BIGSERIAL PRIMARY KEY,
    study_code       VARCHAR(40)  NOT NULL UNIQUE,   -- 业务编码，如 DWELL-2026-11
    period_start     TIMESTAMPTZ  NOT NULL,          -- 统计期起点（期初在场箱的风险观察起点）
    period_end       TIMESTAMPTZ  NOT NULL,          -- 统计截止（期末仍在场箱在此右删失）
    thresholds_hours JSONB        NOT NULL,          -- 超期时限（小时），如 [72, 168, 336]
    quantiles        JSONB        NOT NULL,          -- 分位点，如 [0.5, 0.9]
    created_by       VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (period_end > period_start)
);

-- ============ 在场风险区间版本（每次重算生成新版本；记录作业事件水位） ============
CREATE TABLE dwell_obs_run (
    id                   BIGSERIAL PRIMARY KEY,
    study_id             BIGINT      NOT NULL REFERENCES dwell_study (id),
    version              INT         NOT NULL,        -- 1,2,3...（study 内单调）
    event_watermark      BIGINT      NOT NULL,        -- 本次抽取覆盖到的 ingest_event 最大 id（作业事件版本标识）
    committed_event_count INT        NOT NULL,
    container_count      INT         NOT NULL,
    excluded_containers  JSONB       NOT NULL,        -- 被排除箱及原因（如仅有未提交事件），不静默丢弃
    status               VARCHAR(16) NOT NULL DEFAULT 'COMPUTED',
    created_by           VARCHAR(64),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (study_id, version)
);

-- ============ 每箱删失/截断记录（属于某个 obs run，不可变） ============
CREATE TABLE dwell_record (
    id                   BIGSERIAL PRIMARY KEY,
    run_id               BIGINT      NOT NULL REFERENCES dwell_obs_run (id),
    container_no         VARCHAR(16) NOT NULL,
    voyage_no            VARCHAR(32),
    container_type       VARCHAR(16),
    block                VARCHAR(16),
    entry_kind           VARCHAR(24) NOT NULL,        -- EXACT / INTERVAL（含下界无界与冲突包络）
    entry_exact          TIMESTAMPTZ,
    entry_lower          TIMESTAMPTZ,                 -- NULL = 下界无界（真实进场早于全部证据）
    entry_upper          TIMESTAMPTZ,                 -- 进场不晚于该时刻
    exit_kind            VARCHAR(24) NOT NULL,        -- EXACT / INTERVAL / RIGHT_CENSORED / CONFLICTED
    exit_exact           TIMESTAMPTZ,
    exit_lower           TIMESTAMPTZ,
    exit_upper           TIMESTAMPTZ,                 -- NULL = 上界无界（仍在场或冲突包络）
    left_truncated       BOOLEAN     NOT NULL,        -- 期初已在场（延迟进入）：以统计起点为风险观察起点
    right_censored       BOOLEAN     NOT NULL,        -- 期末/最后在场证明时仍在场，仅右删失，不登记为已离场
    interval_censored    BOOLEAN     NOT NULL,        -- 进场或离场仅有区间证据
    obs_start_lower      TIMESTAMPTZ NOT NULL,        -- 风险观察起点区间 [max(期初, 进场下界), max(期初, 进场上界)]
    obs_start_upper      TIMESTAMPTZ NOT NULL,
    remain_lower_hours   DOUBLE PRECISION NOT NULL,   -- 自观察起点剩余滞留可行下界
    remain_upper_hours   DOUBLE PRECISION,            -- NULL = 上界 +∞
    total_lower_hours    DOUBLE PRECISION NOT NULL,   -- 总堆存可行下界（仅用可核实进场区间）
    total_upper_hours   DOUBLE PRECISION,             -- NULL = 上界 +∞（真实进场未知时不伪装已知）
    conflict_status      VARCHAR(32) NOT NULL DEFAULT 'NONE',
    conflict_detail      TEXT,
    evidence_event_ids   JSONB       NOT NULL,        -- 支撑该记录的已提交事件 id（可溯源）
    pending_event_ids    JSONB       NOT NULL,        -- 未提交/待裁定事件 id（披露，不纳入证据）
    UNIQUE (run_id, container_no)
);
CREATE INDEX idx_dwell_record_run ON dwell_record (run_id, container_no);
CREATE INDEX idx_dwell_record_container ON dwell_record (container_no);

-- ============ 统计估计（路线一可行界 / 路线二生存模型；按分组；不可变） ============
CREATE TABLE dwell_estimate (
    id             BIGSERIAL PRIMARY KEY,
    run_id         BIGINT      NOT NULL REFERENCES dwell_obs_run (id),
    route          VARCHAR(16) NOT NULL,            -- BOUNDS / MODEL
    time_scale     VARCHAR(16) NOT NULL,            -- REMAINING（自观察起点剩余滞留）/ TOTAL（总堆存）
    group_dim      VARCHAR(16) NOT NULL,            -- ALL / BLOCK / VOYAGE / TYPE
    group_key      VARCHAR(64) NOT NULL,
    sample_size    INT         NOT NULL,            -- 纳入该组估计的全部目标箱数（含删失与冲突箱）
    conflict_count INT         NOT NULL,
    metric         VARCHAR(24) NOT NULL,            -- OVERDUE_PROPORTION / QUANTILE / GROUP_SUMMARY
    metric_arg     VARCHAR(16) NOT NULL,            -- 时限小时数或分位点；GROUP_SUMMARY 为 '-'
    point_estimate DOUBLE PRECISION,                -- 路线二点估计；路线一为 NULL（只发布界限）
    lower_bound    DOUBLE PRECISION,                -- 路线一可行下界
    upper_bound    DOUBLE PRECISION,                -- NULL = 上界不可识别（+∞）
    detail         JSONB       NOT NULL,            -- 覆盖率、模型参数、假设与敏感性披露
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dwell_estimate_run ON dwell_estimate (run_id, route, group_dim);

-- ============ 管理报告（版本化，引用某次 obs run 的估计快照） ============
CREATE TABLE dwell_report (
    id         BIGSERIAL PRIMARY KEY,
    study_id   BIGINT      NOT NULL REFERENCES dwell_study (id),
    version    INT         NOT NULL,                -- 1,2,3...（study 内单调）
    run_id     BIGINT      NOT NULL REFERENCES dwell_obs_run (id),
    content    JSONB       NOT NULL,                -- 报告快照：分组汇总、两路线对比、冲突清单、假设披露
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (study_id, version)
);

-- ============ 不可变性约束（与 V1 同一兜底函数） ============
CREATE TRIGGER trg_dwell_study_immutable
    BEFORE UPDATE OR DELETE ON dwell_study
    FOR EACH ROW EXECUTE FUNCTION deny_modify();

CREATE TRIGGER trg_dwell_obs_run_immutable
    BEFORE UPDATE OR DELETE ON dwell_obs_run
    FOR EACH ROW EXECUTE FUNCTION deny_modify();

CREATE TRIGGER trg_dwell_record_immutable
    BEFORE UPDATE OR DELETE ON dwell_record
    FOR EACH ROW EXECUTE FUNCTION deny_modify();

CREATE TRIGGER trg_dwell_estimate_immutable
    BEFORE UPDATE OR DELETE ON dwell_estimate
    FOR EACH ROW EXECUTE FUNCTION deny_modify();

CREATE TRIGGER trg_dwell_report_immutable
    BEFORE UPDATE OR DELETE ON dwell_report
    FOR EACH ROW EXECUTE FUNCTION deny_modify();
