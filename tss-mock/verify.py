#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
时空智审 V1.0 契约 MOCK 服务自检脚本（RC3）

覆盖：
  A. RC1 遗留 38 项自检（全部保留，不得删减）
  B. RC3-R1 工作流字段：definitionVersion / readOnly / events（端点级，全部 taskNum + docType）
  C. RC3-R2 筛选与分页五类回归：匹配 / 无匹配 / 非法日期 / 动作筛选 / 跨页
  D. RC3-R3 新鲜度统一：无 dataFreshness、meta.freshness 枚举、无 STALE
  E. RC3-R4 绑定五态一致性：未绑定 / 申请中 / 待 PC 确认 / 已绑定 / 解绑后（+停用）
  F. RC3-R5 管理员令牌安全：无硬编码密钥、令牌不入 HTTP、错误令牌 403

用法：
    python3 verify.py                          # 自动拉起临时服务（端口取 TSS_MOCK_PORT，默认 9001）
    TSS_MOCK_BASE=http://1.2.3.4:9000 TSS_ADMIN_TOKEN=<启动日志里的临时令牌> python3 verify.py
    TSS_MOCK_BASE=... TSS_MOCK_FAILURE=wechat python3 verify.py   # 只跑故障注入分支

退出码：0=全部通过，1=存在失败。
"""
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

BASE = os.environ.get("TSS_MOCK_BASE")
ADMIN = os.environ.get("TSS_ADMIN_TOKEN")
# TSS_MOCK_DUMP_RAW=1 时打印关键场景的原始 HTTP 响应体（供交付报告取证）
DUMP_RAW = os.environ.get("TSS_MOCK_DUMP_RAW", "0") == "1"
HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.join(HERE, "server.py")

_proc = None
_extra_procs = []        # 模式级验证实例（如 ERP 模式），主流程结束时一并回收
_admin_log_lines = []
_results = []
_found_admin = {"value": None}

# ---------------------------------------------------------------- 静态安全扫描用
# 拼接构造，避免本文件自身被正则命中
_SECREFIX = "adm" + "_"
_HARDCODED_RE = re.compile(_SECREFIX + r"[A-Za-z0-9_-]{8,}")


def call(method, path, token=None, body=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, _safe_json(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return e.code, _safe_json(e.read().decode('utf-8'))


def call_raw(method, path, token=None, body=None):
    """返回 (status, 原始文本)，用于做「响应体不含某字段」的断言。"""
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")


def _safe_json(raw):
    """安全解析响应体：**绝不因解析失败抛异常终止整轮自检**。

    交付制品里出现 traceback 的代价远大于一条 FAIL：对方只会看到崩溃，
    看不到合计行，进而判定交付物跑不起来。因此解析失败时返回一个哨兵 dict，
    调用方的断言会正常记为 FAIL，并由 `check_json_ok()` 把原始 body 打出来。
    """
    try:
        j = json.loads(raw)
        if isinstance(j, dict):
            return j
    except Exception:
        pass
    return {"code": "__NON_JSON_RESPONSE__", "message": "响应体不是合法 JSON",
            "data": None, "_raw": (raw or "")[:400]}


def check_json_ok(where, j):
    """对 `_safe_json` 的结果做一次显式断言：非 JSON 时记 FAIL 并打出原始 body。"""
    if j.get("code") == "__NON_JSON_RESPONSE__":
        check("%s：响应体不是合法 JSON（已按失败登记，未中断自检）" % where, False,
              "原始 body=%r" % j.get("_raw"))
        return False
    return True


def run_group(fn):
    """执行一个自检分组；组内异常只登记为 FAIL，不中断整轮。

    加固动机：曾出现最后一组崩溃导致连"合计行"都没打印的情况。
    自检脚本本身也必须 fail-soft —— 任何一组炸了都要留下可诊断的 FAIL 记录。
    """
    name = getattr(fn, "__name__", str(fn))
    try:
        fn()
    except Exception as e:
        import traceback
        check("分组 %s 异常终止（已登记为失败，未中断整轮）" % name, False,
              "%s: %s" % (type(e).__name__, e))
        sys.stderr.write("[TRACEBACK] %s\n%s\n" % (name, traceback.format_exc()))


def check(name, cond, detail=""):
    _results.append((name, bool(cond), detail))
    print("%-6s %-56s %s" % ("PASS" if cond else "FAIL", name, detail if not cond else ""))
    return cond


def expect_err(name, r, code, http_status=None):
    st, j = r
    ok = j.get("code") == code and j.get("data") is None
    if http_status:
        ok = ok and st == http_status
    return check(name, ok, "期望 %s/%s，实际 %s/%s" % (http_status, code, st, j.get("code")))


def login(code):
    st, j = call("POST", "/wx/v1/auth/login", body={"code": code})
    assert st == 200 and j["code"] == "SUCCESS", (st, j)
    return j["data"]["sessionToken"]


def uniq(tag):
    return "%s_%s_%d" % (tag, os.getpid(), int(time.time() * 1000) % 1000000)


# ================================================================ 服务拉起
def port_busy(port):
    """预检端口占用。

    Windows 上 SO_REUSEADDR 允许多个进程绑定同一端口，若不预检，
    "旧服务仍在监听" 会让拉起分支误以为自己启动成功 —— 表现为随机 403/401，
    极具误导性。这里显式拦截并给出可操作提示。
    """
    s = socket.socket()
    s.settimeout(0.5)
    try:
        s.connect(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def start_local_server():
    """未指定外部 BASE 时，在本地拉起一个临时实例并抓取临时管理员令牌。"""
    global BASE, ADMIN, _proc
    port = int(os.environ.get("TSS_MOCK_PORT", "9001"))
    if port_busy(port):
        sys.stderr.write("[FATAL] 端口 %d 已被占用（多半是遗留的旧服务进程）。\n"
                         "        请先结束该进程，或改用 TSS_MOCK_PORT 换端口后重跑。\n" % port)
        return False
    env = dict(os.environ)
    env["TSS_MOCK_PORT"] = str(port)
    env.pop("TSS_ADMIN_TOKEN", None)          # 强制走「临时随机令牌」路径
    env["TSS_MOCK_SECRET"] = env.get("TSS_MOCK_SECRET", "verify-secret-000111")
    _proc = subprocess.Popen([sys.executable, SERVER], env=env,
                             stdout=subprocess.DEVNULL,
                             stderr=subprocess.PIPE, text=True,
                             encoding="utf-8", errors="replace")
    t = threading.Thread(target=_pump_stderr, args=(_proc,), daemon=True)
    t.start()
    deadline = time.time() + 15
    while time.time() < deadline:
        for line in _admin_log_lines:
            m = re.search(r"管理员令牌[^：]*[：:]\s*(\S+)", line)
            if m and "临时" in line:
                ADMIN = m.group(1)
                BASE = "http://127.0.0.1:%d" % port
                # 等待端口真正可服务
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


def _pump_stderr(proc):
    try:
        for line in proc.stderr:
            _admin_log_lines.append(line.rstrip("\n"))
    except Exception:
        pass


def stop_local_server():
    for p in [_proc] + list(_extra_procs):
        if p is None:
            continue
        try:
            p.terminate()
            p.wait(timeout=5)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass


# ================================================================ 新增：RC3 工作流字段
WF_REQUIRED_EVENT_KEYS = {"eventId", "nodeId", "actionType", "menuName", "operatorDisplay",
                          "occurredAt", "outcome", "serialNum", "comment"}


def t_workflow_fields():
    print("\n-- RC3-R1 工作流字段（端点级覆盖）--")
    tok = login("MOCK_BOUND")
    st, j = call("GET", "/wx/v1/todos?limit=100", token=tok)
    task_nums = [i["taskNum"] for i in j["data"]["items"]]
    check("R1 取样：取到 MOCK_BOUND 全部待办", len(task_nums) == 3, str(task_nums))

    bad = []
    for tn in task_nums:
        st2, j2 = call("GET", "/wx/v1/todos/%s/detail" % tn, token=tok)
        if st2 != 200:
            bad.append("%s:HTTP%s" % (tn, st2))
            continue
        wf = j2["data"]["workflow"]
        if not isinstance(wf.get("definitionVersion"), str) or not wf["definitionVersion"]:
            bad.append("%s:definitionVersion" % tn)
        if wf.get("readOnly") is not True:
            bad.append("%s:readOnly" % tn)
        if not isinstance(wf.get("events"), list):
            bad.append("%s:events 缺失或非数组" % tn)
        for ev in wf.get("events", []):
            missing = WF_REQUIRED_EVENT_KEYS - set(ev.keys())
            if missing:
                bad.append("%s:event 缺 %s" % (tn, sorted(missing)))
        if "workflowVersion" in wf:
            bad.append("%s:残留 workflowVersion" % tn)
    check("R1 全部 taskNum 详情均含 definitionVersion/readOnly/events", not bad, "; ".join(bad))

    st, j = call("GET", "/wx/v1/todos/900006/detail", token=tok)
    wf = j["data"]["workflow"]
    check("R1 events 必存在且为数组（定价单据仅有已证实的提交事件）",
          isinstance(wf.get("events"), list) and len(wf["events"]) == 1
          and wf["events"][0]["actionType"] == "STARTED", str(wf.get("events")))
    check("R1 events 元素 key 与契约一致（不多不少 9 项）",
          all(set(e.keys()) == WF_REQUIRED_EVENT_KEYS for e in wf["events"]),
          str(sorted(wf["events"][0].keys()) if wf["events"] else "EMPTY"))
    check("R1 definitionVersion 与 dataVersion 分字段且不同语义",
          isinstance(wf.get("definitionVersion"), str)
          and isinstance(wf.get("dataVersion"), str)
          and wf["definitionVersion"] != wf["dataVersion"],
          "definitionVersion=%s dataVersion=%s" % (wf.get("definitionVersion"),
                                                   wf.get("dataVersion")))
    st, _ = call_raw("GET", "/wx/v1/todos/900002/detail", token=tok)
    _, raw = call_raw("GET", "/wx/v1/todos/900002/detail", token=tok)
    check("R1 响应体不再出现 workflowVersion", "workflowVersion" not in raw)

    # 跨员工 / 跨 docType 覆盖
    bob = login("MOCK_BOUND_BOB")
    st, j = call("GET", "/wx/v1/todos?limit=100", token=bob)
    items = j["data"]["items"]
    ok = True
    for i in items:
        st2, j2 = call("GET", "/wx/v1/todos/%s/detail" % i["taskNum"], token=bob)
        if st2 != 200:
            ok = False
            continue
        wf = j2["data"]["workflow"]
        ok = ok and isinstance(wf.get("definitionVersion"), str) \
            and wf.get("readOnly") is True and isinstance(wf.get("events"), list)
    check("R1 另一员工详情同样具备工作流三字段", ok and len(items) >= 1, str(len(items)))

    st, j = call("GET", "/wx/v1/todos/900004/detail", token=tok)
    evs = j["data"]["workflow"]["events"]
    check("R1 events 按 occurredAt 升序且字段齐备",
          len(evs) >= 1
          and [e["occurredAt"] for e in evs] == sorted(e["occurredAt"] for e in evs)
          and all(WF_REQUIRED_EVENT_KEYS <= set(e.keys()) for e in evs), str(len(evs)))


# ================================================================ 新增：RC3 筛选与分页
def t_filters():
    print("\n-- RC3-R2 筛选与分页（五类回归）--")
    tok = login("MOCK_BOUND")
    kw_tok = login("MOCK_KEYWORD")

    # ① 匹配
    st, j = call("GET", "/wx/v1/todos?keyword=" + urllib.parse.quote("2026100001"), token=tok)
    items = j["data"]["items"]
    check("R2-匹配 keyword=2026100001 → 命中 1 条单号",
          len(items) == 1 and items[0]["billNo"] == "2026100001", str(len(items)))

    st, j = call("GET", "/wx/v1/todos?keyword=" + urllib.parse.quote("示例"),
                 token=tok)
    hit_titles = {i["title"] for i in j["data"]["items"]}
    check("R2-匹配 keyword=示例 → 命中往来单位/标题多条",
          len(j["data"]["items"]) == 2, str(sorted(hit_titles)))

    st, j = call("GET", "/wx/v1/todos?keyword=" + urllib.parse.quote("acme"), token=kw_tok)
    items = j["data"]["items"]
    check("R2-匹配 keyword 大小写不敏感（acme → Acme Steel Ltd）",
          len(items) == 1 and items[0]["counterparty"] == "Acme Steel Ltd", str(len(items)))
    st, j = call("GET", "/wx/v1/todos?keyword=" + urllib.parse.quote("ACME"), token=kw_tok)
    check("R2-匹配 keyword 大写同样命中",
          len(j["data"]["items"]) == 1)

    # ② 无匹配
    st, j = call("GET", "/wx/v1/todos?keyword=" + urllib.parse.quote("zzz_no_such_key"),
                 token=tok)
    check("R2-无匹配 → 空列表且 hasMore=false",
          st == 200 and j["data"]["items"] == []
          and j["data"]["page"]["hasMore"] is False
          and j["data"]["page"]["nextCursor"] is None, str(j["data"]["page"]))

    # ③ 非法日期
    expect_err("R2-非法日期 from=2026-13-01 → 400 TSS_INVALID_ARGUMENT",
               call("GET", "/wx/v1/history?from=2026-13-01", token=tok),
               "TSS_INVALID_ARGUMENT", 400)
    expect_err("R2-非法日期格式 from=20261028 → 400 TSS_INVALID_ARGUMENT",
               call("GET", "/wx/v1/history?from=20261028", token=tok),
               "TSS_INVALID_ARGUMENT", 400)
    expect_err("R2-非法日期 to=2026/10/28 → 400 TSS_INVALID_ARGUMENT",
               call("GET", "/wx/v1/history?to=" + urllib.parse.quote("2026/10/28"),
                    token=tok),
               "TSS_INVALID_ARGUMENT", 400)
    expect_err("R2-日期区间颠倒 from>to → 400 TSS_INVALID_ARGUMENT",
               call("GET", "/wx/v1/history?from=2026-10-30&to=2026-10-01", token=tok),
               "TSS_INVALID_ARGUMENT", 400)

    # 合法区间（含边界）
    st, j = call("GET", "/wx/v1/history?from=2026-10-26&to=2026-10-28", token=tok)
    check("R2-合法闭区间 [2026-10-26, 2026-10-28] → 3 条",
          len(j["data"]["items"]) == 3, str(len(j["data"]["items"])))
    st, j = call("GET", "/wx/v1/history?from=2026-10-27&to=2026-10-28", token=tok)
    days = sorted(i["processedAt"][:10] for i in j["data"]["items"])
    check("R2-区间边界包含端点且不越界", days == ["2026-10-27", "2026-10-28"], str(days))

    # ④ 动作筛选
    st, j = call("GET", "/wx/v1/history?action=APPROVED", token=tok)
    items = j["data"]["items"]
    check("R2-动作筛选 action=APPROVED → 仅 APPROVED 记录",
          len(items) >= 1 and all(i["action"] == "APPROVED" for i in items), str(len(items)))
    st, j = call("GET", "/wx/v1/history?action=RETURNED", token=tok)
    items = j["data"]["items"]
    check("R2-动作筛选 action=RETURNED → 仅 RETURNED 记录",
          len(items) == 1 and items[0]["recordId"] == "r_2", str(len(items)))
    st, j = call("GET", "/wx/v1/history?action=REJECTED", token=tok)
    check("R2-动作筛选 无命中 action=REJECTED → 空列表", j["data"]["items"] == [])
    expect_err("R2-非法 action → 400 TSS_INVALID_ARGUMENT",
               call("GET", "/wx/v1/history?action=DELETE_ALL", token=tok),
               "TSS_INVALID_ARGUMENT", 400)

    # ⑤ 跨页 + 游标绑定筛选
    st, j = call("GET", "/wx/v1/todos?limit=1", token=tok)
    page1 = j["data"]
    cur = page1["page"]["nextCursor"]
    check("R2-跨页 page1：1 条且 hasMore=true",
          len(page1["items"]) == 1 and page1["page"]["hasMore"] is True)
    seen = [page1["items"][0]["taskNum"]]
    guard = 0
    while True:
        st2, j2 = call("GET", "/wx/v1/todos?limit=1&cursor=" + urllib.parse.quote(cur),
                       token=tok)
        seen += [i["taskNum"] for i in j2["data"]["items"]]
        cur = j2["data"]["page"]["nextCursor"]
        guard += 1
        if not cur or guard > 10:
            break
    check("R2-跨页 逐页取完且无重复", seen == ["900002", "900004", "900006"], str(seen))

    st, j = call("GET", "/wx/v1/todos?limit=1", token=tok)
    cur1 = j["data"]["page"]["nextCursor"]
    expect_err("R2-跨页 改筛选后复用旧游标 → 400 TSS_INVALID_CURSOR",
               call("GET", "/wx/v1/todos?limit=1&docType=PAYMENT&cursor="
                    + urllib.parse.quote(cur1), token=tok),
               "TSS_INVALID_CURSOR", 400)
    expect_err("R2-跨页 加 keyword 后复用旧游标 → 400 TSS_INVALID_CURSOR",
               call("GET", "/wx/v1/todos?limit=1&keyword=2026100001&cursor="
                    + urllib.parse.quote(cur1), token=tok),
               "TSS_INVALID_CURSOR", 400)
    st, j = call("GET", "/wx/v1/todos?limit=1&cursor=" + urllib.parse.quote(cur1),
                 token=tok)
    check("R2-跨页 同一查询后续分页保留原筛选语义", st == 200
          and j["data"]["items"][0]["taskNum"] == "900004",
          str([i["taskNum"] for i in j["data"]["items"]]))

    # 历史：跨页 + 游标绑定 action
    st, j = call("GET", "/wx/v1/history?limit=1", token=tok)
    hcur = j["data"]["page"]["nextCursor"]
    expect_err("R2-历史 改 action 后复用旧游标 → 400 TSS_INVALID_CURSOR",
               call("GET", "/wx/v1/history?limit=1&action=APPROVED&cursor="
                    + urllib.parse.quote(hcur), token=tok),
               "TSS_INVALID_CURSOR", 400)

    # 排序键校验
    st, j = call("GET", "/wx/v1/todos?limit=100", token=tok)
    items = j["data"]["items"]
    keys = [(i["updatedAt"], i["taskNum"], i["approvalId"]) for i in items]
    check("R2-待办排序键 updatedAt DESC, taskNum DESC, approvalId DESC",
          keys == sorted(keys, reverse=True), str(keys))
    st, j = call("GET", "/wx/v1/history?limit=100", token=tok)
    items = j["data"]["items"]
    hkeys = [(i["processedAt"], i["recordId"]) for i in items]
    check("R2-历史排序键 processedAt DESC, recordId DESC",
          hkeys == sorted(hkeys, reverse=True), str(hkeys))

    # keyword 超长保护（契约 maxLength=100）
    expect_err("R2-keyword 超长 → 400 TSS_INVALID_ARGUMENT",
               call("GET", "/wx/v1/todos?keyword=" + "x" * 101, token=tok),
               "TSS_INVALID_ARGUMENT", 400)


# ================================================================ 新增：RC3 新鲜度
def _set_freshness(v):
    return call("POST", "/__mock/freshness", body={"value": v})


def t_freshness():
    print("\n-- RC3-R3 新鲜度统一 --")
    tok = login("MOCK_BOUND")
    st, raw = call_raw("GET", "/wx/v1/todos/summary", token=tok)
    check("R3 summary 响应体不再出现 dataFreshness", "dataFreshness" not in raw)
    check("R3 summary 响应体不再出现已废弃枚举 STALE", "STALE" not in raw)

    st, j = call("GET", "/wx/v1/todos/summary", token=tok)
    fr = j["data"]["meta"]["freshness"]
    check("R3 meta.freshness 存在且属于 FRESH/DELAYED/UNAVAILABLE",
          fr in ("FRESH", "DELAYED", "UNAVAILABLE"), str(fr))
    _, raw = call_raw("GET", "/wx/v1/todos", token=tok)
    check("R3 todos 响应体无第二个新鲜度字段", "dataFreshness" not in raw)

    for v in ("DELAYED", "UNAVAILABLE"):
        st, j = _set_freshness(v)
        check("R3 切换到 %s 成功" % v, st == 200 and j["data"]["freshness"] == v, str(j))

        st, j2 = call("GET", "/wx/v1/todos/summary", token=tok)
        st2, j3 = call("GET", "/wx/v1/todos", token=tok)
        st3, j4 = call("GET", "/wx/v1/history", token=tok)
        st4, j5 = call("GET", "/wx/v1/todos/900002/detail", token=tok)
        check("R3 %s 全接口一致（summary/todos/history/detail）" % v,
              j2["data"]["meta"]["freshness"] == v
              and j3["data"]["meta"]["freshness"] == v
              and j4["data"]["meta"]["freshness"] == v
              and j5["data"]["meta"]["freshness"] == v
              and st == 200 and st2 == 200 and st3 == 200 and st4 == 200)

    st, j = call("POST", "/__mock/freshness", body={"value": "STALE"})
    expect_err("R3 非法新鲜度 STALE → 400 TSS_INVALID_ARGUMENT", (st, j),
               "TSS_INVALID_ARGUMENT", 400)
    _set_freshness("FRESH")
    st, j = call("GET", "/wx/v1/todos/summary", token=tok)
    check("R3 恢复 FRESH 生效", j["data"]["meta"]["freshness"] == "FRESH")


# ================================================================ 新增：RC3 绑定一致性
def binding_pair(tok):
    """取 (me.bindStatus, status) 三元组用于一致性断言。"""
    st1, j1 = call("GET", "/wx/v1/me", token=tok)
    st2, j2 = call("GET", "/wx/v1/bindings/status", token=tok)
    return j1["data"], j2["data"], (st1, st2)


# 每次观测到的绑定视图都会被记录，末尾统一做 INV-5 强校验
_BINDING_OBS = []

# INV-5：bindRequestId 非 null 仅当 status 属于「尚未终结」的申请状态
_KEEP_REQUEST_STATUSES = ("PENDING_PC_CONFIRM", "EXPIRED", "REJECTED")


def inv_ok(name, me, st_data):
    """INV-1..INV-5 五条不变量断言。"""
    bs, bd = me["bindStatus"], st_data
    _BINDING_OBS.append((bs, bd))
    inv1 = (bs == "BOUND") == (bd["status"] == "BOUND") \
        and (bd["status"] == "BOUND") == bool(bd["erpUserMasked"])
    inv2 = (bs == "DISABLED") == (bd["status"] == "DISABLED")
    inv3 = bd["status"] in ("UNBOUND", "PENDING_PC_CONFIRM", "EXPIRED", "REJECTED") \
        and bs in ("UNBOUND", "PENDING") and bd["erpUserMasked"] is None
    inv4 = (bd["bindRequestId"] is None) or isinstance(bd["bindRequestId"], str)
    inv5 = (bd["bindRequestId"] is not None) \
        == (bd["status"] in _KEEP_REQUEST_STATUSES)
    applies = inv1 and inv2 and inv4 and inv5
    if bd["status"] in ("UNBOUND", "PENDING_PC_CONFIRM", "EXPIRED", "REJECTED"):
        applies = applies and inv3
    return check(name, applies,
                 "me.bindStatus=%s status=%s brId=%s masked=%s"
                 % (bs, bd["status"], bd["bindRequestId"], bd["erpUserMasked"]))


def t_binding_consistency():
    print("\n-- RC3-R4 绑定状态一致性（五态 + 停用）--")

    # ① 未绑定
    t1 = login("MOCK_NEW_%s" % uniq("u"))
    me, bd, sts = binding_pair(t1)
    check("R4-未绑定 me.bindStatus=UNBOUND", me["bindStatus"] == "UNBOUND")
    check("R4-未绑定 status=UNBOUND 且 bindRequestId 为 null",
          bd["status"] == "UNBOUND" and bd["bindRequestId"] is None
          and bd["erpUserMasked"] is None, str(bd))
    inv_ok("R4-未绑定 不变量 INV-1..5", me, bd)

    # ② 申请中（预置「待 PC 确认」账号：登录即已存在申请单）
    t2 = login("MOCK_PENDING")
    me, bd, sts = binding_pair(t2)
    check("R4-申请中 me.bindStatus=PENDING", me["bindStatus"] == "PENDING", str(me))
    check("R4-申请中 status=PENDING_PC_CONFIRM 且 bindRequestId 非空",
          bd["status"] == "PENDING_PC_CONFIRM" and bd["bindRequestId"] is not None
          and bd["erpUserMasked"] is None, str(bd))
    inv_ok("R4-申请中 不变量 INV-1..5", me, bd)
    expect_err("R4-申请中 不允许访问待办（未绑定）→ 403 TSS_NOT_BOUND",
               call("GET", "/wx/v1/todos", token=t2), "TSS_NOT_BOUND", 403)

    # ③ 待 PC 确认（经 API-09 发起）
    t3 = login("MOCK_NEW_%s" % uniq("pc"))
    st, j = call("POST", "/wx/v1/bindings/claim", token=t3,
                 body={"inviteCode": "INV-DEMO-0001", "claimNonce": uniq("nonce")})
    check("R4-待PC确认 API-09 返回 PENDING_PC_CONFIRM",
          st == 200 and j["data"]["status"] == "PENDING_PC_CONFIRM")
    br = j["data"]["bindRequestId"]
    me, bd, sts = binding_pair(t3)
    check("R4-待PC确认 me=PENDING 与 status=PENDING_PC_CONFIRM 互证",
          me["bindStatus"] == "PENDING" and bd["status"] == "PENDING_PC_CONFIRM"
          and bd["bindRequestId"] == br, "%s/%s" % (me["bindStatus"], bd["status"]))
    inv_ok("R4-待PC确认 不变量 INV-1..5", me, bd)

    # 重复 claim：返回同一申请单，不产生第二份互相矛盾的申请
    st, j = call("POST", "/wx/v1/bindings/claim", token=t3,
                 body={"inviteCode": "INV-DEMO-0001", "claimNonce": uniq("nonce2")})
    check("R4-待PC确认 重复提交不产生第二份申请单",
          st == 200 and j["data"]["bindRequestId"] == br, str(j))

    # PC 确认 → ④ 已绑定
    st, j = call("POST", "/__mock/pc-confirm", body={"bindRequestId": br})
    me, bd, sts = binding_pair(t3)
    check("R4-已绑定 me=BOUND 且 status=BOUND",
          me["bindStatus"] == "BOUND" and bd["status"] == "BOUND", str(bd))
    check("R4-已绑定 status 返回脱敏用户与企业名",
          "*" in (bd["erpUserMasked"] or "") and bd["entDisplayName"] == "示例企业",
          str(bd))
    inv_ok("R4-已绑定 不变量 INV-1..5", me, bd)
    check("R4-已绑定(PC确认路径) bindRequestId 归 null（申请流程已终结归档）",
          bd["bindRequestId"] is None, str(bd))
    expect_err("R4-已绑定后按旧申请单 id 精确查 → 404 TSS_NOT_FOUND（已归档）",
               call("GET", "/wx/v1/bindings/status?bindRequestId=" + br, token=t3),
               "TSS_NOT_FOUND", 404)

    # ⑤ 解绑后
    st, j = call("POST", "/wx/v1/bindings/unbind", token=t3,
                 body={"confirmation": "UNBIND", "clientNonce": uniq("ub")})
    check("R4-解绑 API-11 返回 UNBOUND", st == 200 and j["data"]["bindStatus"] == "UNBOUND")
    me, bd, sts = binding_pair(t3)
    check("R4-解绑后 me=UNBOUND 且 status=UNBOUND 且 bindRequestId 归 null",
          me["bindStatus"] == "UNBOUND" and bd["status"] == "UNBOUND"
          and bd["bindRequestId"] is None and bd["erpUserMasked"] is None, str(bd))
    inv_ok("R4-解绑后 不变量 INV-1..5", me, bd)
    expect_err("R4-解绑后 旧申请单不再可见 → 404 TSS_NOT_FOUND",
               call("GET", "/wx/v1/bindings/status?bindRequestId=" + br, token=t3),
               "TSS_NOT_FOUND", 404)
    expect_err("R4-解绑后 访问待办 → 403 TSS_NOT_BOUND",
               call("GET", "/wx/v1/todos", token=t3), "TSS_NOT_BOUND", 403)

    # 预置已绑定账号（无申请单路径）
    t6 = login("MOCK_BOUND")
    me, bd, sts = binding_pair(t6)
    check("R4-预置已绑定 me=BOUND 且 status=BOUND（无申请单）",
          me["bindStatus"] == "BOUND" and bd["status"] == "BOUND"
          and bd["bindRequestId"] is None, str(bd))
    inv_ok("R4-预置已绑定 不变量 INV-1..5", me, bd)

    # 停用账号
    t7 = login("MOCK_DISABLED")
    me, bd, sts = binding_pair(t7)
    check("R4-停用 me=DISABLED 且 status=DISABLED 互相印证",
          me["bindStatus"] == "DISABLED" and bd["status"] == "DISABLED"
          and bd["erpUserMasked"] is None, str(bd))
    inv_ok("R4-停用 不变量 INV-1..5", me, bd)

    # 申请过期分支
    t8 = login("MOCK_PENDING")
    st, j = call("GET", "/wx/v1/bindings/status", token=t8)
    br8 = j["data"]["bindRequestId"]
    call("POST", "/__mock/expire", body={"bindRequestId": br8})
    me, bd, sts = binding_pair(t8)
    check("R4-申请过期 status=EXPIRED 且 me 回落为 UNBOUND",
          bd["status"] == "EXPIRED" and me["bindStatus"] == "UNBOUND"
          and bd["erpUserMasked"] is None, "%s/%s" % (me["bindStatus"], bd["status"]))
    inv_ok("R4-申请过期 不变量 INV-1..5", me, bd)
    expect_err("R4-申请过期后再 PC 确认 → 409 TSS_BIND_INVITE_EXPIRED",
               call("POST", "/__mock/pc-confirm", body={"bindRequestId": br8}),
               "TSS_BIND_INVITE_EXPIRED", 409)

    # 完全无会话情况下的兜底
    expect_err("R4-无令牌查询绑定状态 → 401 TSS_UNAUTHORIZED",
               call("GET", "/wx/v1/bindings/status"), "TSS_UNAUTHORIZED", 401)

    # 强不变量总校验：对本次分组内所有观测点统一验证 INV-5
    bad = [(bs, bd["status"], bd["bindRequestId"]) for bs, bd in _BINDING_OBS
           if (bd["bindRequestId"] is not None)
           != (bd["status"] in _KEEP_REQUEST_STATUSES)]
    check("R4-强不变量 INV-5（%d 组观测）：bindRequestId 非空 ⟺ status∈{PENDING_PC_CONFIRM, EXPIRED, REJECTED}"
          % len(_BINDING_OBS), not bad, str(bad))

    # ⑧ 已驳回（REJECTED）：补裁 ② —— 必须能被真实推进到，且 id 不归档
    t9 = login("MOCK_NEW_%s" % uniq("rej"))
    st, j = call("POST", "/wx/v1/bindings/claim", token=t9,
                 body={"inviteCode": "INV-DEMO-0001", "claimNonce": uniq("rejn")})
    br9 = j["data"]["bindRequestId"]
    st, j = call("POST", "/__mock/pc-reject", body={"bindRequestId": br9,
                                                    "reason": "PC 端驳回"})
    check("R8-REJECTED /__mock/pc-reject 可推进到 REJECTED",
          st == 200 and j["data"]["status"] == "REJECTED", str(j))
    # 判据 1：连续两次 status 都是 REJECTED 且 bindRequestId 非空（不是"一拍即灭"）
    st1, j1 = call("GET", "/wx/v1/bindings/status", token=t9)
    st2, j2 = call("GET", "/wx/v1/bindings/status", token=t9)
    check("R8-REJECTED 连续两次 status 均为 REJECTED 且 bindRequestId 非空",
          st1 == 200 and st2 == 200
          and j1["data"]["status"] == "REJECTED" and j2["data"]["status"] == "REJECTED"
          and j1["data"]["bindRequestId"] == br9 and j2["data"]["bindRequestId"] == br9,
          "%s / %s" % (j1["data"], j2["data"]))
    # 判据 2：me.bindStatus 回落 UNBOUND
    st, me9 = call("GET", "/wx/v1/me", token=t9)
    check("R8-REJECTED me.bindStatus=UNBOUND（驳回即不存在绑定关系）",
          me9["data"]["bindStatus"] == "UNBOUND", str(me9["data"]))
    inv_ok("R4-已驳回 不变量 INV-1..5", me9["data"], j2["data"])
    # 判据 3：用该 bindRequestId 回查仍返回同一条，不报 404
    st, j3 = call("GET", "/wx/v1/bindings/status?bindRequestId=" + br9, token=t9)
    check("R8-REJECTED 按 id 回查仍可见（未被归档）",
          st == 200 and j3["data"]["status"] == "REJECTED"
          and j3["data"]["bindRequestId"] == br9, str(j3))
    # 驳回后不得仍可读取业务数据
    expect_err("R8-REJECTED 后访问业务端点 → 403 TSS_NOT_BOUND",
               call("GET", "/wx/v1/todos", token=t9), "TSS_NOT_BOUND", 403)
    expect_err("R8-REJECTED 后再 PC 确认该申请单 → 409 TSS_BIND_CONFLICT",
               call("POST", "/__mock/pc-confirm", body={"bindRequestId": br9}),
               "TSS_BIND_CONFLICT", 409)
    expect_err("R8-REJECTED /__mock/pc-reject 传不存在的 id → 404 TSS_NOT_FOUND",
               call("POST", "/__mock/pc-reject", body={"bindRequestId": "br_nope"}),
               "TSS_NOT_FOUND", 404)
    if DUMP_RAW:
        # 用一条全新会话完整演示一遍，避免打印到的是"已被上面用例消费过"的第二次请求
        td = login("MOCK_NEW_%s" % uniq("rejd"))
        st, jd = call("POST", "/wx/v1/bindings/claim", token=td,
                      body={"inviteCode": "INV-DEMO-0001", "claimNonce": uniq("rejdn")})
        brd = jd["data"]["bindRequestId"]
        print("\n  [RAW] REJECTED 三条复测判据（全新会话完整演示）：")
        dump_raw("pc-reject 推进", "POST", "/__mock/pc-reject",
                 body={"bindRequestId": brd, "reason": "PC 端驳回"})
        dump_raw("判据1 第 1 次 status", "GET", "/wx/v1/bindings/status", token=td)
        dump_raw("判据1 第 2 次 status", "GET", "/wx/v1/bindings/status", token=td)
        dump_raw("判据2 me", "GET", "/wx/v1/me", token=td)
        dump_raw("判据3 按 id 回查", "GET",
                 "/wx/v1/bindings/status?bindRequestId=" + brd, token=td)


# ================================================================ 新增：错误码语义裁决 + 双侧对齐锁定
def t_error_code_rulings():
    print("\n-- RC3-R6 错误码语义裁决与双侧对齐 --")
    # 裁决：未绑定身份调用 unbind → 403 TSS_NOT_BOUND（不用 409 TSS_BIND_CONFLICT）
    expect_err("R6-未绑定(UNBOUND)调用 unbind → 403 TSS_NOT_BOUND",
               call("POST", "/wx/v1/bindings/unbind", token=login("MOCK_NEW_%s" % uniq("ub1")),
                    body={"confirmation": "UNBIND", "clientNonce": uniq("cn1")}),
               "TSS_NOT_BOUND", 403)
    expect_err("R6-申请中(PENDING)调用 unbind → 403 TSS_NOT_BOUND",
               call("POST", "/wx/v1/bindings/unbind", token=login("MOCK_PENDING"),
                    body={"confirmation": "UNBIND", "clientNonce": uniq("cn2")}),
               "TSS_NOT_BOUND", 403)
    expect_err("R6-停用(DISABLED)调用 unbind → 403 TSS_BINDING_DISABLED",
               call("POST", "/wx/v1/bindings/unbind", token=login("MOCK_DISABLED"),
                    body={"confirmation": "UNBIND", "clientNonce": uniq("cn3")}),
               "TSS_BINDING_DISABLED", 403)
    # 二次解绑：已不是绑定态，同样 403 TSS_NOT_BOUND
    t = login("MOCK_BOUND")
    st, j = call("POST", "/wx/v1/bindings/unbind", token=t,
                 body={"confirmation": "UNBIND", "clientNonce": uniq("cn4")})
    check("R6-已绑定首次解绑成功", st == 200 and j["data"]["bindStatus"] == "UNBOUND", str(j))
    expect_err("R6-解绑后二次解绑 → 403 TSS_NOT_BOUND",
               call("POST", "/wx/v1/bindings/unbind", token=t,
                    body={"confirmation": "UNBIND", "clientNonce": uniq("cn5")}),
               "TSS_NOT_BOUND", 403)

    # 对齐点：查询他人 bindRequestId → 404 TSS_NOT_FOUND（不是 403，避免泄露资源是否存在）
    owner = login("MOCK_NEW_%s" % uniq("own"))
    st, j = call("POST", "/wx/v1/bindings/claim", token=owner,
                 body={"inviteCode": "INV-DEMO-0001", "claimNonce": uniq("cn6")})
    other_br = j["data"]["bindRequestId"]
    expect_err("R6-他人查询我的 bindRequestId → 404 TSS_NOT_FOUND",
               call("GET", "/wx/v1/bindings/status?bindRequestId=" + other_br,
                    token=login("MOCK_NEW_%s" % uniq("oth"))),
               "TSS_NOT_FOUND", 404)
    expect_err("R6-查询不存在的 bindRequestId → 404 TSS_NOT_FOUND",
               call("GET", "/wx/v1/bindings/status?bindRequestId=br_not_exist_0001",
                    token=owner),
               "TSS_NOT_FOUND", 404)

    # 对齐点：erpUserMasked / entDisplayName 仅在 status=BOUND 时非空
    st, j = call("GET", "/wx/v1/bindings/status", token=owner)
    check("R6-未绑定时脱敏字段均为 null",
          j["data"]["status"] == "PENDING_PC_CONFIRM"
          and j["data"]["erpUserMasked"] is None
          and j["data"]["entDisplayName"] is None, str(j["data"]))
    call("POST", "/__mock/pc-confirm", body={"bindRequestId": other_br})
    # PC 确认 = 申请流程终结 → 申请单归档，bindRequestId 归 null（INV-5）
    st, j = call("GET", "/wx/v1/bindings/status", token=owner)
    check("R6-绑定后脱敏字段同时非空",
          j["data"]["status"] == "BOUND"
          and bool(j["data"]["erpUserMasked"])
          and bool(j["data"]["entDisplayName"]), str(j["data"]))
    check("R6-PC 确认后 bindRequestId 归 null（申请单已归档）",
          j["data"]["bindRequestId"] is None, str(j["data"]))
    expect_err("R6-PC 确认后按旧申请单 id 精确查 → 404 TSS_NOT_FOUND（已归档）",
               call("GET", "/wx/v1/bindings/status?bindRequestId=" + other_br,
                    token=owner),
               "TSS_NOT_FOUND", 404)


# ================================================================ 新增：RC3 管理员令牌
def t_admin_token():
    print("\n-- RC3-R5 管理员令牌安全 --")
    st, j = call("GET", "/healthz")
    if st == 200:
        _, raw = call_raw("GET", "/healthz")
        check("R5 /healthz 不泄露管理员令牌明文",
              ADMIN not in raw, "令牌出现在 healthz 响应中")
        check("R5 /healthz 只声明令牌来源",
              j["data"].get("adminTokenSource") in ("env", "ephemeral"),
              str(j["data"].get("adminTokenSource")))
    else:
        check("R5 /healthz 可访问", False, "HTTP %s" % st)

    # RC3-D7 补裁：三档分工（与 Java AdminTokenService.resolve() 同形）
    #   ① 未提供凭据      → 401
    #   ② 令牌无效（不在受理表内）→ 401
    #   ③ 令牌有效但非 ADMIN → 403（需角色映射，见下方 t_admin_role_mapping）
    expect_err("R5-D7 ② 令牌无效 → 401 TSS_UNAUTHORIZED",
               call("GET", "/admin/v1/tss/mappings", token="not-the-admin-token"),
               "TSS_UNAUTHORIZED", 401)
    expect_err("R5-D7 ① 未提供管理员凭据（无 Authorization 头）→ 401 TSS_UNAUTHORIZED",
               call("GET", "/admin/v1/tss/mappings"), "TSS_UNAUTHORIZED", 401)
    st, j = call("GET", "/admin/v1/tss/bindings", token="")
    check("R5-D7 ① 空 Bearer 凭据等同未提供 → 401 TSS_UNAUTHORIZED",
          st == 401 and j.get("code") == "TSS_UNAUTHORIZED" and j.get("data") is None,
          "HTTP %s / %s" % (st, j.get("code")))
    st, j = call("GET", "/admin/v1/tss/mappings", token=login("MOCK_BOUND"))
    check("R5-D7 ② 微信会话令牌不在管理员受理表内 → 401 TSS_UNAUTHORIZED（不是 403）",
          st == 401 and j.get("code") == "TSS_UNAUTHORIZED",
          "HTTP %s / %s" % (st, j.get("code")))
    st, j = call("GET", "/admin/v1/tss/mappings", token=ADMIN)
    check("R5 ADMIN 角色令牌可放行",
          st == 200 and len(j["data"]["definitions"]) == 4, "HTTP %s" % st)

    # 静态扫描：目录内不得残留硬编码永久密钥
    hits = []
    roots = [HERE, os.path.abspath(os.path.join(HERE, "..", "contract"))]
    for root in roots:
        if not os.path.isdir(root):
            continue
        for fn in sorted(os.listdir(root)):
            if not fn.endswith((".py", ".md", ".yml", ".yaml", ".csv", ".json", ".txt")):
                continue
            if fn.startswith("CHECKSUMS"):
                continue
            path = os.path.join(root, fn)
            try:
                with open(path, "r", encoding="utf-8") as fh:
                    text = fh.read()
            except Exception:
                continue
            for i, line in enumerate(text.splitlines(), 1):
                if _HARDCODED_RE.search(line):
                    hits.append("%s:%d" % (os.path.relpath(path, HERE), i))
    check("R5 仓库/交付物内无硬编码永久管理员密钥", not hits, ", ".join(hits))


# ================================================================ 新增：RC3-R7 ERP 模式 fail-closed
ERP_PORT = int(os.environ.get("TSS_MOCK_ERP_PORT", "9002"))
ERP_RAW = []          # 供报告取用：ERP 模式下 5 个数据端点的原始响应体


def _probe_ready(base, expect_data_mode=None):
    """就绪探测：不仅要"能连通"，还要确认对端**确实是本轮拉起的实例**。

    只探测连通性是不够的：端口复用窗口内可能连到上一个残留实例，
    从而用它的数据/令牌继续跑 —— 这正是间歇性异常响应的可能来源。
    因此这里校验 /healthz 的服务自描述字段；若调用方指定了 dataMode，一并校验。
    """
    try:
        with urllib.request.urlopen(base + "/healthz", timeout=2) as r:
            j = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError:
        return False          # 注入态下 healthz 也可能非 200，仍视为未就绪（继续重试）
    except Exception:
        return False
    data = j.get("data") or {}
    if data.get("service") != "tss-contract-mock":
        return False
    if expect_data_mode is not None and data.get("dataMode") != expect_data_mode:
        return False
    return True


def start_mode_server(port, extra_env, retries=2):
    """在指定端口拉起一个带额外环境变量的临时实例（用于模式级验证）。

    加固要点（RC3 收口）：
      - 端口占用预检；
      - **就绪探测必须连续成功两次**才认为可用，并校验服务自描述字段，
        避免连到残留实例（端口复用窗口）；
      - 拉起失败时**重试一次**（换用同一个端口，先回收上次的进程）。
    """
    for attempt in range(retries + 1):
        if port_busy(port):
            sys.stderr.write("[FATAL] 端口 %d 已被占用，无法拉起模式验证实例。\n" % port)
            return None
        env = dict(os.environ)
        env["TSS_MOCK_PORT"] = str(port)
        env.pop("TSS_ADMIN_TOKEN", None)
        env["TSS_MOCK_SECRET"] = env.get("TSS_MOCK_SECRET", "verify-secret-000111")
        env.update(extra_env)
        p = subprocess.Popen([sys.executable, SERVER], env=env,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        _extra_procs.append(p)
        base = "http://127.0.0.1:%d" % port
        want_mode = extra_env.get("TSS_MOCK_DATA_MODE")
        deadline = time.time() + 20
        stable = 0
        while time.time() < deadline:
            if _probe_ready(base, want_mode):
                stable += 1
                if stable >= 2:
                    # 给监听线程一个稳定窗口，避免刚 accept 就压请求
                    time.sleep(0.1)
                    return base
            else:
                stable = 0
                if p.poll() is not None:
                    break
            time.sleep(0.15)
        # 未就绪：回收后重试
        try:
            p.terminate()
            p.wait(timeout=5)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass
    return None


def t_erp_fail_closed():
    """RC3-B1：dataMode=ERP 且未接入真实 ERP 只读通道时，业务数据端点必须 fail-closed。

    判定：5 个数据端点全部 503 TSS_ERP_READ_UNAVAILABLE 且 data=null。
    **绝不能是空列表** —— 空列表会被读成"确实没有待办"，比报错危险得多。
    /healthz 属于服务自描述，可以继续声明 dataMode=ERP（不是业务数据）。
    """
    print("\n-- RC3-R7 ERP 模式 fail-closed（端口 %d）--" % ERP_PORT)
    base = start_mode_server(ERP_PORT, {"TSS_MOCK_DATA_MODE": "ERP"})
    if base is None:
        check("R7 ERP 模式实例可拉起", False, "端口 %d 拉起失败" % ERP_PORT)
        return

    def erp_call(method, path, token=None, body=None):
        url = base + path
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        if data:
            req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8")

    st, raw = erp_call("POST", "/wx/v1/auth/login", body={"code": "MOCK_BOUND"})
    check("R7-B1 ERP 模式下登录仍可用（身份不依赖 ERP 只读通道）",
          st == 200 and json.loads(raw).get("code") == "SUCCESS", "HTTP %s" % st)
    tok = json.loads(raw)["data"]["sessionToken"]

    targets = [
        ("API-03 待办统计", "GET", "/wx/v1/todos/summary"),
        ("API-04 待办列表", "GET", "/wx/v1/todos"),
        ("API-05 待办详情", "GET", "/wx/v1/todos/900002/detail"),
        ("API-06 历史记录", "GET", "/wx/v1/history"),
        ("API-07 通知列表", "GET", "/wx/v1/notifications"),
    ]
    bad = []
    for label, mth, path in targets:
        st, raw = erp_call(mth, path, token=tok)
        ERP_RAW.append((label, path, st, raw))
        try:
            j = json.loads(raw)
        except Exception:
            j = {}
        if st != 503 or j.get("code") != "TSS_ERP_READ_UNAVAILABLE" or j.get("data") is not None:
            bad.append("%s:HTTP%s/%s/data=%s" % (label, st, j.get("code"),
                                                 json.dumps(j.get("data"), ensure_ascii=False)))
    check("R7-B1 ERP 模式 5 个数据端点全部 503 TSS_ERP_READ_UNAVAILABLE 且 data=null",
          not bad, " | ".join(bad))

    # 绝不能退化成"空列表"：空列表会被读成确实没有数据
    empty_like = []
    for label, _p, st, raw in ERP_RAW:
        if '"data":[]' in raw.replace(" ", "") or '"items":[]' in raw.replace(" ", ""):
            empty_like.append(label)
    check("R7-B1 ERP 模式不得返回空列表（空列表会被读成『确实没有待办』）",
          not empty_like, ", ".join(empty_like))

    st, raw = erp_call("GET", "/healthz")
    j = json.loads(raw)
    check("R7-B1 /healthz 仍自描述 dataMode=ERP（自描述 ≠ 业务数据）",
          st == 200 and j["data"].get("dataMode") == "ERP", "HTTP %s / %s" % (st, j.get("data")))
    check("R7-B1 /healthz 声明未接入真实 ERP 只读通道",
          j["data"].get("erpReadChannelWired") is False, str(j["data"].get("erpReadChannelWired")))

    if DUMP_RAW:
        print("\n  [RAW] ERP 模式（dataMode=ERP）5 个数据端点原始响应：")
        for label, path, st, raw in ERP_RAW:
            print("  ---- %s  %s  HTTP %s ----" % (label, path, st))
            print("  " + raw.strip())


def dump_raw(label, method, path, token=None, body=None):
    """打印一次真实 HTTP 原始响应（供交付报告取证）。"""
    st, raw = call_raw(method, path, token=token, body=body)
    print("\n  [RAW] %s  %s %s  HTTP %s" % (label, method, path, st))
    print("  " + raw.strip())
    return st, raw


# ================================================================ 新增：RC3-R12 写回冻结探针的门禁顺序
# 写回被禁止是**版本级决策**，与"ERP 数据源通不通""当前有没有注入故障"无关，
# 因此探针必须抢在 global_failure_guard 与 erp_read_guard 之前命中。
PROBE_METHODS = ("GET", "POST", "PUT", "PATCH", "DELETE")
PROBE_PATH = "/dev-support/v1/tss/write-guard-probe"
PROBE_PORT_BASE = int(os.environ.get("TSS_MOCK_PROBE_PORT", "9012"))


def t_write_guard_probe():
    print("\n-- RC3-R12 写回冻结探针（三个实例，验证门禁顺序）--")
    scenarios = [
        ("默认实例", BASE, None),
    ]
    port = PROBE_PORT_BASE
    for label, extra in (("TSS_MOCK_FAILURE=internal", {"TSS_MOCK_FAILURE": "internal"}),
                         ("TSS_MOCK_DATA_MODE=ERP", {"TSS_MOCK_DATA_MODE": "ERP"})):
        b = start_mode_server(port, extra)
        port += 1
        scenarios.append(("%s 实例（端口 %d）" % (label, port - 1), b, extra))

    def rpc(base, method, path, token=None, body=None):
        url = base + path
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        if data:
            req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, _safe_json(r.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            return e.code, _safe_json(e.read().decode('utf-8'))

    for label, base, _extra in scenarios:
        if base is None:
            check("R12 %s 可拉起" % label, False, "拉起失败")
            continue
        bad = []
        for mth in PROBE_METHODS:
            st, j = rpc(base, mth, PROBE_PATH, body={"comment": "x"})
            if not check_json_ok("R12 %s 探针 %s" % (label, mth), j):
                bad.append("%s:非JSON响应" % mth)
                continue
            if st != 403 or j.get("code") != "TSS_WRITE_DISABLED":
                bad.append("%s:HTTP%s/%s" % (mth, st, j.get("code")))
        check("R12 %s：探针全 method 均 403 TSS_WRITE_DISABLED（不被故障注入/ERP 门禁抢先）"
              % label, not bad, " | ".join(bad))
        # 同实例下 /wx/v1/** 写回路径必须仍是 404（契约命名空间保持纯净）。
        # 注意：故障注入实例上任意业务路径都会被注入码抢先（这是注入的设计行为），
        # 因此该断言只在未注入的实例上做。
        if not (_extra or {}).get("TSS_MOCK_FAILURE"):
            st, j = rpc(base, "POST", "/wx/v1/todos/900002/approve", body={"comment": "x"})
            check("R12 %s：/wx/v1/** 下不存在写回路径 → 404 TSS_NOT_FOUND" % label,
                  st == 404 and j.get("code") == "TSS_NOT_FOUND",
                  "HTTP %s / %s" % (st, j.get("code")))
        if DUMP_RAW and label != "默认实例":
            print("\n  [RAW] %s  POST %s" % (label, PROBE_PATH))
            st, j = rpc(base, "POST", PROBE_PATH, body={"comment": "x"})
            print("  HTTP %s  %s" % (st, json.dumps(j, ensure_ascii=False)))


# ================================================================ 新增：RC3-R11 管理员令牌→角色映射
ROLE_PORT = int(os.environ.get("TSS_MOCK_ROLE_PORT", "9009"))
_ROLE_ADMIN_TOK = "verify-admin-tok"
_ROLE_USER_TOK = "verify-user-tok"


def t_admin_role_mapping():
    """RC3 补裁 ③：令牌→角色映射，让 403 TSS_ADMIN_FORBIDDEN 在 Python 侧也能被真实触发。

    没有角色概念时，"令牌有效但非管理员"只能用"令牌不匹配"冒充，而后者其实是 401。
    配置 `TSS_ADMIN_TOKENS='tok1=ADMIN,tok2=USER'` 后：
      ADMIN 令牌 → 放行；USER 令牌 → 403；未知令牌 → 401；不提供 → 401。
    """
    print("\n-- RC3-R11 管理员令牌→角色映射（端口 %d）--" % ROLE_PORT)
    base = start_mode_server(ROLE_PORT, {"TSS_ADMIN_TOKENS": "%s=ADMIN,%s=USER"
                                         % (_ROLE_ADMIN_TOK, _ROLE_USER_TOK)})
    if base is None:
        check("R11 角色映射实例可拉起", False, "端口 %d 拉起失败" % ROLE_PORT)
        return

    def rpc(method, path, token=None, body=None):
        url = base + path
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        if data:
            req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, _safe_json(r.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            return e.code, _safe_json(e.read().decode('utf-8'))

    def ok(name, st, j, code, status):
        return check(name, st == status and j.get("code") == code and j.get("data") is None
                     if code != "SUCCESS" else (st == status and j.get("code") == "SUCCESS"),
                     "期望 %s/%s，实际 %s/%s" % (status, code, st, j.get("code")))

    st, j = rpc("GET", "/admin/v1/tss/mappings", _ROLE_ADMIN_TOK)
    check_json_ok("R11 ADMIN 角色令牌 /admin/v1/tss/mappings", j)
    ok("R11 ADMIN 角色令牌 → 200 放行", st, j, "SUCCESS", 200)
    st, j = rpc("GET", "/admin/v1/tss/mappings", _ROLE_USER_TOK)
    ok("R11 USER 角色令牌（有效但非管理员）→ 403 TSS_ADMIN_FORBIDDEN", st, j,
       "TSS_ADMIN_FORBIDDEN", 403)
    st, j = rpc("GET", "/admin/v1/tss/bindings", _ROLE_USER_TOK)
    ok("R11 USER 角色令牌（另一管理员端点）→ 403 TSS_ADMIN_FORBIDDEN", st, j,
       "TSS_ADMIN_FORBIDDEN", 403)
    st, j = rpc("GET", "/admin/v1/tss/mappings", "unknown-token-xyz")
    ok("R11 未知令牌 → 401 TSS_UNAUTHORIZED", st, j, "TSS_UNAUTHORIZED", 401)
    st, j = rpc("GET", "/admin/v1/tss/mappings")
    ok("R11 未提供凭据 → 401 TSS_UNAUTHORIZED", st, j, "TSS_UNAUTHORIZED", 401)
    st, j = rpc("GET", "/admin/v1/tss/mappings", "")
    ok("R11 空 Bearer → 401 TSS_UNAUTHORIZED", st, j, "TSS_UNAUTHORIZED", 401)
    st, j = rpc("GET", "/healthz")
    check("R11 /healthz 声明已启用角色映射",
          st == 200 and j["data"].get("adminRoleMapping") == ["ADMIN", "USER"],
          str(j["data"].get("adminRoleMapping")))
    if DUMP_RAW:
        for label, tok in (("ADMIN 角色令牌", _ROLE_ADMIN_TOK),
                           ("USER 角色令牌（有效但非管理员）", _ROLE_USER_TOK),
                           ("未知令牌", "unknown-token-xyz"),
                           ("未提供凭据", None)):
            url = base + "/admin/v1/tss/mappings"
            req = urllib.request.Request(url, method="GET")
            if tok:
                req.add_header("Authorization", "Bearer " + tok)
            try:
                with urllib.request.urlopen(req, timeout=10) as r:
                    print("\n  [RAW] %s  GET /admin/v1/tss/mappings  HTTP %s\n  %s"
                          % (label, r.status, r.read().decode("utf-8").strip()))
            except urllib.error.HTTPError as e:
                print("\n  [RAW] %s  GET /admin/v1/tss/mappings  HTTP %s\n  %s"
                      % (label, e.code, e.read().decode("utf-8").strip()))


# ================================================================ 新增：RC3-R8 越权三分 + 判定顺序 + 邀请码
def t_permission_and_order():
    print("\n-- RC3-R8 越权三分 / 判定顺序 / 邀请码白名单 --")
    demo = login("MOCK_BOUND")
    bob = login("MOCK_BOUND_BOB")          # 同企业另一员工
    other = login("MOCK_OTHER_ENT")        # 跨企业已绑定员工

    # ---- D-1 越权三分支 ----
    expect_err("R8-D1 同企业内、资源存在但不属于本人 → 403 TSS_FORBIDDEN",
               call("GET", "/wx/v1/todos/900002/detail", token=bob), "TSS_FORBIDDEN", 403)
    expect_err("R8-D1 跨企业访问他人待办详情 → 403 TSS_ENT_SCOPE_DENIED",
               call("GET", "/wx/v1/todos/900002/detail", token=other),
               "TSS_ENT_SCOPE_DENIED", 403)
    expect_err("R8-D1 反向跨企业（他企业员工访问本企业单据）→ 403 TSS_ENT_SCOPE_DENIED",
               call("GET", "/wx/v1/todos/930002/detail", token=demo),
               "TSS_ENT_SCOPE_DENIED", 403)
    expect_err("R8-D1 资源完全不存在 → 404 TSS_NOT_FOUND",
               call("GET", "/wx/v1/todos/999999/detail", token=demo), "TSS_NOT_FOUND", 404)
    # 三个码不得混用：跨企业不得退回通用 FORBIDDEN，同企业不得升级成 ENT_SCOPE
    st, j = call("GET", "/wx/v1/todos/900002/detail", token=other)
    check("R8-D1 跨企业不使用通用 TSS_FORBIDDEN",
          j.get("code") == "TSS_ENT_SCOPE_DENIED", str(j.get("code")))
    st, j = call("GET", "/wx/v1/todos/900002/detail", token=bob)
    check("R8-D1 同企业越权不升级为 TSS_ENT_SCOPE_DENIED",
          j.get("code") == "TSS_FORBIDDEN", str(j.get("code")))
    # 跨企业身份访问自己的单据必须正常
    st, j = call("GET", "/wx/v1/todos/930002/detail", token=other)
    check("R8-D1 跨企业员工访问本人单据正常 200",
          st == 200 and j["data"]["taskNum"] == "930002", "HTTP %s" % st)

    # ---- D-3 解绑：先鉴权/状态，后校验请求体 ----
    fresh_unbound = login("MOCK_NEW_%s" % uniq("d3u"))
    expect_err("R8-D3 未绑定 + 合法请求体 → 403 TSS_NOT_BOUND",
               call("POST", "/wx/v1/bindings/unbind", token=fresh_unbound,
                    body={"confirmation": "UNBIND", "clientNonce": uniq("d3a")}),
               "TSS_NOT_BOUND", 403)
    expect_err("R8-D3 未绑定 + 非法请求体 → 403 TSS_NOT_BOUND（状态门禁先命中）",
               call("POST", "/wx/v1/bindings/unbind", token=fresh_unbound,
                    body={"confirmation": "BAD", "clientNonce": uniq("d3b")}),
               "TSS_NOT_BOUND", 403)
    expect_err("R8-D3 未绑定 + 空请求体 → 403 TSS_NOT_BOUND",
               call("POST", "/wx/v1/bindings/unbind", token=fresh_unbound, body={}),
               "TSS_NOT_BOUND", 403)
    expect_err("R8-D3 未绑定 + 完全不带 body → 403 TSS_NOT_BOUND",
               call("POST", "/wx/v1/bindings/unbind", token=fresh_unbound),
               "TSS_NOT_BOUND", 403)
    expect_err("R8-D3 停用 + 任意请求体 → 403 TSS_BINDING_DISABLED（不看 body）",
               call("POST", "/wx/v1/bindings/unbind", token=login("MOCK_DISABLED"),
                    body={"confirmation": "WHATEVER"}),
               "TSS_BINDING_DISABLED", 403)
    expect_err("R8-D3 已绑定 + 非法 confirmation → 400 TSS_INVALID_ARGUMENT（与 RC2 一致）",
               call("POST", "/wx/v1/bindings/unbind", token=login("MOCK_BOUND"),
                    body={"confirmation": "NOPE", "clientNonce": uniq("d3c")}),
               "TSS_INVALID_ARGUMENT", 400)
    expect_err("R8-D3 已绑定 + 缺 clientNonce → 400 TSS_INVALID_ARGUMENT",
               call("POST", "/wx/v1/bindings/unbind", token=login("MOCK_BOUND"),
                    body={"confirmation": "UNBIND"}),
               "TSS_INVALID_ARGUMENT", 400)

    # ---- D-4 邀请码白名单 ----
    new_tok = login("MOCK_NEW_%s" % uniq("d4"))
    st, j = call("POST", "/wx/v1/bindings/claim", token=new_tok,
                 body={"inviteCode": "INV-DEMO-0001", "claimNonce": uniq("d4ok")})
    check("R8-D4 白名单内邀请码可正常受理 → 200 PENDING_PC_CONFIRM",
          st == 200 and j["data"]["status"] == "PENDING_PC_CONFIRM", str(j))
    for bad_code in ("INV-RANDOM-XYZ", "INV-A", "inv", "INV-", "NOT_A_CODE", "INV-DEMO-9999"):
        expect_err("R8-D4 白名单外邀请码 %r → 400 TSS_INVALID_ARGUMENT" % bad_code,
                   call("POST", "/wx/v1/bindings/claim",
                        token=login("MOCK_NEW_%s" % uniq("d4x")),
                        body={"inviteCode": bad_code, "claimNonce": uniq("d4n")}),
                   "TSS_INVALID_ARGUMENT", 400)
    expect_err("R8-D4 异常分支专用码 INV-EXPIRED 仍走 409（不被白名单拦截）",
               call("POST", "/wx/v1/bindings/claim",
                    token=login("MOCK_NEW_%s" % uniq("d4e")),
                    body={"inviteCode": "INV-EXPIRED", "claimNonce": uniq("d4e2")}),
               "TSS_BIND_INVITE_EXPIRED", 409)
    expect_err("R8-D4 异常分支专用码 INV-CONFLICT 仍走 409（不被白名单拦截）",
               call("POST", "/wx/v1/bindings/claim",
                    token=login("MOCK_NEW_%s" % uniq("d4c")),
                    body={"inviteCode": "INV-CONFLICT", "claimNonce": uniq("d4c2")}),
               "TSS_BIND_CONFLICT", 409)

    # ---- 写回冻结：/wx/v1/** 保持 13 条纯净，不存在的路径一律 404（与 Java 同形）----
    for mth, path in (("POST", "/wx/v1/todos/900002/approve"),
                      ("PUT", "/wx/v1/todos/900002/reject"),
                      ("DELETE", "/wx/v1/approvals/900001/approve"),
                      ("GET", "/wx/v1/todos/900002/transfer")):
        expect_err("R8 /wx/v1/** 下不存在写回路径：%s %s → 404 TSS_NOT_FOUND" % (mth, path),
                   call(mth, path, token=demo, body={"comment": "x"}),
                   "TSS_NOT_FOUND", 404)

    # ---- 写回冻结探针（DEV 支持命名空间，非契约端点）：全 method 均 403 ----
    for mth in ("GET", "POST", "PUT", "PATCH", "DELETE"):
        expect_err("R8 写回探针 %s /dev-support/v1/tss/write-guard-probe → 403 TSS_WRITE_DISABLED"
                   % mth,
                   call(mth, "/dev-support/v1/tss/write-guard-probe",
                        token=demo, body={"comment": "x"}),
                   "TSS_WRITE_DISABLED", 403)
    expect_err("R8 写回探针匿名访问同样 403（无需会话）",
               call("POST", "/dev-support/v1/tss/write-guard-probe"),
               "TSS_WRITE_DISABLED", 403)
    if DUMP_RAW:
        dump_raw("写回冻结探针", "POST", "/dev-support/v1/tss/write-guard-probe", token=demo)
    st, j = call("GET", "/wx/v1/todos/900002/detail", token=demo)
    check("R8 availableActions 恒为 [\"VIEW\"]（V1.0 不下发审批动作）",
          j["data"]["availableActions"] == ["VIEW"], str(j["data"]["availableActions"]))

    # ---- D-6 解绑后形态确认（Java 侧已去掉 revoke，与我方"保留会话"同形）----
    d6 = login("MOCK_BOUND")
    st, j = call("POST", "/wx/v1/bindings/unbind", token=d6,
                 body={"confirmation": "UNBIND", "clientNonce": uniq("d6")})
    check("R8-D6 解绑成功", st == 200 and j["data"]["bindStatus"] == "UNBOUND", str(j))
    st, me6 = call("GET", "/wx/v1/me", token=d6)
    check("R8-D6 解绑后 GET /wx/v1/me → 200 且 bindStatus=UNBOUND（会话未被撤销）",
          st == 200 and me6["data"]["bindStatus"] == "UNBOUND", "HTTP %s / %s" % (st, me6))
    st, st6 = call("GET", "/wx/v1/bindings/status", token=d6)
    check("R8-D6 解绑后 GET /wx/v1/bindings/status → 200，status=UNBOUND 且 bindRequestId=null",
          st == 200 and st6["data"]["status"] == "UNBOUND"
          and st6["data"]["bindRequestId"] is None, "HTTP %s / %s" % (st, st6["data"]))
    expect_err("R8-D6 解绑后业务端点（/wx/v1/todos）→ 403 TSS_NOT_BOUND",
               call("GET", "/wx/v1/todos", token=d6), "TSS_NOT_BOUND", 403)
    expect_err("R8-D6 解绑后业务端点（/wx/v1/todos/summary）→ 403 TSS_NOT_BOUND",
               call("GET", "/wx/v1/todos/summary", token=d6), "TSS_NOT_BOUND", 403)
    expect_err("R8-D6 解绑后业务端点（/wx/v1/history）→ 403 TSS_NOT_BOUND",
               call("GET", "/wx/v1/history", token=d6), "TSS_NOT_BOUND", 403)
    if DUMP_RAW:
        print("\n  [RAW] D-6 解绑后三端点形态：")
        dump_raw("解绑后 /wx/v1/me", "GET", "/wx/v1/me", token=d6)
        dump_raw("解绑后 /wx/v1/bindings/status", "GET", "/wx/v1/bindings/status", token=d6)
        dump_raw("解绑后 /wx/v1/todos", "GET", "/wx/v1/todos", token=d6)


# ================================================================ 新增：RC3-R9 本轮补齐的两个夹具
def t_round4_fixtures():
    print("\n-- RC3-R9 空 events 夹具 / 跨企业夹具 --")
    evt_tok = login("MOCK_EMPTY_EVT")
    st, j = call("GET", "/wx/v1/todos?limit=100", token=evt_tok)
    check("R9-空事件 夹具账号可见 1 条待办", st == 200 and len(j["data"]["items"]) == 1, str(j))
    st, j = call("GET", "/wx/v1/todos/920002/detail", token=evt_tok)
    wf = j["data"]["workflow"]
    check("R9-空事件 workflow.events 字段存在且为数组",
          "events" in wf and isinstance(wf["events"], list), str(wf.get("events")))
    check("R9-空事件 workflow.events == []（不是 null、不是省略、不是伪提交事件）",
          wf.get("events") == [], str(wf.get("events")))
    st, raw = call_raw("GET", "/wx/v1/todos/920002/detail", token=evt_tok)
    check("R9-空事件 响应体中 events 以 [] 字面出现（字段未被省略）",
          '"events": []' in raw.replace(" ", "") or '"events":[]' in raw.replace(" ", ""),
          raw[raw.find('"events"'):raw.find('"events"') + 60] if '"events"' in raw else "no events key")
    check("R9-空事件 其余工作流字段齐备（definitionVersion/readOnly/dataVersion）",
          bool(wf.get("definitionVersion")) and wf.get("readOnly") is True
          and bool(wf.get("dataVersion")), str({k: wf.get(k) for k in
                                                ("definitionVersion", "readOnly", "dataVersion")}))
    expect_err("R9-空事件 他人访问该单据 → 403 TSS_FORBIDDEN（同企业）",
               call("GET", "/wx/v1/todos/920002/detail", token=login("MOCK_BOUND")),
               "TSS_FORBIDDEN", 403)

    other_tok = login("MOCK_OTHER_ENT")
    st, j = call("GET", "/wx/v1/me", token=other_tok)
    check("R9-跨企业 /me 显示另一企业 entId",
          st == 200 and j["data"]["entId"] == "E-OTHER-000002" and j["data"]["bindStatus"] == "BOUND",
          str(j["data"]))
    st, j = call("GET", "/wx/v1/todos?limit=100", token=other_tok)
    check("R9-跨企业 本人只看到本企业待办", st == 200 and len(j["data"]["items"]) == 1
          and j["data"]["items"][0]["taskNum"] == "930002", str(j["data"]["items"]))
    st, j = call("GET", "/wx/v1/todos/summary", token=other_tok)
    check("R9-跨企业 统计只统计本企业数据",
          j["data"]["counts"]["total"] == 1, str(j["data"]["counts"]))

    if DUMP_RAW:
        print("\n  [RAW] 新增夹具响应片段：")
        dump_raw("空 events 夹具（MOCK_EMPTY_EVT）详情", "GET",
                 "/wx/v1/todos/920002/detail", token=evt_tok)
        dump_raw("跨企业夹具（MOCK_OTHER_ENT）/me", "GET", "/wx/v1/me", token=other_tok)
        dump_raw("跨企业越权：他企业身份访问本企业单据", "GET",
                 "/wx/v1/todos/900002/detail", token=other_tok)
        dump_raw("同企业越权：同企业他人单据", "GET",
                 "/wx/v1/todos/900002/detail", token=login("MOCK_BOUND_BOB"))


# ================================================================ 新增：RC3-R10 错误码运行时覆盖矩阵
# 口径红线：对外只能说「20 个码三方静态对齐（csv × server.ERRORS × Java 枚举）」；
# 运行时覆盖必须分开说，且要区分「默认路径」与「故障注入路径」。
# 本组把 20 个码逐个打出真实 HTTP 响应，并输出分类表，避免"静态 20/20"被读成"运行时 20/20"。
AUTHORITATIVE_CODES = [
    "TSS_INVALID_ARGUMENT", "TSS_INVALID_CURSOR", "TSS_UNAUTHORIZED", "TSS_SESSION_EXPIRED",
    "TSS_NOT_BOUND", "TSS_FORBIDDEN", "TSS_ENT_SCOPE_DENIED", "TSS_BINDING_DISABLED",
    "TSS_ADMIN_FORBIDDEN", "TSS_NOT_FOUND", "TSS_BIND_INVITE_EXPIRED", "TSS_BIND_CONFLICT",
    "TSS_IDEMPOTENCY_CONFLICT", "TSS_DATA_STALE", "TSS_RATE_LIMITED", "TSS_WECHAT_UNAVAILABLE",
    "TSS_ERP_READ_UNAVAILABLE", "TSS_NOT_CONFIGURED", "TSS_INTERNAL_ERROR",
    "TSS_WRITE_DISABLED",
]

# 每个码的触发方式：(分类, 说明, 需要的实例, 触发函数(base)->(http_status, code))
#   分类 ∈ {"默认", "注入"}   —— "默认"=MOCK 默认配置下用夹具即可打出；"注入"=需要 TSS_MOCK_FAILURE


def _trigger_default(base):
    """返回 {code: (status, 触发说明)} —— 默认配置（dataMode=MOCK, failure=none）下可打出的码。"""
    out = {}

    def rpc(method, path, token=None, body=None):
        url = base + path
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        if data:
            req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, _safe_json(r.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            return e.code, _safe_json(e.read().decode('utf-8'))

    def lg(code):
        st, j = rpc("POST", "/wx/v1/auth/login", body={"code": code})
        return j["data"]["sessionToken"]

    def add(code, st, j, how):
        if j.get("code") == code:
            out[code] = (st, how)

    demo, bob, other, dis = lg("MOCK_BOUND"), lg("MOCK_BOUND_BOB"), lg("MOCK_OTHER_ENT"), lg("MOCK_DISABLED")

    st, j = rpc("GET", "/wx/v1/todos?limit=101", demo)
    add("TSS_INVALID_ARGUMENT", st, j, "GET /wx/v1/todos?limit=101")
    st, j = rpc("GET", "/wx/v1/todos?cursor=tampered-cursor-value", demo)
    add("TSS_INVALID_CURSOR", st, j, "GET /wx/v1/todos?cursor=<篡改>")
    st, j = rpc("GET", "/wx/v1/me")
    add("TSS_UNAUTHORIZED", st, j, "GET /wx/v1/me 不带 Authorization")

    exp_tok = lg("MOCK_BOUND")
    # 控制台把令牌重签为已过期版本，必须用返回的新令牌再请求
    st, j = rpc("POST", "/__mock/expire-session", exp_tok)
    expired_tok = j["data"]["sessionToken"]
    st, j = rpc("GET", "/wx/v1/me", expired_tok)
    add("TSS_SESSION_EXPIRED", st, j, "POST /__mock/expire-session 后用重签令牌请求（MOCK 控制台）")

    st, j = rpc("GET", "/wx/v1/todos", lg("MOCK_NEW_%s" % uniq("cov")))
    add("TSS_NOT_BOUND", st, j, "未绑定身份 GET /wx/v1/todos")
    st, j = rpc("GET", "/wx/v1/todos/900002/detail", bob)
    add("TSS_FORBIDDEN", st, j, "同企业他人单据 GET /wx/v1/todos/900002/detail")
    st, j = rpc("GET", "/wx/v1/todos/900002/detail", other)
    add("TSS_ENT_SCOPE_DENIED", st, j, "跨企业身份 GET /wx/v1/todos/900002/detail")
    st, j = rpc("GET", "/wx/v1/todos", dis)
    add("TSS_BINDING_DISABLED", st, j, "停用身份 GET /wx/v1/todos")
    # TSS_ADMIN_FORBIDDEN 需要「令牌有效但角色非 ADMIN」，依赖角色映射实例，见下方注入段
    st, j = rpc("GET", "/wx/v1/todos/999999/detail", demo)
    add("TSS_NOT_FOUND", st, j, "GET /wx/v1/todos/999999/detail（资源不存在）")

    nu = lg("MOCK_NEW_%s" % uniq("cov2"))
    st, j = rpc("POST", "/wx/v1/bindings/claim", nu,
                body={"inviteCode": "INV-EXPIRED", "claimNonce": uniq("cov_a")})
    add("TSS_BIND_INVITE_EXPIRED", st, j, "claim inviteCode=INV-EXPIRED")
    st, j = rpc("POST", "/wx/v1/bindings/claim", lg("MOCK_NEW_%s" % uniq("cov3")),
                body={"inviteCode": "INV-CONFLICT", "claimNonce": uniq("cov_b")})
    add("TSS_BIND_CONFLICT", st, j, "claim inviteCode=INV-CONFLICT")

    nu2 = lg("MOCK_NEW_%s" % uniq("cov4"))
    n = uniq("cov_c")
    rpc("POST", "/wx/v1/bindings/claim", nu2, body={"inviteCode": "INV-DEMO-0001", "claimNonce": n})
    st, j = rpc("POST", "/wx/v1/bindings/claim", nu2,
                body={"inviteCode": "INV-DEMO-0002", "claimNonce": n})
    add("TSS_IDEMPOTENCY_CONFLICT", st, j, "同一 claimNonce 重复提交且内容不同")

    st, j = rpc("POST", "/dev-support/v1/tss/write-guard-probe", demo, body={"comment": "x"})
    add("TSS_WRITE_DISABLED", st, j,
        "POST /dev-support/v1/tss/write-guard-probe（DEV 支持命名空间，非契约端点）")
    return out


def _role_rpc(base, method, path, token=None, body=None):
    """对角色映射实例发一次请求（R10 与 R11 共用）。"""
    url = base + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, _safe_json(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return e.code, _safe_json(e.read().decode('utf-8'))


def _trigger_injected(mode, base):
    """在带故障注入的实例上打出对应错误码。"""
    def rpc(method, path, token=None, body=None):
        url = base + path
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        if data:
            req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, _safe_json(r.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            return e.code, _safe_json(e.read().decode('utf-8'))

    out = {}
    if mode == "wechat":
        st, j = rpc("POST", "/wx/v1/auth/login", body={"code": "MOCK_BOUND"})
        out["TSS_WECHAT_UNAVAILABLE"] = (st, j.get("code"))
        return out
    # 全局注入在路由之前生效（/healthz 与 /__mock/* 除外），因此无需先登录；
    # 唯 erp_read 是端点内门禁（在鉴权之后），需要先取会话令牌。
    tok = None
    if mode == "erp_read":
        st, j = rpc("POST", "/wx/v1/auth/login", body={"code": "MOCK_BOUND"})
        tok = j["data"]["sessionToken"]
    st, j = rpc("GET", "/wx/v1/todos", tok)
    out[{"data_stale": "TSS_DATA_STALE", "rate_limited": "TSS_RATE_LIMITED",
         "erp_read": "TSS_ERP_READ_UNAVAILABLE", "not_configured": "TSS_NOT_CONFIGURED",
         "internal": "TSS_INTERNAL_ERROR"}[mode]] = (st, j.get("code"))
    return out


def t_error_code_coverage():
    print("\n-- RC3-R10 错误码运行时覆盖矩阵（真实 HTTP）--")
    observed = {}
    for code, meta in _trigger_default(BASE).items():
        observed[code] = (CODE_KIND.get(code, "默认"), meta[0], meta[1])

    port = 9003
    for mode in ("data_stale", "rate_limited", "wechat", "erp_read",
                 "not_configured", "internal"):
        b = start_mode_server(port, {"TSS_MOCK_FAILURE": mode})
        port += 1
        if b is None:
            check("R10 故障注入实例 %s 可拉起" % mode, False, "拉起失败")
            continue
        for code, (stv, got) in _trigger_injected(mode, b).items():
            observed.setdefault(code, (CODE_KIND.get(code, "故障注入"), stv,
                                       "TSS_MOCK_FAILURE=%s" % mode))

    # 角色映射实例：TSS_ADMIN_FORBIDDEN 的唯一真实触发路径
    # （避开 R11 已占用的 ROLE_PORT，否则本组会误判为"拉起失败"）
    role_port = ROLE_PORT + 1
    if port > role_port:
        role_port = port
    rb = start_mode_server(role_port, {"TSS_ADMIN_TOKENS": "%s=ADMIN,%s=USER"
                                       % (_ROLE_ADMIN_TOK, _ROLE_USER_TOK)})
    port = role_port + 1
    if rb is None:
        check("R10 角色映射实例可拉起", False, "拉起失败")
    else:
        st, j = _role_rpc(rb, "GET", "/admin/v1/tss/mappings", _ROLE_USER_TOK)
        check_json_ok("R10 角色映射实例 /admin/v1/tss/mappings（USER 角色令牌）", j)
        observed.setdefault("TSS_ADMIN_FORBIDDEN",
                            (CODE_KIND.get("TSS_ADMIN_FORBIDDEN", "需显式构造"), st,
                             "TSS_ADMIN_TOKENS 中 USER 角色令牌（令牌有效但非管理员）"))

    missing = [c for c in AUTHORITATIVE_CODES if c not in observed]
    wrong = [c for c, (_, stv, _h) in observed.items()
             if c in AUTHORITATIVE_CODES and stv not in _EXPECTED_HTTP.get(c, (stv,))]
    check("R10 20 个权威错误码全部具备运行时触发路径（真实 HTTP）",
          not missing, "缺少：%s" % ", ".join(missing))
    check("R10 已触发码的 HTTP 状态与权威表一致", not wrong, ", ".join(wrong))
    # 三分类：默认 / 需显式构造 / 故障注入
    # 「默认」= 默认实例上按正常业务请求即可遇到；
    # 「需显式构造」= 必须额外配置或调用 MOCK 控制台/DEV 探针才会出现；
    # 「故障注入」= 必须显式注入故障。
    # TSS_SESSION_EXPIRED 属第二类：要先调 /__mock/expire-session 把令牌重签为过期，
    # 默认实例上不会自发产生，因此**不得**记成默认。
    n_default = len([c for c, (k, _s, _h) in observed.items() if k == "默认"])
    n_construct = len([c for c, (k, _s, _h) in observed.items() if k == "需显式构造"])
    n_inject = len([c for c, (k, _s, _h) in observed.items() if k == "故障注入"])
    check("R10 覆盖三分类：默认 %d / 需显式构造 %d / 故障注入 %d（合计 %d）"
          % (n_default, n_construct, n_inject, n_default + n_construct + n_inject),
          n_default + n_construct + n_inject >= 20
          and n_default == len([c for c in AUTHORITATIVE_CODES
                                if CODE_KIND.get(c) == "默认"]),
          "合计 %d" % (n_default + n_construct + n_inject))
    for c in AUTHORITATIVE_CODES:
        if c in observed:
            kind, stv, how = observed[c]
            print("      [%s] %-26s HTTP %-3s  %s" % (kind, c, stv, how))
        else:
            print("      [无路径] %-26s  ——" % c)


# 运行时覆盖的**权威分类**（三分类，分类以"是否需要额外动作才能遇到"为准）：
#   默认       —— 默认实例上按正常业务请求即可遇到
#   需显式构造 —— 必须额外配置或调用 MOCK 控制台 / DEV 探针才会出现
#                （绝不能记成"默认"，否则与我们对外的交付口径打架）
#   故障注入   —— 必须显式注入 TSS_MOCK_FAILURE
CODE_KIND = {
    "TSS_INVALID_ARGUMENT": "默认",
    "TSS_INVALID_CURSOR": "默认",
    "TSS_UNAUTHORIZED": "默认",
    "TSS_NOT_BOUND": "默认",
    "TSS_FORBIDDEN": "默认",
    "TSS_ENT_SCOPE_DENIED": "默认",
    "TSS_BINDING_DISABLED": "默认",
    "TSS_NOT_FOUND": "默认",
    "TSS_BIND_INVITE_EXPIRED": "默认",
    "TSS_BIND_CONFLICT": "默认",
    "TSS_IDEMPOTENCY_CONFLICT": "默认",
    # 需显式构造
    "TSS_SESSION_EXPIRED": "需显式构造",        # 需先调 /__mock/expire-session 重签令牌
    "TSS_ADMIN_FORBIDDEN": "需显式构造",        # 需 TSS_ADMIN_TOKENS 配置 USER 角色令牌
    "TSS_WRITE_DISABLED": "需显式构造",         # 需打 DEV 支持面探针（非契约端点）
    "TSS_ERP_READ_UNAVAILABLE": "需显式构造",   # 需 TSS_MOCK_DATA_MODE=ERP（或注入）
    # 故障注入
    "TSS_DATA_STALE": "故障注入",
    "TSS_RATE_LIMITED": "故障注入",
    "TSS_WECHAT_UNAVAILABLE": "故障注入",
    "TSS_NOT_CONFIGURED": "故障注入",
    "TSS_INTERNAL_ERROR": "故障注入",
}

_EXPECTED_HTTP = {
    "TSS_INVALID_ARGUMENT": (400,), "TSS_INVALID_CURSOR": (400,),
    "TSS_UNAUTHORIZED": (401,), "TSS_SESSION_EXPIRED": (401,),
    "TSS_NOT_BOUND": (403,), "TSS_FORBIDDEN": (403,), "TSS_ENT_SCOPE_DENIED": (403,),
    "TSS_BINDING_DISABLED": (403,), "TSS_ADMIN_FORBIDDEN": (403,), "TSS_NOT_FOUND": (404,),
    "TSS_BIND_INVITE_EXPIRED": (409,), "TSS_BIND_CONFLICT": (409,),
    "TSS_IDEMPOTENCY_CONFLICT": (409,), "TSS_DATA_STALE": (409,),
    "TSS_RATE_LIMITED": (429,), "TSS_WECHAT_UNAVAILABLE": (503,),
    "TSS_ERP_READ_UNAVAILABLE": (503,), "TSS_NOT_CONFIGURED": (503,),
    "TSS_INTERNAL_ERROR": (500,), "TSS_WRITE_DISABLED": (403,),
}


# ================================================================ 主流程
def main():
    global BASE
    print("=" * 100)
    print("时空智审 V1.0 /wx/v1 契约 MOCK 自检（RC3）   base=%s" % BASE)
    print("=" * 100)

    mode = os.environ.get("TSS_MOCK_FAILURE")
    if mode and mode != "none":
        # ---------- 故障注入模式（只跑异常分支，正例在 none 模式下已覆盖）----------
        if mode == "wechat":
            expect_err("故障注入 wechat → API-01 503 TSS_WECHAT_UNAVAILABLE",
                       call("POST", "/wx/v1/auth/login", body={"code": "MOCK_BOUND"}),
                       "TSS_WECHAT_UNAVAILABLE", 503)
        elif mode.startswith("erp_read"):
            st, j = call("POST", "/wx/v1/auth/login", body={"code": "MOCK_BOUND"})
            t2 = j["data"]["sessionToken"]
            dt = mode.split(":", 1)[1] if ":" in mode else None
            p = "/wx/v1/todos/summary" if not dt else "/wx/v1/todos?docType=" + dt
            expect_err("故障注入 %s → 503 TSS_ERP_READ_UNAVAILABLE" % mode,
                       call("GET", p, token=t2), "TSS_ERP_READ_UNAVAILABLE", 503)
            if dt:
                other = "PAYMENT" if dt != "PAYMENT" else "SALES_BUSINESS"
                st, _ = call("GET", "/wx/v1/todos?docType=" + other, token=t2)
                check("故障注入仅影响 %s，%s 不受影响" % (dt, other), st == 200)
        elif mode == "data_stale":
            expect_err("故障注入 data_stale → 409 TSS_DATA_STALE",
                       call("GET", "/wx/v1/todos", token=login("MOCK_BOUND")),
                       "TSS_DATA_STALE", 409)
        elif mode == "rate_limited":
            expect_err("故障注入 rate_limited → 429 TSS_RATE_LIMITED",
                       call("GET", "/wx/v1/todos", token=login("MOCK_BOUND")),
                       "TSS_RATE_LIMITED", 429)
        elif mode == "not_configured":
            expect_err("故障注入 not_configured → 503 TSS_NOT_CONFIGURED",
                       call("GET", "/wx/v1/todos", token=login("MOCK_BOUND")),
                       "TSS_NOT_CONFIGURED", 503)
        elif mode == "internal":
            expect_err("故障注入 internal → 500 TSS_INTERNAL_ERROR",
                       call("GET", "/wx/v1/todos", token=login("MOCK_BOUND")),
                       "TSS_INTERNAL_ERROR", 500)
        return _summary()

    # ---------- 正例 ----------
    st, j = call("POST", "/wx/v1/auth/login", body={"code": "MOCK_BOUND"})
    check("API-01 登录成功(HTTP200+SUCCESS)", st == 200 and j["code"] == "SUCCESS", str(j.get("code")))
    tok = j["data"]["sessionToken"]
    check("API-01 返回不透明会话令牌与绑定态",
          j["data"]["bindStatus"] == "BOUND" and j["data"]["needBinding"] is False
          and j["data"]["expiresIn"] == 7200)

    st, j = call("GET", "/wx/v1/me", token=tok)
    me = j["data"]
    check("API-02 会话本人(USERID/ENTID)",
          me["userId"] == "U-DEMO-000001" and me["entId"] == "E-DEMO-000001"
          and me["bindStatus"] == "BOUND")

    st, j = call("GET", "/wx/v1/todos/summary", token=tok)
    c = j["data"]["counts"]
    check("API-03 待办统计(采购入库恒0-本库无流程)",
          c["total"] == 3 and c["purchaseInbound"] == 0 and c["salesBusiness"] == 1
          and c["payment"] == 1 and c["salesPricing"] == 1, str(c))
    check("API-03 meta.dataMode=MOCK", j["data"]["meta"]["dataMode"] == "MOCK")

    st, j = call("GET", "/wx/v1/todos?limit=2", token=tok)
    items = j["data"]["items"]
    check("API-04 分页第1页(2条+hasMore)", len(items) == 2 and j["data"]["page"]["hasMore"] is True)
    cur = j["data"]["page"]["nextCursor"]
    st2, j2 = call("GET", "/wx/v1/todos?limit=2&cursor=" + urllib.parse.quote(cur), token=tok)
    check("API-04 游标翻页第2页(1条+hasMore=false)",
          st2 == 200 and len(j2["data"]["items"]) == 1
          and j2["data"]["page"]["hasMore"] is False)
    check("API-04 稳定排序(updatedAt DESC)",
          items[0]["updatedAt"] >= items[1]["updatedAt"],
          "%s vs %s" % (items[0]["updatedAt"], items[1]["updatedAt"]))

    st, j = call("GET", "/wx/v1/todos?docType=PURCHASE_INBOUND", token=tok)
    check("API-04 采购入库待办=0(不伪造)", j["data"]["items"] == [])

    st, j = call("GET", "/wx/v1/todos/900002/detail", token=tok)
    d = j["data"]
    check("API-05 详情内嵌 workflow(无独立端点)",
          d["workflow"]["instanceId"] == "900001" and len(d["workflow"]["nodes"]) == 3)
    check("API-05 availableActions 仅 VIEW(不下发审批动作)",
          d["availableActions"] == ["VIEW"])
    check("API-05 MOCK 不伪装真实完成",
          d["sourceStatus"]["isFinalApproved"] is False
          and d["sourceStatus"]["downstreamReceiptStatus"] == "NOT_VERIFIED")
    check("API-05 金额/编号为字符串", isinstance(d["base"][0]["value"], str))

    st, j = call("GET", "/wx/v1/history", token=tok)
    check("API-06 历史记录(resultVerified=false 不伪装)",
          len(j["data"]["items"]) == 3
          and all(i["resultVerified"] is False for i in j["data"]["items"]))

    st, j = call("GET", "/wx/v1/notifications", token=tok)
    check("API-07 通知列表", len(j["data"]["items"]) == 2)

    st, j = call("POST", "/wx/v1/notifications/consent", token=tok,
                 body={"templateIds": ["wx_tpl_01"], "consented": True})
    check("API-08 订阅意愿记录", j["data"]["subscriptionStatus"] == "GRANTED")

    st, j = call("GET", "/admin/v1/tss/mappings", token=ADMIN)
    defs = j["data"]["definitions"]
    forms = {x["docType"]: x["formId"] for x in defs}
    check("API-13 业务映射含实测 FORMID",
          forms.get("SALES_BUSINESS") == "87rflp785rg9ptt0"
          and forms.get("PAYMENT") == "3p29u0c2dku4th49"
          and forms.get("SALES_PRICING") == "26k6gqyucbyylov9"
          and forms.get("PURCHASE_INBOUND") == "0dqkuagm3iw04e0a", str(forms))
    check("API-13 未核实字段标 verified=false",
          any(f["verified"] is False for d2 in defs for f in d2["fields"]))

    st, j = call("GET", "/admin/v1/tss/bindings", token=ADMIN)
    check("API-12 管理员绑定列表+审计标记",
          j["data"]["auditInfo"]["accessLogged"] is True
          and j["data"]["auditInfo"]["scope"] == "AUTHORIZED_ENT")

    # ---------- 绑定流程 ----------
    st, j = call("POST", "/wx/v1/auth/login", body={"code": "MOCK_NEW_001"})
    new_tok = j["data"]["sessionToken"]
    check("API-01 新用户未绑定", j["data"]["bindStatus"] == "UNBOUND"
          and j["data"]["needBinding"] is True)
    expect_err("API-03 未绑定访问待办 → 403 TSS_NOT_BOUND",
               call("GET", "/wx/v1/todos/summary", token=new_tok), "TSS_NOT_BOUND", 403)

    st, j = call("POST", "/wx/v1/bindings/claim", token=new_tok,
                 body={"inviteCode": "INV-DEMO-0001", "claimNonce": "nonce_001"})
    check("API-09 发起绑定 → PENDING_PC_CONFIRM",
          j["data"]["status"] == "PENDING_PC_CONFIRM" and j["data"]["bindRequestId"].startswith("br_"))
    br = j["data"]["bindRequestId"]
    expect_err("API-09 nonce 重放且内容不同 → 409 TSS_IDEMPOTENCY_CONFLICT",
               call("POST", "/wx/v1/bindings/claim", token=new_tok,
                    body={"inviteCode": "INV-DEMO-0002", "claimNonce": "nonce_001"}),
               "TSS_IDEMPOTENCY_CONFLICT", 409)
    expect_err("API-09 过期邀请 → 409 TSS_BIND_INVITE_EXPIRED",
               call("POST", "/wx/v1/bindings/claim", token=new_tok,
                    body={"inviteCode": "INV-EXPIRED", "claimNonce": "nonce_002"}),
               "TSS_BIND_INVITE_EXPIRED", 409)
    st, j = call("GET", "/wx/v1/bindings/status", token=new_tok)
    check("API-10 轮询状态=PENDING_PC_CONFIRM(不伪造已完成)",
          j["data"]["status"] == "PENDING_PC_CONFIRM" and j["data"]["erpUserMasked"] is None)
    st, j = call("POST", "/__mock/pc-confirm", body={"bindRequestId": br})
    st, j = call("GET", "/wx/v1/bindings/status", token=new_tok)
    check("API-10 PC 确认后 BOUND 且 USERID 脱敏",
          j["data"]["status"] == "BOUND" and "*" in (j["data"]["erpUserMasked"] or ""))
    st, j = call("GET", "/wx/v1/todos/summary", token=new_tok)
    check("绑定后可读取本人待办", j["data"]["counts"]["total"] == 3)

    # ---------- 异常与越权 ----------
    expect_err("无令牌访问 /wx/v1/me → 401 TSS_UNAUTHORIZED",
               call("GET", "/wx/v1/me"), "TSS_UNAUTHORIZED", 401)
    expect_err("伪造令牌 → 401 TSS_UNAUTHORIZED",
               call("GET", "/wx/v1/me", token="forged.token.value"), "TSS_UNAUTHORIZED", 401)
    st, j = call("POST", "/wx/v1/auth/login", body={"code": "MOCK_DISABLED"})
    dis_tok = j["data"]["sessionToken"]
    expect_err("停用绑定访问 → 403 TSS_BINDING_DISABLED",
               call("GET", "/wx/v1/todos", token=dis_tok), "TSS_BINDING_DISABLED", 403)
    expect_err("limit=101 越界 → 400 TSS_INVALID_ARGUMENT",
               call("GET", "/wx/v1/todos?limit=101", token=tok), "TSS_INVALID_ARGUMENT", 400)
    expect_err("非法 docType → 400 TSS_INVALID_ARGUMENT",
               call("GET", "/wx/v1/todos?docType=XXX", token=tok), "TSS_INVALID_ARGUMENT", 400)
    bad = (cur[:-3] + "AAA") if len(cur) > 3 else "tampered"
    expect_err("篡改游标 → 400 TSS_INVALID_CURSOR",
               call("GET", "/wx/v1/todos?cursor=" + urllib.parse.quote(bad), token=tok),
               "TSS_INVALID_CURSOR", 400)
    expect_err("微信令牌访问管理端 → 401 TSS_UNAUTHORIZED（管理员受理表内无此令牌）",
               call("GET", "/admin/v1/tss/mappings", token=tok), "TSS_UNAUTHORIZED", 401)
    expect_err("解绑 confirmation 非法 → 400 TSS_INVALID_ARGUMENT",
               call("POST", "/wx/v1/bindings/unbind", token=tok,
                    body={"confirmation": "YES", "clientNonce": "n1"}),
               "TSS_INVALID_ARGUMENT", 400)

    # ---------- 跨员工隔离 ----------
    st, j = call("POST", "/wx/v1/auth/login", body={"code": "MOCK_BOUND_BOB"})
    bob = j["data"]["sessionToken"]
    st, j = call("GET", "/wx/v1/todos", token=bob)
    check("跨员工隔离：BOB 只看到自己的待办",
          len(j["data"]["items"]) == 1
          and j["data"]["items"][0]["approvalId"] == "a_900007_900008")
    # RC3-D1 裁决修正：同企业内、资源存在但不属于本人 → 403 TSS_FORBIDDEN
    # （原来是 404；裁决理由见 CHANGELOG §12 —— 404 的权威文案是"资源不存在或不可见"，
    #   而这条资源确实存在，只是无权，用 404 属于语义错配）
    expect_err("跨员工访问他人待办详情 → 403 TSS_FORBIDDEN（同企业内无权）",
               call("GET", "/wx/v1/todos/900002/detail", token=bob), "TSS_FORBIDDEN", 403)
    st, j = call("GET", "/wx/v1/history", token=bob)
    check("跨员工隔离：历史按人隔离",
          len(j["data"]["items"]) == 1 and j["data"]["items"][0]["recordId"] == "r_4")

    # ---------- RC3 新增回归 ----------
    # 每组独立包裹：任何一组异常只登记为 FAIL，不会吞掉末尾的合计行
    run_group(t_workflow_fields)
    run_group(t_filters)
    run_group(t_freshness)
    run_group(t_binding_consistency)
    run_group(t_error_code_rulings)
    run_group(t_admin_token)
    run_group(t_permission_and_order)
    run_group(t_round4_fixtures)
    run_group(t_admin_role_mapping)
    run_group(t_write_guard_probe)
    run_group(t_erp_fail_closed)
    run_group(t_error_code_coverage)

    return _summary()


def _summary():
    total = len(_results)
    failed = [r for r in _results if not r[1]]
    print("-" * 100)
    print("合计 %d 项，通过 %d，失败 %d" % (total, total - len(failed), len(failed)))
    for n, _, d in failed:
        print("  [FAIL] %s  %s" % (n, d))
    print("=" * 100)
    return 1 if failed else 0


if __name__ == "__main__":
    rc = 1
    started = False
    try:
        if not BASE:
            if not start_local_server():
                sys.stderr.write("[FATAL] 本地临时服务启动失败，无法自检\n")
                for line in _admin_log_lines[:20]:
                    sys.stderr.write("        %s\n" % line)
                sys.exit(1)
            started = True
        elif not ADMIN:
            # 故障注入分支只用微信会话即可跑完，无需管理员令牌
            if (os.environ.get("TSS_MOCK_FAILURE") or "none") == "none":
                sys.stderr.write("[FATAL] 指定了 TSS_MOCK_BASE 时必须同时提供 TSS_ADMIN_TOKEN"
                                 "（从服务端启动日志 [ADMIN] 行获取）\n")
                sys.exit(1)
        rc = main()
    finally:
        if started:
            stop_local_server()
    sys.exit(rc)
