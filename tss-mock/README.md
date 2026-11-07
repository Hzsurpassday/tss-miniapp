# 时空智审 V1.0 `/wx/v1` 契约 MOCK 服务（RC3）

对应契约：`tss-openapi-v1.0-rc3.yaml` + `error-codes.csv` + `CHANGELOG-RC2-RC3.md`（均在 `../contract/`）。
用途：在**不接真实 ERP、不配微信 secret** 的前提下，让小程序 / PC 管理端按冻结契约联调 13 个接口。

> ⚠️ 本服务是 **MOCK**：`dataMode=MOCK`，所有业务数据为内置样例，**不代表任何真实审批结果**。
> 契约明确「MOCK 不能伪装真实审批完成」，因此严格模式下
> `resultVerified=false`、`isFinalApproved=false`、`downstreamReceiptStatus=NOT_VERIFIED`、
> `availableActions` 只下发 `["VIEW"]`、详情 `workflow.readOnly` 恒为 `true`。

---

## 一、快速开始

```bash
# 本机直接跑（推荐换端口，避免与旧版本进程冲突）
TSS_MOCK_PORT=9001 python3 server.py

# 自检（122 项，含 RC3 新增回归；会自行拉起临时实例）
TSS_MOCK_PORT=9001 python3 verify.py

# 或指向已启动的服务
TSS_MOCK_BASE=http://<host>:9001 TSS_ADMIN_TOKEN=<启动日志里的令牌> python3 verify.py
```

Docker：

```bash
docker build -t tss-contract-mock:1.0.0-rc3 .
# 管理员令牌必须显式注入，镜像内不含任何默认值
docker run -d --name tss-wx-mock -p 9000:9000 \
  -e TSS_ADMIN_TOKEN="$(openssl rand -base64 32)" \
  tss-contract-mock:1.0.0-rc3
docker exec tss-wx-mock python3 verify.py
```

## 二、管理员令牌怎么拿（RC3 安全要求）

**本目录内不存在任何硬编码永久密钥。** 令牌只有两个来源：

| 来源 | 做法 | 适用场景 |
|---|---|---|
| 环境变量 `TSS_ADMIN_TOKEN` | 自己设：`export TSS_ADMIN_TOKEN=$(openssl rand -base64 32)` | CI / 长期起服务 |
| 启动临时生成 | 不设环境变量启动，令牌会**只打印到 stderr**（`[ADMIN]` 行），重启即变 | 本地跑一次就关 |

```bash
TSS_MOCK_PORT=9001 python3 server.py 2>&1 | grep '\[ADMIN\]'
# [ADMIN] TSS_ADMIN_TOKEN 未设置，已生成临时管理员令牌（仅本次进程有效...）：<临时令牌>
```

- 令牌**不落库、不进仓库、不进任何文档/交涉文件**，也不会通过 `/healthz` 等 HTTP 端点返回（`healthz` 只告知来源是 `env` 还是 `ephemeral`）。
- 相应淆求：`Authorization: Bearer <令牌>`；不匹配 → `403 TSS_ADMIN_FORBIDDEN`。
- `verify.py` 内含静态扫描用例，若目录内再次出现形如硬编码密钥的字面量，自检会直接 FAIL。

## 三、登录 code（服务端预置账号）

| code | 身份 | 说明 |
|---|---|---|
| `MOCK_BOUND` | `U-DEMO-000001` / `E-DEMO-000001` / 示例员工 | 已绑定，3 条待办（销售 1 / 付款 1 / 定价 1） |
| `MOCK_BOUND_BOB` | `U-BOB-000001` / `E-DEMO-000001` / 员工乙 | 另一已绑定员工，1 条待办 → 验证跨员工隔离 |
| `MOCK_KEYWORD` | `U-KEY-000001` | 关键字筛选回归夹具：3 条含英文往来单位待办 → 验证大小写不敏感与跨页 |
| `MOCK_EMPTY_EVT` | `U-EVT-000001` | 空 `events` 夹具（taskNum `920002`）：ERP 同步产生、工作流尚无流转事件 → `workflow.events == []` |
| `MOCK_OTHER_ENT` | `U-OTHER-000001` / **`E-OTHER-000002`** | 跨企业已绑定身份（taskNum `930002`）→ 让 `TSS_ENT_SCOPE_DENIED` 可被真实打出 |
| `MOCK_PENDING` | — | 申请中（登录即存在一条待 PC 确认申请单） |
| `MOCK_DISABLED` | `U-DISABLED01` | 绑定已停用 → 访问业务数据返回 `TSS_BINDING_DISABLED` |
| 其他任意非空 | — | 新用户（未绑定，needBinding=true） |

**邀请码白名单**（`POST /wx/v1/bindings/claim`，白名单外一律 400 `TSS_INVALID_ARGUMENT`）：
`INV-PRESET` / `INV-DEMO-0001` / `INV-DEMO-0002`（正常可用） +
`INV-EXPIRED` / `INV-CONFLICT`（异常分支专用，分别走 409）。完整名单亦见 `/healthz` 的 `inviteCodeWhitelist`。

## 四、接口清单

| 编号 | 方法 | 路径 | 鉴权 | 支持的查询参数 |
|---|---|---|---|---|
| API-01 | POST | `/wx/v1/auth/login` | 匿名 | — |
| API-02 | GET | `/wx/v1/me` | 微信会话 | — |
| API-03 | GET | `/wx/v1/todos/summary` | 微信会话 + 已绑定 | — |
| API-04 | GET | `/wx/v1/todos` | 同上 | `docType` `keyword` `limit` `cursor` |
| API-05 | GET | `/wx/v1/todos/{taskNum}/detail` | 同上 | —（内嵌 workflow） |
| API-06 | GET | `/wx/v1/history` | 同上 | `docType` `action` `from` `to` `limit` `cursor` |
| API-07 | GET | `/wx/v1/notifications` | 同上 | `limit` `cursor` |
| API-08 | POST | `/wx/v1/notifications/consent` | 同上 | — |
| API-09 | POST | `/wx/v1/bindings/claim` | 微信会话 | — |
| API-10 | GET | `/wx/v1/bindings/status` | 微信会话 | `bindRequestId`（可选） |
| API-11 | POST | `/wx/v1/bindings/unbind` | 微信会话 | — |
| API-12 | GET | `/admin/v1/tss/bindings` | 管理员会话 | `limit` `cursor` |
| API-13 | GET | `/admin/v1/tss/mappings` | 管理员会话 | — |

非契约控制面（仅供联调，不得当作正式 API）：
`GET /healthz`、`POST /__mock/reset`、`POST /__mock/pc-confirm`（模拟 PC 端确认绑定）、
`POST /__mock/pc-reject`（模拟 PC 端驳回）、`POST /__mock/expire`（把申请单置为已过期）、
`POST /__mock/expire-session`（把会话令牌重签为已过期）、
`POST /__mock/freshness`（切换 `meta.freshness`，取值只能是 FRESH/DELAYED/UNAVAILABLE）。

**控制面安全约束（RC3 安全整改）**：

- **仅 `POST`**：6 个 `/__mock/*` 端点一律只接受 `POST`；`GET` / `PUT` / `PATCH` /
  `DELETE` 一律 `404 TSS_NOT_FOUND`（与「路径不存在」同形，不新增错误码、不泄露控制面存在性）。
  写回冻结探针 `/dev-support/v1/tss/write-guard-probe` 不在此列，仍按「不挑 method」原样。

  > **已知字面偏差（P3-1，判文档化、不改码）**：服务未实现 `do_HEAD`，`HEAD /__mock/*`
  > 由 `BaseHTTPRequestHandler` 直接返回 **501**，不是统一信封的 404。已实测该请求**不进
  > `_handle()`、不分发路由、不改变任何状态**（HEAD 之后已登录会话仍有效），属 fail-closed，
  > 不构成绕过。本次冻结已部署的 `server.py`（sha256 `65e8f976…`）不作改动；若后续要求
  > 口径字面严格成立，补一行 `do_HEAD` 即可。
- **默认仅 loopback 可访问**：未配置 `TSS_MOCK_CTRL_TOKEN` 时，只有来源为
  `127.0.0.1` / `::1` / `localhost` 的请求可进入；非 loopback 一律 `404 TSS_NOT_FOUND`。
  `verify.py` 本地自检（连 `127.0.0.1`、用 `POST`）与全部联调脚本因此不受影响。
- **公网部署必须配 Nginx 白名单 + 可选令牌**：若 nginx 与 Mock 同一网络命名空间、直连后端
  进程（或任何使后端看到的 `client_address` 变成真 loopback 的形态），代码层 loopback
  判定会**失效**，控制面将对公网敞开。因此公网暴露时
  **`nginx-protect.conf.example` 的 location 白名单是必需主防线，不是可选项**
  （这一层同样能给存量 RC1 实例止血）。确需远程控制面时，显式注入
  `TSS_MOCK_CTRL_TOKEN` 并在请求中携带 `X-Mock-Ctrl-Token` 头（常量时间比较，不匹配即 404）；
  令牌**禁止写入代码 / 文档 / 制品 / 仓库，禁止回显或打印明文**。

  > **令牌字符集（P3-2）**：请求头按 latin-1 解码，UTF-8 多字节字符（中文 / emoji 等）
  > 无法与服务端 Unicode 令牌相等，表现为 404 fail-closed（不崩溃、不绕过）。运维统一使用
  > **ASCII 令牌**；Latin-1 可表示的非 ASCII 字符实测可用。
  >
  > **线上实测（2026-11-07，详见 `DEPLOY.md`）**：本部署为 Docker 端口映射 + OpenResty
  > `host` 网络反代 `127.0.0.1:9000`，请求经 `docker-proxy` 后容器看到的是网关
  > `172.17.0.1`（**非 loopback**）；宿主 loopback 与经 80 反代的 `POST /__mock/*` **均 404**，
  > 容器内部真 loopback 才 200，`nginx -t` 通过且 `/__mock/` 有 `deny all`。
  > 该风险在本拓扑下已被两道防线闭环，但**换部署形态必须重新验收**。
- **reset 不再清空限流 / 幂等记录**：`POST /__mock/reset` 只清
  `STATE["sessions"]` / `STATE["bind_requests"]` / `STATE["consents"]`，**保留
  `STATE["rate"]`（限流桶）与 `STATE["nonces"]`（幂等记录）**，避免「循环 reset
  自我解除限流」这类让控制面削弱自身防护的做法。

## 五、筛选与分页语义（RC3 落实）

管线固定：**先筛选 → 再稳定排序 → 再游标分页**，任何绕过都不允许。

| 列表 | 筛选 | 排序键 |
|---|---|---|
| 待办 API-04 | `docType` 枚举；`keyword` 对 `billNo`/`title`/`counterparty` 大小写不敏感子串匹配 | `updatedAt DESC, taskNum DESC, approvalId DESC` |
| 历史 API-06 | `docType`/`action` 枚举；`from`/`to` 按 `processedAt` 日期闭区间 | `processedAt DESC, recordId DESC` |

- `keyword` 长度上限 100，超出 → `400 TSS_INVALID_ARGUMENT`；空串视为未传。
- `from`/`to` 必须 `YYYY-MM-DD`，且 `from <= to`；否则 → `400 TSS_INVALID_ARGUMENT`。
- 游标是**服务端签名的不透明令牌**，绑定 (用户, 企业, 筛选指纹, 数据快照, 排序管线)。
  同一查询的后续分页保留原筛选语义；**改筛选后必须丢弃旧游标**，复用旧游标 → `400 TSS_INVALID_CURSOR`。

## 六、工作流字段（RC3 落实，端点级）

API-05 的 `data.workflow` 对每个 taskNum、每个 docType 都输出：

```json
{
  "workflowId": "dq.xskd",
  "definitionVersion": "v1",
  "instanceId": "900001",
  "currentNodeIds": ["risk"],
  "nodes": [ ... ],
  "edges": [ ... ],
  "graphType": "LINEAR",
  "dataVersion": "20261031-001",
  "readOnly": true,
  "events": [
    {
      "eventId": "evt_900002_0",
      "nodeId": "start",
      "actionType": "STARTED",
      "menuName": "保存并提交",
      "operatorDisplay": "示例员工",
      "occurredAt": "2026-10-30T09:15:00+08:00",
      "outcome": "SUCCESS",
      "serialNum": "900001",
      "comment": null
    }
  ]
}
```

**两个版本字段不可混用**：

| 字段 | 含义 | 何时变化 |
|---|---|---|
| `definitionVersion` | 流程**定义**版本（流程图/拓扑是否迭代） | 审批流节点或边变更 |
| `dataVersion` | 实例**数据内容**版本（单据数据快照） | 数据刷新、重新取数 |

`readOnly` 恒为 `true`（V1.0 不开放真实审批写回）；`events` 允许为空数组，但字段不得省略，
且每个元素必含 `eventId`/`nodeId`/`actionType`/`menuName`/`operatorDisplay`/`occurredAt`/`outcome`/`serialNum`/`comment`。

## 七、新鲜度语义（RC3 落实）

- 唯一来源：`data.meta.freshness`，取值只能是 `FRESH` / `DELAYED` / `UNAVAILABLE`（**已禁止历史取值 `STALE`**）。
- `SummaryData` 已无 `dataFreshness` 字段，任何响应都不会出现第二个新鲜度字段。
- 服务启动时可用 `TSS_MOCK_FRESHNESS` 指定初始值；运行时可用 `/__mock/freshness` 切换，非法取值 → `400 TSS_INVALID_ARGUMENT`。

## 八、绑定状态一致性（RC3 落实）

`/wx/v1/me.bindStatus` 与 `/wx/v1/bindings/status` 由**同一份会话状态**推导，`status` 表达绑定关系、`bindRequestId` 表达申请流程，二者不得互相矛盾。不变量（由 `verify.py` 断言）：

| INV | 断言 |
|---|---|
| INV-1 | `status=BOUND` ⟺ `me.bindStatus=BOUND` ⟺ `erpUserMasked` 非空 |
| INV-2 | `status=DISABLED` ⟺ `me.bindStatus=DISABLED` |
| INV-3 | `status ∈ {UNBOUND, PENDING_PC_CONFIRM, EXPIRED, REJECTED}` ⟹ `me.bindStatus ∈ {UNBOUND, PENDING}` 且 `erpUserMasked=null` |
| INV-4 | `bindRequestId` 非 null 仅当会话当前持有该申请单 |
| **INV-5** | **`bindRequestId` 非 null ⟺ `status ∈ {PENDING_PC_CONFIRM, EXPIRED, REJECTED}`** —— `bindRequestId` 只表示「当前存在尚未终结、需要用户继续感知的申请流程」 |

状态迁移与清理规则：
- **PC 确认完成 = 申请流程终结**：申请单即刻归档移除，`bindRequestId` 归 `null`，
  与「预置已绑定账号压根没有申请单」的响应**完全同形**（同为 BOUND，不因路径不同而形状不同）。
- 归档后按旧 `bindRequestId` 精确查询 → `404 TSS_NOT_FOUND`（不泄露是否曾经存在）。
- 解绑后**移除**该身份名下的申请单（不是标记 EXPIRED），`status` 回到 `UNBOUND`、`bindRequestId` 归 `null`。
- 查询**他人**或不存在的 `bindRequestId` → `404 TSS_NOT_FOUND`（**不是 403**，避免泄露「该资源是否存在」这一信息）。
- `erpUserMasked` / `entDisplayName` **仅在 `status=BOUND` 时非空**。
- 会保留申请单 id 的终态只有 `EXPIRED`（需用户感知并重发）与 **`REJECTED`（需用户看到驳回结果）**；
  其余终态（如 `BOUND`）即刻归档，`bindRequestId` 归 `null`。
  **REJECTED 必须保留 id**：驳回恰恰是最需要用户能查到"我的申请被驳回了"的场景，一拍即灭等于抹掉驳回这件事。
- 申请过期 / 被驳回后关系侧均回落为 `UNBOUND`，`status` 分别返回 `EXPIRED` / `REJECTED`。
- **REJECTED 不是"一拍即灭"**：连续多次查询都是 `REJECTED` 且 id 非空，按 id 回查也不报 404；
  驳回后的申请单不能再被 PC 确认（→ `409 TSS_BIND_CONFLICT`），否则等于把 REJECTED 复活成 BOUND。
- 复现 REJECTED：`POST /__mock/pc-reject {"bindRequestId":"...","reason":"..."}`（MOCK 控制台，非契约 API）。
- 重复提交绑定不会生成第二份互相矛盾的申请单。

> 实现要点：申请单生命周期（过期推进 + 终态归档）的唯一执行点是 `advance_bind_state()`，
> `me` 与 `status` 共用它 —— 既保证两个端点同状态，也保证归档是**存储层移除**而非展现层修饰。

### 错误码语义裁决（RC3，team-lead 裁定）

| 场景 | 错误码 | HTTP | 说明 |
|---|---|---|---|
| **未绑定身份调用 `POST /wx/v1/bindings/unbind`** | `TSS_NOT_BOUND` | **403** | 见下方「为什么不用 409」 |
| 停用身份调用 `POST /wx/v1/bindings/unbind` | `TSS_BINDING_DISABLED` | 403 | 停用是既存绑定关系，与 `require_bound` / API-09 对停用的处理一致 |
| 解绑后二次解绑 | `TSS_NOT_BOUND` | 403 | 已非绑定态 |
| `confirmation` 不等于 `UNBIND` | `TSS_INVALID_ARGUMENT` | 400 | 参数校验先于状态校验 |
| 查询他人 / 不存在的 `bindRequestId` | `TSS_NOT_FOUND` | 404 | 不泄露资源是否存在 |

**为什么不用 409 `TSS_BIND_CONFLICT`**：其权威文案是「**已有冲突绑定**」，而「未绑定却请求解绑」根本不存在任何绑定，谈不上冲突，用它属于语义错配；
`TSS_NOT_BOUND` 的权威文案是「当前微信尚未绑定员工」，与该场景完全对得上。两个码都在权威 20 条清单内、都不违反契约，
但 RC3 验收要求两侧错误码与 HTTP 状态一致，故统一裁定为 **403 `TSS_NOT_BOUND`**。

### 五态矩阵（真实 HTTP，专供交叉比对）

```bash
python3 binding_matrix.py                    # 自动拉起临时实例并打印矩阵
TSS_MOCK_BASE=http://<java 侧地址>:8080 python3 binding_matrix.py   # 直接对比 Java
```

脚本会把每个格子的真实取值与脚本内置的 Java 基线逐格 diff，不一致处自动标 ✗。

## 九、手工 curl 示例

```bash
B=http://127.0.0.1:9001
TOK=$(curl -s -XPOST $B/wx/v1/auth/login -H 'Content-Type: application/json' \
      -d '{"code":"MOCK_BOUND"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["sessionToken"])')
ADMIN=<启动日志 [ADMIN] 行里的令牌>

curl -s $B/wx/v1/me -H "Authorization: Bearer $TOK"
curl -s "$B/wx/v1/todos?keyword=2026100001" -H "Authorization: Bearer $TOK"
curl -s "$B/wx/v1/history?action=APPROVED&from=2026-10-01&to=2026-10-31" -H "Authorization: Bearer $TOK"
curl -s "$B/wx/v1/todos/900002/detail" -H "Authorization: Bearer $TOK"
curl -s "$B/admin/v1/tss/mappings" -H "Authorization: Bearer $ADMIN"
```

## 十、环境变量（故障注入与联调开关）

| 变量 | 默认 | 说明 |
|---|---|---|
| `TSS_MOCK_PORT` | 9000 | 监听端口 |
| `TSS_MOCK_SECRET` | 随机 | 会话 / 游标签名密钥；**多副本部署必须一致**，否则令牌互不相认 |
| `TSS_ADMIN_TOKEN` | 无默认（未设则临时生成并打印到 stderr） | 管理员令牌（等价于 `<令牌>=ADMIN`），**禁止写入任何文件或交涉文档** |
| `TSS_ADMIN_TOKENS` | 无 | 令牌→角色映射，形如 `'tok1=ADMIN,tok2=USER'`。`USER` 角色令牌用于复现 `403 TSS_ADMIN_FORBIDDEN`；未配置则该档无法触发（`/healthz.adminRoleMapping` 会说明） |
| `TSS_MOCK_CTRL_TOKEN` | **无默认**（未配置时 `/__mock/*` 仅 loopback 可用） | 控制面令牌，**仅远程控制面需要**：配置后 `/__mock/*` 须带 `X-Mock-Ctrl-Token` 头且匹配（常量时间比较），否则 404。只接受环境变量注入，**禁止写入文件 / 仓库 / 文档 / 制品，禁止回显或打印明文** |
| `TSS_MOCK_FRESHNESS` | FRESH | `FRESH`/`DELAYED`/`UNAVAILABLE`，非法值拒绝启动 |
| `TSS_MOCK_DATA_MODE` | MOCK | **置为 `ERP` 即 fail-closed**：本进程未接入真实 ERP 只读通道，5 个业务数据端点一律 503 `TSS_ERP_READ_UNAVAILABLE` + `data=null`（**绝不返回空列表**，详见§十四）；`/healthz` 仍如实自描述 `dataMode=ERP` |
| `TSS_MOCK_RATE_LIMIT` | 0（不限） | 每令牌每分钟上限，超出返回 429 |
| `TSS_MOCK_FAILURE` | none | `wechat` / `erp_read` / `erp_read:PAYMENT` / `data_stale` / `rate_limited` / `not_configured` / `internal` —— 用于联调异常分支（后四个仅用于把静态错误码变成可复跑的运行时路径，见§十五） |
| `TSS_MOCK_LOOSE_EXAMPLES` | false | true 时按契约示例原样输出（含 `resultVerified=true`） |

```bash
# 联调「微信服务不可用」分支
TSS_MOCK_PORT=9001 TSS_MOCK_FAILURE=wechat python3 server.py
TSS_MOCK_BASE=http://127.0.0.1:9001 TSS_MOCK_FAILURE=wechat python3 verify.py
```

## 十一、自检覆盖

`python3 verify.py` 共 206 项：

| 分组 | 项数 | 内容 |
|---|---|---|
| RC1 遗留正例与异常/越权 | 38 | 全量保留，未删减（其中「跨员工详情」的**预期**按 D-1 裁决由 404 改为 403） |
| RC3-R1 工作流字段 | 8 | 端点级覆盖全部 taskNum / docType + 跨员工 |
| RC3-R2 筛选与分页 | 24 | 匹配 / 无匹配 / 非法日期 / 动作筛选 / 跨页五类回归 + 排序键 |
| RC3-R3 新鲜度 | 10 | 无第二个新鲜度字段、无 `STALE`、全接口枚举一致 |
| RC3-R4 绑定一致性 | 37 | 未绑定 / 申请中 / 待 PC 确认 / 已绑定 / 解绑后 + 停用 + 过期 + **已驳回** + **INV-5 强不变量** |
| RC3-R6 错误码裁决与对齐 | 11 | unbind 403 `TSS_NOT_BOUND` 三种路径、他人申请单 404、脱敏字段仅 BOUND 非空、绑定后归档 |
| RC3-R5 管理员令牌 | 8 | HTTP 不泄露明文、**缺凭据 401 / 令牌无效 401 拆分**、静态扫描无硬编码密钥 |
| RC3-R11 令牌→角色映射 | 7 | ADMIN 放行 / USER 角色 403 / 未知令牌 401 / 未提供 401 / 空 Bearer 401 / healthz 声明 |
| RC3-R8 越权三分 + 判定顺序 + 邀请码 + D-6 形态 | 29 | D-1 三个码不混用、D-3 状态门禁先于参数校验（7 格）、D-4 白名单外 400、/wx/v1/** 无写回路径 404、探针全 method 403、D-6 解绑后三端点形态 |
| RC3-R9 新增夹具 | 9 | 空 `events` 夹具（`events==[]` 且字段不省略）、跨企业 BOUND 夹具 |
| RC3-R7 ERP fail-closed | 5 | 端口 9002 独立实例，5 个数据端点 503 + `data=null` + 不得为空列表 + `/healthz` 自描述 |
| RC3-R12 写回冻结探针门禁顺序 | 5 | 默认 / `failure=internal` / `dataMode=ERP` 三实例下探针均 403，且 `/wx/v1/**` 仍 404 |
| RC3-R10 错误码运行时覆盖 | 3 | 20 个权威码逐个打真实 HTTP，并输出「默认 / 注入」分类表 |

另：`python3 binding_matrix.py` 独立产出绑定五态矩阵（含关键四格原始响应体），用于与 Java 后端逐格交叉比对。

> 想看关键场景的**原始 HTTP 响应体**（ERP 模式 5 端点、新夹具、越权三分），加环境变量：
> `TSS_MOCK_DUMP_RAW=1 python3 verify.py`。

## 十二、已知边界（如实标注，不掩盖）

1. 数据为内置样例，**不随 ERP 变化**；真实数据联调须接 ERP 只读库（当前未开放）。
2. `expiresIn` 恒 7200；会话与游标、申请单等状态存内存，容器重启即失效（未做持久化）。
3. 绑定流程的 PC 端确认由 `/__mock/pc-confirm` 模拟，真实实现在 PC/后端。
4. 未实现：真实 `code2session`（无 appid/secret）、订阅消息下发、限流默认关闭、详情 ETag/304 缓存协商（按契约要求诚实返回 200）。
5. `workflow.events` 只输出有证据的事件（提交事件取自 `createdAt`，归档陈述取自同一 `approvalId` 的历史记录）；没有证据时不编造、输出**空数组**（字段不省略）。`MOCK_EMPTY_EVT` 就是这种"零事件"夹具（注意：`createdAt` 是待办产生时间，不等于工作流已落提交事件，故不能用它反推事件）。
6. 故障注入类错误码（见§十五）在默认业务语义下没有触发路径，需要显式 `TSS_MOCK_FAILURE` 才能打出；不要把"静态已定义"当成"运行时已覆盖"。
7. `TSS_SESSION_EXPIRED` 需通过 `POST /__mock/expire-session`（MOCK 控制台）把令牌重签为过期后才能复现 —— 该端点仅存在于 MOCK 服务，真实实现没有对应物。
8. `REJECTED` 需通过 `POST /__mock/pc-reject`（MOCK 控制台）推进 —— 同上，真实实现的驳回动作在 PC/后端。
9. `TSS_ADMIN_FORBIDDEN`（令牌有效但非管理员）需要显式配置 `TSS_ADMIN_TOKENS` 里的 USER 角色令牌才能触发；只用 `TSS_ADMIN_TOKEN` 单令牌模式时，所有失败都归 401 `TSS_UNAUTHORIZED`。

## 十三、排障（看着像 bug、实为环境残留）

**症状**：自检突然大面积 403 / 401，或管理员接口莫名 `TSS_ADMIN_FORBIDDEN`，但上一次跑是全绿的。

**根因**：上一次遗留的 `server.py` 进程仍占着端口。Windows 的 `SO_REUSEADDR` 允许后续进程"绑定成功"，
于是你连到的是**旧进程**，拿到的是**旧进程**的临时管理员令牌 —— 表现为随机鉴权失败，极具误导性。

**处理**：
```bash
# 1) 查占用
netstat -ano | grep ":9001"
# 2) 结束它（注意镜像名可能是 python3.exe，只杀 python.exe 会漏）
taskkill //F //PID <pid>
taskkill //F //IM python3.exe
# 3) 或直接换端口
TSS_MOCK_PORT=9011 python3 verify.py
```

**已内置防线**：`verify.py` / `capture_examples.py` / `binding_matrix.py` 三个启动入口都有**端口占用预检**，
命中即打印 `[FATAL] 端口 X 已被占用…请先结束该进程或改用 TSS_MOCK_PORT`，不再表现为随机 403。

**另一个坑**：用 `python3 binding_matrix.py | head -20` 这类管道截断长输出，会因 SIGPIPE 提前杀掉主进程，
`finally` 里的清理来不及执行而留下孤儿服务进程。**请重定向到文件再查看**：`python3 binding_matrix.py > matrix.txt`。

## 十四、`dataMode=ERP` 是 fail-closed，不是"换个标签"

> **一句话**：本服务**没有接入任何真实 ERP 只读通道**。把 `TSS_MOCK_DATA_MODE` 置为 `ERP`
> 不会让你拿到 ERP 数据，只会让业务数据端点**全部拒绝服务**。

| 端点 | `dataMode=ERP` 时的行为 |
|---|---|
| `GET /wx/v1/todos/summary` | 503 `TSS_ERP_READ_UNAVAILABLE`，`data=null` |
| `GET /wx/v1/todos` | 同上 |
| `GET /wx/v1/todos/{taskNum}/detail` | 同上 |
| `GET /wx/v1/history` | 同上 |
| `GET /wx/v1/notifications` | 同上 |
| `GET /healthz` | **200**，照实自描述 `dataMode=ERP` + `erpReadChannelWired=false`（自描述 ≠ 业务数据） |
| `POST /wx/v1/auth/login` | 200（身份不依赖 ERP 只读通道） |

**为什么不返回空列表**：空列表会被调用方读成"确实没有待办"，是静默错误；
显式 503 才能被立刻发现。谎报数据源比直接报错危险得多。

```bash
TSS_MOCK_PORT=9002 TSS_MOCK_DATA_MODE=ERP python3 server.py
curl -s http://127.0.0.1:9002/wx/v1/todos/summary -H "Authorization: Bearer $TOK"
# {"code":"TSS_ERP_READ_UNAVAILABLE","message":"ERP只读数据不可用","data":null,...}
```

## 十五、错误码运行时覆盖（口径红线）

**20 个权威错误码三方静态对齐**（`error-codes.csv` × `server.py ERRORS` × Java `TssErrorCode` 的
`code`/`httpStatus`/`retryable` 全等）—— 这是**静态比对**结论。

**运行时是否真能触发，是另一件事**（`verify.py` R10 组逐个打真实 HTTP 实测）：

| 分类 | 数量 | 码 |
|---|---|---|
| **默认路径**（MOCK 默认配置 + 夹具即可打出） | 12 | `TSS_INVALID_ARGUMENT`、`TSS_INVALID_CURSOR`、`TSS_UNAUTHORIZED`、`TSS_SESSION_EXPIRED`、`TSS_NOT_BOUND`、`TSS_FORBIDDEN`、`TSS_ENT_SCOPE_DENIED`、`TSS_BINDING_DISABLED`、`TSS_NOT_FOUND`、`TSS_BIND_INVITE_EXPIRED`、`TSS_BIND_CONFLICT`、`TSS_IDEMPOTENCY_CONFLICT` |
| **DEV 支持命名空间**（非契约端点） | 1 | `TSS_WRITE_DISABLED`（`POST /dev-support/v1/tss/write-guard-probe`；`/wx/v1/**` 下不存在任何写回路径） |
| **故障注入路径**（需 `TSS_MOCK_FAILURE=<mode>`） | 6 | `TSS_DATA_STALE`、`TSS_RATE_LIMITED`、`TSS_WECHAT_UNAVAILABLE`、`TSS_NOT_CONFIGURED`、`TSS_INTERNAL_ERROR`、`TSS_ERP_READ_UNAVAILABLE`（后者另有 `dataMode=ERP` 的默认路径） |
| **需显式配置**（需 `TSS_ADMIN_TOKENS` 含 USER 角色令牌） | 1 | `TSS_ADMIN_FORBIDDEN`（只用 `TSS_ADMIN_TOKEN` 单令牌模式时该档无法出现） |

**对外表述必须带定语**：**注入类 5 个码**（`TSS_DATA_STALE` / `TSS_RATE_LIMITED` /
`TSS_WECHAT_UNAVAILABLE` / `TSS_NOT_CONFIGURED` / `TSS_INTERNAL_ERROR`）
在 V1.0 默认业务语义下**没有**触发路径（不存在写冲突、不限流、微信能力恒可用、服务能力恒已配置），
只能说"**已定义、三方静态对齐，运行时需显式故障注入方可触发**"。

**`TSS_WRITE_DISABLED` 特别说明**：该码的运行时触发路径是 **DEV 支持命名空间**下的探针
`POST /dev-support/v1/tss/write-guard-probe`（`GET/POST/PUT/PATCH/DELETE` 全 method 均 403）。
**它不是契约端点** —— V1.0 契约 `paths` 只含 13 条，其中**不存在任何写回路径**，
`/wx/v1/**` 下不存在的路径一律 404（与 Java 侧同形），`availableActions` 恒为 `["VIEW"]`。

> **对外定性（红线）**：只能说「**已定义并可触发，触发路径为 DEV 支持命名空间（非契约端点）；
> V1.0 契约中不存在任何写回端点，`availableActions` 恒 `["VIEW"]`」，
> **绝不能说成"已实现审批写回拦截"** —— 两者含义相反。

**为什么不在 `/wx/v1/**` 下挂 approve/reject 路径**：在契约命名空间里挂这些路径，等于宣称
"这些写回操作是我们设计的一部分、只是当前禁用"，与 RC2 决议「V1.0 不开放真实 APPROVE/REJECT 写回」
的对外表述直接冲突；也会让契约符合性扫描报出"说好 13 条、实际还有 9 条"。故 `/wx/v1/**` 保持 13 条纯净。

### 两套控制面命名空间（不要混用）

| 命名空间 | 语义 | 内容 |
|---|---|---|
| `/__mock/*` | **Mock 数据控制面**（Python Mock 专有，改数据状态） | `reset` / `pc-confirm` / `pc-reject` / `expire` / `expire-session` / `freshness` |
| `/dev-support/v1/tss/*` | **DEV 支持面**（与 Java `DevBindingFixtureController` 同级，能力探测） | `write-guard-probe` |

> **安全说明（RC3 安全整改）**：`/__mock/*` 是**改数据状态**的联调后门，绝不能对公网匿名可达。
> 四层防线：① 代码层强制 `POST` only（`GET`/`PUT`/`PATCH`/`DELETE` 一律 404；
> `HEAD` 为 501 fail-closed，见上文 P3-1）；② 未配令牌时仅 loopback
> 来源可进入，拒绝即 `404 TSS_NOT_FOUND`，对公网隐藏控制面存在性（不新增错误码）；
> ③ 需要远程时显式注入 `TSS_MOCK_CTRL_TOKEN` + `X-Mock-Ctrl-Token` 头（常量时间比较）；
> ④ **部署层 `nginx-protect.conf.example` 的 location 精确白名单是公网暴露时的必需主防线**
> —— 若反代使后端看到的 `client_address` 变成真 loopback（例如 nginx 与 Mock 同一网络
> 命名空间直连后端进程），② 会失效，故④不可省略；本部署经 `docker-proxy` 后看到的是网关
> `172.17.0.1`（**非 loopback**），实测②仍生效，但换部署形态必须重新验收（见「控制面安全约束」）；
> `location ^~ /__mock/ { deny all; }`（或 `return 404;`）同时可用于给**存量 RC1 实例**止血。
> `/__mock/reset` 不再清空限流桶与幂等记录。`GET /healthz` 与 DEV 支持面探针不受这套门禁影响
> （探针仍按「不挑 method」原样，门禁顺序见下文）。

### ⚠️ 跨端复跑必读：`write-guard-probe` 的 404 可能是预期

> `write-guard-probe` 属 DEV 支持命名空间，仅在 DEV 四重门禁成立于 Java 侧时可访问；
> **非 DEV 环境该路径不存在（404）属预期行为，不计入跨端差异。**
> Python 侧因整体为 DEV Mock、没有 profile 概念，恒可访问、恒返回 403。

### 探针的门禁顺序（已加断言）

写回被禁止是**版本级决策**，与"ERP 数据源通不通""当前有没有注入故障"无关，
因此探针抢在 `global_failure_guard()` 与 `erp_read_guard()` **之前**命中，
并已加入故障注入的豁免名单（与 `/healthz`、`/__mock/*` 同级）。三个实例实测：

| 实例 | `POST /dev-support/v1/tss/write-guard-probe` |
|---|---|
| 默认（`failure=none`、`dataMode=MOCK`） | 403 `TSS_WRITE_DISABLED` |
| `TSS_MOCK_FAILURE=internal` | **403**（不是 500） |
| `TSS_MOCK_DATA_MODE=ERP` | **403**（不是 503） |

## 十六、越权与判定顺序（一眼记住）

| 场景 | 码 |
|---|---|
| 同企业内、资源存在但不属于本人 | 403 `TSS_FORBIDDEN`（无权查看该资源） |
| 跨企业访问 | 403 `TSS_ENT_SCOPE_DENIED`（无权访问企业数据） |
| 资源不存在 | 404 `TSS_NOT_FOUND` |
| 管理员端点：未提供凭据 / 空 Bearer | 401 `TSS_UNAUTHORIZED`（未登录或令牌无效） |
| 管理员端点：令牌无效（不在受理表内，含微信会话令牌） | 401 `TSS_UNAUTHORIZED` |
| 管理员端点：令牌有效但角色非 ADMIN（需 `TSS_ADMIN_TOKENS` 配 USER 角色） | 403 `TSS_ADMIN_FORBIDDEN`（非管理人员） |
| 解绑：未绑定 / 停用身份（**无论请求体**） | 403 `TSS_NOT_BOUND` / `TSS_BINDING_DISABLED` |
| 解绑：已绑定但 `confirmation` 非法 | 400 `TSS_INVALID_ARGUMENT` |

**判定顺序**：权限与状态是"能不能进这个门"，参数是"进门之后做得对不对"，**前者永远先于后者**
（详见 `CHANGELOG-RC2-RC3.md` §13 的完整理由链 —— 该条推翻了上一版"参数校验先于状态校验"的表述）。
