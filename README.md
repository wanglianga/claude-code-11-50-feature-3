# 港口集装箱作业事件湖仓与航次对账服务

闸口放行、场桥移动、堆场盘点、装船确认由不同系统上报，同一箱子可能被多条链路并发改写。
本服务以 **事件账 + 不可变提交清单** 架构替代"各系统直接更新一张作业表"：

- **接入服务** 保存来源事件及其顺序标识（`sourceSystem + sourceSeq` 幂等）；
- **湖仓写入服务** 按箱号+作业分区生成**不可变提交**，按来源序号和业务时点检测冲突，
  无法排序的状态进入**待裁定**，不让数据库最后写入者决定箱子是否已装船；
- **航次清单服务** 从已提交数据**冻结截关快照**，只引用完整提交清单，
  分区提交不完整时整个冻结失败，不用半个分区发布航次；
- **对账服务** 将冻结快照与承运人回执**逐项比较**，差异由对账人员决定生成**费用调整单**；
- **截关后更正** 形成**修订候选**，裁定接受后生成费用调整单，**不改写原始结算**；
- **调度查询** 读取当前提交合并视图（承担合并延迟）；**结算只能引用已冻结航次版本**；
- **滞留分析服务** 在事件湖仓之上构造**延迟进入 / 右删失 / 区间删失**记录，
  用**两条路线**（非参数可行界 / Weibull 生存模型）报告滞留分布与超期风险，
  在场风险区间、统计估计与管理报告**分别版本化**，迟到离场事件只产生新版本。

## 架构取舍（按题目要求）

| 方案 | 优势 | 代价 | 选择 |
| --- | --- | --- | --- |
| 事件账 + 提交清单（本平台） | 每次更正可溯源到具体闸口/场桥/装船记录；补传更正不改写已锁定航次收费事实 | 调度查询需合并更多事件，存储和读取成本较高 | ✅ 采用 |
| 箱位版本表 + 同事务更正记录 | 当前查询更快 | 多来源并发时需维护版本边界与提交一致性 | 不采用 |

### 滞留分析两条路线（均纳入全部目标箱及其删失状态）

| 路线 | 输出 | 假设 | 代价 |
| --- | --- | --- | --- |
| 路线一：非参数可行界（BOUNDS） | 超期比例与分位数的**可行上下界**；分位点上界可能不可识别（+∞） | 无需删失分布假设，不猜测区间内时点 | 记录不确定时范围较宽 |
| 路线二：Weibull 生存模型（MODEL） | 更集中的滞留分布、超期概率与分位数点估计 | 非信息性删失/进入 + Weibull 分布假设（附指数分布参照敏感性） | 模型失配会扭曲尾部风险 → 以 `boundsContainment` 诊断并披露 |

## 技术栈

Java 25 · Spring Boot 3.5 · PostgreSQL 18（JSONB、咨询锁、触发器）· Flyway · springdoc-openapi（Swagger UI）· JUnit 5

## 快速启动

### 方式一：Docker Compose（一键）

```bash
docker compose up --build
```

- 应用：http://localhost:8080 （首次构建需下载依赖，约几分钟）
- Swagger UI：http://localhost:8080/swagger-ui.html
- PostgreSQL：`localhost:5432`，库/账号/密码 `portdb / portops / portops`

### 方式二：本地开发

前置：JDK 25（`JAVA_HOME` 指向）、Maven 3.9+、PostgreSQL（默认 `127.0.0.1:5432`，库/账号/密码 `ccdev`）。

```bash
# 1. 准备数据库（按实际环境调整）
psql -h 127.0.0.1 -U ccdev -d ccdev -c "SELECT 1"   # 确认可连接；Flyway 自动建表

# 2. 启动（环境变量可覆盖数据源：DB_URL / DB_USER / DB_PASSWORD / SERVER_PORT）
mvn spring-boot:run
# 或：mvn package -DskipTests && java -jar target/port-lakehouse-1.0.0.jar
```

## 测试账号（HTTP Basic，仅本地开发用）

| 账号 | 密码 | 角色 | 权限 |
| --- | --- | --- | --- |
| `ingest` | `ingest123` | INGEST | 事件上报、样例回放、提交生成 |
| `dispatcher` | `disp123` | DISPATCH | 调度视图、冲突裁定、滞留分析（研究/区间版本/估计/报告） |
| `settlement` | `settle123` | SETTLEMENT | 订舱登记、清单冻结、修订裁定、对账、结算 |
| `admin` | `admin123` | 全部 | 含 `/api/dev/reset`（仅本地） |

## 验证方式

### 1. 自动化测试（29 个用例）

```bash
# 测试使用独立数据库 ccdev_test（首次需创建：createdb -h 127.0.0.1 -U ccdev ccdev_test）
mvn test
```

覆盖：并列业务时点冲突进待裁定、裁定后胜选生效、乱序入库不覆盖装船确认、来源序号回退冲突、
幂等去重、未提交/待裁定时冻结失败且零残留、完整提交边界、提交与清单行不可变（触发器）、
截关后更正→修订候选→费用调整单且原始清单不改写、回执差异对账、角色越权拒绝；
**滞留分析**：五类删失样例的记录构造（期初在场/期末在场/区间盘点/进出均不确知/出场证据冲突）、
路线一只发布证据支持的界限（含分位点上界不可识别）、路线二纳入删失箱并披露假设与敏感性、
迟到离场事件只产生新版本、dwell 各表不可变、待裁定与无证据箱披露不丢弃；
**生存模型数值**：Weibull MLE 在精确/区间删失/右删失/左截断合成数据上恢复真实参数。

### 2. 验收回放脚本（96 项断言）

服务启动后执行（可重复执行，脚本开头自动重置业务表）：

```bash
./scripts/replay-acceptance.sh        # 或 BASE_URL=http://localhost:8080 ./scripts/replay-acceptance.sh
```

回放四个作业事件场景与滞留分析场景并逐项断言：

1. **并发位置更新**：装船确认先入库、场桥报文后入库（业务时点更早）→ 当前视图仍为已装船；
   场桥与装船同一业务时点冲突 → 待裁定，裁定前 `loaded` 不定，裁定后胜选生效、被取代报文可溯源。
2. **分区提交中断**：存在未提交事件时冻结 → 409 且清单表零残留；存在待裁定时冻结 → 409 零残留；
   裁定并补齐提交后冻结成功，`commitFrontier` 覆盖 6 箱 × 3 分区共 18 个提交。
3. **截关后更正**：更正报文（业务时点在截关前）提交后生成修订候选；冻结清单行不被改写；
   接受候选生成 +80.00 移箱费调整单；调度当前视图已反映更正。
4. **承运人回执差异**：6 箱中 4 箱匹配；CNU0004 装船事实与费用差异、CNU0006 回执缺失；
   差异分别生成调整单（+450.00）或忽略；结算视图原始结算（3260）与调整单（530）分列，总额 3790；
   绕过冻结清单直接结算被 400 拒绝。
5. **滞留分析（15 箱目标总体 = 6 基础箱 + 9 样例箱）**：
   - 五类删失记录：CNU1001 期初在场且长期未离场（延迟进入 + 期末右删失，剩余滞留 ≥719h，
     总堆存上界 +∞ 不伪装已知）；CNU1002 期末前在场（右删失 122h，不登记为已离场）；
     CNU1003 仅区间盘点（滞留 [0,360]h，不取中点）；CNU1004 进出均不确知（进场下界无界）；
     CNU1005 出场证据冲突（盘点矛盾闸口，取最宽包络 [192h,+∞)，不挑选较短时长）；
   - 路线一：72/168/336h 超期比例可行界（如 168h ∈ [6/15, 8/15]）、中位数可识别范围 [122h,192h]、
     q0.9 上界不可识别、分组覆盖率披露；路线一无点估计；
   - 路线二：Weibull 模型纳入全部 15 箱（右删失 4、区间删失 2），披露非信息性删失与分布假设、
     指数分布参照敏感性、模型-界限一致性诊断；总堆存模型仅纳入进场可核实箱（13），
     延迟进入按左截断计入（CNU1009）；
   - 版本化：迟到离场事件（CNU1002 闸口出场）入账后新区间版本 v2（精确离场 433h），
     v1 记录仍为右删失不可改写；管理报告 v1/v2 分别引用对应区间版本。

### 3. Swagger UI 手工验证

打开 http://localhost:8080/swagger-ui.html ，用上方测试账号（Authorize → basicAuth）逐个接口调用。
OpenAPI 3.x 文档：http://localhost:8080/v3/api-docs

## API 概览

| 分组 | 接口 | 说明 |
| --- | --- | --- |
| 01-事件接入 | `POST /api/ingest/events` · `POST /api/ingest/replay/{sample}` · `GET /api/ingest/events` | 事件入账（幂等）、本地样例回放、事件账查询 |
| 02-湖仓提交 | `POST /api/commits/generate[-all]` · `GET /api/commits` · `GET /api/commits/conflicts` · `POST /api/commits/conflicts/{id}/resolve` | 不可变提交生成、冲突查询与裁定 |
| 03-航次清单 | `POST /api/manifests/bookings` · `POST /api/manifests/freeze` · `GET /api/manifests/{id}` | 订舱登记、截关快照冻结、清单明细（含提交边界） |
| 04-修订候选 | `GET /api/revisions` · `POST /api/revisions/{id}/decide` | 截关后更正候选查询与裁定 |
| 05-对账 | `POST /api/recon/receipts[/replay/{sample}]` · `POST /api/recon/compare` · `POST /api/recon/diffs/{id}/adjust|ignore` | 回执入账、逐项比对、差异处理 |
| 06-调度视图 | `GET /api/dispatch/containers/{no}` · `GET /api/dispatch/containers/{no}/lineage` · `GET /api/dispatch/partitions/{p}/containers` | 当前合并视图（逐字段溯源）、事件溯源、分区堆位 |
| 07-结算 | `GET /api/settlement/manifests/{id}/charges` · `GET /api/settlement/manifests/{id}/adjustments` · `GET /api/settlement/quote`（拒绝演示） | 只能引用已冻结航次版本 |
| 08-滞留分析 | `POST /api/dwell/studies` · `POST /api/dwell/studies/{id}/runs` · `GET /api/dwell/runs/{runId}/records[/estimates]` · `POST /api/dwell/studies/{id}/reports` · `GET /api/dwell/reports/{id}` | 滞留研究、在场风险区间版本、两路线估计、版本化管理报告（DISPATCH/ADMIN） |

## 本地事件样例（`src/main/resources/samples/`）

| 文件 | 内容 |
| --- | --- |
| `bookings.json` | 航次 V2026-11A 订舱 6 箱（CNU0001–CNU0006） |
| `gate-events.json` | 闸口放行 6 条（GATE#1–6） |
| `vessel-events.json` | 装船确认 6 条（VESSEL#1–6） |
| `crane-events.json` | 场桥移动 8 条（CRANE#1–7,9；含 CNU0002 迟到重复报文、CNU0003 乱序报文） |
| `yard-events.json` | 堆场盘点 2 条（YARD#1–2） |
| `correction-events.json` | 截关后更正 1 条（CRANE#8：CNU0005 漏报移箱，业务时点在截关前） |
| `carrier-receipts.json` | 承运人 MASK-LINE 回执 5 份（CNU0004 主张未装船、CNU0006 缺失） |
| `dwell-events.json` | 滞留样例 23 条（CNU1001–CNU1009：期初在场、期末在场、区间盘点、进出均不确知、出场证据冲突、重复进场、期初精确进场对照） |
| `dwell-late-events.json` | 迟到离场 1 条（GATE#190：CNU1002 闸口出场，触发新区间版本） |

## 关键设计

- **排序键**：`(bizTime, sourceSystem, sourceSeq)`，与入库先后无关 → 后到的消息无法覆盖先到的装船确认。
- **冲突检测**（箱号维度跨分区）：业务时点并列且效果冲突（`BIZ_TIME_TIE`）、来源序号回退却声称更晚业务时点
  （`SOURCE_SEQ_REGRESSION`）→ 相关事件 `PENDING_ARBITRATION`，不产生提交；人工裁定胜选事件后重新生成提交。
- **不可变性**：`lake_commit`、`manifest_item` 禁止 UPDATE/DELETE，`ingest_event`、`voyage_manifest` 禁止 DELETE
  （数据库触发器兜底，应用层亦无相应接口）。
- **冻结原子性**：完整性校验（无未提交/待裁定的截关前事件）+ 单事务写入清单与清单行 → 要么完整发布，要么零残留。
- **可解释性**：清单行 `basisCommitIds` 记录费用基于哪版提交；费用调整单 `basis` 记录清单行/提交/触发事件/对账差异。
- **串行化**：同一箱号提交生成、同一航次冻结、同一研究的区间版本生成分别使用 PostgreSQL 咨询锁（`pg_advisory_xact_lock`）。

### 滞留分析关键设计

- **删失/截断记录构造**（`dwell_record`，仅基于已提交事件）：
  - 进场：闸口进场为精确时刻；无闸口记录时进场区间为 `(最后不在场证明, 首次在场证明]`，
    无不在场证明则下界无界（真实进场早于全部证据）；
  - 离场：闸口出场/装船确认为精确离场；无离场事件时离场区间为 `(最后在场证明, 首次不在场证明]`；
    期末仍在场**仅在最后在场证明时刻右删失，不登记为已离场**；
  - **延迟进入**：进场上界 ≤ 统计期起点 → 以统计期起点为风险观察起点，报告自该时点起的**剩余滞留**；
    真实进场未知时**总堆存上界为 +∞，不把未观测进场当作零**；总堆存只使用可核实进场区间并扩大范围；
  - **区间证据不取中点**：盘点区间只作为区间端点，不作为精确进/离场时刻；
  - **证据冲突**（盘点与闸口/装船矛盾、重复进场、在场断裂、待裁定事件）：取各解释**最宽包络区间**，
    分层保留冲突标记与待核范围，**不挑选较短时长**；仅有未提交事件的箱列入排除清单披露，不静默丢弃。
- **路线一（BOUNDS）**：每箱滞留时长只取证据允许区间，分组超期比例与分位数只发布可行上下界
  （Manski 型界）；分位点上界可能不可识别（`upper=null`）；不假定删失分布、不猜测区间内时点。
- **路线二（MODEL）**：Weibull 生存模型 MLE（自实现 Nelder-Mead），同一似然同时处理
  延迟进入（左截断条件似然 `1/S(L0)`）、右删失 `S(c)`、区间删失 `S(L)−S(R)`；
  披露非信息性删失与分布假设、指数分布参照敏感性（`tailDelta`）、
  模型-界限一致性诊断（`boundsContainment`，模型点估计越出非参数界时给出失配警告，
  尾部风险以路线一上界为准）；总堆存模型仅纳入进场可核实箱并披露排除数。
- **分别版本化**：作业事件（事件账水位）、在场风险区间（`dwell_obs_run.version`）、
  统计估计（随区间版本）、航次快照（`voyage_manifest`）、管理报告（`dwell_report.version`）
  各自独立版本化；迟到离场事件入账后重新计算**只产生新版本**，dwell 各表触发器兜底不可改写。
- **覆盖率与未决冲突**：每个分组披露证据构成（精确/区间/无界进场、精确/区间/删失离场、
  左截断、冲突数），报告输出未决冲突清单（含证据事件与待裁定事件 id）。

## 已执行的验证结论（本仓库交付前实际运行）

- `mvn test`：29 个用例全部通过（CommitConflictFlowTest 5 · ManifestFreezeFlowTest 4 ·
  RevisionAndReconFlowTest 3 · SecurityFlowTest 6 · DwellFlowTest 6 · DwellModelAnalyzerTest 5）。
- `./scripts/replay-acceptance.sh`：96 项断言全部通过（输出 `通过 96 项，失败 0 项`）。
- Docker 交付物：本环境无 Docker 守护进程，`docker-compose.yml` 已经 YAML 解析校验，Dockerfile 经静态检查；
  本地启动路径（方式二）已实测，Compose 启动使用同一 jar 与等价环境变量。

## 目录结构

```
├── Dockerfile / docker-compose.yml / docker/maven-settings.xml
├── scripts/replay-acceptance.sh          # 验收回放（96 项断言）
├── src/main/resources/db/migration/V1__init.sql   # schema + 不可变触发器
├── src/main/resources/db/migration/V2__dwell.sql  # 滞留分析表 + 不可变触发器
├── src/main/resources/samples/*.json     # 本地事件样例（含滞留样例）
└── src/main/java/com/portops/lakehouse/
    ├── ingest/      接入服务（事件账）
    ├── commit/      湖仓写入（不可变提交、冲突裁定）
    ├── manifest/    航次清单（截关快照、修订候选）
    ├── recon/       对账（回执、比对、差异）
    ├── settlement/  结算（冻结版本引用、费用调整单、费用表）
    ├── dispatch/    调度查询（读时合并视图）
    ├── dwell/       滞留分析（证据抽取、两路线估计、版本化报告）
    └── common/ config/  状态折叠器、异常、安全与 OpenAPI 配置
```

> 说明：`/api/dev/reset` 仅供本地验收重复回放，由 `DEV_RESET_ENABLED`（默认 `true`）控制，生产部署必须设为 `false`。
