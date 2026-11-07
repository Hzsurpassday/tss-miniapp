#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
契约样例抓取脚本（RC3）：对**真实启动的 MOCK 服务**发起 HTTP 请求，
把 13 个端点的 request/response 原样落盘为 ../contract/api-examples-rc3.json。

用法：
    python3 capture_examples.py                      # 自动拉起临时实例（默认端口 9001）
    TSS_MOCK_BASE=http://1.2.3.4:9000 TSS_ADMIN_TOKEN=<令牌> python3 capture_examples.py

说明：
  - 所有样例均为 MOCK 数据，文件内逐接口标注 dataMode=MOCK 与「不代表真实审批结果」。
  - 管理员令牌不会写入文件，Authorization 头一律记录为占位符 ${TSS_ADMIN_TOKEN}。
"""
import datetime
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.join(HERE, "server.py")
OUT = os.path.abspath(os.path.join(HERE, "..", "contract", "api-examples-rc3.json"))
TOKEN_PLACEHOLDER = "${TSS_ADMIN_TOKEN}"

BASE = os.environ.get("TSS_MOCK_BASE")
ADMIN = os.environ.get("TSS_ADMIN_TOKEN")
_log_lines = []
_proc = None

MOCK_NOTE = ("MOCK 样例：由本机契约 Mock 服务（dataMode=MOCK）真实返回，"
             "业务数据为内置样例，不代表任何真实审批结果。")


# ---------------------------------------------------------------- 服务拉起
def _pump(proc):
    try:
        for line in proc.stderr:
            _log_lines.append(line.rstrip("\n"))
    except Exception:
        pass


def port_busy(port):
    """预检端口占用：避免 Windows SO_REUSEADDR 下"以为拉起成功实则连到旧进程"。"""
    s = socket.socket()
    s.settimeout(0.5)
    try:
        s.connect(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def start_server():
    global BASE, ADMIN, _proc
    port = int(os.environ.get("TSS_MOCK_PORT", "9001"))
    if port_busy(port):
        sys.stderr.write("[FATAL] 端口 %d 已被占用（多半是遗留的旧服务进程）。\n"
                         "        请先结束该进程，或改用 TSS_MOCK_PORT 换端口后重跑。\n" % port)
        return False
    env = dict(os.environ)
    env["TSS_MOCK_PORT"] = str(port)
    env.pop("TSS_ADMIN_TOKEN", None)
    env["TSS_MOCK_SECRET"] = env.get("TSS_MOCK_SECRET", "verify-secret-000222")
    _proc = subprocess.Popen([sys.executable, SERVER], env=env,
                             stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                             text=True, encoding="utf-8", errors="replace")
    threading.Thread(target=_pump, args=(_proc,), daemon=True).start()
    deadline = time.time() + 15
    while time.time() < deadline:
        for line in _log_lines:
            if "临时管理员令牌" in line:
                m = re.search(r"[：:]\s*(\S+)", line)
                if m:
                    ADMIN = m.group(1)
                    BASE = "http://127.0.0.1:%d" % port
                    last = time.time() + 10
                    while time.time() < last:
                        try:
                            urllib.request.urlopen(BASE + "/healthz", timeout=2).read()
                            return True
                        except Exception:
                            time.sleep(0.1)
                    return True
        time.sleep(0.1)
    return False


def stop_server():
    if _proc is not None:
        try:
            _proc.terminate()
            _proc.wait(timeout=5)
        except Exception:
            try:
                _proc.kill()
            except Exception:
                pass


# ---------------------------------------------------------------- HTTP
def call(method, path, token=None, body=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def redact(headers):
    """把真实管理员令牌替换为占位符，避免密钥落进交付物。"""
    out = {}
    for k, v in headers.items():
        if k.lower() == "authorization" and v.startswith("Bearer "):
            raw = v[7:].strip()
            out[k] = "Bearer " + (TOKEN_PLACEHOLDER if raw == ADMIN else raw[:6] + "…(已省略)")
        else:
            out[k] = v
    return out


def case(name, note, method, path, token=None, body=None, query=None):
    """执行一个真实 HTTP 用例并组装样例结构。"""
    full = path + ("?" + urllib.parse.urlencode(query, doseq=True) if query else "")
    st, j = call(method, full, token=token, body=body)
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    request = {"method": method, "path": path}
    if query:
        request["query"] = query
    request["headers"] = redact(headers)
    if body is not None:
        request["body"] = body
    mode = (j.get("data") or {}).get("meta", {}).get("dataMode") if j.get("data") else None
    return {
        "case": name,
        "note": note,
        "dataMode": mode or "MOCK",
        "request": request,
        "response": {"httpStatus": st, "body": j},
    }


# ---------------------------------------------------------------- 抓取主流程
def build():
    doc = {}
    doc["_meta"] = {
        "document": "时空智审 V1.0 /wx/v1 API 真实响应样例",
        "rcVersion": "1.0.0-rc3",
        "schemaVersion": "1.0.0",
        "generatedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "source": "契约 MOCK 服务（server.py）真实 HTTP 响应，非手写静态样例",
        "baseUrl": BASE,
        "dataMode": "MOCK",
        "mockNote": MOCK_NOTE,
        "redaction": "管理员令牌已替换为占位符 " + TOKEN_PLACEHOLDER + "，交付物内不含任何密钥",
        "apis": ["API-%02d" % i for i in range(1, 14)],
        "erpMode": "本文件全部样例均在 dataMode=MOCK 下抓取。dataMode=ERP 且未接入真实 ERP "
                   "只读通道时，5 个业务数据端点一律 503 TSS_ERP_READ_UNAVAILABLE 且 data=null"
                   "（fail-closed，绝不返回空列表）；/healthz 属服务自描述，仍声明 dataMode=ERP。",
        "errorCodeCoverageNote": "20 个权威错误码三方静态对齐（error-codes.csv × server.ERRORS "
                                 "× TssErrorCode.java）；运行时覆盖另见 CHANGELOG §15 覆盖表，"
                                 "分为『默认路径』与『故障注入路径』两类，不可混为一谈。",
        "writeGuardProbe": {
            "path": "/dev-support/v1/tss/write-guard-probe",
            "methods": ["GET", "POST", "PUT", "PATCH", "DELETE"],
            "namespace": "DEV 支持命名空间（与 Java DevBindingFixtureController 同级），"
                         "**不是契约端点**；V1.0 契约 paths 只含 13 条，其中不存在任何写回路径。",
            "meaning": "表达『写回被禁止』，**不是**『已实现审批写回拦截』。"
                       "availableActions 恒为 [\"VIEW\"]，服务端不存在任何真实写回代码路径。",
            "devGateNote": "Java 侧该路径在 DEV 四重门禁下注册，非 DEV 条件表现为 404 属预期；"
                           "Python 侧整体为 DEV Mock，恒可访问。这不是跨端行为差异。",
            "casesRef": "见本文件 DEV-SUPPORT 段（探针不属契约端点，故不挂在 API-01..13 下）",
        },
    }

    # ---- API-01 登录
    st, j = call("POST", "/wx/v1/auth/login", body={"code": "MOCK_BOUND"})
    tok = j["data"]["sessionToken"]
    st, j_new = call("POST", "/wx/v1/auth/login", body={"code": "MOCK_NEW_USER"})
    new_tok = j_new["data"]["sessionToken"]
    st, j_dis = call("POST", "/wx/v1/auth/login", body={"code": "MOCK_DISABLED"})
    dis_tok = j_dis["data"]["sessionToken"]
    # 同企业另一员工 / 跨企业员工 / 空 events 夹具：本轮新增越权三分与空事件覆盖
    bob_tok = call("POST", "/wx/v1/auth/login",
                   body={"code": "MOCK_BOUND_BOB"})[1]["data"]["sessionToken"]
    other_tok = call("POST", "/wx/v1/auth/login",
                     body={"code": "MOCK_OTHER_ENT"})[1]["data"]["sessionToken"]
    evt_tok = call("POST", "/wx/v1/auth/login",
                   body={"code": "MOCK_EMPTY_EVT"})[1]["data"]["sessionToken"]
    doc["API-01"] = {
        "apiId": "API-01", "operationId": "API_01", "summary": "微信登录换取会话令牌",
        "method": "POST", "path": "/wx/v1/auth/login", "auth": "匿名",
        "mockNote": MOCK_NOTE,
        "cases": [
            case("已绑定账号登录", "预置 code=MOCK_BOUND → 直接可用会话",
                 "POST", "/wx/v1/auth/login", body={"code": "MOCK_BOUND"}),
            case("新用户未绑定登录", "任意非预置 code → needBinding=true",
                 "POST", "/wx/v1/auth/login", body={"code": "MOCK_NEW_USER"}),
            case("缺少 code", "参数缺失 → 400 TSS_INVALID_ARGUMENT",
                 "POST", "/wx/v1/auth/login", body={}),
        ],
    }

    # ---- API-02 me
    doc["API-02"] = {
        "apiId": "API-02", "operationId": "API_02", "summary": "当前微信身份与绑定状态",
        "method": "GET", "path": "/wx/v1/me", "auth": "微信会话",
        "mockNote": MOCK_NOTE + "；bindStatus 与 API-10 同源推导，二者互证。",
        "cases": [
            case("已绑定员工", "ME 返回服务端推导的 userId/entId",
                 "GET", "/wx/v1/me", token=tok),
            case("停用员工", "bindStatus=DISABLED",
                 "GET", "/wx/v1/me", token=dis_tok),
            case("无令牌访问", "未携带 Authorization → 401 TSS_UNAUTHORIZED",
                 "GET", "/wx/v1/me"),
        ],
    }

    # ---- API-03 summary
    doc["API-03"] = {
        "apiId": "API-03", "operationId": "API_03", "summary": "待办数量统计",
        "method": "GET", "path": "/wx/v1/todos/summary", "auth": "微信会话 + 已绑定",
        "mockNote": MOCK_NOTE + "；RC3 起 SummaryData 已移除冗余新鲜度字段，"
                    "统一读 meta.freshness（FRESH/DELAYED/UNAVAILABLE）。",
        "cases": [
            case("已绑定统计", "采购入库恒为 0（本 ERP 库未配置审批流程，不伪造）",
                 "GET", "/wx/v1/todos/summary", token=tok),
            case("未绑定访问", "无有效绑定 → 403 TSS_NOT_BOUND",
                 "GET", "/wx/v1/todos/summary", token=new_tok),
        ],
    }

    # ---- API-04 todos
    st, page1 = call("GET", "/wx/v1/todos?limit=2", token=tok)
    cur = page1["data"]["page"]["nextCursor"]
    st, page2 = call("GET", "/wx/v1/todos?limit=2&cursor=" + urllib.parse.quote(cur),
                     token=tok)
    doc["API-04"] = {
        "apiId": "API-04", "operationId": "API_04", "summary": "本人待办列表（筛选+游标分页）",
        "method": "GET", "path": "/wx/v1/todos", "auth": "微信会话 + 已绑定",
        "mockNote": MOCK_NOTE + "；管线：先筛选 → 再稳定排序 → 再游标分页。",
        "cases": [
            case("第一页 limit=2", "排序键 updatedAt DESC, taskNum DESC, approvalId DESC",
                 "GET", "/wx/v1/todos", token=tok, query={"limit": 2}),
            case("游标第二页", "同一查询续拉，nextCursor 由服务端签发",
                 "GET", "/wx/v1/todos", token=tok, query={"limit": 2, "cursor": cur}),
            case("docType 过滤", "PURCHASE_INBOUND 恒为 0 条",
                 "GET", "/wx/v1/todos", token=tok, query={"docType": "PURCHASE_INBOUND"}),
            case("keyword 匹配单号", "对 billNo/title/counterparty 大小写不敏感子串匹配",
                 "GET", "/wx/v1/todos", token=tok, query={"keyword": "2026100001"}),
            case("keyword 无匹配", "空列表且 hasMore=false",
                 "GET", "/wx/v1/todos", token=tok, query={"keyword": "zzz_no_such"}),
            case("改筛选后复用旧游标", "筛选指纹变化 → 400 TSS_INVALID_CURSOR",
                 "GET", "/wx/v1/todos", token=tok,
                 query={"limit": 2, "keyword": "2026100001", "cursor": cur}),
            case("非法 docType", "枚举外取值 → 400 TSS_INVALID_ARGUMENT",
                 "GET", "/wx/v1/todos", token=tok, query={"docType": "XXX"}),
        ],
    }

    # ---- API-05 detail
    doc["API-05"] = {
        "apiId": "API-05", "operationId": "API_05", "summary": "待办详情（含内嵌工作流）",
        "method": "GET", "path": "/wx/v1/todos/{taskNum}/detail", "auth": "微信会话 + 已绑定",
        "mockNote": MOCK_NOTE + "；readOnly 恒 true、availableActions 仅 VIEW、"
                    "isFinalApproved=false、downstreamReceiptStatus=NOT_VERIFIED。",
        "cases": [
            case("销售业务审批详情", "含 definitionVersion / dataVersion / readOnly / events",
                 "GET", "/wx/v1/todos/900002/detail", token=tok),
            case("付款审批详情", "不同 docType 同样输出完整工作流三字段",
                 "GET", "/wx/v1/todos/900004/detail", token=tok),
            case("销售定价审批详情", "未核实金额为 null 且 verified=false",
                 "GET", "/wx/v1/todos/900006/detail", token=tok),
            case("空 events 夹具", "刚同步、工作流尚无流转事件 → events 字段存在且 == []"
                 "（不是 null、不是省略、不是编造的提交事件）",
                 "GET", "/wx/v1/todos/920002/detail", token=evt_tok),
            case("同企业越权看他人单据", "资源存在但不属于本人 → 403 TSS_FORBIDDEN"
                 "（RC3-D1：404 的语义是『不存在或不可见』，此处资源存在，故不用 404）",
                 "GET", "/wx/v1/todos/900002/detail", token=bob_tok),
            case("跨企业越权看他人单据", "归属另一企业 → 403 TSS_ENT_SCOPE_DENIED"
                 "（RC3-D1 补充：跨企业有专用码，不得退回通用 TSS_FORBIDDEN）",
                 "GET", "/wx/v1/todos/900002/detail", token=other_tok),
            case("资源不存在", "全库均无此 taskNum → 404 TSS_NOT_FOUND",
                 "GET", "/wx/v1/todos/999999/detail", token=tok),
            case("契约命名空间内不存在写回路径",
                 "/wx/v1/** 只含契约 13 条，写回路径不存在 → 404 TSS_NOT_FOUND"
                 "（与 Java 侧同形；V1.0 不在契约命名空间内挂 approve/reject 等路径）",
                 "POST", "/wx/v1/todos/900002/approve", token=tok,
                 body={"comment": "同意"}),
        ],
    }

    # ---- API-06 history
    doc["API-06"] = {
        "apiId": "API-06", "operationId": "API_06", "summary": "本人已处理历史",
        "method": "GET", "path": "/wx/v1/history", "auth": "微信会话 + 已绑定",
        "mockNote": MOCK_NOTE + "；resultVerified 恒 false（不伪装真实审批完成）。",
        "cases": [
            case("全量历史", "排序键 processedAt DESC, recordId DESC",
                 "GET", "/wx/v1/history", token=tok),
            case("action 过滤 RETURNED", "仅返回 action=RETURNED 的记录",
                 "GET", "/wx/v1/history", token=tok, query={"action": "RETURNED"}),
            case("日期区间过滤", "from/to 闭区间，按 processedAt 过滤",
                 "GET", "/wx/v1/history", token=tok,
                 query={"from": "2026-10-26", "to": "2026-10-28"}),
            case("非法日期格式", "非 YYYY-MM-DD → 400 TSS_INVALID_ARGUMENT",
                 "GET", "/wx/v1/history", token=tok, query={"from": "20261028"}),
            case("日期区间颠倒", "from > to → 400 TSS_INVALID_ARGUMENT",
                 "GET", "/wx/v1/history", token=tok,
                 query={"from": "2026-10-30", "to": "2026-10-01"}),
        ],
    }

    # ---- API-07 notifications
    doc["API-07"] = {
        "apiId": "API-07", "operationId": "API_07", "summary": "通知列表",
        "method": "GET", "path": "/wx/v1/notifications", "auth": "微信会话 + 已绑定",
        "mockNote": MOCK_NOTE,
        "cases": [
            case("通知列表", "按 createdAt DESC 排序",
                 "GET", "/wx/v1/notifications", token=tok),
        ],
    }

    # ---- API-08 consent
    doc["API-08"] = {
        "apiId": "API-08", "operationId": "API_08", "summary": "记录订阅意愿",
        "method": "POST", "path": "/wx/v1/notifications/consent", "auth": "微信会话 + 已绑定",
        "mockNote": MOCK_NOTE + "；真实订阅消息下发不在本 MOCK 范围内。",
        "cases": [
            case("同意订阅", "consented=true → GRANTED",
                 "POST", "/wx/v1/notifications/consent", token=tok,
                 body={"templateIds": ["wx_tpl_01"], "consented": True}),
            case("参数非法", "缺少 consented → 400 TSS_INVALID_ARGUMENT",
                 "POST", "/wx/v1/notifications/consent", token=tok,
                 body={"templateIds": ["wx_tpl_01"]}),
        ],
    }

    # ---- API-09 claim
    nonce1 = "cap_%d" % int(time.time())
    st, claim = call("POST", "/wx/v1/bindings/claim", token=new_tok,
                     body={"inviteCode": "INV-DEMO-0001", "claimNonce": nonce1})
    br_id = claim["data"]["bindRequestId"]
    doc["API-09"] = {
        "apiId": "API-09", "operationId": "API_09", "summary": "提交绑定申请",
        "method": "POST", "path": "/wx/v1/bindings/claim", "auth": "微信会话",
        "mockNote": MOCK_NOTE + "；V1.0 不在小程序端伪造已完成绑定，恒返回 PENDING_PC_CONFIRM。"
                    "邀请码只受理白名单（INV-PRESET / INV-DEMO-0001 / INV-DEMO-0002 "
                    "以及异常分支专用码 INV-EXPIRED / INV-CONFLICT）。",
        "cases": [
            case("发起绑定申请", "返回 bindRequestId + PENDING_PC_CONFIRM",
                 "POST", "/wx/v1/bindings/claim", token=new_tok,
                 body={"inviteCode": "INV-DEMO-0001", "claimNonce": nonce1}),
            case("幂等键内容冲突", "同一 claimNonce 内容不同 → 409 TSS_IDEMPOTENCY_CONFLICT",
                 "POST", "/wx/v1/bindings/claim", token=new_tok,
                 body={"inviteCode": "INV-DEMO-0002", "claimNonce": nonce1}),
            case("邀请码不在白名单", "RC3-D4：白名单外邀请码 → 400 TSS_INVALID_ARGUMENT",
                 "POST", "/wx/v1/bindings/claim", token=new_tok,
                 body={"inviteCode": "INV-RANDOM-XYZ", "claimNonce": nonce1 + "_wl"}),
            case("邀请码已过期", "INV-EXPIRED → 409 TSS_BIND_INVITE_EXPIRED",
                 "POST", "/wx/v1/bindings/claim", token=new_tok,
                 body={"inviteCode": "INV-EXPIRED", "claimNonce": nonce1 + "_2"}),
            case("已绑定再申请", "BOUND 会话 → 409 TSS_BIND_CONFLICT",
                 "POST", "/wx/v1/bindings/claim", token=tok,
                 body={"inviteCode": "INV-DEMO-0002", "claimNonce": nonce1 + "_3"}),
        ],
    }

    # ---- API-10 status（按真实状态机顺序推进：待确认 → PC 确认 → 解绑）
    api10_cases = [
        case("待 PC 确认", "申请流程未完成时不伪造已绑定，bindRequestId 指向该申请",
             "GET", "/wx/v1/bindings/status", token=new_tok),
    ]
    call("POST", "/__mock/pc-confirm", body={"bindRequestId": br_id})
    api10_cases.append(
        case("PC 确认后已绑定", "经 /__mock/pc-confirm 推进 → BOUND（含脱敏用户与企业名）；"
             "申请流程终结即归档，bindRequestId 归 null",
             "GET", "/wx/v1/bindings/status", token=new_tok))
    api10_cases.append(
        case("PC 确认后按旧 id 精确查", "已归档的申请单不再可见 → 404 TSS_NOT_FOUND",
             "GET", "/wx/v1/bindings/status", token=new_tok,
             query={"bindRequestId": br_id}))
    call("POST", "/wx/v1/bindings/unbind", token=new_tok,
         body={"confirmation": "UNBIND", "clientNonce": "cap_ub_%d" % int(time.time())})
    api10_cases.append(
        case("解绑后", "关系与申请同时回到未绑定，bindRequestId 归 null（与 /wx/v1/me 一致）",
             "GET", "/wx/v1/bindings/status", token=new_tok))
    api10_cases.append(
        case("停用绑定", "DISABLED 与 /wx/v1/me 互相印证",
             "GET", "/wx/v1/bindings/status", token=dis_tok))
    # 已驳回（REJECTED）：补裁 ② —— 保留 bindRequestId，不是"一拍即灭"
    rej_tok = call("POST", "/wx/v1/auth/login",
                   body={"code": "MOCK_NEW_REJ_%d" % int(time.time() * 1000)}
                   )[1]["data"]["sessionToken"]
    st, rej = call("POST", "/wx/v1/bindings/claim", token=rej_tok,
                   body={"inviteCode": "INV-DEMO-0001",
                         "claimNonce": "cap_rej_%d" % int(time.time() * 1000)})
    rej_br = rej["data"]["bindRequestId"]
    call("POST", "/__mock/pc-reject", body={"bindRequestId": rej_br, "reason": "PC 端驳回"})
    api10_cases.append(
        case("已驳回（第 1 次查询）", "REJECTED 且 bindRequestId 非空（驳回结果需用户感知）",
             "GET", "/wx/v1/bindings/status", token=rej_tok))
    api10_cases.append(
        case("已驳回（第 2 次查询）", "仍是 REJECTED 且 id 不变 —— 不是『一拍即灭』",
             "GET", "/wx/v1/bindings/status", token=rej_tok))
    api10_cases.append(
        case("已驳回 按 id 回查", "未被归档 → 200 同一条，不报 404",
             "GET", "/wx/v1/bindings/status", token=rej_tok,
             query={"bindRequestId": rej_br}))
    doc["API-10"] = {
        "apiId": "API-10", "operationId": "API_10", "summary": "查询绑定状态",
        "method": "GET", "path": "/wx/v1/bindings/status", "auth": "微信会话",
        "mockNote": MOCK_NOTE + "；status 表达绑定关系、bindRequestId 表达申请流程，"
                    "与 API-02 同源推导且不互相矛盾。",
        "cases": api10_cases,
    }

    # ---- API-11 unbind（用一个仍处于已绑定的会话）
    bound_tok = call("POST", "/wx/v1/auth/login",
                     body={"code": "MOCK_BOUND_BOB"})[1]["data"]["sessionToken"]
    unbound_tok = call("POST", "/wx/v1/auth/login",
                       body={"code": "MOCK_NEW_%d" % int(time.time() * 1000)}
                       )[1]["data"]["sessionToken"]
    doc["API-11"] = {
        "apiId": "API-11", "operationId": "API_11", "summary": "解除绑定",
        "method": "POST", "path": "/wx/v1/bindings/unbind", "auth": "微信会话",
        "mockNote": MOCK_NOTE + "；未绑定身份调用时按 RC3 裁决返回 403 TSS_NOT_BOUND"
                    "（不用 409：不存在任何绑定即谈不上冲突）。",
        "cases": [
            case("解绑 confirmation 非法", "confirmation≠UNBIND → 400 TSS_INVALID_ARGUMENT",
                 "POST", "/wx/v1/bindings/unbind", token=bound_tok,
                 body={"confirmation": "YES", "clientNonce": "cap_bad"}),
            case("正常解绑", "返回 UNBOUND；解绑后移除该身份名下申请单，me 与 status 同步回落",
                 "POST", "/wx/v1/bindings/unbind", token=bound_tok,
                 body={"confirmation": "UNBIND", "clientNonce": "cap_ok"}),
            case("未绑定身份解绑", "RC3 裁定：403 TSS_NOT_BOUND（非 409 TSS_BIND_CONFLICT）",
                 "POST", "/wx/v1/bindings/unbind", token=unbound_tok,
                 body={"confirmation": "UNBIND", "clientNonce": "cap_notbound"}),
            case("未绑定 + 非法请求体", "RC3-D3：状态门禁先于参数校验 → 仍是 403 TSS_NOT_BOUND",
                 "POST", "/wx/v1/bindings/unbind", token=unbound_tok,
                 body={"confirmation": "WRONG", "clientNonce": "cap_notbound2"}),
            case("停用身份解绑", "RC3-D3：不看请求体，直接 403 TSS_BINDING_DISABLED",
                 "POST", "/wx/v1/bindings/unbind", token=dis_tok,
                 body={"confirmation": "WHATEVER", "clientNonce": "cap_dis"}),
            case("解绑后二次解绑", "已非绑定态 → 403 TSS_NOT_BOUND",
                 "POST", "/wx/v1/bindings/unbind", token=bound_tok,
                 body={"confirmation": "UNBIND", "clientNonce": "cap_twice"}),
        ],
    }

    # ---- API-12 admin bindings
    doc["API-12"] = {
        "apiId": "API-12", "operationId": "API_12", "summary": "管理员查看绑定列表",
        "method": "GET", "path": "/admin/v1/tss/bindings", "auth": "管理员会话",
        "mockNote": MOCK_NOTE + "；Authorization 头中的管理员令牌已脱敏占位。",
        "cases": [
            case("管理员查询绑定", "带审计标记 auditInfo",
                 "GET", "/admin/v1/tss/bindings", token=ADMIN),
            case("微信令牌越权", "凭据有效但非管理员 → 403 TSS_ADMIN_FORBIDDEN",
                 "GET", "/admin/v1/tss/bindings", token=tok),
            case("未提供凭据", "RC3-D7：无 Authorization → 401 TSS_UNAUTHORIZED",
                 "GET", "/admin/v1/tss/bindings"),
        ],
    }

    # ---- API-13 admin mappings
    doc["API-13"] = {
        "apiId": "API-13", "operationId": "API_13", "summary": "管理员查看字段映射",
        "method": "GET", "path": "/admin/v1/tss/mappings", "auth": "管理员会话",
        "mockNote": MOCK_NOTE + "；未核实字段 sourceColumn=null 且 verified=false。",
        "cases": [
            case("管理员查询映射", "含实测 formId 与 validationLevel",
                 "GET", "/admin/v1/tss/mappings", token=ADMIN),
            case("无效令牌", "令牌不在受理表内 → 401 TSS_UNAUTHORIZED",
                 "GET", "/admin/v1/tss/mappings", token="wrong-token"),
            case("未提供凭据", "RC3-D7：无 Authorization → 401 TSS_UNAUTHORIZED"
                 "（三档分工：401=未认证 / 令牌无效，403=令牌有效但角色非 ADMIN；"
                 "403 档需 TSS_ADMIN_TOKENS 配置 USER 角色令牌才能触发）",
                 "GET", "/admin/v1/tss/mappings"),
        ],
    }

    # ---- DEV 支持面（非契约端点，不挂在 API-01..13 下）
    doc["DEV-SUPPORT"] = {
        "section": "DEV 支持命名空间（与 Java DevBindingFixtureController 同级）",
        "note": "以下路径**不是契约端点**，RC3 契约 paths 只含 13 条，"
                "且其中不存在任何写回路径；/wx/v1/** 下不存在的路径一律 404。",
        "apis": [
            {
                "path": "/dev-support/v1/tss/write-guard-probe",
                "methods": ["GET", "POST", "PUT", "PATCH", "DELETE"],
                "summary": "写回冻结探针（验证 V1.0 禁止真实审批写回）",
                "meaning": "表达『写回被禁止』，**不是**『已实现审批写回拦截』；"
                           "availableActions 恒为 [\"VIEW\"]，服务端不存在任何真实写回代码路径。",
                "devGateNote": "Java 侧该路径在 DEV 四重门禁下注册，非 DEV 条件表现为 404 属预期；"
                               "Python 侧整体为 DEV Mock，恒可访问。这不是跨端行为差异。",
                "cases": [
                    case("写回冻结探针", "任意 method 均 403 TSS_WRITE_DISABLED",
                         "POST", "/dev-support/v1/tss/write-guard-probe", token=tok,
                         body={"comment": "同意"}),
                    case("写回冻结探针（GET）", "不挑 method，GET 同样 403",
                         "GET", "/dev-support/v1/tss/write-guard-probe", token=tok),
                    case("契约命名空间内无写回路径",
                         "POST /wx/v1/todos/{taskNum}/approve → 404 TSS_NOT_FOUND（与 Java 同形）",
                         "POST", "/wx/v1/todos/900002/approve", token=tok,
                         body={"comment": "同意"}),
                ],
            },
        ],
    }
    return doc


def main():
    global BASE, ADMIN
    started = False
    if not BASE:
        if not start_server():
            sys.stderr.write("[FATAL] 临时服务启动失败\n")
            for line in _log_lines[:20]:
                sys.stderr.write("        %s\n" % line)
            return 1
        started = True
    if not ADMIN:
        sys.stderr.write("[FATAL] 需要 TSS_ADMIN_TOKEN（显式传入或由临时实例日志获取）\n")
        return 1
    try:
        doc = build()
    finally:
        if started:
            stop_server()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    total = sum(len(v["cases"]) for k, v in doc.items() if k.startswith("API-"))
    print("已写入 %s" % OUT)
    print("覆盖端点 %d 个，用例 %d 条，全为真实 HTTP 响应（dataMode=MOCK）"
          % (len([k for k in doc if k.startswith("API-")]), total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
