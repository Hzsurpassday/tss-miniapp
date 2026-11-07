#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
时空智审 V1.0 —— /wx/v1 契约 MOCK 服务（RC3）

【定位】
  只用于**联调**：让小程序端 / PC 管理端在不接触真实 ERP、不配置微信 secret 的前提下，
  按冻结契约跑通全部 13 个接口的正常与异常分支。

【RC3 相对 RC1 Mock 的整改（契约侧零语义变更，见 contract/CHANGELOG-RC2-RC3.md）】
  R1 API-05 工作流字段补齐：definitionVersion / readOnly / events 必填输出，
     移除 RC1 Mock 自行添加的 workflowVersion（该字段不属于 RC2/RC3 契约）。
     - definitionVersion：流程**定义**版本（审批流本身的版本）
     - dataVersion      ：该实例的**数据内容**版本（单据数据快照版本）
     两者语义不同、取值来源不同，**禁止混用**（见 build_workflow() 注释）。
  R2 API-04/06 补齐筛选与分页：实现 keyword / action / from / to；
     管线固定为「先筛选 → 再稳定排序 → 再游标分页」；游标绑定筛选条件。
  R3 新鲜度统一：删除 SummaryData.dataFreshness，全局唯一来源 data.meta.freshness，
     枚举只能 FRESH/DELAYED/UNAVAILABLE。
  R4 绑定状态一致性：/wx/v1/me 与 /wx/v1/bindings/status 由同一会话状态推导，
     status 表达绑定关系、bindRequestId 表达申请流程，二者不得互相矛盾。
  R5 管理员令牌：删除硬编码永久密钥，改由 TSS_ADMIN_TOKEN 提供；
     未设置时启动时生成临时随机值，仅写入 stderr，**不入代码、不入文档、不入制品**。

【严格遵守的冻结决策（契约《一、冻结决策》12 条）】
  1. 统一信封 {code,message,data,traceId,timestamp}；错误时 data=null 且保留对应 HTTP 状态码
  2. 身份只由服务端会话推导；请求体里的 userId/entId 一律不作为授权依据（本服务根本不接受）
  3. dataMode 恒为 MOCK（除非 TSS_MOCK_DATA_MODE=ERP 显式声明，且此时才允许 ERP 语义）
  4. MOCK 不伪装真实审批完成：resultVerified=false、isFinalApproved=false、
     downstreamReceiptStatus=NOT_VERIFIED、availableActions 只下发 ["VIEW"]
  5. 金额 / 重量一律十进制字符串
  6. cursor 为后端签名不透明令牌，绑定 (用户,企业,筛选,快照)；篡改或过期 → TSS_INVALID_CURSOR
  7. 稳定排序：待办 updatedAt DESC, taskNum DESC, approvalId DESC；
     历史 processedAt DESC, recordId DESC
  8. 未知字段值为 null + verified=false，不捏造
  9. API-09 之后状态只能是 PENDING_PC_CONFIRM（PC 确认动作不在本 MOCK 内伪造，
     仅通过 /__mock/ 控制面显式推进）
 10. workflow 内嵌于 API-05，不提供独立 /workflow 端点；readOnly 恒为 true
 11. 采购入库在本 ERP 库内未配置审批流程 → 待办恒为 0 条，不伪造数据
 12. V1.0 无任何审批写回接口

【运行】
  python3 server.py                        # 监听 0.0.0.0:9000
  TSS_MOCK_PORT=9001 python3 server.py     # 换端口（推荐，避免与旧进程冲突）
【环境变量】
  TSS_MOCK_SECRET              游标/会话签名密钥（默认随机，重启即失效）
  TSS_MOCK_PORT                端口，默认 9000
  TSS_ADMIN_TOKEN              管理员令牌；**不设置时自动生成临时随机值并只打印到 stderr**
  TSS_MOCK_CTRL_TOKEN          控制面令牌（无默认）；不设置则 /__mock/* 仅 loopback 来源可用，
                               设置后须带 X-Mock-Ctrl-Token 头匹配；**禁止写入代码/文档/制品、禁止回显**
  TSS_MOCK_FRESHNESS           初始新鲜度 FRESH|DELAYED|UNAVAILABLE，默认 FRESH
  TSS_MOCK_DATA_MODE           MOCK（默认）/ ERP
  TSS_MOCK_RATE_LIMIT          每令牌每分钟上限，0=不限（默认 0）
  TSS_MOCK_FAILURE             wechat | erp_read | erp_read:PAYMENT | none（默认 none）
  TSS_MOCK_LOOSE_EXAMPLES      true 时输出契约示例原文（含 resultVerified=true）；
                               默认 false=严格诚实模式（不伪装真实审批完成）
"""

import base64
import hashlib
import hmac
import ipaddress
import json
import os
import re
import secrets
import sys
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

CN = timezone(timedelta(hours=8))
SCHEMA_VERSION = "1.0.0"
SNAPSHOT = "mock-20261031-001"
RC_VERSION = "1.0.0-rc3"

# ------------------------------------------------------------------ 契约常量
# 新鲜度枚举（RC3 唯一合法取值；历史值 STALE 已废弃，代码中不得出现）
FRESHNESS_ENUM = ("FRESH", "DELAYED", "UNAVAILABLE")

# 待办 / 历史允许的动作枚举（历史 action 查询参数与 HistoryItem.action 同源）
HISTORY_ACTIONS = ("APPROVED", "REJECTED", "RETURNED", "SAVED", "UNKNOWN")

# 工作流事件：HistoryItem.action → WorkflowEvent.actionType 的确定性映射
ACTION_TO_MENU = {
    "APPROVED": "审批通过",
    "REJECTED": "审批驳回",
    "RETURNED": "退回发起人",
    "SAVED": "保存",
    "STARTED": "保存并提交",
    "UNKNOWN": "未知操作",
}

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

# 错误消息后缀：不暴露内部细节，仅用于定位哪一类前置校验失败
_ARG_HINTS = {
    "docType": "docType 非法",
    "keyword": "keyword 超长（上限 100）",
    "action": "action 非法",
    "date": "from/to 日期非法（要求 YYYY-MM-DD）",
    "range": "from 不得晚于 to",
    "inviteCode": "inviteCode 不在受理白名单内",
}

# ------------------------------------------------------------------ 错误码表
# 来源：error-codes.csv（与契约《三、统一错误码》一致，20 条）
ERRORS = {
    "TSS_INVALID_ARGUMENT":      (400, "参数无效", False),
    "TSS_INVALID_CURSOR":        (400, "分页游标无效或过期", False),
    "TSS_UNAUTHORIZED":          (401, "未登录或令牌无效", False),
    "TSS_SESSION_EXPIRED":       (401, "登录已过期", False),
    "TSS_NOT_BOUND":             (403, "当前微信尚未绑定员工", False),
    "TSS_FORBIDDEN":             (403, "无权查看该资源", False),
    "TSS_ENT_SCOPE_DENIED":      (403, "无权访问企业数据", False),
    "TSS_BINDING_DISABLED":      (403, "绑定已停用", False),
    "TSS_ADMIN_FORBIDDEN":       (403, "非管理人员", False),
    "TSS_NOT_FOUND":             (404, "资源不存在或不可见", False),
    "TSS_BIND_INVITE_EXPIRED":   (409, "绑定邀请已过期", False),
    "TSS_BIND_CONFLICT":         (409, "已有冲突绑定", False),
    "TSS_IDEMPOTENCY_CONFLICT":  (409, "相同幂等键内容不同", False),
    "TSS_DATA_STALE":            (409, "数据版本已变化", True),
    "TSS_RATE_LIMITED":          (429, "请求频率过高", True),
    "TSS_WECHAT_UNAVAILABLE":    (503, "微信服务不可用或未配置", True),
    "TSS_ERP_READ_UNAVAILABLE":  (503, "ERP只读数据不可用", True),
    "TSS_NOT_CONFIGURED":        (503, "服务能力尚未配置", False),
    "TSS_INTERNAL_ERROR":        (500, "服务异常", True),
    "TSS_WRITE_DISABLED":        (403, "当前版本禁止真实审批写回", False),
}

DOC_TYPES = ["PURCHASE_INBOUND", "SALES_BUSINESS", "PAYMENT", "SALES_PRICING"]

# 邀请码白名单（RC3-D4）：MOCK 服务只受理这些码。
# 前两个是异常分支专用码（用于稳定复现 409），其余是正常可用码。
# 白名单之外的任何取值（含"看起来像邀请码"的随机串）→ 400 TSS_INVALID_ARGUMENT。
INVITE_SPECIAL_CODES = ("INV-EXPIRED", "INV-CONFLICT")
INVITE_NORMAL_CODES = ("INV-PRESET", "INV-DEMO-0001", "INV-DEMO-0002")
INVITE_CODE_WHITELIST = tuple(list(INVITE_SPECIAL_CODES) + list(INVITE_NORMAL_CODES))


class TssError(Exception):
    """统一业务错误：只携带 TSS_* 稳定错误码与可选定位提示。"""

    def __init__(self, code, hint=None):
        super().__init__(code)
        self.code = code
        self.hint = hint


def now_iso():
    return datetime.now(CN).isoformat(timespec="seconds")


def env(k, d=None):
    v = os.environ.get(k)
    return d if v is None or v == "" else v


def resolve_admin_tokens():
    """解析管理员「令牌 → 角色」映射（RC3 补裁 ③）。

    安全要求（RC3）：源码/文档中不得存在任何硬编码永久密钥。
    支持两种环境变量（可同时给）：
      - `TSS_ADMIN_TOKENS='tok1=ADMIN,tok2=USER'`：角色映射。
        `tok1` 有效且是管理员 → 放行；`tok2` 有效但非管理员 → 403 `TSS_ADMIN_FORBIDDEN`。
      - `TSS_ADMIN_TOKEN=<单令牌>`：等价于 `<令牌>=ADMIN`（向后兼容旧用法）。
      - 两者都未设置 → 生成一个临时随机 ADMIN 令牌（仅本次进程有效），只写入 stderr。

    为什么要角色映射：权威错误码里 `TSS_ADMIN_FORBIDDEN` 的文案是「非管理人员」，
    隐含"我们知道你是谁、但你不是管理员"。只有「令牌有效但角色不是 ADMIN」这一档才对得上，
    没有角色概念就只能靠"令牌不匹配"冒充，而那其实是 401「未登录或令牌无效」。
    返回：({token: role}, source)
    """
    mapping = {}
    raw = env("TSS_ADMIN_TOKENS")
    if raw:
        for item in raw.split(","):
            item = item.strip()
            if not item:
                continue
            tok, _, role = item.partition("=")
            tok = tok.strip()
            role = (role.strip() or "ADMIN").upper()
            if tok:
                mapping[tok] = role
    single = env("TSS_ADMIN_TOKEN")
    if single:
        mapping[single] = "ADMIN"
    if mapping:
        return mapping, "env"
    return {secrets.token_urlsafe(32): "ADMIN"}, "ephemeral"


_ADMIN_TOKENS, _ADMIN_TOKEN_SOURCE = resolve_admin_tokens()
# 联调用：取第一个 ADMIN 角色的令牌（角色映射场景下可能还有 USER 角色令牌）
_ADMIN_TOKEN = next((t for t, r in _ADMIN_TOKENS.items() if r == "ADMIN"),
                    next(iter(_ADMIN_TOKENS)))

CFG = {
    "secret": env("TSS_MOCK_SECRET") or secrets.token_hex(16),
    "port": int(env("TSS_MOCK_PORT", "9000")),
    "admin_token": _ADMIN_TOKEN,
    "admin_tokens": _ADMIN_TOKENS,
    "admin_token_source": _ADMIN_TOKEN_SOURCE,
    "data_mode": env("TSS_MOCK_DATA_MODE", "MOCK"),
    "rate_limit": int(env("TSS_MOCK_RATE_LIMIT", "0")),
    "failure": env("TSS_MOCK_FAILURE", "none"),
    "loose": env("TSS_MOCK_LOOSE_EXAMPLES", "false").lower() == "true",
    "freshness": (env("TSS_MOCK_FRESHNESS", "FRESH") or "FRESH").upper(),
    # 控制面令牌：无默认值、只接受环境变量注入；未配置时 /__mock/* 仅 loopback 可用。
    # 绝不硬编码、绝不通过 HTTP / 日志回显明文（沿用 resolve_admin_tokens() 的安全范式）。
    "ctrl_token": env("TSS_MOCK_CTRL_TOKEN"),
}

# 新鲜度是全局唯一来源，配置非法直接拒绝启动，避免在线上吐出枚举外取值。
if CFG["freshness"] not in FRESHNESS_ENUM:
    sys.stderr.write("[FATAL] TSS_MOCK_FRESHNESS=%r 非法，"
                     "允许值：%s\n" % (CFG["freshness"], "/".join(FRESHNESS_ENUM)))
    sys.exit(2)

# ------------------------------------------------------------------ 签名工具
def _b64e(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def _b64d(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _sign(payload: dict) -> str:
    body = _b64e(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode())
    sig = hmac.new(CFG["secret"].encode(), body.encode(), hashlib.sha256).digest()[:16]
    return body + "." + _b64e(sig)


def _verify(token: str, invalid_code: str = "TSS_UNAUTHORIZED") -> dict:
    """校验签名令牌。

    invalid_code：签名/格式不合法时抛出的错误码。会话用 TSS_UNAUTHORIZED，
    游标用 TSS_INVALID_CURSOR（契约要求：游标过期或篡改必须显式区分，
    不能借用会话语义的 401）。
    """
    if not token or "." not in token:
        raise TssError(invalid_code)
    body, sig = token.rsplit(".", 1)
    want = hmac.new(CFG["secret"].encode(), body.encode(), hashlib.sha256).digest()[:16]
    if not hmac.compare_digest(sig, _b64e(want)):
        raise TssError(invalid_code)
    try:
        return json.loads(_b64d(body))
    except Exception:
        raise TssError(invalid_code)


def new_session(sid, uid, ent, bind_status, scope="WX"):
    return _sign({"sid": sid, "uid": uid, "ent": ent, "bs": bind_status,
                  "exp": int(time.time()) + 7200, "scope": scope, "snap": SNAPSHOT})


def new_cursor(offset, ctx):
    """游标绑定 (用户, 企业, 筛选条件, 快照, 排序管线版本)。

    ctx["filter"] 为规范化后的筛选指纹；改筛选条件后旧游标必然验签不过。
    """
    return _sign({"o": offset, "u": ctx["uid"], "e": ctx["ent"], "f": ctx["filter"],
                  "s": SNAPSHOT, "p": ctx.get("pipeline", "v1"),
                  "exp": int(time.time()) + 900})


def read_cursor(token, ctx):
    p = _verify(token, invalid_code="TSS_INVALID_CURSOR")
    if p.get("exp", 0) < int(time.time()):
        raise TssError("TSS_INVALID_CURSOR")
    # 游标与调用方身份/筛选/快照/排序管线绑定：换人或换筛选条件即失效
    if p.get("u") != ctx["uid"] or p.get("e") != ctx["ent"] \
            or p.get("f") != ctx["filter"] or p.get("s") != SNAPSHOT \
            or p.get("p") != ctx.get("pipeline", "v1"):
        raise TssError("TSS_INVALID_CURSOR")
    return int(p.get("o", 0))


# ------------------------------------------------------------------ MOCK 数据
# 说明：ERP 侧实测口径来自本库 INFORMATION_SCHEMA 直连（见 dherp-kb/docs/13 §15.1）：
#   销售开单 SaleNotesMt 只有 TaxAmount（无 Amount）
#   付款申请 PayApplyMt 无金额列，金额 = SUM(PayApplyDt.zhifje)
#   销售定价 KF_SalePriceMt 无金额列，金额 = SUM(KF_SalePriceDt.Taxprice)（口径待业务确认）
#   采购入库（采购小合同 PURNOTESMT）本库未配置审批流程 → 待办恒 0
MAPPINGS = [
    {
        "docType": "PURCHASE_INBOUND", "formId": "0dqkuagm3iw04e0a",
        "displayName": "采购入库审批", "erpName": "采购小合同",
        "masterTable": "PURNOTESMT", "amountExpr": "m.Amount",
        "wfCode": None, "definitionVersion": "NONE",
        "validationLevel": "PARTIAL",
        "note": "本 ERP 库未给采购小合同配置审批流程，当前不会产生待办；"
                "真增库存的采购入库单 PurInMt 亦无流程。",
        "fields": [
            {"key": "supplier", "label": "供应商", "sourceColumn": "SuppliersId",
             "nullable": True, "verified": True},
            {"key": "amount", "label": "无税金额", "sourceColumn": "Amount",
             "nullable": True, "verified": True},
        ],
    },
    {
        "docType": "SALES_BUSINESS", "formId": "87rflp785rg9ptt0",
        "displayName": "销售业务审批", "erpName": "销售开单（销售小合同）",
        "masterTable": "SaleNotesMt", "amountExpr": "m.TaxAmount",
        "wfCode": "dq.xskd", "definitionVersion": "v1",
        "validationLevel": "PARTIAL",
        "note": "销售开单主表只有 TaxAmount，不存在 Amount 列（易踩坑）。",
        "fields": [
            {"key": "client", "label": "客户", "sourceColumn": "ClientId",
             "nullable": True, "verified": True},
            {"key": "amount", "label": "含税金额", "sourceColumn": "TaxAmount",
             "nullable": True, "verified": True},
        ],
    },
    {
        "docType": "PAYMENT", "formId": "3p29u0c2dku4th49",
        "displayName": "付款审批", "erpName": "付款申请单",
        "masterTable": "PayApplyMt", "amountExpr": "SUM(PayApplyDt.zhifje)",
        "wfCode": "dq.fksq", "definitionVersion": "v1",
        "validationLevel": "PARTIAL",
        "note": "付款申请主表无金额列、无 Summaries（只有 Remark）；金额取自明细 zhifje。",
        "fields": [
            {"key": "payee", "label": "收款方", "sourceColumn": "PayApplyDt.BusinessName",
             "nullable": True, "verified": True},
            {"key": "amount", "label": "支付金额", "sourceColumn": "PayApplyDt.zhifje",
             "nullable": True, "verified": True},
        ],
    },
    {
        "docType": "SALES_PRICING", "formId": "26k6gqyucbyylov9",
        "displayName": "销售定价审批", "erpName": "商品基本价格调整",
        "masterTable": "KF_SalePriceMt", "amountExpr": "SUM(KF_SalePriceDt.Taxprice)",
        "wfCode": "dq.jgxg", "definitionVersion": "v1",
        "validationLevel": "PENDING",
        "note": "主表无金额列；Taxprice 为单价，求和口径待业务确认，故列表 amount 暂置 null。",
        "fields": [
            {"key": "scope", "label": "调价范围", "sourceColumn": "RuleId",
             "nullable": True, "verified": True},
            {"key": "amount", "label": "金额", "sourceColumn": None,
             "nullable": True, "verified": False},
        ],
    },
]

BY_DOC_TYPE = {m["docType"]: m for m in MAPPINGS}

# 待办数据（按用户隔离）。updatedAt 用于稳定排序主键。
TODOS = {
    "U-DEMO-000001": [
        {"approvalId": "a_900001_900002", "taskNum": "900002", "instanceId": "900001",
         "billNo": "2026100001", "docType": "SALES_BUSINESS", "title": "销售业务审批",
         "counterparty": "示例客户", "amount": "19800.00",
         "currentNodeName": "风控审核", "createdAt": "2026-10-30T09:15:00+08:00",
         "updatedAt": "2026-10-31T09:15:00+08:00", "status": "PENDING"},
        {"approvalId": "a_900003_900004", "taskNum": "900004", "instanceId": "900003",
         "billNo": "2026100002", "docType": "PAYMENT", "title": "付款审批",
         "counterparty": "示例供应商", "amount": "8600.00",
         "currentNodeName": "财务复核", "createdAt": "2026-10-30T11:00:00+08:00",
         "updatedAt": "2026-10-30T16:40:00+08:00", "status": "PENDING"},
        {"approvalId": "a_900005_900006", "taskNum": "900006", "instanceId": "900005",
         "billNo": "2026100003", "docType": "SALES_PRICING", "title": "销售定价审批",
         "counterparty": None, "amount": None,
         "currentNodeName": "价格审核", "createdAt": "2026-10-29T14:20:00+08:00",
         "updatedAt": "2026-10-29T14:20:00+08:00", "status": "PENDING"},
    ],
    "U-BOB-000001": [
        {"approvalId": "a_900007_900008", "taskNum": "900008", "instanceId": "900007",
         "billNo": "2026100004", "docType": "SALES_BUSINESS", "title": "销售业务审批",
         "counterparty": "客户乙", "amount": "5300.00",
         "currentNodeName": "部门经理审批", "createdAt": "2026-10-31T08:00:00+08:00",
         "updatedAt": "2026-10-31T08:00:00+08:00", "status": "PENDING"},
    ],
    # 空 events 夹具（RC3 覆盖缺口①）：由 ERP 同步产生、工作流引擎尚未落任何流转事件的单据。
    # noWorkflowEvents=True 是夹具侧的显式声明 —— 连"提交"事件都没有证据。
    # 此时 workflow.events 必须是 [] 且字段不得省略，不得以 null / 省略 / 伪提交事件偷换语义。
    # 注意：不能用 createdAt 反推提交事件 —— createdAt 是待办自身的产生时间，
    # 不等于工作流已落 STARTED 事件；编造提交事件会让人误以为流程已启动。
    "U-EVT-000001": [
        {"approvalId": "a_920001_920002", "taskNum": "920002", "instanceId": "920001",
         "billNo": "2026101001", "docType": "SALES_BUSINESS", "title": "销售业务审批",
         "counterparty": "新发起客户", "amount": "4500.00",
         "currentNodeName": "提交待受理", "createdAt": "2026-10-31T09:00:00+08:00",
         "updatedAt": "2026-10-31T09:00:00+08:00", "status": "PENDING",
         "noWorkflowEvents": True},
    ],
    # 关键字筛选回归专用夹具：含英文往来单位，用于验证大小写不敏感匹配与跨页
    "U-KEY-000001": [
        {"approvalId": "a_910001_910002", "taskNum": "910002", "instanceId": "910001",
         "billNo": "AC-1001", "docType": "SALES_BUSINESS", "title": "销售业务审批",
         "counterparty": "Acme Steel Ltd", "amount": "1200.00",
         "currentNodeName": "风控审核", "createdAt": "2026-10-30T10:00:00+08:00",
         "updatedAt": "2026-10-31T10:00:00+08:00", "status": "PENDING"},
        {"approvalId": "a_910003_910004", "taskNum": "910004", "instanceId": "910003",
         "billNo": "AC-1002", "docType": "PAYMENT", "title": "付款审批",
         "counterparty": "Beta Trade Co", "amount": "880.50",
         "currentNodeName": "财务复核", "createdAt": "2026-10-29T10:00:00+08:00",
         "updatedAt": "2026-10-30T10:00:00+08:00", "status": "PENDING"},
        {"approvalId": "a_910005_910006", "taskNum": "910006", "instanceId": "910005",
         "billNo": "AC-2001", "docType": "SALES_PRICING", "title": "销售定价审批",
         "counterparty": "Gamma Materials", "amount": None,
         "currentNodeName": "价格审核", "createdAt": "2026-10-28T10:00:00+08:00",
         "updatedAt": "2026-10-29T10:00:00+08:00", "status": "PENDING"},
    ],
    # 跨企业夹具（RC3 覆盖缺口②）：另一企业 E-OTHER-000002 的已绑定身份。
    # 用于让 TSS_ENT_SCOPE_DENIED / 跨企业可见性判断能被真实打出并复跑验证。
    "U-OTHER-000001": [
        {"approvalId": "a_930001_930002", "taskNum": "930002", "instanceId": "930001",
         "billNo": "2026102001", "docType": "PAYMENT", "title": "付款审批",
         "counterparty": "其他企业供应商", "amount": "7700.00",
         "currentNodeName": "财务复核", "createdAt": "2026-10-30T15:00:00+08:00",
         "updatedAt": "2026-10-31T11:00:00+08:00", "status": "PENDING"},
    ],
}

HISTORY = {
    "U-DEMO-000001": [
        {"recordId": "r_1", "approvalId": "a_900011_900012", "taskNum": "900012",
         "billNo": "2026090001", "docType": "SALES_BUSINESS", "action": "APPROVED",
         "actionName": "审批完成", "processedAt": "2026-10-28T17:20:00+08:00",
         "operatorDisplay": "示例员工"},
        {"recordId": "r_2", "approvalId": "a_900013_900014", "taskNum": "900014",
         "billNo": "2026090002", "docType": "PAYMENT", "action": "RETURNED",
         "actionName": "退回发起人", "processedAt": "2026-10-27T10:05:00+08:00",
         "operatorDisplay": "示例员工"},
        {"recordId": "r_3", "approvalId": "a_900015_900016", "taskNum": "900016",
         "billNo": "2026090003", "docType": "SALES_PRICING", "action": "SAVED",
         "actionName": "保存", "processedAt": "2026-10-26T15:30:00+08:00",
         "operatorDisplay": "示例员工"},
    ],
    "U-BOB-000001": [
        {"recordId": "r_4", "approvalId": "a_900017_900018", "taskNum": "900018",
         "billNo": "2026090004", "docType": "SALES_BUSINESS", "action": "APPROVED",
         "actionName": "审批完成", "processedAt": "2026-10-25T09:00:00+08:00",
         "operatorDisplay": "员工乙"},
    ],
}

NOTIFICATIONS = {
    "U-DEMO-000001": [
        {"id": "n_1", "title": "待办提醒", "body": "您有 1 条销售业务审批待办",
         "type": "NEW_TODO", "createdAt": "2026-10-31T09:15:00+08:00",
         "readAt": None, "approvalId": "a_900001_900002"},
        {"id": "n_2", "title": "任务变更", "body": "付款审批 2026100002 已转交财务复核",
         "type": "TASK_CHANGED", "createdAt": "2026-10-30T16:40:00+08:00",
         "readAt": "2026-10-30T17:00:00+08:00", "approvalId": "a_900003_900004"},
    ],
    "U-BOB-000001": [
        {"id": "n_3", "title": "待办提醒", "body": "您有 1 条销售业务审批待办",
         "type": "NEW_TODO", "createdAt": "2026-10-31T08:00:00+08:00",
         "readAt": None, "approvalId": "a_900007_900008"},
    ],
}

# 登录 code → 身份。其余 code 一律视为新用户（未绑定）。
LOGIN_CODES = {
    "MOCK_BOUND":      {"uid": "U-DEMO-000001", "ent": "E-DEMO-000001", "name": "示例员工",
                        "bs": "BOUND", "ver": 3},
    "MOCK_PENDING":    {"uid": None, "ent": None, "name": None,
                        "bs": "PENDING", "ver": None},
    "MOCK_DISABLED":   {"uid": "U-DISABLED01", "ent": "E-DEMO-000001", "name": "停用员工",
                        "bs": "DISABLED", "ver": 2},
    "MOCK_BOUND_BOB":  {"uid": "U-BOB-000001", "ent": "E-DEMO-000001", "name": "员工乙",
                        "bs": "BOUND", "ver": 1},
    # 关键字筛选回归夹具账号：3 条含英文往来单位的待办（验证大小写不敏感与跨页）
    "MOCK_KEYWORD":    {"uid": "U-KEY-000001", "ent": "E-DEMO-000001", "name": "关键字回归员工",
                        "bs": "BOUND", "ver": 1},
    # 空 events 夹具账号：1 条"刚发起、尚无任何流转事件"的待办 → workflow.events == []
    "MOCK_EMPTY_EVT":  {"uid": "U-EVT-000001", "ent": "E-DEMO-000001", "name": "空事件员工",
                        "bs": "BOUND", "ver": 1},
    # 跨企业已绑定账号（另一企业 E-OTHER-000002）→ 让 TSS_ENT_SCOPE_DENIED 可被真实打出
    "MOCK_OTHER_ENT":  {"uid": "U-OTHER-000001", "ent": "E-OTHER-000002", "name": "其他企业员工",
                        "bs": "BOUND", "ver": 1},
}

# uid → entId 映射：唯一来源是上表，避免可见性判断与登录身份各说一套。
# 跨企业 / 同企业越权的判定完全依赖它。
USER_ENT = {v["uid"]: v["ent"] for v in LOGIN_CODES.values() if v["uid"]}

# ------------------------------------------------------------------ 运行时状态
LOCK = threading.Lock()
STATE = {
    "sessions": {},        # sid -> session dict
    "bind_requests": {},   # bindRequestId -> {id,sid,inviteCode,status,expiresAt,uid,ent}
    "nonces": {},          # nonce -> request fingerprint（幂等）
    "consents": {},        # sid -> {templateIds, consented, recordedAt}
    "rate": {},            # token -> [ts,...]
}


def meta():
    """RC3 新鲜度唯一来源：data.meta.freshness。

    SummaryData 已按 RC2 决策移除 dataFreshness，任何响应都不得再出现第二个新鲜度字段。
    """
    return {"dataMode": CFG["data_mode"], "asOf": now_iso(),
            "freshness": CFG["freshness"], "schemaVersion": SCHEMA_VERSION}


def field(key, label, value, fmt, verified):
    return {"key": key, "label": label, "value": value, "format": fmt, "verified": verified}


# ------------------------------------------------------------------ 工具函数
def check_rate(token_key):
    n = CFG["rate_limit"]
    if n <= 0:
        return
    now = time.time()
    with LOCK:
        hits = [t for t in STATE["rate"].get(token_key, []) if now - t < 60]
        if len(hits) >= n:
            STATE["rate"][token_key] = hits
            raise TssError("TSS_RATE_LIMITED")
        hits.append(now)
        STATE["rate"][token_key] = hits


def erp_read_guard(doc_type=None):
    """ERP 只读数据源门禁（RC3-B1：fail-closed）。

    ① `TSS_MOCK_DATA_MODE=ERP` 而本进程并未接入真实 ERP 只读通道时，
       **所有业务数据端点一律 503 TSS_ERP_READ_UNAVAILABLE、data=null**。
       这样做是必须的：声明 ERP 数据源却返回 MOCK 夹具，调用方与评审方都会把它
       读成"真实 ERP 数据"，比直接报错危险得多（RC1 的 README 承诺了这一安全属性，
       但实现里根本不存在）。**不得返回空列表** —— 空列表会被读成"确实没有待办"。
    ② 故障注入 `TSS_MOCK_FAILURE=erp_read[:DOC_TYPE]` → 同样 503。
    """
    if CFG["data_mode"] == "ERP":
        raise TssError("TSS_ERP_READ_UNAVAILABLE")
    f = CFG["failure"]
    if f == "erp_read":
        raise TssError("TSS_ERP_READ_UNAVAILABLE")
    if f.startswith("erp_read:") and doc_type == f.split(":", 1)[1]:
        raise TssError("TSS_ERP_READ_UNAVAILABLE")


# 全局故障注入 → 错误码。用于把「静态已定义、默认路径打不出来」的码变成可复跑的运行时路径。
# 说明：这些码本轮仍**没有**默认业务路径（见 CHANGELOG §15 的错误码覆盖表），
# 这里只提供显式的 TSS_MOCK_FAILURE 注入入口，不改变任何默认行为。
GLOBAL_FAILURE_CODES = {
    "data_stale": "TSS_DATA_STALE",            # 409
    "rate_limited": "TSS_RATE_LIMITED",        # 429
    "not_configured": "TSS_NOT_CONFIGURED",    # 503
    "internal": "TSS_INTERNAL_ERROR",          # 500
}


def global_failure_guard():
    """全局故障注入门禁：命中即抛对应权威错误码，未命中（none / 其它值）不做任何事。"""
    code = GLOBAL_FAILURE_CODES.get(CFG["failure"])
    if code:
        raise TssError(code)


# ------------------------------------------------------------- 写回冻结探针
# V1.0 冻结决策：不得提供任何真实审批写回端点。契约 `paths` 只有 13 条，
# 其中**不存在**任何写回路径，`availableActions` 恒为 `["VIEW"]`。
#
# 因此 TSS_WRITE_DISABLED 的唯一运行时触发路径放在 **DEV 支持命名空间**
# `/dev-support/v1/tss/` 下（与 Java 的 DevBindingFixtureController 同级），
# 而**不是**挂在 `/wx/v1/**` 下 —— 理由：
#   ① 在契约命名空间里挂 approve/reject/transfer 之类的写回路径，等于宣称
#      "这些路径是我们设计的一部分、只是当前禁用"，与 RC2 决议「V1.0 不开放
#      真实 APPROVE/REJECT 写回」的对外表述直接冲突；
#   ② 会制造跨端差异（Java 侧对不存在的路径是 404），也会让契约符合性扫描
#      报出"说好 13 条、实际还有 9 条"。
# `/wx/v1/**` 保持 13 条纯净：不存在的路径一律 404，与 Java 侧同形。
WRITE_GUARD_PROBE_PATH = "/dev-support/v1/tss/write-guard-probe"
# 探针不挑 method：写回语义本来就与 method 无关，只支持 POST 会让人用 GET 一试
# 看到 404 就怀疑我们没实现。
WRITE_GUARD_PROBE_METHODS = ("GET", "POST", "PUT", "PATCH", "DELETE")


def write_guard_probe(handler, body, q, m):
    """DEV 支持面探针：验证「V1.0 禁止真实审批写回」这条版本级决策。

    注意语义：它表达的是「**写回被禁止**」，**不是**「我们已经拦截并实现了写回」。
    V1.0 契约里没有任何写回端点，所以这个码本就不存在业务触发路径 ——
    本探针只是让它可被复跑验证，不改变"无写回端点"这个事实。
    """
    raise TssError("TSS_WRITE_DISABLED")


def auth_session(handler):
    """解析并校验微信会话；返回 session dict。"""
    h = handler.headers.get("Authorization", "")
    if not h.startswith("Bearer "):
        raise TssError("TSS_UNAUTHORIZED")
    token = h[7:].strip()
    p = _verify(token)
    if p.get("scope") != "WX":
        raise TssError("TSS_UNAUTHORIZED")
    if p.get("exp", 0) < int(time.time()):
        raise TssError("TSS_SESSION_EXPIRED")
    sid = p.get("sid")
    s = STATE["sessions"].get(sid)
    if s is None or s.get("token") != token:
        raise TssError("TSS_UNAUTHORIZED")
    return s


def require_bound(s):
    if s["bs"] == "DISABLED":
        raise TssError("TSS_BINDING_DISABLED")
    if s["bs"] != "BOUND" or not s.get("uid"):
        raise TssError("TSS_NOT_BOUND")
    return s


def auth_admin(handler):
    """管理员鉴权（RC3-D7 补裁：三档分工，与 Java `AdminTokenService.resolve()` 同形）。

      - 未提供 `Authorization: Bearer`（或为空串） → 401 `TSS_UNAUTHORIZED`（未登录或令牌无效）
      - 提供了但令牌不在受理表内（无效令牌）       → 401 `TSS_UNAUTHORIZED`
      - 令牌有效但角色不是 ADMIN                  → 403 `TSS_ADMIN_FORBIDDEN`（非管理人员）

    第三档依赖 `TSS_ADMIN_TOKENS='tok=ADMIN,tok2=USER'` 的角色映射；
    没有 USER 角色令牌时该档无法触发，需显式配置（/healthz 会说明是否已启用角色映射）。
    令牌来源只能是环境变量或启动时生成的临时随机值；服务内不存在任何硬编码默认密钥。
    """
    h = handler.headers.get("Authorization", "")
    # ① 压根没带凭据 → 401「未登录或令牌无效」（不是"非管理员"）
    if not h.startswith("Bearer ") or not h[7:].strip():
        raise TssError("TSS_UNAUTHORIZED")
    tok = h[7:].strip()
    # ② 查角色表；查不到 = 令牌无效 → 401（不是 403）
    role = None
    for known, known_role in CFG["admin_tokens"].items():
        if hmac.compare_digest(tok, known):
            role = known_role
            break
    if role is None:
        raise TssError("TSS_UNAUTHORIZED")
    # ③ 令牌有效但非管理员 → 403
    if role != "ADMIN":
        raise TssError("TSS_ADMIN_FORBIDDEN")


def paginate(items, cursor, limit, ctx):
    """游标分页。调用方必须保证 items 已完成「筛选 → 稳定排序」。"""
    offset = read_cursor(cursor, ctx) if cursor else 0
    if offset < 0 or offset > len(items):
        raise TssError("TSS_INVALID_CURSOR")
    page = items[offset:offset + limit]
    has_more = offset + limit < len(items)
    return page, {
        "nextCursor": new_cursor(offset + limit, ctx) if has_more else None,
        "hasMore": has_more,
        "totalApprox": len(items),
    }


def sort_todos(items):
    """契约要求的稳定排序（待办）：updatedAt DESC, taskNum DESC, approvalId DESC。"""
    return sorted(items, key=lambda x: (x["updatedAt"], x["taskNum"], x["approvalId"]),
                  reverse=True)


def sort_history(items):
    """契约要求的稳定排序（历史）：processedAt DESC, recordId DESC。"""
    return sorted(items, key=lambda x: (x["processedAt"], x["recordId"]), reverse=True)


def idempotency_guard(nonce, fingerprint):
    """相同 nonce 但内容不同 → 409；相同 nonce 且内容相同 → 幂等重放。"""
    with LOCK:
        old = STATE["nonces"].get(nonce)
        if old is None:
            STATE["nonces"][nonce] = fingerprint
            return False
        if old != fingerprint:
            raise TssError("TSS_IDEMPOTENCY_CONFLICT")
        return True


def qparam(q, name):
    """读取单值 query 参数；未传返回 None。重复传参按首个取值（与 RC1 行为一致）。"""
    vals = q.get(name)
    if not vals:
        return None
    v = vals[0]
    return v.strip() if isinstance(v, str) else v


def parse_date_arg(value):
    """解析日期参数。未传 / 空串视为不过滤；有值但格式非法 → 400 TSS_INVALID_ARGUMENT。

    非法判定覆盖 `2026-13-45`、`20261028`、`2026/10/28` 等一切非 YYYY-MM-DD 取值。
    """
    if value is None or value == "":
        return None
    if not DATE_RE.match(value):
        raise TssError("TSS_INVALID_ARGUMENT", "date")
    try:
        datetime.strptime(value, "%Y-%m-%d")
    except ValueError:
        raise TssError("TSS_INVALID_ARGUMENT", "date")
    return value


def build_filter_ctx(prefix, **filters):
    """构造游标绑定的筛选指纹。

    只有参与筛选的参数才进指纹；顺序固定，保证同一查询的后续分页语义一致。
    """
    parts = []
    for k in sorted(filters.keys()):
        v = filters[k]
        if v not in (None, ""):
            parts.append("%s=%s" % (k, v))
    return "|".join([prefix] + parts)


# ------------------------------------------------------------------ API 实现
def api_01_login(handler, body, q, m):
    if CFG["failure"] == "wechat":
        raise TssError("TSS_WECHAT_UNAVAILABLE")
    code = (body or {}).get("code")
    if not isinstance(code, str) or not code.strip():
        raise TssError("TSS_INVALID_ARGUMENT")
    who = LOGIN_CODES.get(code.strip(), {"uid": None, "ent": None, "name": None,
                                         "bs": "UNBOUND", "ver": None})
    sid = "sid_" + uuid.uuid4().hex[:16]
    token = new_session(sid, who["uid"], who["ent"], who["bs"])
    s = {"sid": sid, "token": token, "uid": who["uid"], "ent": who["ent"],
         "name": who["name"], "bs": who["bs"], "ver": who["ver"],
         "created": now_iso(), "last_bind_request": None}
    with LOCK:
        STATE["sessions"][sid] = s
        # RC3-R4：MOCK_PENDING 代表「已提交申请、待 PC 确认」，
        # 因此登录即落成一条同语义的申请单，保证 me 与 status 互证不矛盾。
        if who["bs"] == "PENDING":
            s["last_bind_request"] = _create_bind_request_locked(s)
    return {"sessionToken": token, "expiresIn": 7200,
            "bindStatus": who["bs"], "needBinding": who["bs"] != "BOUND"}


def advance_bind_state(s):
    """推进会话绑定状态机，供 me 与 status 共用（调用方需持 LOCK）。

    RC3-R4 要求两个端点任何时刻都看到同一份状态，因此状态推进不能只发生在
    status 端点内：me 也必须走同一函数，避免调用顺序影响观测结果。

    本函数是申请单生命周期的**唯一执行点**（不是展现层修饰）：
      ① 过期推进：PENDING_PC_CONFIRM 且已过期 → EXPIRED，关系侧回落 UNBOUND
      ② 终态归档：申请流程一旦终结（BOUND / REJECTED 等），即刻从 STATE 中移除，
         并断开会话引用 —— 从而保证 `bindRequestId` 只表达「尚未终结的申请流程」。
    调用方需持有 LOCK。
    """
    br_id = s.get("last_bind_request")
    br = STATE["bind_requests"].get(br_id) if br_id else None
    if br is None or br.get("sid") != s["sid"]:
        return None
    if br["status"] == "PENDING_PC_CONFIRM" and br["expiresAt"] < now_iso():
        br["status"] = "EXPIRED"
        if s["bs"] == "PENDING":
            s["bs"] = "UNBOUND"
    # 终态归档：只有「待 PC 确认」「已过期（需用户感知并重发）」「已驳回（需用户看到结果）」
    # 三种未终结状态保留 id；其余（如 BOUND）即从 STATE 中移除。
    if br["status"] not in KEEP_REQUEST_STATUSES:
        STATE["bind_requests"].pop(br["id"], None)
        if s.get("last_bind_request") == br["id"]:
            s["last_bind_request"] = None
        return None
    return br


def api_02_me(handler, body, q, m):
    s = auth_session(handler)
    with LOCK:
        advance_bind_state(s)
    return {"userId": s["uid"], "entId": s["ent"], "displayName": s["name"],
            "bindStatus": s["bs"], "bindingVersion": s["ver"],
            "enterpriseSwitchEnabled": False}


def api_03_summary(handler, body, q, m):
    s = require_bound(auth_session(handler))
    erp_read_guard()
    uid = s["uid"]
    items = TODOS.get(uid, [])
    counts = {
        "total": len(items),
        "purchaseInbound": len([i for i in items if i["docType"] == "PURCHASE_INBOUND"]),
        "salesBusiness": len([i for i in items if i["docType"] == "SALES_BUSINESS"]),
        "payment": len([i for i in items if i["docType"] == "PAYMENT"]),
        "salesPricing": len([i for i in items if i["docType"] == "SALES_PRICING"]),
    }
    # RC3-R3：不再输出 dataFreshness，新鲜度只存在于 meta.freshness
    return {"counts": counts, "lastFetchedAt": now_iso(), "meta": meta()}


def api_04_todos(handler, body, q, m):
    """API-04 待办列表。

    管线固定：**先筛选 → 再稳定排序 → 再游标分页**。
      - docType：枚举过滤
      - keyword：对单号 / 标题 / 往来单位做大小写不敏感子串匹配
    游标绑定筛选指纹，改筛选后复用旧游标 → TSS_INVALID_CURSOR。
    """
    s = require_bound(auth_session(handler))
    erp_read_guard()
    doc_type = qparam(q, "docType")
    if doc_type and doc_type not in DOC_TYPES:
        raise TssError("TSS_INVALID_ARGUMENT", "docType")
    erp_read_guard(doc_type)
    keyword = qparam(q, "keyword")
    if keyword is not None and len(keyword) > 100:
        raise TssError("TSS_INVALID_ARGUMENT", "keyword")
    keyword = keyword or None          # 空串视为未传
    limit = _limit(q)

    # ① 筛选
    items = list(TODOS.get(s["uid"], []))
    if doc_type:
        items = [i for i in items if i["docType"] == doc_type]
    if keyword:
        kw = keyword.lower()
        items = [i for i in items
                 if _match_keyword(i, kw)]
    # ② 稳定排序
    items = sort_todos(items)
    # ③ 游标分页
    ctx = {"uid": s["uid"], "ent": s["ent"], "pipeline": "todos-v1",
           "filter": build_filter_ctx("T", docType=doc_type, keyword=keyword)}
    page, pmeta = paginate(items, qparam(q, "cursor"), limit, ctx)
    out = [dict(i, currency="CNY", dataMode=CFG["data_mode"]) for i in page]
    out = [_todo_item(i) for i in out]
    return {"items": out, "page": pmeta, "meta": meta()}


def _match_keyword(item, kw_lower):
    """keyword 命中范围：单据编号 / 显示标题 / 往来单位；大小写不敏感子串。"""
    for k in ("billNo", "title", "counterparty"):
        v = item.get(k)
        if isinstance(v, str) and kw_lower in v.lower():
            return True
    return False


def _todo_item(i):
    """按 TodoItem schema 输出固定字段顺序（便于契约快照 diff）。"""
    return {
        "approvalId": i["approvalId"], "taskNum": i["taskNum"],
        "instanceId": i["instanceId"], "billNo": i["billNo"],
        "docType": i["docType"], "title": i["title"],
        "counterparty": i["counterparty"], "amount": i["amount"],
        "currency": i["currency"], "currentNodeName": i["currentNodeName"],
        "createdAt": i["createdAt"], "updatedAt": i["updatedAt"],
        "status": i["status"], "dataMode": i["dataMode"],
    }


def build_workflow(t, mp, s, uid):
    """构造 API-05 内嵌 workflow（端点级：所有 taskNum / 所有 docType 均走此函数）。

    【版本字段语义，禁止混用】
      definitionVersion —— 流程**定义**版本：审批流程图本身的版本是否迭代
                           （节点/边拓扑变更才递增），与单据数据无关。
      dataVersion      —— 实例**数据内容**版本：该待办所引用单据数据的快照版本
                           （数据刷新/重新取数才变化），不表达流程拓扑。
      readOnly         —— V1.0 恒 true，不因 ERP 存在可执行动作而开放审批。
      events           —— 实际已发生的流转事件，按 occurredAt 升序；
                          可为空数组但不得省略；未证实的事件不得编造。
    """
    current_node = "risk" if t["docType"] == "SALES_BUSINESS" else "approve"
    events = build_workflow_events(t, s, uid)
    return {
        "workflowId": mp["wfCode"] or "unknown",
        "definitionVersion": mp["definitionVersion"],
        "instanceId": t["instanceId"],
        "currentNodeIds": [current_node],
        "nodes": [
            {"nodeId": "start", "name": "提交", "nodeType": "START", "status": "COMPLETED",
             "assignees": [], "completedAt": t["createdAt"], "branchGroup": None,
             "parallelMode": "NONE"},
            {"nodeId": current_node, "name": t["currentNodeName"], "nodeType": "APPROVAL",
             "status": "ACTIVE", "assignees": [s["name"] or "本人"], "completedAt": None,
             "branchGroup": None, "parallelMode": "NONE"},
            {"nodeId": "end", "name": "结束", "nodeType": "END", "status": "PENDING",
             "assignees": [], "completedAt": None, "branchGroup": None,
             "parallelMode": "NONE"},
        ],
        "edges": [{"from": "start", "to": current_node, "label": None},
                  {"from": current_node, "to": "end", "label": None}],
        "graphType": "LINEAR",
        "dataVersion": "20261031-001",
        "readOnly": True,
        "events": events,
    }


def build_workflow_events(t, s, uid):
    """确定性生成 events（按 occurredAt 升序）。

    只输出有证据的事件：
      ① 提交事件：来自待办自身的 createdAt；
      ② 已归档陈述：同一 approvalId 的历史记录（若有），动作按固定映射转换。
    没有任何证据时不编造，返回空数组而非省略字段。
    夹具显式声明 noWorkflowEvents 时（如 U-EVT-000001）直接返回 []。
    """
    if t.get("noWorkflowEvents"):
        # 「零事件」与「字段缺失」是两件事：这里返回 []，字段在 build_workflow 里照常输出。
        return []
    operator = s["name"] or "本人"
    # 提交事件以 createdAt 为证据；无证据时不编造，events 保持为空数组（而非省略该字段）
    events = []
    if t.get("createdAt"):
        events.append({
            "eventId": "evt_%s_0" % t["taskNum"],
            "nodeId": "start",
            "actionType": "STARTED",
            "menuName": ACTION_TO_MENU["STARTED"],
            "operatorDisplay": operator,
            "occurredAt": t["createdAt"],
            "outcome": "SUCCESS",
            "serialNum": t["instanceId"],
            "comment": None,
        })
    hist = [h for h in HISTORY.get(uid, []) if h["approvalId"] == t["approvalId"]]
    for idx, h in enumerate(sorted(hist, key=lambda x: x["processedAt"]), start=1):
        action = h["action"] if h["action"] in ACTION_TO_MENU else "UNKNOWN"
        events.append({
            "eventId": "evt_%s_%d" % (t["taskNum"], idx),
            "nodeId": "start",
            "actionType": action,
            "menuName": ACTION_TO_MENU[action],
            "operatorDisplay": h["operatorDisplay"],
            "occurredAt": h["processedAt"],
            "outcome": "SUCCESS" if action != "UNKNOWN" else "UNKNOWN",
            "serialNum": h["taskNum"],
            "comment": None,
        })
    return sorted(events, key=lambda x: (x["occurredAt"], x["eventId"]))


def find_todo_owner(task_num):
    """按 taskNum 在全部夹具中定位归属用户（越权分级的唯一依据）。

    返回 (owner_uid, todo)；不存在返回 (None, None)。
    """
    for uid, items in TODOS.items():
        for i in items:
            if i["taskNum"] == task_num:
                return uid, i
    return None, None


def api_05_detail(handler, body, q, m):
    """API-05 详情。

    【越权三分支，各司其职】（RC3-D1，见 CHANGELOG §12）
      完全不存在                     → 404 TSS_NOT_FOUND「资源不存在或不可见」
      同企业内、资源存在但不属于本人  → 403 TSS_FORBIDDEN「无权查看该资源」
      跨企业                         → 403 TSS_ENT_SCOPE_DENIED「无权访问企业数据」
    三个码不再混用：跨企业有专门的码，不该退回通用 FORBIDDEN。
    """
    s = require_bound(auth_session(handler))
    erp_read_guard()
    task_num = m["taskNum"]
    own = [i for i in TODOS.get(s["uid"], []) if i["taskNum"] == task_num]
    if not own:
        owner_uid, _ = find_todo_owner(task_num)
        if owner_uid is None:
            # 全库都不存在 → 404
            raise TssError("TSS_NOT_FOUND")
        if USER_ENT.get(owner_uid) != s["ent"]:
            # 资源存在，但归属另一企业 → 跨企业越权（专用码）
            raise TssError("TSS_ENT_SCOPE_DENIED")
        # 同企业、资源存在、不属于本人 → 通用无权
        raise TssError("TSS_FORBIDDEN")
    t = own[0]
    mp = BY_DOC_TYPE[t["docType"]]
    erp_read_guard(t["docType"])

    loose = CFG["loose"]
    base = [
        field("billNo", "单据编号", t["billNo"], "TEXT", True),
        field("billCode", "单据号", t["docType"][:2] + "-" + t["billNo"], "TEXT", True),
        field("docType", "单据类型", mp["displayName"], "TEXT", True),
        field("amount", "金额", t["amount"], "AMOUNT", t["docType"] != "SALES_PRICING"),
        field("currentNode", "当前节点", t["currentNodeName"], "BADGE", True),
        field("sourceTable", "来源表", mp["masterTable"], "TEXT", True),
    ]
    if t["docType"] == "SALES_PRICING":
        base.append(field("amountNote", "金额说明",
                          "主表无金额列，Taxprice 求和口径待业务确认，暂不展示",
                          "TEXT", True))
    lines = [{"lineId": "1", "fields": [
        field("goodsSpec", "规格", None, "TEXT", False),
        field("weightTons", "重量（吨）", None, "WEIGHT_TON", False),
        field("goodsName", "品名", None, "TEXT", False),
    ]}]
    workflow = build_workflow(t, mp, s, s["uid"])
    return {
        "approvalId": t["approvalId"], "taskNum": t["taskNum"], "docType": t["docType"],
        "base": base, "lines": lines, "workflow": workflow,
        "sourceStatus": {
            "sourceName": mp["masterTable"],
            "isFinalApproved": True if loose else False,
            "downstreamReceiptStatus": "COMPLETE" if loose else "NOT_VERIFIED",
            "inventoryChanged": True if loose else None,
            "lastVerifiedAt": t["updatedAt"] if loose else None,
            "notes": "MOCK 数据，未经 ERP 回查证实" if not loose else "契约示例原样输出",
        },
        "availableActions": ["VIEW"],   # 冻结决策：V1.0 不得下发 APPROVE/REJECT
        "meta": meta(),
    }


def api_06_history(handler, body, q, m):
    """API-06 历史记录。

    支持的筛选：docType / action / from / to（按 processedAt 闭区间）。
    日期格式非法或 from > to → 400 TSS_INVALID_ARGUMENT。
    管线固定：先筛选 → 再稳定排序 → 再游标分页。
    """
    s = require_bound(auth_session(handler))
    erp_read_guard()
    doc_type = qparam(q, "docType")
    if doc_type and doc_type not in DOC_TYPES:
        raise TssError("TSS_INVALID_ARGUMENT", "docType")
    action = qparam(q, "action")
    if action and action not in HISTORY_ACTIONS:
        raise TssError("TSS_INVALID_ARGUMENT", "action")
    action = action or None
    date_from = parse_date_arg(qparam(q, "from"))
    date_to = parse_date_arg(qparam(q, "to"))
    if date_from and date_to and date_from > date_to:
        raise TssError("TSS_INVALID_ARGUMENT", "range")
    limit = _limit(q)

    # ① 筛选
    items = list(HISTORY.get(s["uid"], []))
    if doc_type:
        items = [i for i in items if i["docType"] == doc_type]
    if action:
        items = [i for i in items if i["action"] == action]
    if date_from:
        items = [i for i in items if i["processedAt"][:10] >= date_from]
    if date_to:
        items = [i for i in items if i["processedAt"][:10] <= date_to]
    # ② 稳定排序
    items = sort_history(items)
    # ③ 游标分页
    ctx = {"uid": s["uid"], "ent": s["ent"], "pipeline": "history-v1",
           "filter": build_filter_ctx("H", docType=doc_type, action=action,
                                      **{"from": date_from, "to": date_to})}
    page, pmeta = paginate(items, qparam(q, "cursor"), limit, ctx)
    # 严格模式：MOCK 不伪装真实审批完成 → resultVerified 恒 false
    out = [dict(i, resultVerified=(True if CFG["loose"] else False)) for i in page]
    out = [_history_item(i) for i in out]
    return {"items": out, "page": pmeta, "meta": meta()}


def _history_item(i):
    """按 HistoryItem schema 输出固定字段顺序。"""
    return {
        "recordId": i["recordId"], "approvalId": i["approvalId"],
        "taskNum": i["taskNum"], "billNo": i["billNo"], "docType": i["docType"],
        "action": i["action"], "actionName": i["actionName"],
        "processedAt": i["processedAt"], "operatorDisplay": i["operatorDisplay"],
        "resultVerified": i["resultVerified"],
    }


def api_07_notifications(handler, body, q, m):
    s = require_bound(auth_session(handler))
    erp_read_guard()          # RC3-B1：ERP 模式下同样 fail-closed
    limit = _limit(q)
    items = sorted(NOTIFICATIONS.get(s["uid"], []),
                   key=lambda x: x["createdAt"], reverse=True)
    ctx = {"uid": s["uid"], "ent": s["ent"], "pipeline": "notif-v1",
           "filter": build_filter_ctx("N", kind="ALL")}
    page, pmeta = paginate(items, qparam(q, "cursor"), limit, ctx)
    return {"items": page, "page": pmeta, "meta": meta()}


def api_08_consent(handler, body, q, m):
    s = require_bound(auth_session(handler))
    b = body or {}
    tids = b.get("templateIds")
    consented = b.get("consented")
    if not isinstance(tids, list) or not tids or not isinstance(consented, bool):
        raise TssError("TSS_INVALID_ARGUMENT")
    recorded = now_iso()
    with LOCK:
        STATE["consents"][s["sid"]] = {"templateIds": tids, "consented": consented,
                                       "recordedAt": recorded}
    status = "GRANTED" if consented else "DENIED"
    return {"subscriptionStatus": status, "recordedAt": recorded}


def _create_bind_request_locked(s, invite="INV-PRESET"):
    """创建一条申请单（调用方持锁）。MOCK_PENDING 登录时用于落成待 PC 确认态。"""
    br_id = "br_" + uuid.uuid4().hex[:10]
    exp = (datetime.now(CN) + timedelta(minutes=10)).isoformat(timespec="seconds")
    STATE["bind_requests"][br_id] = {"id": br_id, "sid": s["sid"], "inviteCode": invite,
                                     "status": "PENDING_PC_CONFIRM", "expiresAt": exp,
                                     "uid": None, "ent": None}
    return br_id


def _existing_open_request(s):
    """返回会话当前持有、尚未过期且仍在等待 PC 确认的申请单。"""
    br_id = s.get("last_bind_request")
    if not br_id:
        return None
    br = STATE["bind_requests"].get(br_id)
    if br is None or br["sid"] != s["sid"]:
        return None
    if br["status"] != "PENDING_PC_CONFIRM":
        return None
    if br["expiresAt"] < now_iso():
        return None
    return br


def api_09_claim(handler, body, q, m):
    """API-09 申领绑定。

    邀请码只接受白名单（RC3-D4）：白名单外的码一律 400 TSS_INVALID_ARGUMENT，
    不允许"任意码都能成功"——否则调用方永远测不到非法邀请码这条分支，
    也会误以为申领接口不做任何校验。
    """
    s = auth_session(handler)
    if s["bs"] == "DISABLED":
        raise TssError("TSS_BINDING_DISABLED")
    if s["bs"] == "BOUND":
        raise TssError("TSS_BIND_CONFLICT")
    b = body or {}
    invite = b.get("inviteCode")
    nonce = b.get("claimNonce")
    if not isinstance(invite, str) or not invite.strip() \
            or not isinstance(nonce, str) or not nonce.strip():
        raise TssError("TSS_INVALID_ARGUMENT")
    invite = invite.strip()
    # 特殊邀请码先于一切短路判定，保证异常分支可被稳定复现
    if invite.upper() == "INV-EXPIRED":
        raise TssError("TSS_BIND_INVITE_EXPIRED")
    if invite.upper() == "INV-CONFLICT":
        raise TssError("TSS_BIND_CONFLICT")
    # 白名单校验：异常分支专用码 + 预置可用码，除此之外一律 400
    if invite.upper() not in INVITE_CODE_WHITELIST:
        raise TssError("TSS_INVALID_ARGUMENT", "inviteCode")
    idempotency_guard("claim:" + nonce.strip(), invite)
    with LOCK:
        # RC3-R4：会话已持有未过期申请单时返回同一条，避免出现两条互相矛盾的申请
        open_br = _existing_open_request(s)
        if open_br is not None:
            return {"bindRequestId": open_br["id"], "status": "PENDING_PC_CONFIRM",
                    "expiresAt": open_br["expiresAt"]}
        br_id = _create_bind_request_locked(s, invite)
        STATE["bind_requests"][br_id]["inviteCode"] = invite
        s["last_bind_request"] = br_id
        s["bs"] = "PENDING"
        br = STATE["bind_requests"][br_id]
        return {"bindRequestId": br["id"], "status": "PENDING_PC_CONFIRM",
                "expiresAt": br["expiresAt"]}


# me.bindStatus → BindingStatusData.status 的同义映射。
# 两者均由同一会话状态推导，不允许各自成一套。
SESSION_TO_BIND_STATUS = {
    "UNBOUND": "UNBOUND",
    "PENDING": "PENDING_PC_CONFIRM",
    "BOUND": "BOUND",
    "DISABLED": "DISABLED",
}

# 允许保留 bindRequestId 的申请单状态（INV-5）：
# 只有「尚未终结、需要用户继续操作」的状态才暴露 id；
# PC 确认完成（BOUND）即视为流程终结 → 归档，bindRequestId 归 null，
# 与「预置已绑定账号压根没有申请单」的响应形状完全一致。
#
# REJECTED 必须保留（RC3 补裁 ②）：申请被驳回恰恰是最需要保留 id 的场景 ——
# 用户要靠这个 id 才能查到"我的申请被驳回了、原因是什么"，驳回结果一拍即灭
# 等于把驳回这件事本身抹掉。契约 status 枚举里有 REJECTED，就必须能观测到它，
# 否则它就是一个契约死值（与裁决 A 反对「PENDING 永不出现」是同一条逻辑）。
KEEP_REQUEST_STATUSES = ("PENDING_PC_CONFIRM", "EXPIRED", "REJECTED")


def resolve_binding_view(s, explicit_br_id=None):
    """统一解析「绑定关系 + 申请流程」视图，保证 me 与 status 互证。

    不变量（INV-*，由 verify.py 断言）：
      INV-1 status=BOUND  ⟺ me.bindStatus=BOUND ⟺ erpUserMasked 非空
      INV-2 status=DISABLED ⟺ me.bindStatus=DISABLED
      INV-3 status∈{UNBOUND,PENDING_PC_CONFIRM,EXPIRED,REJECTED}
            ⟹ me.bindStatus∈{UNBOUND,PENDING} 且 erpUserMasked 为 null
      INV-4 bindRequestId 非 null 仅当会话当前持有该申请单
      INV-5 bindRequestId 非 null ⟺ status ∈ {PENDING_PC_CONFIRM, EXPIRED}
            （职责单一：bindRequestId 只表达「当前存在尚未终结的申请流程」；
              PC 确认完成即终结 → 归档 → 归 null，与"从未申请"形状一致。）
    """
    with LOCK:
        # 先跑同一状态机：过期推进 + 终态归档都在 advance_bind_state 里落地，
        # 因此这里拿到的申请单必然是「尚未终结」的那一条（或 None）。
        br = advance_bind_state(s)
        rel = SESSION_TO_BIND_STATUS.get(s["bs"], "UNBOUND")
        if explicit_br_id is not None:
            if br is None or br["id"] != explicit_br_id:
                # 不属于本会话、不存在、或已被归档 → 一律 404（不泄露是否曾经存在）
                raise TssError("TSS_NOT_FOUND")
            br_id = explicit_br_id
        else:
            br_id = br["id"] if br is not None else None
        if br is not None:
            if br["status"] == "EXPIRED":
                # status 表达的是申请流程结果；关系为 UNBOUND 时两者不冲突（INV-3）
                rel = "EXPIRED"
            elif br["status"] == "REJECTED":
                rel = "REJECTED"
            elif br["status"] == "PENDING_PC_CONFIRM":
                if s["bs"] in ("UNBOUND", "PENDING"):
                    rel = "PENDING_PC_CONFIRM"
        bound = (rel == "BOUND")
        return {
            "bindRequestId": br_id,
            "status": rel,
            "erpUserMasked": _mask(s.get("uid")) if bound else None,
            "entDisplayName": "示例企业" if bound else None,
            "checkedAt": now_iso(),
        }


def api_10_status(handler, body, q, m):
    s = auth_session(handler)
    return resolve_binding_view(s, qparam(q, "bindRequestId"))


def api_11_unbind(handler, body, q, m):
    """API-11 解绑。

    【判定顺序：先鉴权与绑定状态，后校验请求体】（RC3-D3，理由链见 CHANGELOG §13）
      未绑定 / 已停用身份 + 任意请求体 → 403（状态门禁先命中，不看 body）
      已绑定身份 + 非法 confirmation  → 400（body 校验后命中，与 RC2 一致）
    """
    s = auth_session(handler)
    # ① 绑定状态门禁必须先于请求体校验
    # RC3 语义选型（见 CHANGELOG-RC2-RC3.md §8）：
    #   未绑定身份请求解绑时，权威错误码统一为 403 TSS_NOT_BOUND。
    #   不用 409 TSS_BIND_CONFLICT —— 其权威文案是「已有冲突绑定」，
    #   而本场景根本不存在任何绑定，谈不上"冲突"，用它属于语义错配。
    #   停用是一种既存绑定关系（只是被停用），故不复用 NOT_BOUND，
    #   与 require_bound / API-09 对停用的处理保持一致。
    if s["bs"] == "DISABLED":
        raise TssError("TSS_BINDING_DISABLED")
    if s["bs"] != "BOUND":
        raise TssError("TSS_NOT_BOUND")
    # ② 请求体校验：只有已绑定身份才轮到它
    b = body or {}
    confirmation = b.get("confirmation")
    nonce = b.get("clientNonce")
    if confirmation != "UNBIND" or not isinstance(nonce, str) or not nonce.strip():
        raise TssError("TSS_INVALID_ARGUMENT")
    idempotency_guard("unbind:" + nonce.strip(), s["sid"])
    with LOCK:
        # RC3-R4：解绑后必须让 status 与 me 同时回到 UNBOUND。
        # 历史申请单随之作废（不再属于本会话），避免 status 仍指向一条 BOUND 申请。
        br_id = s.get("last_bind_request")
        if br_id:
            STATE["bind_requests"].pop(br_id, None)
        s.update({"uid": None, "ent": None, "name": None, "bs": "UNBOUND",
                  "ver": None, "last_bind_request": None})
    return {"revokedAt": now_iso(), "bindStatus": "UNBOUND"}


def api_12_admin_bindings(handler, body, q, m):
    auth_admin(handler)
    limit = _limit(q)
    items = [
        {"bindingId": "b_1", "userId": "U-DEMO-000001", "entId": "E-DEMO-000001",
         "openidMasked": "o_****", "bindStatus": "BOUND", "bindingVersion": 3,
         "updatedAt": "2026-10-31T09:00:00+08:00"},
        {"bindingId": "b_2", "userId": "U-BOB-000001", "entId": "E-DEMO-000001",
         "openidMasked": "o_****", "bindStatus": "BOUND", "bindingVersion": 1,
         "updatedAt": "2026-10-30T20:00:00+08:00"},
    ]
    ctx = {"uid": "ADMIN", "ent": "E-DEMO-000001", "pipeline": "admin-bindings-v1",
           "filter": build_filter_ctx("AB", kind="ALL")}
    page, pmeta = paginate(items, qparam(q, "cursor"), limit, ctx)
    return {"items": page, "page": pmeta,
            "auditInfo": {"accessLogged": True, "scope": "AUTHORIZED_ENT"}}


def api_13_admin_mappings(handler, body, q, m):
    auth_admin(handler)
    defs = []
    for mp in MAPPINGS:
        d = {"docType": mp["docType"], "formId": mp["formId"],
             "displayName": mp["displayName"], "validationLevel": mp["validationLevel"],
             "fields": mp["fields"]}
        defs.append(d)
    return {"definitions": defs, "validationLevel": "PARTIAL", "updatedAt": now_iso()}


def _limit(q):
    raw = qparam(q, "limit") or "20"
    try:
        n = int(raw)
    except ValueError:
        raise TssError("TSS_INVALID_ARGUMENT", "limit")
    if n < 1 or n > 100:
        raise TssError("TSS_INVALID_ARGUMENT", "limit")
    return n


def _mask(uid):
    if not uid or len(uid) < 4:
        return "****"
    return uid[:2] + "*" * (len(uid) - 5) + uid[-3:]


# ------------------------------------------------------------------ 控制面门禁
# /__mock/* 是**改数据状态**的联调后门，绝不能对公网匿名可达。
# 拒绝一律抛 TSS_NOT_FOUND（404）：与「路径不存在」完全同形，对公网隐藏控制面存在性，
# 不新增错误码、不改错误码字典。
CTRL_TOKEN_HEADER = "X-Mock-Ctrl-Token"
# loopback 字面量（含 IPv4-mapped IPv6 形态）；另用 ipaddress 兜住整个 127/8 与 ::1。
LOOPBACK_LITERALS = {"127.0.0.1", "::1", "localhost", "::ffff:127.0.0.1"}


def _is_loopback(peer: str) -> bool:
    """判断对端地址是否为 loopback。

    HTTP 层 handler.client_address[0] 通常是字符串 IP：先做字面量快判，
    再用 ipaddress 覆盖 127.0.0.0/8、::1、::ffff:127.0.0.1 等全部回环形态。
    解析失败（异常取值）一律按非 loopback 处理，fail-closed。
    """
    if not peer:
        return False
    if peer in LOOPBACK_LITERALS:
        return True
    try:
        return ipaddress.ip_address(peer).is_loopback
    except ValueError:
        return False


def ctrl_guard(handler, path: str):
    """控制面身份门禁：所有 /__mock/* 请求在路由分发前先过这道门。

    两种互斥模式：
      ① 显式配置了 TSS_MOCK_CTRL_TOKEN → 只认令牌，请求头 CTRL_TOKEN_HEADER
         必须与之一致（hmac.compare_digest 常量时间比较），不看来源 IP；
         用于确实需要远程控制面的场景。令牌只来自环境变量，禁止硬编码/回显。
      ② 未配置（默认）→ 只认来源，仅 loopback 可访问；非 loopback 一律 404。
         verify.py 本地自检连 127.0.0.1 且用 POST，因此不受影响。

    ⚠️ 反代场景下后端看到的 client_address 恒为 127.0.0.1，模式 ② 会失效：
       公网暴露必须前置 Nginx location 白名单（见 nginx-protect.conf.example），
       那一层是**必需主防线，不是可选项**。
    """
    if not path.startswith("/__mock/"):
        return
    configured = CFG["ctrl_token"]
    if configured:
        given = handler.headers.get(CTRL_TOKEN_HEADER) or ""
        # 统一编码成 bytes：避免非 ASCII 头值让 compare_digest 抛 TypeError；
        # 未带头（空串）也会走完比较并返回 False，绝不会短路成「放行」。
        if not hmac.compare_digest(given.encode("utf-8", "replace"),
                                   configured.encode("utf-8", "replace")):
            raise TssError("TSS_NOT_FOUND")
        return
    peer = handler.client_address[0] if handler.client_address else ""
    if not _is_loopback(peer):
        raise TssError("TSS_NOT_FOUND")


# ------------------------------------------------------------------ 控制面（非契约 API）
def ctrl_reset(handler, body, q, m):
    """重置运行时状态（联调用，不属于冻结契约）。

    只清业务会话 / 申请单 / 订阅同意记录。
    【保留限流桶 STATE["rate"] 与幂等记录 STATE["nonces"]】—— 这两者是服务自身的
    防护状态，绝不能由一次 reset 自我解除：否则可循环 reset 清空限流计数、
    重放幂等键，等于让控制面削弱自身防护。
    """
    with LOCK:
        STATE["sessions"].clear()
        STATE["bind_requests"].clear()
        STATE["consents"].clear()
    return {"reset": True, "at": now_iso()}


def ctrl_pc_confirm(handler, body, q, m):
    """模拟 PC 端确认绑定（契约明确该动作由 PC/后端另行实现，不在小程序接口内）。"""
    b = body or {}
    br_id = b.get("bindRequestId")
    uid = b.get("userId") or "U-DEMO-000001"
    ent = b.get("entId") or "E-DEMO-000001"
    with LOCK:
        br = STATE["bind_requests"].get(br_id)
        if not br:
            raise TssError("TSS_NOT_FOUND")
        if br["status"] == "EXPIRED" or br["expiresAt"] < now_iso():
            raise TssError("TSS_BIND_INVITE_EXPIRED")
        # 已终结的申请单（已绑定 / 已驳回）不能再被确认 —— 否则 REJECTED 会被"复活"成 BOUND，
        # 与「REJECTED 是需要用户感知的终态」自相矛盾
        if br["status"] in ("BOUND", "REJECTED"):
            raise TssError("TSS_BIND_CONFLICT")
        br["status"] = "BOUND"
        br["uid"] = uid
        br["ent"] = ent
        s = STATE["sessions"].get(br["sid"])
        if s:
            s.update({"uid": uid, "ent": ent, "name": "示例员工", "bs": "BOUND", "ver": 1})
    return {"bindRequestId": br_id, "status": "BOUND", "checkedAt": now_iso()}


def ctrl_pc_reject(handler, body, q, m):
    """模拟 PC 端驳回绑定申请（契约明确该动作由 PC/后端另行实现，不在小程序接口内）。

    存在的理由（RC3 补裁 ②）：契约 `status` 枚举里有 `REJECTED`，但如果没有任何入口
    能推进到它，它就是一个**契约死值** —— 与裁决 A 反对「`PENDING` 永不出现」是同一条逻辑。
    驳回后申请单**不归档**（`REJECTED ∈ KEEP_REQUEST_STATUSES`），
    `bindRequestId` 保持非空，用户才能据此查到驳回结果。
    关系侧回落 `UNBOUND`（与 EXPIRED 同处理）：申请被驳回即不存在绑定关系。
    """
    b = body or {}
    br_id = b.get("bindRequestId")
    reason = b.get("reason") or "PC 端驳回"
    with LOCK:
        br = STATE["bind_requests"].get(br_id)
        if not br:
            raise TssError("TSS_NOT_FOUND")
        # 已终结的申请单（已绑定 / 已驳回）不能再被确认 —— 否则 REJECTED 会被"复活"成 BOUND，
        # 与「REJECTED 是需要用户感知的终态」自相矛盾
        if br["status"] in ("BOUND", "REJECTED"):
            raise TssError("TSS_BIND_CONFLICT")
        br["status"] = "REJECTED"
        br["rejectReason"] = reason
        s = STATE["sessions"].get(br["sid"])
        if s and s["bs"] == "PENDING":
            s["bs"] = "UNBOUND"
    return {"bindRequestId": br_id, "status": "REJECTED", "reason": reason,
            "checkedAt": now_iso()}


def ctrl_expire(handler, body, q, m):
    """把指定申请单置为已过期（用于稳定复现「申请过期」分支，非契约 API）。"""
    b = body or {}
    br_id = b.get("bindRequestId")
    with LOCK:
        br = STATE["bind_requests"].get(br_id)
        if not br:
            raise TssError("TSS_NOT_FOUND")
        br["expiresAt"] = (datetime.now(CN) - timedelta(seconds=1)).isoformat(
            timespec="seconds")
        br["status"] = "PENDING_PC_CONFIRM"
    return {"bindRequestId": br_id, "expiresAt": br["expiresAt"]}


def ctrl_expire_session(handler, body, q, m):
    """把当前会话令牌重签为「已过期」版本（仅 MOCK 控制台）。

    用途：让 401 TSS_SESSION_EXPIRED 具备可复跑的运行时触发路径 ——
    否则该码只能靠"等 2 小时"或伪造签名才能出现，等于没有路径。
    """
    s = auth_session(handler)
    p = _verify(s["token"])
    p["exp"] = int(time.time()) - 10
    with LOCK:
        s["token"] = _sign(p)
    return {"sessionToken": s["token"], "expired": True, "note": "该令牌已过期，后续请求将返回 401 TSS_SESSION_EXPIRED"}


def ctrl_freshness(handler, body, q, m):
    """切换全局新鲜度（只能是 FRESH/DELAYED/UNAVAILABLE，非契约 API）。"""
    b = body or {}
    value = (b.get("value") or query_freshness(q) or "").upper()
    if value not in FRESHNESS_ENUM:
        raise TssError("TSS_INVALID_ARGUMENT", "freshness")
    CFG["freshness"] = value
    return {"freshness": value, "asOf": now_iso()}


def query_freshness(q):
    return qparam(q, "value")


# 6 个控制面端点统一映射：强制 POST only（缺陷修复 B），非 POST 一律 404。
# 注意：写回冻结探针 /dev-support/v1/tss/write-guard-probe 不在此列，
# 保持「故意不挑 method」原样，不受本次整改影响。
CTRL_ROUTES = {
    "/__mock/reset": ctrl_reset,
    "/__mock/pc-confirm": ctrl_pc_confirm,
    "/__mock/pc-reject": ctrl_pc_reject,
    "/__mock/expire": ctrl_expire,
    "/__mock/expire-session": ctrl_expire_session,
    "/__mock/freshness": ctrl_freshness,
}


# ------------------------------------------------------------------ 路由表
def _route(method, path):
    if path == "/healthz":
        return lambda h, b, q, m: {
            "service": "tss-contract-mock", "rcVersion": RC_VERSION,
            "schemaVersion": SCHEMA_VERSION, "dataMode": CFG["data_mode"],
            "snapshot": SNAPSHOT, "failureInjection": CFG["failure"],
            "looseExamples": CFG["loose"], "freshness": CFG["freshness"],
            "freshnessEnum": list(FRESHNESS_ENUM),
            # 只告知来源，绝不通过 HTTP 返回令牌明文
            "adminTokenSource": CFG["admin_token_source"],
            # 是否配置了 USER 角色令牌（决定 403 TSS_ADMIN_FORBIDDEN 能否被真实触发）
            "adminRoleMapping": sorted(set(CFG["admin_tokens"].values())),
            "loginCodes": sorted(LOGIN_CODES.keys()),
            # 自描述：ERP 模式下业务数据端点一律 503（fail-closed），此处只声明模式本身
            "erpReadChannelWired": False,
            "writeBackDisabled": True,
            # DEV 支持面探针路径（非契约端点）；Java 侧同级路径在 DEV 四重门禁下注册
            "writeGuardProbePath": WRITE_GUARD_PROBE_PATH,
            "writeGuardProbeMethods": list(WRITE_GUARD_PROBE_METHODS),
            "inviteCodeWhitelist": list(INVITE_CODE_WHITELIST),
            "note": "MOCK 联调服务；不含任何真实 ERP 凭据，也不含硬编码永久管理员密钥",
            "now": now_iso(),
        }
    # 控制面（非契约 API）：统一强制 POST only（缺陷修复 B）。
    # 非 POST 或未知 /__mock/* 路径 → 返回 None, None，走既有 404 TSS_NOT_FOUND，
    # 与「路径根本不存在」完全同形，不泄露控制面存在性。
    # 身份门禁在 _handle() 中、路由分发之前统一执行，见 ctrl_guard()。
    if path.startswith("/__mock/"):
        ctrl = CTRL_ROUTES.get(path)
        if ctrl is None or method != "POST":
            return None, None
        return ctrl, {}
    # 写回冻结探针（DEV 支持命名空间，非契约端点）。
    # 【门禁顺序】必须放在路由表之前、且不挑 method：
    #   写回被禁止是**版本级决策**，与"ERP 数据源通不通""当前有没有注入故障"无关，
    #   因此它的优先级高于 global_failure_guard 与 erp_read_guard ——
    #   这条路径同时被加进了这两道门禁的豁免名单，见 _handle() 与 erp_read_guard()。
    if path == WRITE_GUARD_PROBE_PATH:
        return write_guard_probe, {}
    table = [
        ("POST", re.compile(r"^/wx/v1/auth/login$"), api_01_login, None),
        ("GET",  re.compile(r"^/wx/v1/me$"), api_02_me, None),
        ("GET",  re.compile(r"^/wx/v1/todos/summary$"), api_03_summary, None),
        ("GET",  re.compile(r"^/wx/v1/todos$"), api_04_todos, None),
        ("GET",  re.compile(r"^/wx/v1/todos/(?P<taskNum>[^/]+)/detail$"), api_05_detail, None),
        ("GET",  re.compile(r"^/wx/v1/history$"), api_06_history, None),
        ("GET",  re.compile(r"^/wx/v1/notifications$"), api_07_notifications, None),
        ("POST", re.compile(r"^/wx/v1/notifications/consent$"), api_08_consent, None),
        ("POST", re.compile(r"^/wx/v1/bindings/claim$"), api_09_claim, None),
        ("GET",  re.compile(r"^/wx/v1/bindings/status$"), api_10_status, None),
        ("POST", re.compile(r"^/wx/v1/bindings/unbind$"), api_11_unbind, None),
        ("GET",  re.compile(r"^/admin/v1/tss/bindings$"), api_12_admin_bindings, None),
        ("GET",  re.compile(r"^/admin/v1/tss/mappings$"), api_13_admin_mappings, None),
    ]
    for mth, rx, fn, _ in table:
        mm = rx.match(path)
        if mm and mth == method:
            return fn, mm.groupdict()
    return None, None


# ------------------------------------------------------------------ HTTP Handler
class Handler(BaseHTTPRequestHandler):
    server_version = "TssContractMock/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[%s] %s %s\n" % (now_iso(), self.command, self.path))

    def _send(self, status, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("X-Data-Mode", CFG["data_mode"])
        self.send_header("X-Schema-Version", SCHEMA_VERSION)
        self.send_header("X-RC-Version", RC_VERSION)
        self.end_headers()
        self.wfile.write(raw)

    def _handle(self):
        trace_id = "tr_" + uuid.uuid4().hex[:16]
        u = urlparse(self.path)
        q = parse_qs(u.query)
        body = None
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            try:
                body = json.loads(self.rfile.read(length).decode("utf-8"))
            except Exception:
                return self._err(trace_id, "TSS_INVALID_ARGUMENT")
        token_key = self.headers.get("Authorization", "anon")
        try:
            check_rate(token_key)
            # 控制面身份门禁：必须抢在路由分发之前，且对全部 /__mock/* 生效。
            # 拒绝时抛 TSS_NOT_FOUND → 404，与路径不存在同形（见 ctrl_guard()）。
            ctrl_guard(self, u.path)
            # 故障注入只作用于业务端点。豁免名单（与 /healthz 同级，必须始终可用）：
            #   ① /healthz            —— 运维探活
            #   ② /__mock/*           —— Mock 数据控制面
            #   ③ 写回冻结探针        —— 写回被禁止是**版本级决策**，
            #      与"当前有没有注入故障"无关，优先级高于故障注入
            if u.path not in ("/healthz", WRITE_GUARD_PROBE_PATH) \
                    and not u.path.startswith("/__mock/"):
                global_failure_guard()
            fn = _route(self.command, u.path)
            if isinstance(fn, tuple):
                fn, m = fn
            else:
                m = {}
            if fn is None:
                return self._err(trace_id, "TSS_NOT_FOUND")
            data = fn(self, body, q, m)
            self._send(200, {"code": "SUCCESS", "message": "OK", "data": data,
                             "traceId": trace_id, "timestamp": now_iso()})
        except TssError as e:
            hint = (" (%s)" % _ARG_HINTS.get(e.hint, e.hint)) if e.hint else ""
            self._err(trace_id, e.code, hint)
        except Exception as e:  # 不暴露内部细节
            sys.stderr.write("[ERROR] %s\n" % e)
            self._err(trace_id, "TSS_INTERNAL_ERROR")

    def _err(self, trace_id, code, hint=""):
        http_status, msg, _ = ERRORS[code]
        self._send(http_status, {"code": code, "message": msg + hint, "data": None,
                                 "traceId": trace_id, "timestamp": now_iso()})

    def do_GET(self):
        self._handle()

    def do_POST(self):
        self._handle()

    # PUT/PATCH/DELETE 全部收敛到 _handle，让写回守卫（TSS_WRITE_DISABLED）可被真实打出。
    def do_PUT(self):
        self._handle()

    def do_PATCH(self):
        self._handle()

    def do_DELETE(self):
        self._handle()


def main():
    port = CFG["port"]
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    sys.stderr.write("[READY] tss-contract-mock %s :%d dataMode=%s failure=%s "
                     "loose=%s freshness=%s\n"
                     % (RC_VERSION, port, CFG["data_mode"], CFG["failure"],
                        CFG["loose"], CFG["freshness"]))
    # 只说明门禁状态，绝不打印 TSS_MOCK_CTRL_TOKEN 明文。
    if CFG["ctrl_token"]:
        sys.stderr.write("[CTRL] 控制面门禁：已配置 TSS_MOCK_CTRL_TOKEN，"
                         "/__mock/* 须携带 %s 且与之一致（令牌明文不落日志）\n"
                         % CTRL_TOKEN_HEADER)
    else:
        sys.stderr.write("[CTRL] 控制面门禁：未配置 TSS_MOCK_CTRL_TOKEN，"
                         "/__mock/* 仅 loopback(127.0.0.1/::1) 来源可用；"
                         "公网暴露必须前置 Nginx 白名单"
                         "（见 nginx-protect.conf.example）\n")
    if CFG["admin_token_source"] == "ephemeral":
        sys.stderr.write("[ADMIN] TSS_ADMIN_TOKEN 未设置，已生成临时管理员令牌"
                         "（仅本次进程有效，不入代码/不入文档/不入制品）：%s\n"
                         % CFG["admin_token"])
    else:
        sys.stderr.write("[ADMIN] 管理员令牌取自环境变量 TSS_ADMIN_TOKEN"
                         "（长度 %d，明文不落日志）\n" % len(CFG["admin_token"]))
        roles = sorted(set(CFG["admin_tokens"].values()))
        if len(roles) > 1:
            sys.stderr.write("[ADMIN] 已启用令牌→角色映射，可用角色：%s"
                             "（USER 角色令牌用于复现 403 TSS_ADMIN_FORBIDDEN）\n"
                             % ", ".join(roles))
    sys.stderr.write("[LOGIN] MOCK_BOUND(已绑定) / MOCK_BOUND_BOB(同企业另一员工) / "
                     "MOCK_PENDING / MOCK_DISABLED / MOCK_KEYWORD(关键字回归) / "
                     "MOCK_EMPTY_EVT(空 events) / MOCK_OTHER_ENT(跨企业) / 其他=未绑定\n")
    sys.stderr.write("[INVITE] 受理邀请码白名单：%s（其余 → 400 TSS_INVALID_ARGUMENT）\n"
                     % ", ".join(INVITE_CODE_WHITELIST))
    srv.serve_forever()


if __name__ == "__main__":
    main()
