-- 港口集装箱作业事件湖仓：事件账 + 不可变提交 + 截关快照 + 对账
-- V1 初始 schema

-- ============ 事件账（接入服务） ============
CREATE TABLE ingest_event (
    id            BIGSERIAL PRIMARY KEY,
    source_system VARCHAR(16)  NOT NULL,          -- GATE/CRANE/YARD/VESSEL
    source_seq    BIGINT       NOT NULL,          -- 来源序号（来源系统内单调）
    container_no  VARCHAR(16)  NOT NULL,
    op_partition  VARCHAR(32)  NOT NULL,          -- 作业分区
    event_type    VARCHAR(20)  NOT NULL,          -- GATE_RELEASE/CRANE_MOVE/YARD_CHECK/LOAD_CONFIRM/CORRECTION
    biz_time      TIMESTAMPTZ  NOT NULL,          -- 业务时点
    voyage_no     VARCHAR(32),
    payload       JSONB        NOT NULL,
    status        VARCHAR(24)  NOT NULL DEFAULT 'RECEIVED',  -- RECEIVED/COMMITTED/PENDING_ARBITRATION/SUPERSEDED
    commit_id     BIGINT,
    ingested_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (source_system, source_seq)            -- 来源事件及其顺序标识：幂等约束
);
CREATE INDEX idx_event_container ON ingest_event (container_no, op_partition, status);
CREATE INDEX idx_event_container_biz ON ingest_event (container_no, biz_time);
CREATE INDEX idx_event_commit ON ingest_event (commit_id);

-- ============ 不可变提交（湖仓写入服务） ============
CREATE TABLE lake_commit (
    id              BIGSERIAL PRIMARY KEY,
    container_no    VARCHAR(16) NOT NULL,
    op_partition    VARCHAR(32) NOT NULL,
    commit_seq      BIGINT      NOT NULL,         -- (箱,分区) 内单调递增
    event_ids       JSONB       NOT NULL,         -- 本提交包含的事件 id
    max_source_seqs JSONB       NOT NULL,         -- 来源序号水位 {GATE: n, ...}
    state           JSONB       NOT NULL,         -- 提交后箱子状态（含逐字段溯源）
    max_biz_time    TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (container_no, op_partition, commit_seq)
);
CREATE INDEX idx_commit_container ON lake_commit (container_no, op_partition, commit_seq);

-- ============ 待裁定冲突 ============
CREATE TABLE commit_conflict (
    id            BIGSERIAL PRIMARY KEY,
    container_no  VARCHAR(16) NOT NULL,
    op_partition  VARCHAR(32) NOT NULL,
    event_ids     JSONB       NOT NULL,
    reason        VARCHAR(40) NOT NULL,           -- BIZ_TIME_TIE / SOURCE_SEQ_REGRESSION
    detail        TEXT,
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/RESOLVED
    resolved_by   VARCHAR(64),
    resolved_at   TIMESTAMPTZ,
    resolution    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_conflict_container ON commit_conflict (container_no, status);

-- ============ 航次订舱 ============
CREATE TABLE voyage_booking (
    voyage_no    VARCHAR(32) NOT NULL,
    container_no VARCHAR(16) NOT NULL,
    PRIMARY KEY (voyage_no, container_no)
);

-- ============ 航次清单（截关快照） ============
CREATE TABLE voyage_manifest (
    id              BIGSERIAL PRIMARY KEY,
    voyage_no       VARCHAR(32) NOT NULL,
    cutoff_time     TIMESTAMPTZ NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'FROZEN',
    commit_frontier JSONB       NOT NULL,         -- {"箱|分区": commitId} 完整提交边界
    frozen_by       VARCHAR(64),
    frozen_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_manifest_voyage ON voyage_manifest (voyage_no, status);

CREATE TABLE manifest_item (
    id               BIGSERIAL PRIMARY KEY,
    manifest_id      BIGINT       NOT NULL REFERENCES voyage_manifest (id),
    container_no     VARCHAR(16)  NOT NULL,
    position         VARCHAR(64),
    op_status        VARCHAR(24),
    voyage_no        VARCHAR(32),
    loaded           BOOLEAN      NOT NULL,
    move_count       INT          NOT NULL,
    gate_released    BOOLEAN      NOT NULL,
    charges          JSONB        NOT NULL,       -- 可收费装卸事实的费用明细
    basis_commit_ids JSONB        NOT NULL,       -- 费用事实基于哪版提交
    UNIQUE (manifest_id, container_no)
);

-- ============ 修订候选（截关后更正） ============
CREATE TABLE revision_candidate (
    id             BIGSERIAL PRIMARY KEY,
    manifest_id    BIGINT      NOT NULL REFERENCES voyage_manifest (id),
    container_no   VARCHAR(16) NOT NULL,
    event_id       BIGINT      NOT NULL,          -- 触发的更正事件
    proposed_state JSONB       NOT NULL,
    diff_summary   TEXT,
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/ACCEPTED/REJECTED
    decided_by     VARCHAR(64),
    decided_at     TIMESTAMPTZ,
    decision_note  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_revision_manifest ON revision_candidate (manifest_id, status);

-- ============ 承运人回执 ============
CREATE TABLE carrier_receipt (
    id           BIGSERIAL PRIMARY KEY,
    carrier      VARCHAR(64) NOT NULL,
    voyage_no    VARCHAR(32) NOT NULL,
    container_no VARCHAR(16) NOT NULL,
    receipt_seq  BIGINT      NOT NULL,
    claimed      JSONB       NOT NULL,            -- 承运人主张的事实与费用
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (carrier, voyage_no, container_no, receipt_seq)
);

-- ============ 对账 ============
CREATE TABLE recon_run (
    id          BIGSERIAL PRIMARY KEY,
    manifest_id BIGINT      NOT NULL REFERENCES voyage_manifest (id),
    created_by  VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    summary     JSONB
);

CREATE TABLE recon_diff (
    id             BIGSERIAL PRIMARY KEY,
    run_id         BIGINT      NOT NULL REFERENCES recon_run (id),
    container_no   VARCHAR(16) NOT NULL,
    diff_type      VARCHAR(32) NOT NULL,
    field          VARCHAR(40),
    snapshot_value TEXT,
    receipt_value  TEXT,
    status         VARCHAR(16) NOT NULL DEFAULT 'OPEN',  -- OPEN/ADJUSTED/IGNORED
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_recon_diff_run ON recon_diff (run_id, status);

-- ============ 费用调整单 ============
CREATE TABLE fee_adjustment (
    id           BIGSERIAL PRIMARY KEY,
    source       VARCHAR(16)   NOT NULL,          -- REVISION / RECON
    source_id    BIGINT        NOT NULL,          -- revision_candidate.id / recon_diff.id
    manifest_id  BIGINT        NOT NULL REFERENCES voyage_manifest (id),
    container_no VARCHAR(16)   NOT NULL,
    fee_type     VARCHAR(32)   NOT NULL,
    delta_amount NUMERIC(12, 2) NOT NULL,         -- 正=补收 负=退减
    reason       TEXT,
    basis        JSONB         NOT NULL,          -- 调整依据（清单行/提交/事件/差异）
    status       VARCHAR(16)   NOT NULL DEFAULT 'APPROVED',
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_adjustment_manifest ON fee_adjustment (manifest_id);

-- ============ 不可变性约束 ============
-- 提交、清单行：禁止 UPDATE/DELETE；事件账、清单头：禁止 DELETE。
-- 应用层不发放相应写权限的接口，数据库触发器兜底。
CREATE OR REPLACE FUNCTION deny_modify() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 表记录不可变：禁止 % 操作（事件湖仓提交/快照不可改写）', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_lake_commit_immutable
    BEFORE UPDATE OR DELETE ON lake_commit
    FOR EACH ROW EXECUTE FUNCTION deny_modify();

CREATE TRIGGER trg_manifest_item_immutable
    BEFORE UPDATE OR DELETE ON manifest_item
    FOR EACH ROW EXECUTE FUNCTION deny_modify();

CREATE TRIGGER trg_ingest_event_no_delete
    BEFORE DELETE ON ingest_event
    FOR EACH ROW EXECUTE FUNCTION deny_modify();

CREATE TRIGGER trg_voyage_manifest_no_delete
    BEFORE DELETE ON voyage_manifest
    FOR EACH ROW EXECUTE FUNCTION deny_modify();
