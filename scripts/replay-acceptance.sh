#!/usr/bin/env bash
# 验收回放：并发位置更新 / 分区提交中断 / 截关后更正 / 承运人回执差异
#   + 滞留分析（五类删失样例、两路线估计、迟到离场事件版本化）
# 前置：服务已启动（本地 mvn spring-boot:run 或 docker compose up）
# 用法：./scripts/replay-acceptance.sh   （可用 BASE_URL 覆盖服务地址）
set -uo pipefail

BASE=${BASE_URL:-http://127.0.0.1:8080}
INGEST="ingest:ingest123"
DISPATCH="dispatcher:disp123"
SETTLE="settlement:settle123"
ADMIN="admin:admin123"
VOYAGE="V2026-11A"
CUTOFF="2026-11-20T18:00:00+08:00"

PASS=0; FAIL=0
check() { # check <描述> <实际> <期望>
  if [[ "$2" == "$3" ]]; then PASS=$((PASS+1)); echo "  ✔ $1（=$2）";
  else FAIL=$((FAIL+1)); echo "  ✘ $1：期望[$3] 实际[$2]"; fi
}

api() { # api <user:pass> <METHOD> <path> [json-body] → stdout=body
  local auth=$1 method=$2 path=$3 body=${4:-}
  if [[ -n "$body" ]]; then
    curl -sS -u "$auth" -X "$method" "$BASE$path" -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS -u "$auth" -X "$method" "$BASE$path"
  fi
}

api_status() { # api_status <user:pass> <METHOD> <path> [json-body] → stdout=http_code, body落盘 /tmp/replay_body.json
  local auth=$1 method=$2 path=$3 body=${4:-}
  if [[ -n "$body" ]]; then
    curl -sS -o /tmp/replay_body.json -w "%{http_code}" -u "$auth" -X "$method" "$BASE$path" -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS -o /tmp/replay_body.json -w "%{http_code}" -u "$auth" -X "$method" "$BASE$path"
  fi
}

echo "== 0. 等待服务并就绪重置 =="
for i in $(seq 1 30); do
  if curl -sS -o /dev/null "$BASE/v3/api-docs" 2>/dev/null; then break; fi
  sleep 1
done
curl -sS -o /dev/null "$BASE/v3/api-docs" || { echo "服务不可达: $BASE"; exit 1; }
api "$ADMIN" POST /api/dev/reset > /dev/null
echo "  服务可达，业务表已重置"

echo "== 1. 登记航次订舱（$VOYAGE，6 箱）=="
BOOKINGS=$(cat "$(dirname "$0")/../src/main/resources/samples/bookings.json")
api "$SETTLE" POST /api/manifests/bookings "$BOOKINGS" > /dev/null
echo "  订舱完成"

echo "== 2. 回放本地事件样例（先装船后场桥，模拟乱序入库）=="
for s in gate-events vessel-events crane-events yard-events; do
  N=$(api "$INGEST" POST "/api/ingest/replay/$s" | jq 'length')
  echo "  回放 $s: $N 条"
done

echo "== 3. 场景B-1：分区提交未完成时冻结清单 → 必须失败且不发布任何数据 =="
CODE=$(api_status "$SETTLE" POST /api/manifests/freeze "{\"voyageNo\":\"$VOYAGE\",\"cutoffTime\":\"$CUTOFF\"}")
check "冻结被拒绝(HTTP 409)" "$CODE" "409"
check "失败原因=存在未提交事件" "$(jq -r '.message | contains("尚未提交")' /tmp/replay_body.json)" "true"
check "清单表仍为空" "$(api "$SETTLE" GET "/api/manifests?voyageNo=$VOYAGE" | jq 'length')" "0"

echo "== 4. 生成湖仓提交（不可变提交，按箱号+作业分区）=="
api "$INGEST" POST /api/commits/generate-all > /tmp/gen.json
check "全部箱号已处理" "$(jq 'length' /tmp/gen.json)" "6"

echo "== 5. 场景A：并发位置更新 —— 冲突进待裁定，不由最后写入者决定 =="
check "待裁定冲突数" "$(api "$DISPATCH" GET '/api/commits/conflicts?status=PENDING' | jq 'length')" "1"
check "冲突原因" "$(api "$DISPATCH" GET '/api/commits/conflicts?status=PENDING' | jq -r '.[0].reason')" "BIZ_TIME_TIE"
check "冲突箱号" "$(api "$DISPATCH" GET '/api/commits/conflicts?status=PENDING' | jq -r '.[0].containerNo')" "CNU0002"
V3=$(api "$DISPATCH" GET /api/dispatch/containers/CNU0003)
check "CNU0003 后入库的场桥报文未覆盖装船确认(loaded)" "$(echo "$V3" | jq -r '.currentState.loaded')" "true"
check "CNU0003 当前位置在船上" "$(echo "$V3" | jq -r '.currentState.position')" "VESSEL:V2026-11A"
V2=$(api "$DISPATCH" GET /api/dispatch/containers/CNU0002)
check "CNU0002 冲突未裁定前 loaded 未定" "$(echo "$V2" | jq -r '.currentState.loaded')" "false"
check "CNU0002 待裁定事件数" "$(echo "$V2" | jq -r '.pendingArbitrationCount')" "2"

echo "== 6. 场景B-2：存在待裁定冲突时冻结清单 → 必须失败（不用半个分区发布航次）=="
CODE=$(api_status "$SETTLE" POST /api/manifests/freeze "{\"voyageNo\":\"$VOYAGE\",\"cutoffTime\":\"$CUTOFF\"}")
check "冻结被拒绝(HTTP 409)" "$CODE" "409"
check "失败原因=存在待裁定" "$(jq -r '.message | contains("待裁定")' /tmp/replay_body.json)" "true"
check "清单表仍为空" "$(api "$SETTLE" GET "/api/manifests?voyageNo=$VOYAGE" | jq 'length')" "0"

echo "== 7. 调度裁定冲突：装船确认有效，场桥迟到重复报文被取代 =="
CONFLICT_ID=$(api "$DISPATCH" GET '/api/commits/conflicts?status=PENDING' | jq '.[0].id')
WINNER=$(api "$DISPATCH" GET '/api/ingest/events?containerNo=CNU0002&status=PENDING_ARBITRATION' \
  | jq '[.[] | select(.eventType=="LOAD_CONFIRM")][0].id')
api "$DISPATCH" POST "/api/commits/conflicts/$CONFLICT_ID/resolve" \
  "{\"winnerEventId\":$WINNER,\"note\":\"场桥14:00报文为迟到重复上报，装船确认有效\"}" > /dev/null
V2=$(api "$DISPATCH" GET /api/dispatch/containers/CNU0002)
check "裁定后 CNU0002 已装船" "$(echo "$V2" | jq -r '.currentState.loaded')" "true"
check "被取代报文仍可溯源" "$(api "$DISPATCH" GET /api/dispatch/containers/CNU0002/lineage | jq '[.[] | select(.status=="SUPERSEDED")] | length')" "1"

echo "== 8. 冻结截关快照（截关 $CUTOFF）=="
api "$SETTLE" POST /api/manifests/freeze "{\"voyageNo\":\"$VOYAGE\",\"cutoffTime\":\"$CUTOFF\"}" > /tmp/freeze.json
check "清单状态" "$(jq -r '.status' /tmp/freeze.json)" "FROZEN"
check "清单行数" "$(jq '.itemCount' /tmp/freeze.json)" "6"
check "完整提交边界(6箱×3分区)" "$(jq '.commitFrontier | length' /tmp/freeze.json)" "18"
MID=$(jq '.manifestId' /tmp/freeze.json)
check "CNU0005 快照移箱次数=2（更正前）" \
  "$(api "$SETTLE" GET "/api/manifests/$MID/items" | jq '[.[] | select(.containerNo=="CNU0005")][0].moveCount')" "2"

echo "== 9. 场景C：截关后更正 → 修订候选 → 费用调整单；原始结算不改写 =="
api "$INGEST" POST /api/ingest/replay/correction-events > /dev/null
api "$INGEST" POST /api/commits/generate-all > /dev/null
CAND=$(api "$SETTLE" GET "/api/revisions?manifestId=$MID")
check "修订候选数" "$(echo "$CAND" | jq 'length')" "1"
check "候选差异说明" "$(echo "$CAND" | jq -r '.[0].diffSummary | contains("moveCount: 2 → 3")')" "true"
check "冻结清单行未被改写(仍=2)" \
  "$(api "$SETTLE" GET "/api/manifests/$MID/items" | jq '[.[] | select(.containerNo=="CNU0005")][0].moveCount')" "2"
check "调度当前视图已反映更正(=3)" \
  "$(api "$DISPATCH" GET /api/dispatch/containers/CNU0005 | jq '.currentState.moveCount')" "3"
CAND_ID=$(echo "$CAND" | jq '.[0].id')
ADJ=$(api "$SETTLE" POST "/api/revisions/$CAND_ID/decide" '{"accept":true,"note":"盘点确认漏报一次移箱"}')
check "接受候选生成调整单数" "$(echo "$ADJ" | jq '.feeAdjustmentIds | length')" "1"

echo "== 10. 场景D：承运人回执差异对账 =="
api "$SETTLE" POST /api/recon/receipts/replay/carrier-receipts > /dev/null
RUN=$(api "$SETTLE" POST "/api/recon/compare?manifestId=$MID")
RUN_ID=$(echo "$RUN" | jq '.runId')
check "回执匹配箱数" "$(echo "$RUN" | jq '.summary.matched')" "4"
DIFFS=$(api "$SETTLE" GET "/api/recon/runs/$RUN_ID" | jq '.diffs')
check "CNU0004 装船事实差异" "$(echo "$DIFFS" | jq '[.[] | select(.diffType=="LOADED_MISMATCH")] | length')" "1"
check "CNU0004 费用差异" "$(echo "$DIFFS" | jq '[.[] | select(.diffType=="FEE_MISMATCH")] | length')" "1"
check "CNU0006 回执缺失" "$(echo "$DIFFS" | jq '[.[] | select(.diffType=="MISSING_ON_RECEIPT")] | length')" "1"
LOADED_DIFF_ID=$(echo "$DIFFS" | jq '[.[] | select(.diffType=="LOADED_MISMATCH")][0].id')
FEE_DIFF_ID=$(echo "$DIFFS" | jq '[.[] | select(.diffType=="FEE_MISMATCH")][0].id')
MISS_DIFF_ID=$(echo "$DIFFS" | jq '[.[] | select(.diffType=="MISSING_ON_RECEIPT")][0].id')
ADJ2=$(api "$SETTLE" POST "/api/recon/diffs/$LOADED_DIFF_ID/adjust" '{"note":"快照为已装船，向承运人补收装船费"}')
check "装船差异调整金额(+450)" "$(echo "$ADJ2" | jq '.deltaAmount == 450')" "true"
api "$SETTLE" POST "/api/recon/diffs/$FEE_DIFF_ID/ignore" '{"note":"已被LOADED_MISMATCH调整覆盖"}' > /dev/null
api "$SETTLE" POST "/api/recon/diffs/$MISS_DIFF_ID/ignore" '{"note":"承运人系统延迟，下周补回执"}' > /dev/null
check "差异处理完成(OPEN=0)" "$(api "$SETTLE" GET '/api/recon/diffs?status=OPEN' | jq 'length')" "0"

echo "== 11. 结算视图：只能引用已冻结版本；调整单与原始结算分列 =="
CHARGES=$(api "$SETTLE" GET "/api/settlement/manifests/$MID/charges")
check "原始结算合计(6箱=3260)" "$(echo "$CHARGES" | jq '.originalTotal == 3260')" "true"
check "调整单合计(80更正+450对账=530)" "$(echo "$CHARGES" | jq '.adjustmentTotal == 530')" "true"
check "结算总额(=3790)" "$(echo "$CHARGES" | jq '.settledTotal == 3790')" "true"
check "调整单可解释(含依据)" \
  "$(api "$SETTLE" GET "/api/settlement/manifests/$MID/adjustments" | jq '[.[] | select(.basis != null)] | length')" "2"
CODE=$(api_status "$SETTLE" GET "/api/settlement/quote?voyageNo=$VOYAGE")
check "绕过冻结清单直接结算被拒绝(HTTP 400)" "$CODE" "400"

echo "== 12. 滞留分析：回放五类删失样例事件并生成提交 =="
N=$(api "$INGEST" POST /api/ingest/replay/dwell-events | jq 'length')
check "滞留样例回放条数" "$N" "23"
api "$INGEST" POST /api/commits/generate-all > /dev/null
echo "  提交完成（6 基础箱 + 9 滞留样例箱 = 15 箱目标总体）"

echo "== 13. 创建滞留研究（统计窗口 2026-11，阈值 72/168/336h，分位点 0.5/0.9）=="
CODE=$(api_status "$INGEST" POST /api/dwell/studies "{\"studyCode\":\"DWELL-NOAUTH\",\"periodStart\":\"2026-11-01T00:00:00+08:00\",\"periodEnd\":\"2026-12-01T00:00:00+08:00\",\"thresholdsHours\":[72],\"quantiles\":[0.5]}")
check "接入账号无权创建研究(HTTP 403)" "$CODE" "403"
STUDY=$(api "$DISPATCH" POST /api/dwell/studies \
  "{\"studyCode\":\"DWELL-2026-11\",\"periodStart\":\"2026-11-01T00:00:00+08:00\",\"periodEnd\":\"2026-12-01T00:00:00+08:00\",\"thresholdsHours\":[72,168,336],\"quantiles\":[0.5,0.9]}")
SID=$(echo "$STUDY" | jq '.id')
check "研究已创建" "$([ -n "$SID" ] && [ "$SID" != "null" ] && echo yes)" "yes"

echo "== 14. 计算在场风险区间版本 v1：五类删失/截断记录构造 =="
RUN1=$(api "$DISPATCH" POST "/api/dwell/studies/$SID/runs")
R1=$(echo "$RUN1" | jq '.runId')
check "区间版本号=1" "$(echo "$RUN1" | jq '.version')" "1"
check "目标箱总数(15)" "$(echo "$RUN1" | jq '.containerCount')" "15"
check "无被排除箱(全部有已提交证据)" "$(echo "$RUN1" | jq '.excludedContainers | length')" "0"
REC() { api "$DISPATCH" GET "/api/dwell/runs/$R1/records?containerNo=$1"; }
check "CNU1001 期初在场(延迟进入)" "$(REC CNU1001 | jq -r '.[0].leftTruncated')" "true"
check "CNU1001 期末仍在场右删失" "$(REC CNU1001 | jq -r '.[0].rightCensored')" "true"
check "CNU1001 剩余滞留下界=719h" "$(REC CNU1001 | jq '.[0].remainLowerHours')" "719"
check "CNU1001 剩余滞留上界=+∞" "$(REC CNU1001 | jq '.[0].remainUpperHours == null')" "true"
check "CNU1001 真实进场未知→总堆存上界=+∞(不伪装已知)" "$(REC CNU1001 | jq '.[0].totalUpperHours == null')" "true"
check "CNU1002 精确进场+右删失(不登记为已离场)" "$(REC CNU1002 | jq -r '.[0].exitKind')" "RIGHT_CENSORED"
check "CNU1002 剩余滞留下界=122h" "$(REC CNU1002 | jq '.[0].remainLowerHours')" "122"
check "CNU1003 仅区间盘点→区间删失" "$(REC CNU1003 | jq -r '.[0].intervalCensored')" "true"
check "CNU1003 滞留区间=[0,360h](不取中点)" "$(REC CNU1003 | jq -r '"\(.[0].remainLowerHours)-\(.[0].remainUpperHours)"')" "0-360"
check "CNU1004 进场下界无界" "$(REC CNU1004 | jq '.[0].entryLower == null')" "true"
check "CNU1004 总堆存上界=+∞" "$(REC CNU1004 | jq '.[0].totalUpperHours == null')" "true"
check "CNU1004 剩余滞留=[192,513]h" "$(REC CNU1004 | jq -r '"\(.[0].remainLowerHours)-\(.[0].remainUpperHours)"')" "192-513"
check "CNU1005 出场证据冲突(盘点矛盾闸口)" "$(REC CNU1005 | jq -r '.[0].conflictStatus | contains("PRESENCE_AFTER_EXIT")')" "true"
check "CNU1005 取最宽包络上界=+∞(不挑选较短时长)" "$(REC CNU1005 | jq '.[0].remainUpperHours == null')" "true"
check "CNU1005 包络下界=192h" "$(REC CNU1005 | jq '.[0].remainLowerHours')" "192"
check "CNU1006 完整记录对照=144h 精确" "$(REC CNU1006 | jq -r '"\(.[0].remainLowerHours)-\(.[0].remainUpperHours)"')" "144-144"
check "CNU1008 跨航次重复进场冲突" "$(REC CNU1008 | jq -r '.[0].conflictStatus | contains("DUPLICATE_ENTRY")')" "true"
check "CNU1009 期初精确进场剩余滞留=224h" "$(REC CNU1009 | jq '.[0].remainLowerHours')" "224"
check "CNU1009 总堆存=384h(可核实进场)" "$(REC CNU1009 | jq '.[0].totalLowerHours')" "384"

echo "== 15. 路线一：只发布证据支持的可行界（含删失箱，不以完整子集替代总体）=="
B=$(api "$DISPATCH" GET "/api/dwell/runs/$R1/estimates?route=BOUNDS&timeScale=REMAINING&groupDim=ALL")
BEST() { echo "$B" | jq "[.[] | select(.metric==\"$1\" and .metricArg==\"$2\")][0]"; }
check "N=15 纳入估计" "$(BEST OVERDUE_PROPORTION 168 | jq '.sampleSize')" "15"
check "72h 超期比例界=[8/15,9/15]" "$(BEST OVERDUE_PROPORTION 72 | jq -r '"\((.lowerBound*15000|round)/1000)-\((.upperBound*15000|round)/1000)"')" "8-9"
check "168h 超期比例界=[6/15,8/15]" "$(BEST OVERDUE_PROPORTION 168 | jq -r '"\((.lowerBound*15000|round)/1000)-\((.upperBound*15000|round)/1000)"')" "6-8"
check "336h 超期比例界=[2/15,6/15]" "$(BEST OVERDUE_PROPORTION 336 | jq -r '"\((.lowerBound*15000|round)/1000)-\((.upperBound*15000|round)/1000)"')" "2-6"
check "中位数可识别范围=[122h,192h]" "$(BEST QUANTILE 0.5 | jq -r '"\(.lowerBound)-\(.upperBound)"')" "122-192"
check "q0.9 下界=552h 且上界不可识别" "$(BEST QUANTILE 0.9 | jq -r '"\(.lowerBound)-\(.upperBound == null)"')" "552-true"
check "路线一无点估计(只发布界限)" "$(BEST OVERDUE_PROPORTION 168 | jq '.pointEstimate == null')" "true"
check "分组覆盖率披露(UNKNOWN 箱型=6)" \
  "$(api "$DISPATCH" GET "/api/dwell/runs/$R1/estimates?route=BOUNDS&groupDim=TYPE" | jq '[.[] | select(.metric=="GROUP_SUMMARY" and .groupKey=="UNKNOWN")][0].sampleSize')" "6"

echo "== 16. 路线二：生存模型纳入删失箱并披露假设与敏感性 =="
M=$(api "$DISPATCH" GET "/api/dwell/runs/$R1/estimates?route=MODEL&timeScale=REMAINING&groupDim=ALL")
MSUM=$(echo "$M" | jq '[.[] | select(.metric=="GROUP_SUMMARY")][0]')
check "模型纳入全部 15 箱(含删失)" "$(echo "$MSUM" | jq '.detail.censoring.n')" "15"
check "模型右删失=4" "$(echo "$MSUM" | jq '.detail.censoring.rightCensored')" "4"
check "模型区间删失=2" "$(echo "$MSUM" | jq '.detail.censoring.intervalCensored')" "2"
check "模型已拟合" "$(echo "$MSUM" | jq -r '.detail.status as $s | ($s == "FITTED" or $s == "CONVERGENCE_WARN")')" "true"
check "Weibull 形状参数有限" "$(echo "$MSUM" | jq '.detail.params.shape | isfinite')" "true"
check "假设披露(非信息性删失)" "$(echo "$MSUM" | jq -r '.detail.assumptions | join(" ") | contains("非信息性")')" "true"
check "敏感性披露(指数分布参照)" "$(echo "$MSUM" | jq '.detail.sensitivity | has("tailDeltaWeibullMinusExponential")')" "true"
check "模型-界限一致性诊断存在" "$(echo "$MSUM" | jq '.detail.sensitivity.boundsContainment | has("168")')" "true"
MP168=$(echo "$M" | jq '[.[] | select(.metric=="OVERDUE_PROPORTION" and .metricArg=="168")][0].pointEstimate')
check "模型 168h 超期概率为合法概率" "$(jq -n "$MP168 >= 0 and $MP168 <= 1")" "true"
MT=$(api "$DISPATCH" GET "/api/dwell/runs/$R1/estimates?route=MODEL&timeScale=TOTAL&groupDim=ALL")
MTSUM=$(echo "$MT" | jq '[.[] | select(.metric=="GROUP_SUMMARY")][0]')
check "总堆存模型仅纳入进场可核实箱(13)" "$(echo "$MTSUM" | jq '.detail.censoring.n')" "13"
check "真实进场未知箱排除并披露(2)" "$(echo "$MTSUM" | jq '.detail.censoring.excludedUnverifiableEntry')" "2"
check "延迟进入按左截断计入(1)" "$(echo "$MTSUM" | jq '.detail.censoring.leftTruncated')" "1"

echo "== 17. 管理报告 v1：分组汇总 + 未决冲突 + 假设披露 =="
REP1=$(api "$DISPATCH" POST "/api/dwell/studies/$SID/reports")
RP1=$(echo "$REP1" | jq '.reportId')
check "报告版本=1" "$(echo "$REP1" | jq '.version')" "1"
check "报告引用区间版本 v1" "$(echo "$REP1" | jq '.runId')" "$R1"
RC1=$(api "$DISPATCH" GET "/api/dwell/reports/$RP1")
check "报告含分组汇总" "$(echo "$RC1" | jq '.content.groups | length > 0')" "true"
check "未决冲突清单(CNU1005+CNU1008)" "$(echo "$RC1" | jq '.content.unresolvedConflicts | length')" "2"
check "报告含路线对比说明" "$(echo "$RC1" | jq '.content.routeComparisonNote | length > 0')" "true"

echo "== 18. 迟到离场事件只产生新版本，旧版本不可改写 =="
api "$INGEST" POST /api/ingest/replay/dwell-late-events > /dev/null
api "$INGEST" POST /api/commits/generate-all > /dev/null
RUN2=$(api "$DISPATCH" POST "/api/dwell/studies/$SID/runs")
R2=$(echo "$RUN2" | jq '.runId')
check "新区间版本号=2" "$(echo "$RUN2" | jq '.version')" "2"
check "事件水位前进" "$(echo "$RUN2" | jq ".eventWatermark > $(echo "$RUN1" | jq '.eventWatermark')")" "true"
V2REC=$(api "$DISPATCH" GET "/api/dwell/runs/$R2/records?containerNo=CNU1002")
check "v2 中 CNU1002 精确离场" "$(echo "$V2REC" | jq -r '.[0].exitKind')" "EXACT"
check "v2 中 CNU1002 剩余滞留=433h" "$(echo "$V2REC" | jq '.[0].remainLowerHours')" "433"
V1REC=$(api "$DISPATCH" GET "/api/dwell/runs/$R1/records?containerNo=CNU1002")
check "v1 记录仍为右删失(旧版本不可改写)" "$(echo "$V1REC" | jq -r '.[0].rightCensored')" "true"
check "v1 记录仍为 122h" "$(echo "$V1REC" | jq '.[0].remainLowerHours')" "122"
B2=$(api "$DISPATCH" GET "/api/dwell/runs/$R2/estimates?route=BOUNDS&timeScale=REMAINING&groupDim=ALL")
check "v2 的 168h 超期比例下界=7/15(迟到离场转为确定超期)" \
  "$(echo "$B2" | jq '([.[] | select(.metric=="OVERDUE_PROPORTION" and .metricArg=="168")][0].lowerBound * 15000 | round / 1000)')" "7"
REP2=$(api "$DISPATCH" POST "/api/dwell/studies/$SID/reports")
check "报告版本=2" "$(echo "$REP2" | jq '.version')" "2"
check "报告 v2 引用区间版本 v2" "$(echo "$REP2" | jq '.runId')" "$R2"
check "报告 v1 仍引用 v1(分别版本化)" "$(api "$DISPATCH" GET "/api/dwell/reports/$RP1" | jq '.runId')" "$R1"

echo ""
echo "=========================================="
echo "验收回放完成：通过 $PASS 项，失败 $FAIL 项"
echo "=========================================="
[[ $FAIL -eq 0 ]]
