#!/usr/bin/env bash
# 分页正向多页流转 + 非契约路径包络 —— 真实 HTTP 证据脚本
#
# 前置：服务已以 dev profile 启动在 http://127.0.0.1:8787，且 DEV Mock 令牌四重门禁满足。
# 用法：BASE=http://127.0.0.1:8787 bash ./smoke-pagination.sh
set -u

BASE="${BASE:-http://127.0.0.1:8787}"
ENT="E498AU5L35J"
USER="U4FXG07VRDI"
# RC3 红线：管理端令牌由环境注入；未注入时管理端路径一律 401（fail-closed）。
ADMIN_TOKEN="${TSS_ADMIN_TOKEN:-}"

hr() { echo "--------------------------------------------------------------"; }

echo "BASE = $BASE"
hr

MOCK_JSON=$(curl -s --noproxy '*' -X POST "$BASE/dev-support/v1/mock-token" \
  -H "Content-Type: application/json" \
  -d "{\"entId\":\"$ENT\",\"userId\":\"$USER\",\"bindStatus\":\"BOUND\"}")
TOKEN=$(printf '%s' "$MOCK_JSON" | sed -E 's/.*"sessionToken":"([^"]+)".*/\1/')
if [ -z "$TOKEN" ] || [ "$TOKEN" = "$MOCK_JSON" ]; then
  echo "!! 取 Mock 令牌失败。响应："; echo "$MOCK_JSON"; exit 1
fi
echo "[dev] mock token = ${TOKEN:0:12}...（已省略）"
hr

AUTH=(-H "Authorization: Bearer $TOKEN")

field() { # $1=json $2=field
  printf '%s' "$1" | sed -E "s/.*\"$2\":\"?([^,\"]*)\"?.*/\1/"
}

# ---------- 第 1 页 ----------
P1=$(curl -s --noproxy '*' "$BASE/wx/v1/todos?limit=1" "${AUTH[@]}")
echo "### 第 1 页  GET /wx/v1/todos?limit=1"
echo "$P1"
C1=$(field "$P1" nextCursor)
T1=$(printf '%s' "$P1" | sed -E 's/.*"taskNum":"([^"]+)".*/\1/')
echo "  -> taskNum=$T1  nextCursor=${C1:0:40}...  hasMore=$(field "$P1" hasMore)"
hr

# ---------- 第 2 页 ----------
P2=$(curl -s --noproxy '*' "$BASE/wx/v1/todos?limit=1&cursor=$C1" "${AUTH[@]}")
echo "### 第 2 页  GET /wx/v1/todos?limit=1&cursor=<第1页游标>"
echo "$P2"
C2=$(field "$P2" nextCursor)
T2=$(printf '%s' "$P2" | sed -E 's/.*"taskNum":"([^"]+)".*/\1/')
echo "  -> taskNum=$T2  nextCursor=${C2:0:40}...  hasMore=$(field "$P2" hasMore)"
hr

# ---------- 第 3 页 ----------
P3=$(curl -s --noproxy '*' "$BASE/wx/v1/todos?limit=1&cursor=$C2" "${AUTH[@]}")
echo "### 第 3 页  GET /wx/v1/todos?limit=1&cursor=<第2页游标>"
echo "$P3"
T3=$(printf '%s' "$P3" | sed -E 's/.*"taskNum":"([^"]+)".*/\1/')
echo "  -> taskNum=$T3  hasMore=$(field "$P3" hasMore)  nextCursor=$(field "$P3" nextCursor)"
hr

echo "### 三页 taskNum 汇总（期望互不重复）"
echo "  page1=$T1  page2=$T2  page3=$T3"
if [ "$T1" != "$T2" ] && [ "$T2" != "$T3" ] && [ "$T1" != "$T3" ]; then
  echo "  => 断言①/② 通过：第 1 页给出非空 nextCursor，翻页取到下一项且三页互不重复"
else
  echo "  => 断言①/② 失败：出现重复项"; exit 1
fi
hr

# ---------- 改筛选后复用旧游标 ----------
echo "### 断言③：改筛选条件后复用旧游标（期望 400 TSS_INVALID_CURSOR）"
curl -s --noproxy '*' -w "\n<<HTTP %{http_code}>>\n" \
  "$BASE/wx/v1/todos?docType=SALES_BUSINESS&cursor=$C1" "${AUTH[@]}"
echo
echo "### 断言③-b：加 keyword 后复用旧游标（期望 400 TSS_INVALID_CURSOR）"
curl -s --noproxy '*' -w "\n<<HTTP %{http_code}>>\n" \
  "$BASE/wx/v1/todos?keyword=77971&cursor=$C1" "${AUTH[@]}"
echo
hr

# ---------- 非契约路径包络 ----------
echo "### 非契约路径：GET /admin/v1/tss/overview（期望 404 + 统一包络，而非 Spring 默认错误体）"
curl -s --noproxy '*' -w "\n<<HTTP %{http_code}>>\n" \
  "$BASE/admin/v1/tss/overview" -H "Authorization: Bearer $ADMIN_TOKEN"
echo
hr
echo "分页与包络证据采集完成。"
