#!/usr/bin/env bash
# 绑定状态八格矩阵 + 裁决 C 解绑错误码矩阵：真实 HTTP，逐格打印原始响应体。
#
# 用法：BASE=http://127.0.0.1:8787 bash ./smoke-binding-matrix.sh
# 前置：服务以 dev profile + 四重门禁打开启动（见 README §4），本机回环可访问。
set -u
BASE="${BASE:-http://127.0.0.1:8787}"
c() { curl -s --noproxy '*' "$@"; }

# 取一枚 DEV Mock 会话令牌（四重门禁须全部满足）
token() { # $1=entId $2=userId $3=bindStatus
  local body="{\"entId\":\"$1\",\"userId\":\"$2\",\"bindStatus\":\"$3\"}"
  c -X POST "$BASE/dev-support/v1/mock-token" -H 'Content-Type: application/json' -d "$body" \
    | sed -n 's/.*"sessionToken":"\([^"]*\)".*/\1/p'
}

# 打印一格：先 /wx/v1/me，再 /wx/v1/bindings/status
cell() { # $1=小标题 $2=token $3=status 查询串（可空）
  echo "--------------------------------------------------------------------------------"
  echo "【$1】"
  echo "\$ curl -s -H \"Authorization: Bearer <token>\" $BASE/wx/v1/me"
  c -H "Authorization: Bearer $2" "$BASE/wx/v1/me"; echo
  echo "\$ curl -s -H \"Authorization: Bearer <token>\" $BASE/wx/v1/bindings/status$3"
  c -H "Authorization: Bearer $2" "$BASE/wx/v1/bindings/status$3"; echo
}

post() { # $1=path $2=body $3=token(可空)
  if [ -n "${3:-}" ]; then
    c -X POST "$BASE$1" -H 'Content-Type: application/json' -H "Authorization: Bearer $3" -d "$2"
  else
    c -X POST "$BASE$1" -H 'Content-Type: application/json' -d "$2"
  fi
  echo
}

echo "=============================================================="
echo " 绑定状态矩阵（GET /wx/v1/me 与 GET /wx/v1/bindings/status 原始响应体）"
echo " BASE=$BASE"
echo "=============================================================="

# ① 未绑定
T1=$(token E901UNBOUND U9UNBOUND01 UNBOUND)
cell "① 未绑定" "$T1" ""

# ② 申请中（刚发起 claim）
T2=$(token E902CLAIM U8CLAIM0001 UNBOUND)
echo "--------------------------------------------------------------------------------"
echo "【② 申请中】先 POST /wx/v1/bindings/claim"
C2=$(post /wx/v1/bindings/claim '{"inviteCode":"INV-EXAMPLE","claimNonce":"mx-claim-2"}' "$T2")
echo "$C2"
BR2=$(echo "$C2" | sed -n 's/.*"bindRequestId":"\([^"]*\)".*/\1/p')
cell "② 申请中" "$T2" ""

# ③ 待 PC 确认（按 bindRequestId 精确查询）
cell "③ 待PC确认（?bindRequestId=$BR2）" "$T2" "?bindRequestId=$BR2"

# ④ 已绑定（预置，从未发起申请）
T4=$(token E903BOUND U7BOUND0001 BOUND)
cell "④ 已绑定（预置）" "$T4" ""

# ⑤ 已绑定（PC 确认后）—— 经 DEV 夹具模拟 PC 端确认通过
T5=$(token E904PCCONF U6PCCONF001 UNBOUND)
echo "--------------------------------------------------------------------------------"
echo "【⑤ 已绑定（PC确认后）】先 claim，再经 DEV 夹具（非契约端点）模拟 PC 确认通过"
post /wx/v1/bindings/claim '{"inviteCode":"INV-EXAMPLE","claimNonce":"mx-claim-5"}' "$T5"
post /dev-support/v1/binding-fixture '{"action":"PC_CONFIRM","entId":"E904PCCONF","userId":"U6PCCONF001"}' ""
cell "⑤ 已绑定（PC确认后）" "$T5" ""

# ⑥ 解绑后（含二次解绑）
T6=$(token E905UNBIND U5UNBIND001 BOUND)
echo "--------------------------------------------------------------------------------"
echo "【⑥ 解绑后】先 POST /wx/v1/bindings/unbind"
post /wx/v1/bindings/unbind '{"confirmation":"UNBIND","clientNonce":"mx-unbind-6"}' "$T6"
cell "⑥ 解绑后" "$T6" ""
echo "\$ 二次解绑（同一会话再解一次）"
post /wx/v1/bindings/unbind '{"confirmation":"UNBIND","clientNonce":"mx-unbind-6b"}' "$T6"

# ⑦ 停用
T7=$(token E906DISABLE U4DISABLE01 DISABLED)
cell "⑦ 停用" "$T7" ""

# ⑧ 申请过期
T8=$(token E907EXPIRE U3EXPIRE001 UNBOUND)
echo "--------------------------------------------------------------------------------"
echo "【⑧ 申请过期】先 claim，再经 DEV 夹具把申请单立即置为过期"
post /wx/v1/bindings/claim '{"inviteCode":"INV-EXAMPLE","claimNonce":"mx-claim-8"}' "$T8"
post /dev-support/v1/binding-fixture '{"action":"EXPIRE","entId":"E907EXPIRE","userId":"U3EXPIRE001"}' ""
cell "⑧ 申请过期" "$T8" ""

echo "=============================================================="
echo " 裁决 C：解绑错误码矩阵（原始响应体）"
echo "=============================================================="
TU=$(token E908M1 U2M00000001 UNBOUND)
echo "\$ 未绑定身份解绑 ⇒ 期望 403 TSS_NOT_BOUND"
post /wx/v1/bindings/unbind '{"confirmation":"UNBIND","clientNonce":"mx-m1"}' "$TU"
TP=$(token E909M2 U2M00000002 PENDING)
echo "\$ 申请中（PENDING）身份解绑 ⇒ 期望 403 TSS_NOT_BOUND"
post /wx/v1/bindings/unbind '{"confirmation":"UNBIND","clientNonce":"mx-m2"}' "$TP"
TD=$(token E910M3 U2M00000003 DISABLED)
echo "\$ 停用身份解绑 ⇒ 期望 403 TSS_BINDING_DISABLED"
post /wx/v1/bindings/unbind '{"confirmation":"UNBIND","clientNonce":"mx-m3"}' "$TD"
echo "\$ confirmation≠UNBIND 且身份未绑定 ⇒ 期望 400 TSS_INVALID_ARGUMENT（参数检查先于状态检查）"
post /wx/v1/bindings/unbind '{"confirmation":"YES","clientNonce":"mx-m4"}' "$TU"
TB=$(token E911M5 U2M00000005 BOUND)
echo "\$ 正常解绑一次 ⇒ 期望 200"
post /wx/v1/bindings/unbind '{"confirmation":"UNBIND","clientNonce":"mx-m5"}' "$TB"
echo "\$ 二次解绑 ⇒ 期望 403 TSS_NOT_BOUND"
post /wx/v1/bindings/unbind '{"confirmation":"UNBIND","clientNonce":"mx-m5b"}' "$TB"
echo "\$ 他人的 / 不存在的 bindRequestId 查询 API-10 ⇒ 期望 404 TSS_NOT_FOUND"
echo "  （注：RC2 的 UnbindRequest 只有 confirmation/clientNonce，没有 bindRequestId，故该格落在 API-10）"
c -H "Authorization: Bearer $TB" "$BASE/wx/v1/bindings/status?bindRequestId=br_not_exist"; echo
echo "=============================================================="
echo " 矩阵结束"
echo "=============================================================="
