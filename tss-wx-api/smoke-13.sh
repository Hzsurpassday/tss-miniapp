#!/usr/bin/env bash
# 时空智审 V1.0 RC2 —— 13 端点真实 HTTP 冒烟脚本
#
# 用途：对已启动的服务（默认 http://127.0.0.1:8787）逐一端到端调用 RC2 的 13 个端点，
#       打印 HTTP 状态与响应体，用于人工核对「包络 / 错误码 / 字段」是否符合契约。
#
# 前置：服务必须以 dev profile 启动，且 DEV Mock 令牌颁发器四重门禁全部满足
#       （dev profile + mock-token.enabled=true + env-attestation=DEV_LOCAL + 来源为回环地址）。
#
# 用法：
#   BASE=http://127.0.0.1:8787 ./smoke-13.sh
#
# 说明：API-01 的真实微信登录在「未配置 appid/secret」时会返回 503 TSS_NOT_CONFIGURED，
#       这是预期的 fail-closed 结果，不是脚本错误。
set -u

BASE="${BASE:-http://127.0.0.1:8787}"
ENT="E498AU5L35J"
USER="U4FXG07VRDI"
# RC3 红线：管理端令牌不得硬编码，必须由运行环境注入。
# 未注入时 /admin/v1/tss/** 一律 401（fail-closed，属预期行为，不是故障）。
ADMIN_TOKEN="${TSS_ADMIN_TOKEN:-}"

hr() { echo "--------------------------------------------------------------"; }

echo "BASE = $BASE"
hr

# ---- 取 DEV Mock 会话令牌（非 RC2 端点，仅 DEV 支撑） ----
MOCK_JSON=$(curl -s --noproxy '*' -X POST "$BASE/dev-support/v1/mock-token" \
  -H "Content-Type: application/json" \
  -d "{\"entId\":\"$ENT\",\"userId\":\"$USER\",\"bindStatus\":\"BOUND\"}")
TOKEN=$(printf '%s' "$MOCK_JSON" | sed -E 's/.*"sessionToken":"([^"]+)".*/\1/')
if [ -z "$TOKEN" ] || [ "$TOKEN" = "$MOCK_JSON" ]; then
  echo "!! 取 Mock 令牌失败（四重门禁未通过？）。响应："
  echo "$MOCK_JSON"
  exit 1
fi
echo "[dev] mock token  = ${TOKEN:0:12}...（已省略）"

# API-09 必须由「未绑定」身份发起：已绑定身份再申请 ⇒ 409 TSS_BIND_CONFLICT（权威文案「已有冲突绑定」）。
MOCK_JSON_UB=$(curl -s --noproxy '*' -X POST "$BASE/dev-support/v1/mock-token" \
  -H "Content-Type: application/json" \
  -d "{\"entId\":\"$ENT\",\"userId\":\"U9SMOKE0001\",\"bindStatus\":\"UNBOUND\"}")
TOKEN_UB=$(printf '%s' "$MOCK_JSON_UB" | sed -E 's/.*"sessionToken":"([^"]+)".*/\1/')
echo "[dev] unbound token = ${TOKEN_UB:0:12}...（用于 API-09/10，已省略）"
AUTH_UB=(-H "Authorization: Bearer $TOKEN_UB")
hr

call() {
  local label="$1"; shift
  echo "### $label"
  curl -s --noproxy '*' -w "\n<<HTTP %{http_code}>>\n" "$@"
  echo
}

AUTH=(-H "Authorization: Bearer $TOKEN")
ADMIN=(-H "Authorization: Bearer $ADMIN_TOKEN")
JSON=(-H "Content-Type: application/json")

call "API-01 POST /wx/v1/auth/login" \
  -X POST "$BASE/wx/v1/auth/login" "${JSON[@]}" -d '{"code":"WECHAT_LOGIN_CODE"}'

call "API-02 GET /wx/v1/me" \
  "$BASE/wx/v1/me" "${AUTH[@]}"

call "API-03 GET /wx/v1/todos/summary" \
  "$BASE/wx/v1/todos/summary" "${AUTH[@]}"

call "API-04 GET /wx/v1/todos" \
  "$BASE/wx/v1/todos?limit=20" "${AUTH[@]}"

call "API-04b GET /wx/v1/todos?docType=PAYMENT（筛选不命中）" \
  "$BASE/wx/v1/todos?docType=PAYMENT" "${AUTH[@]}"

call "API-04c GET /wx/v1/todos?cursor=BAD.SIG（非法游标）" \
  "$BASE/wx/v1/todos?cursor=BAD.SIG" "${AUTH[@]}"

call "API-05 GET /wx/v1/todos/355377/detail" \
  "$BASE/wx/v1/todos/355377/detail" "${AUTH[@]}"

call "API-06 GET /wx/v1/history" \
  "$BASE/wx/v1/history?from=2026-10-01&to=2026-12-31" "${AUTH[@]}"

call "API-07 GET /wx/v1/notifications" \
  "$BASE/wx/v1/notifications" "${AUTH[@]}"

call "API-08 POST /wx/v1/notifications/consent" \
  -X POST "$BASE/wx/v1/notifications/consent" "${JSON[@]}" "${AUTH[@]}" \
  -d '{"templateIds":["wx_tpl_01"],"consented":true}'

call "API-09 POST /wx/v1/bindings/claim（未绑定身份）" \
  -X POST "$BASE/wx/v1/bindings/claim" "${JSON[@]}" "${AUTH_UB[@]}" \
  -d '{"inviteCode":"INV-EXAMPLE","claimNonce":"nonce_001"}'

echo "### API-09b 已绑定身份再申请（期望 409 TSS_BIND_CONFLICT）"
curl -s --noproxy '*' -w "\n<<HTTP %{http_code}>>\n" -X POST "$BASE/wx/v1/bindings/claim" \
  "${JSON[@]}" "${AUTH[@]}" -d '{"inviteCode":"INV-EXAMPLE","claimNonce":"nonce_001b"}'
echo

call "API-10 GET /wx/v1/bindings/status（已绑定：bindRequestId 应为 null）" \
  "$BASE/wx/v1/bindings/status" "${AUTH[@]}"

call "API-10b GET /wx/v1/bindings/status（申请中：PENDING_PC_CONFIRM + bindRequestId 非空）" \
  "$BASE/wx/v1/bindings/status" "${AUTH_UB[@]}"

echo "### API-02b 申请中身份的 /wx/v1/me（裁决 A：期望 bindStatus=PENDING）"
curl -s --noproxy '*' -w "\n<<HTTP %{http_code}>>\n" "$BASE/wx/v1/me" "${AUTH_UB[@]}"
echo

call "API-12 GET /admin/v1/tss/bindings" \
  "$BASE/admin/v1/tss/bindings" "${ADMIN[@]}"

call "API-13 GET /admin/v1/tss/mappings" \
  "$BASE/admin/v1/tss/mappings" "${ADMIN[@]}"

# 注意：API-11 解绑会撤销绑定并让当前会话失效，因此放在最后执行，
# 否则后续的 API-12 绑定列表示例会变成空列表（不是缺陷，是解绑的真实后果）。
call "API-11 POST /wx/v1/bindings/unbind" \
  -X POST "$BASE/wx/v1/bindings/unbind" "${JSON[@]}" "${AUTH[@]}" \
  -d '{"confirmation":"UNBIND","clientNonce":"nonce_002"}'

echo "### 解绑后复用同一会话（会话仍有效：期望 200 且 bindStatus=UNBOUND，与 Python 侧一致）"
curl -s --noproxy '*' -w "\n<<HTTP %{http_code}>>\n" "$BASE/wx/v1/me" "${AUTH[@]}"
echo
echo "### 解绑后访问业务数据（期望 403 TSS_NOT_BOUND）"
curl -s --noproxy '*' -w "\n<<HTTP %{http_code}>>\n" "$BASE/wx/v1/todos" "${AUTH[@]}"
echo
echo "### 二次解绑（期望 403 TSS_NOT_BOUND，不会被 401 抢先短路）"
curl -s --noproxy '*' -w "\n<<HTTP %{http_code}>>\n" -X POST "$BASE/wx/v1/bindings/unbind" \
  "${JSON[@]}" "${AUTH[@]}" -d '{"confirmation":"UNBIND","clientNonce":"nonce_003"}'
echo

hr
echo "### 负面用例：无凭据访问 /wx/v1/me（期望 401 TSS_UNAUTHORIZED）"
curl -s --noproxy '*' -w "\n<<HTTP %{http_code}>>\n" "$BASE/wx/v1/me"
echo
echo "### 负面用例：错误管理端令牌（期望 403 TSS_ADMIN_FORBIDDEN；D-7：带无效令牌 ≠ 没带凭据）"
curl -s --noproxy '*' -w "\n<<HTTP %{http_code}>>\n" "$BASE/admin/v1/tss/mappings" -H "Authorization: Bearer nope"
echo
hr
echo "冒烟完成。"
