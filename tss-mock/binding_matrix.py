#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
绑定五态交叉验证矩阵（RC3）—— 专供 Python Mock 与 Java 后端逐行比对使用。

每个格子都是**真实 HTTP** 取回来的值，不写死、不推断：
  ① 先打 GET /wx/v1/me
  ② 再打 GET /wx/v1/bindings/status
  ③ 最后（仅在 state=="解绑后" 等需要时）打业务接口验证是否 403 TSS_NOT_BOUND

用法：
    python3 binding_matrix.py                          # 自动拉起临时实例（默认端口 9001）
    TSS_MOCK_BASE=http://<java-or-python>:9000 python3 binding_matrix.py

输出：Markdown 表格 + 「与 Java 侧基线不一致」自动标注。
"""
import json
import os
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
BASE = os.environ.get("TSS_MOCK_BASE")
_proc = None
_log_lines = []

# Java 侧实测基线（team-lead 提供）+ 三项裁决后的**共同目标**：
#   裁决 A：②③ 的 me.bindStatus 取 PENDING（Java 侧改），理由见 CHANGELOG §10
#   裁决 B：④B「经 PC 确认完成绑定」后 bindRequestId 归 null（Python 侧改，已完成）
#   裁决 C：DISABLED 调 unbind 维持 TSS_BINDING_DISABLED（两侧均如此）
JAVA_BASELINE = {
    "未绑定":   ("UNBOUND", "UNBOUND", "null"),
    "申请中":   ("PENDING", "PENDING_PC_CONFIRM", "br_xxx"),
    "待PC确认": ("PENDING", "PENDING_PC_CONFIRM", "br_xxx"),
    "已绑定":   ("BOUND", "BOUND", "null"),
    "解绑后":   ("UNBOUND", "UNBOUND", "null"),
}


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
    global BASE, _proc
    port = int(os.environ.get("TSS_MOCK_PORT", "9001"))
    if port_busy(port):
        sys.stderr.write("[FATAL] 端口 %d 已被占用（多半是遗留的旧服务进程）。\n"
                         "        请先结束该进程，或改用 TSS_MOCK_PORT 换端口后重跑。\n" % port)
        return False
    env = dict(os.environ)
    env["TSS_MOCK_PORT"] = str(port)
    env.pop("TSS_ADMIN_TOKEN", None)
    env["TSS_MOCK_SECRET"] = "matrix-secret-000333"
    _proc = subprocess.Popen([sys.executable, SERVER], env=env,
                             stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                             text=True, encoding="utf-8", errors="replace")
    threading.Thread(target=_pump, args=(_proc,), daemon=True).start()
    BASE = "http://127.0.0.1:%d" % port
    last = time.time() + 15
    while time.time() < last:
        try:
            urllib.request.urlopen(BASE + "/healthz", timeout=2).read()
            return True
        except Exception:
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


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def login(code):
    st, j = call("POST", "/wx/v1/auth/login", body={"code": code})
    assert st == 200, (st, j)
    return j["data"]["sessionToken"]


def pair(tok, br_id=None):
    """真实取一组 (me, status)。"""
    _, me = call("GET", "/wx/v1/me", token=tok)
    path = "/wx/v1/bindings/status"
    if br_id:
        path += "?bindRequestId=" + urllib.parse.quote(br_id)
    st, stt = call("GET", path, token=tok)
    return me["data"], stt["data"], st


def business_probe(tok):
    st, j = call("GET", "/wx/v1/todos", token=tok)
    return "%d/%s" % (st, j.get("code"))


def build_rows():
    """严格按五态顺序推进，每一步都用真实 HTTP 取值。返回 (rows, raws)。"""
    rows = []
    raws = {}

    def snap(key, tok, br_id=None, how=""):
        _, me = call("GET", "/wx/v1/me", token=tok)
        path = "/wx/v1/bindings/status"
        if br_id:
            path += "?bindRequestId=" + urllib.parse.quote(br_id)
        st, stt = call("GET", path, token=tok)
        _, todo = call("GET", "/wx/v1/todos", token=tok)
        raws[key] = {"how": how, "me": me, "status": stt, "todos": todo}
        return me["data"], stt["data"], stt["data"]["bindRequestId"]

    # ① 未绑定
    t1 = login("MOCK_NEW_%d" % int(time.time() * 1000))
    me, stt, _ = snap("未绑定", t1, None, "任意非预置 code 登录")
    rows.append(("未绑定", "任意非预置 code 登录", me["bindStatus"], stt["status"],
                 stt["bindRequestId"], stt["erpUserMasked"], business_probe(t1)))

    # ② 申请中（预置待 PC 确认账号，登录即持有申请单）
    t2 = login("MOCK_PENDING")
    me, stt, _ = snap("申请中", t2, None, "MOCK_PENDING 登录（已有申请单）")
    rows.append(("申请中", "MOCK_PENDING 登录（已有申请单）", me["bindStatus"], stt["status"],
                 stt["bindRequestId"], stt["erpUserMasked"], business_probe(t2)))

    # ③ 待 PC 确认（新用户经 API-09 发起，并按 bindRequestId 精确查询）
    t3 = login("MOCK_NEW_PC_%d" % int(time.time() * 1000))
    _, j = call("POST", "/wx/v1/bindings/claim", token=t3,
                body={"inviteCode": "INV-DEMO-0001", "claimNonce": "mtx_%d" % int(time.time() * 1000)})
    br3 = j["data"]["bindRequestId"]
    me, stt, _ = snap("待PC确认", t3, br3, "API-09 claim 后按 id 精确查")
    rows.append(("待PC确认", "API-09 claim 后按 id 精确查", me["bindStatus"], stt["status"],
                 stt["bindRequestId"], stt["erpUserMasked"], business_probe(t3)))

    # ④ 已绑定 —— 两种来源分别取值
    t4a = login("MOCK_BOUND")
    me, stt, _ = snap("已绑定(A)", t4a, None, "预置 MOCK_BOUND 登录（无申请单）")
    rows.append(("已绑定(A)", "预置 MOCK_BOUND 登录（无申请单）", me["bindStatus"], stt["status"],
                 stt["bindRequestId"], stt["erpUserMasked"], business_probe(t4a)))

    call("POST", "/__mock/pc-confirm", body={"bindRequestId": br3})
    me, stt, _ = snap("已绑定(B)", t3, None, "③ 经 PC 确认完成绑定（申请单已归档）")
    rows.append(("已绑定(B)", "③ 经 PC 确认完成绑定（申请单已归档）", me["bindStatus"], stt["status"],
                 stt["bindRequestId"], stt["erpUserMasked"], business_probe(t3)))

    # ⑤ 解绑后
    call("POST", "/wx/v1/bindings/unbind", token=t3,
         body={"confirmation": "UNBIND", "clientNonce": "mtx_ub_%d" % int(time.time() * 1000)})
    me, stt, _ = snap("解绑后", t3, None, "④B 执行 API-11 解绑")
    rows.append(("解绑后", "④B 执行 API-11 解绑", me["bindStatus"], stt["status"],
                 stt["bindRequestId"], stt["erpUserMasked"], business_probe(t3)))

    # 附加：停用 / 申请过期
    t6 = login("MOCK_DISABLED")
    me, stt, _ = snap("(附加)停用", t6, None, "MOCK_DISABLED 登录")
    rows.append(("(附加)停用", "MOCK_DISABLED 登录", me["bindStatus"], stt["status"],
                 stt["bindRequestId"], stt["erpUserMasked"], business_probe(t6)))

    t7 = login("MOCK_PENDING")
    _, j7 = call("GET", "/wx/v1/bindings/status", token=t7)
    call("POST", "/__mock/expire", body={"bindRequestId": j7["data"]["bindRequestId"]})
    me, stt, _ = snap("(附加)申请过期", t7, None, "__mock/expire 后轮询")
    rows.append(("(附加)申请过期", "__mock/expire 后轮询", me["bindStatus"], stt["status"],
                 stt["bindRequestId"], stt["erpUserMasked"], business_probe(t7)))
    return rows, raws


def main():
    started = False
    if not BASE:
        if not start_server():
            sys.stderr.write("[FATAL] 临时服务启动失败\n")
            for line in _log_lines[:20]:
                sys.stderr.write("        %s\n" % line)
            return 1
        started = True
    try:
        rows, raws = build_rows()
    finally:
        if started:
            stop_server()

    print("=" * 118)
    print("绑定五态交叉验证矩阵（Python Mock / RC3）  base=%s" % BASE)
    print("全部格子为真实 HTTP 取值：GET /wx/v1/me + GET /wx/v1/bindings/status")
    print("=" * 118)
    print("| 状态 | 构造方式 | me.bindStatus | status.status | bindRequestId | erpUserMasked | GET /wx/v1/todos |")
    print("|---|---|---|---|---|---|---|")
    for name, how, bs, stt, brid, masked, probe in rows:
        print("| %s | %s | `%s` | `%s` | `%s` | `%s` | %s |"
              % (name, how, bs, stt,
                 "null" if brid is None else brid,
                 "null" if masked is None else masked, probe))
    print()
    print("--- 与 Java 侧基线逐格 diff ---")
    diff_found = []
    for name, how, bs, stt, brid, masked, probe in rows:
        key = {"已绑定(A)": "已绑定", "已绑定(B)": "已绑定"}.get(name, name)
        base = JAVA_BASELINE.get(key)
        if not base:
            continue
        cur = (bs, stt, "null" if brid is None else "br_xxx")
        if cur != base:
            diff_found.append((name, base, cur))
            print("  ✗ %-12s Java=%s  Python=%s" % (name, base, cur))
        else:
            print("  ✓ %-12s 完全一致" % name)
    print()
    if diff_found:
        print("结论：存在 %d 处不一致，需 team-lead 裁决后统一。" % len(diff_found))
    else:
        print("结论：五态矩阵与 Java 侧基线逐格一致。")

    # 关键四格原始响应体（供逐字节比对，不做任何加工）
    print()
    print("--- 关键四格原始 HTTP 响应体（未加工）---")
    for key in ("已绑定(A)", "已绑定(B)", "解绑后", "(附加)停用"):
        r = raws.get(key)
        if not r:
            continue
        print()
        print("### %s  （%s）" % (key, r["how"]))
        print("GET /wx/v1/me")
        print(json.dumps(r["me"], ensure_ascii=False, indent=2))
        print("GET /wx/v1/bindings/status")
        print(json.dumps(r["status"], ensure_ascii=False, indent=2))
        print("GET /wx/v1/todos")
        print(json.dumps(r["todos"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
