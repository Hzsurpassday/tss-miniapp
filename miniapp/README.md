# 时空智审 V1.0 · 微信小程序（`tss-miniapp`）

原生微信小程序 + TypeScript 工程。**不使用** Taro / uni-app 等任何跨端抽象。

- AppID：`wx16d988aa2b90af65`（已写入 `project.config.json`）
- 工程路径：`E:\my-project\small\tss-miniapp`
- 契约版本：`tss-openapi-v1.0-rc2`（唯一字段级标准）+ `契约修订决议.md`
- 展示版本：`1.0.0-rc2`

> **红线提醒**：V1.0 为**只读**版本。小程序内不存在任何「同意 / 驳回」等真实审批入口，
> 也不存在任何 ERP 写回能力。`workflow.readOnly` 为 true（服务端未返回时按 fail-safe 视为 true）。

---

## 一、启动步骤

1. 打开 **微信开发者工具** → 导入项目 → 目录选择 `E:\my-project\small\tss-miniapp`
   → AppID 填 `wx16d988aa2b90af65`（或选择「使用测试号」时会与本工程 AppID 不一致，不推荐）。
2. 项目设置中确认已勾选 **「使用 TypeScript 编译」**（`project.config.json` 的
   `setting.useCompilerPlugins` 已包含 `typescript`）。
3. **默认后端是本机 `http://127.0.0.1:8787`（开发态）**。启动 `tss-wx-api` 的 dev profile 时**必须显式
   `--server.port=8787`**（终端可能注入 `SERVER__PORT=63891` 导致静默起错端口）；
   用 curl 自测时**必须加 `--noproxy '*'`**（否则 `HTTP_PROXY` 会把 127.0.0.1 请求打去代理返回 502）。
   后端未启动时所有业务接口会失败并给出明确错误，不会显示空数据冒充「零待办」。
4. 想连远程 Mock：在「我的 → 开发者选项」切到「远程 Mock 9000」预设。
5. 开发者工具 **详情 → 本地设置 → 勾选「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」**
   （`project.private.config.json` 已把 `urlCheck` 设为 `false`，仅本机生效）。
6. 点击「编译」。若提示未绑定，见下一节「如何拿到已绑定会话」。

### 如何拿到「已绑定」会话（两个后端都行，机制不同）

| 方式 | 适用后端 | 操作 | 说明 |
| --- | --- | --- | --- |
| **DEV Mock 令牌**（推荐，本机 8787） | 本机 8787 | 我的 → 开发者选项 → 打开「DEV Mock 令牌」→ 填 entId/userId → 点「按当前配置重新登录」 | 走**非 RC2** 的 `/dev-support/v1/mock-token`，直接签发已绑定会话。需后端 dev profile + `mock-token.enabled=true` + `env-attestation=DEV_LOCAL` + 回环来源（四重门禁，缺一即 fail-closed） |
| **Mock 会话码**（远程 9000） | 远程 Mock | 我的 → 开发者选项 → 打开「Mock 会话码登录」→ 码填 `MOCK_BOUND` → 重新登录 | 走 RC2 API-01。远程 Mock 接受任意 code，但只有 `MOCK_BOUND` 会返回 `bindStatus=BOUND`；其它值（如 `probe`）得到未绑定会话，业务接口随后返回 `403 TSS_NOT_BOUND` |

> 两个通道都**默认禁用、仅 `develop` 可用、与微信真实登录完全隔离**（fail-closed，非 develop 硬禁用）。
> 远程 Mock 的 `/wx/v1/bindings/status` 在已绑定会话下仍返回 `UNBOUND`（与 `/wx/v1/me` 的 `BOUND` 不一致），
> 属该 Mock 服务内部不一致，**已登记为待确认项**，不影响待办/详情/历史/消息读取。

### 不需要 npm install

运行时**零第三方依赖**。仓库自带 `typings/wx.d.ts`（极简 `wx` / `Page` / `Component` 声明），
离线即可编译。若你要改用官方 `miniprogram-api-typings`：

```bash
npm i -D miniprogram-api-typings
# 然后：删除 typings/wx.d.ts，并在 tsconfig.json 中设置 "types": ["miniprogram-api-typings"]
```

（可选）本地自检。**提交前必跑 `npm run check`**（只跑 `tsc` 会漏掉 .wxml 缺陷）：

```bash
npm i -D typescript@4.9.5
npm run typecheck        # 仅 .ts：类型检查（strict）
npm run check:wxml       # 仅 .wxml：标签配平 + 嵌套结构校验
npm run check            # 两者都跑，任一失败即退出码 1
```

> ⚠️ `tsc` **只看 .ts，完全看不到 .wxml**。曾经出现过 `<view>` 开标签 55 个 / 闭标签 54 个的
> 重复开标签缺陷，`tsc` 报 0 错误，但开发者工具编译该页面会报错。
> 因此 `scripts/check-wxml.mjs` 是**提交前必跑**项：它逐标签统计开/闭数量（排除自闭合 `/>`），
> 并做栈式嵌套校验（能抓出数量相等但嵌套错乱的情况），失败时输出文件与行号并退出码 1。

---

## 二、base URL 切换（`TSS_API_BASE_URL` 可配）

三层优先级（高 → 低）：

1. **构建期环境变量**：`scripts/gen-env.mjs` 读取 `TSS_API_BASE_URL` 生成 `config/generated.env.ts`

   ```powershell
   $env:TSS_API_BASE_URL="http://39.106.179.158:9000"; node scripts/gen-env.mjs
   ```

   未设置时生成空串，运行期回退到预设。
2. **运行期覆盖**：「我的 → 开发者选项」里选预设或填自定义地址（写入 storage 键 `tss:baseUrlOverride`）。
3. **环境预设**（`config/env.ts` 的 `BASE_URL_PRESETS`，索引 0 即开发态默认）：

   | 预设 | 地址 | 说明 |
   | --- | --- | --- |
   | `LOCAL_DEV`（**开发默认**） | `http://127.0.0.1:8787` | 本机 `tss-wx-api`（dev profile），**需先启动**；唯一能稳定提供已绑定全量 Mock 数据 |
   | `REMOTE_MOCK` | `http://39.106.179.158:9000` | 已方可用的远程 Mock；默认未绑定会话，用 `MOCK_BOUND` 可取已绑定会话 |
   | `APPROVED_HTTPS` | `https://smallsp.bestsoft.vip` | 已备案域名，**真机/体验版/生产唯一允许形态** |

### 两个后端的能力边界（实测）

| 能力 | 本机 `127.0.0.1:8787` | 远程 `39.106.179.158:9000` |
| --- | --- | --- |
| RC2 包络 `{code,message,data,traceId,timestamp}` | ✅ | ✅ |
| 未带凭据 → `401 TSS_UNAUTHORIZED` | ✅ | ✅ |
| 已绑定会话（待办/详情/历史/消息有数据） | ✅（DEV Mock 令牌） | ✅（登录 code 用 `MOCK_BOUND`） |
| 默认（任意 code / 真实 wx.login）会话 | 需真实 appid/secret，未配置 → fail-closed | 未绑定 → 业务接口 `403 TSS_NOT_BOUND` |
| `me` / 绑定 / 解绑 / 负向用例（401/403/404） | ✅ | ✅ |
| PC 端确认绑定 | 内存态，可完成 | **永远停在 `PENDING_PC_CONFIRM`** |
| `bindings/status` 与 `me` 一致性 | 一致 | **不一致**（已绑定会话下仍返回 `UNBOUND`，对方缺陷，客户端原样展示 + develop 可见提示） |
| 详情 `workflow` 的 RC2 必填字段 | **齐全**（`definitionVersion` / `readOnly` / `events` / `dataVersion` 全在；源码与 mock 样例已核实，运行时待 QA 确认） | **缺 `definitionVersion`/`readOnly`/`events`，且多出非 RC2 字段 `workflowVersion`（对方缺陷）** |
| 非 RC2 字段 `workflowVersion` | 无（全模块 0 处） | 有 |

> 本机后端**当前默认是停止的**，需按上文步骤 3 启动后才能联调。

安全约束（已编码实现）：当 `wx.getAccountInfoSync().miniProgram.envVersion` 为
`trial` / `release` / `gray` 时，**强制只接受 HTTPS**，非 HTTPS 覆盖值会被忽略并回退到
`APPROVED_HTTPS`；开发者选项整体仅在 `develop` 下可见。

---

## 三、真机 / 体验版 / 生产的 HTTPS 限制（必须阅读）

- 微信小程序要求 request 域名必须是 **已备案 + HTTPS + 已在小程序后台登记为合法 request 域名**。
- `http://39.106.179.158:9000` 与 `http://127.0.0.1:8787` **在真机上不可用**：
  IP/HTTP 不合法，且真机无法访问开发机 localhost。
- 开发者工具里可以用「不校验合法域名」跑 HTTP/IP，但这**只是本地联调通道**。
- **本工程尚未在真机 / 体验版 / 生产环境验证通过**（`smallsp.bestsoft.vip` 是否已备案、
  是否已登记为合法 request 域名、后端是否已部署到该域名，均待确认）。
  在这些前置条件完成并实测之前，不应宣称「真机已通过」或「B2 已关闭」。
- `project.config.json` 的 `urlCheck` 保持 `true`（面向真机/生产）；
  本机联调用 `project.private.config.json` 覆盖为 `false`。

---

## 四、目录结构

```
tss-miniapp/
├─ app.ts / app.json / app.wxss        入口
├─ project.config.json                 团队共享配置（AppID、urlCheck=true）
├─ project.private.config.json         本机私有（urlCheck=false，仅联调）
├─ tsconfig.json / package.json / sitemap.json
├─ typings/wx.d.ts                     离线可用的极简运行时声明
├─ types/api.ts                        ★ RC2 全部 schema 的 TS 镜像（1:1）
├─ config/
│  ├─ env.ts                           base URL 解析与切换
│  ├─ generated.env.ts                 由 scripts/gen-env.mjs 生成
│  └─ app.ts                           展示常量（无密钥）
├─ utils/
│  ├─ envelope.ts                      包络宽容解析
│  ├─ http.ts                          wx.request Promise 封装
│  ├─ request.ts                       ★ 统一请求（Bearer / 错误码 / traceId）
│  ├─ api.ts                           13 个端点封装
│  ├─ session.ts                       会话（含开发期 Mock 会话通道）
│  ├─ errors.ts                        ★ 权威 20 条 TSS_* + 非权威码兼容分类
│  ├─ format.ts                        十进制金额/重量/时间格式化
│  ├─ dict.ts                          枚举 → 中文文案
│  ├─ workflow.ts                      ★ 工作流只读视图模型
│  ├─ detail-cache.ts                  条件 GET（ETag / Last-Modified）
│  ├─ mock.ts                          本地静态样例兜底（默认禁用）
│  ├─ storage.ts / page-utils.ts
├─ components/
│  ├─ field-row/                       动态字段渲染（BaseField.format）
│  ├─ workflow-graph/                  ★ 工作流动态只读渲染 + 事件时间轴
│  ├─ freshness-bar/                   数据新鲜度（meta.freshness）
│  └─ empty-state/
├─ pages/
│  ├─ index/      MP-010 首页（总待办 / 四业务分类 / 新鲜度）
│  ├─ todos/      MP-011 待办列表（docType + keyword + cursor 分页）
│  ├─ detail/     MP-020~023 详情（动态字段 + 工作流 + sourceStatus）
│  ├─ history/    MP-030 历史（action / from / to 筛选）
│  ├─ messages/   MP-040 消息（订阅意愿 + 通知列表）
│  └─ profile/    MP-050 个人中心（绑定 / 解绑 / 退出 / 版本隐私）
└─ scripts/
   ├─ gen-env.mjs             TSS_API_BASE_URL → config/generated.env.ts
   └─ check-wxml.mjs          ★ WXML 标签配平 + 嵌套校验（提交前必跑）
```

---

## 五、关键实现说明

### 5.1 类型生成方式

`types/api.ts` 是**依据 `tss-openapi-v1.0-rc2.yaml` 的 47 个 schema 逐字段人工转写**的 TS 镜像，
并与后端 `tss-wx-api` 的 `dto/*.java`（30 个 RC2 镜像 DTO）与 `dto/TssEnums.java` 逐字段核对，
保证前后端零漂移。未使用任何自动生成器，避免生成器与契约版本不一致造成静默漂移。

- 包络固定：`{ code, message, data, traceId, timestamp }`，成功码 `SUCCESS`。
- 已废弃不读取：旧 `requestId` / `serverTime` / `sourceMode`；`dataMode` 统一读 `data.meta.dataMode`。
- 未知字段：运行时**只读取 RC2 定义字段**，不做 `additionalProperties:false` 校验，
  服务端新增字段不会导致崩溃（实测远程 Mock 在 `summary` 里多返回了历史字段 `dataFreshness`，已验证被安全忽略）。

### 5.2 统一请求服务（`utils/request.ts`）

- 自动附加 `Authorization: Bearer <sessionToken>`（`auth: false` 的登录接口除外）。
- 统一解包络；`SUCCESS` 取 `data`，否则按 `utils/errors.ts` 的 `TSS_*` 映射抛错。
- **`401` / `TSS_UNAUTHORIZED`**：自动清空本地会话，下次请求重新登录（不会崩页面）。
- **`403 TSS_NOT_BOUND`（及 `TSS_BINDING_DISABLED` / `TSS_ENT_SCOPE_DENIED`）**：
  **不弹 toast、不显示空列表**，由五个业务页面统一渲染「尚未绑定 ERP 员工」引导卡 + 「去绑定」按钮，
  避免把「拿不到数据」误呈现为「零待办」。此路径也**不会**退回静态样例。
- `traceId` 统一 `console.info` 打印，并在页面错误条上展示（可点击复制），便于与后端日志对齐。
- 网络失败/超时/解析失败使用本地错误码（`TSS_CLIENT_*`，不属于 `TSS_*` 主集，已在代码注释中声明）。

### 5.2.1 错误码权威清单（`utils/errors.ts`）

以 team-lead 下发并保存的**权威 20 条**为准（`error-codes.csv` 已从桌面删除）：

| code | HTTP | 可重试 | | code | HTTP | 可重试 |
| --- | --- | --- | --- | --- | --- | --- |
| `TSS_INVALID_ARGUMENT` | 400 | 否 | | `TSS_BIND_INVITE_EXPIRED` | 409 | 否 |
| `TSS_INVALID_CURSOR` | 400 | 否 | | `TSS_BIND_CONFLICT` | 409 | 否 |
| `TSS_UNAUTHORIZED` | 401 | 否 | | `TSS_IDEMPOTENCY_CONFLICT` | 409 | 否 |
| `TSS_SESSION_EXPIRED` | 401 | 否 | | `TSS_DATA_STALE` | 409 | 是 |
| `TSS_NOT_BOUND` | 403 | 否 | | `TSS_RATE_LIMITED` | 429 | 是 |
| `TSS_FORBIDDEN` | 403 | 否 | | `TSS_WECHAT_UNAVAILABLE` | 503 | 是 |
| `TSS_ENT_SCOPE_DENIED` | 403 | 否 | | `TSS_ERP_READ_UNAVAILABLE` | 503 | 是 |
| `TSS_BINDING_DISABLED` | 403 | 否 | | `TSS_NOT_CONFIGURED` | 503 | 否 |
| `TSS_ADMIN_FORBIDDEN` | 403 | 否 | | `TSS_INTERNAL_ERROR` | 500 | 是 |
| `TSS_NOT_FOUND` | 404 | 否 | | `TSS_WRITE_DISABLED` | 403 | 否 |

- 权威清单**未提供文案**，故 `defaultMessage` 是客户端兜底；展示优先使用服务端 `message`，
  仅当服务端返回泛化文案（如「请求未完成」）时回退兜底。
- 后端 `TssErrorCode.java` 中**不在权威清单内**的码（如 `TSS_WECHAT_NOT_CONFIGURED`、
  `TSS_SESSION_INVALID`、`TSS_BILL_NOT_ACCESSIBLE` 等 14 个）登记在 `NON_AUTHORITATIVE_CODES`：
  只用于**分类**（需重登录 / 需绑定 / 可重试），展示一律用服务端 message 或「请求未完成（原码）」，
  客户端不为此臆造语义。

### 5.3 工作流动态只读渲染（`components/workflow-graph`）

按 `workflow` 的字段动态渲染：

| RC2 字段 | 渲染方式 |
| --- | --- |
| `nodes[]` | 节点卡片：名称、类型、状态、处理人、完成时间；顺序严格以 `nodes` 为准 |
| `edges[]` | 仅用于连线/分支提示，**不据此重排节点** |
| `graphType` | LINEAR / DAG / UNKNOWN 显示为「线性流程 / 分支流程 / 未知」 |
| `branchGroup` | 同一 `branchGroup` 的节点归为一个分组块 |
| `parallelMode` | ANY=或签、ALL=会签、SEQUENTIAL=依次审批、NONE=不标注（分组头展示标签） |
| `events[]` | 时间轴：处理人 / 时间 / 动作 / 结果 / 流水号 / 意见，按 `occurredAt` 升序 |
| `readOnly` | 展示「只读」标签；**组件内不存在任何同意/驳回按钮** |
| `currentNodeIds` | 对应节点高亮并标记「当前」 |

`availableActions`（`utils/workflow.ts#buildActionViews`）**只渲染后端返回的 `VIEW` / `OPEN_ERP`**，
其它值记 warn 日志并忽略，**前端绝不臆造 `APPROVE` / `REJECT`**。
`OPEN_ERP` 点击只提示「请在电脑端 ERP 中处理」，不编造跳转链接。

### 5.4 字段渲染与「不编造假值」

`components/field-row` 按 `BaseField.format` 渲染：

- `AMOUNT`：十进制字符串千分位 + `¥`（**不转浮点**，不做任何数值重算）
- `WEIGHT_TON`：原字符串 + ` 吨`（保留原始小数位）
- `DATE` / `DATETIME`：按服务端 ISO 8601 拆解展示，**不做跨时区换算**（避免显示成另一个时刻）
- `BADGE`：徽标样式；`TEXT`：原样
- `verified === false`：
  - 值为空 → 显示「**字段暂未提供**」
  - 值非空 → 显示原值并追加「未核实」灰色标记（不把未实证数据伪装成已实证）
- 任何情况下**不会用 0、占位数值或推测值填充**。

### 5.5 V1.0 单当前企业

`entId` 由服务端绑定推导（API-02 返回），前端**只展示、不提交**；
所有请求均不携带 `userId` / `entId` 作为授权依据；六个页面**均无企业切换入口**
（`MeData.enterpriseSwitchEnabled` 即使为 true 也不渲染切换 UI）。

### 5.6 本地静态样例兜底（默认禁用）

`utils/mock.ts` 内的数据**逐字取自** `api-examples-rc2.json`，是**静态样例，不是真实后端返回**。
仅在「我的 → 开发者选项 → 静态样例兜底」开启 **且** 真实请求失败时使用，
并在页面顶部显著展示提示条：`当前展示的是本地静态样例（api-examples-rc2.json），不是真实后端返回`。

---

## 六、已知未完成项 / 待确认事项

1. **`workflow` 缺字段是【远程 Mock 单方问题】，本机后端字段齐全**
   - **远程 Mock `39.106.179.158:9000`（对方缺陷，已实测）**：`GET /wx/v1/todos/{taskNum}/detail`
     的 `workflow` **缺少** RC2 要求的 `definitionVersion`、`readOnly`、`events` 三个必填字段，
     另多返回一个**非 RC2 字段** `workflowVersion`。
   - **本机 `tss-wx-api`（我方实现，源码已核实）**：`dto/Workflow.java` 明确含
     `dataVersion` / `definitionVersion` / `readOnly`，并有独立的 `dto/WorkflowEvent.java`
     （含 `menuName` / `operatorDisplay` / `occurredAt` / `serialNum` / `comment`）；
     `src/main/resources/mock/api-examples-rc2.json` 的 API-05 workflow 实际字段为
     `workflowId, instanceId, currentNodeIds, nodes, edges, graphType, dataVersion,
     definitionVersion, readOnly, events` —— **四个字段齐全**；全模块检索 `workflowVersion` = **0 处**。
     即本机不存在该缺陷（运行时以 QA 实测为准）。
   - 前端处理：不崩溃、`readOnly` 缺失时按 fail-safe 视为 `true`、缺失字段在详情页**如实列出**。
     详情页那段「服务端未返回字段：…」是**按真实响应动态渲染**的，打本机时自然不会显示 —— 这是正确行为。
2. **条件 GET 未真正生效**：远程 Mock 未返回 `ETag` / `Last-Modified`，因此详情页始终 200
   （符合决议 F4「未实现条件 GET 的后端须先返回 200」）。前端代码已就绪，服务端支持后自动启用。
3. **真机 HTTPS 域名**：`smallsp.bestsoft.vip` 的备案状态、小程序后台 request 域名登记状态、
   后端部署状态均待确认；未完成前不能宣称真机可用。
4. **订阅消息模板 ID 未配置**：`config/app.ts` 的 `SUBSCRIBE_TEMPLATE_IDS` 为空数组。
   需在小程序后台申请模板后填入；当前状态下只记录意愿、不弹微信授权窗、不伪造订阅结果。
5. **通知跳转**：`NotificationItem` 只有 `approvalId` 无 `taskNum`，RC2 也无「按 approvalId 取详情」
   端点，因此点击通知只弹详情层 + 「去待办列表」，不臆造接口。
6. **PC 管理端（API-12 / API-13）不在小程序实现**：属 `adminAuth` 作用域，`types/api.ts`
   只保留类型镜像，无调用。
7. **错误码权威来源已变更**：契约决议引用的 `error-codes.csv` 已从桌面删除。
   `utils/errors.ts` 现以 team-lead 下发并保存的**权威 20 条**为准（见 5.2.1 表格）。
   **待修正项**：后端 `TssErrorCode.java` 仍定义并使用权威清单里没有的码，
   例如 API-01 用的 `TSS_WECHAT_NOT_CONFIGURED`（应为 `TSS_WECHAT_UNAVAILABLE` 或 `TSS_NOT_CONFIGURED`）。
   前端已登记兼容分类（不臆造语义），后端改码后本项才能关闭。
8. **远程 Mock 内部不一致（对方缺陷，已登记，客户端不替它圆）**：已绑定会话下
   `GET /wx/v1/bindings/status` 仍返回 `status=UNBOUND`，与同一会话 `GET /wx/v1/me` 的
   `bindStatus=BOUND` 矛盾（对照组未绑定码下 status 也是 UNBOUND，说明该端点没跟随会话态）。
   处理口径：① `bindings/status` **原样展示**，绝不用 `me` 的值覆盖它的渲染
   （真实场景存在「me 已绑定但 bindRequest 仍在 PENDING_PC_CONFIRM」的合法中间态，静默覆盖会误报）；
   ② 仅在**开发者选项 / develop 可见**范围内显示一条冲突提示条，**不弹 toast** 骚扰普通用户。
9. **后端默认未启动**：本机 `tss-wx-api` 当前是停止状态，需按第一节步骤 3 启动（显式 `--server.port=8787`）。
8. **未做真机预览 / 体验版验证**，也未做小程序官方审核相关配置（如用户隐私保护指引）。

---

## 七、不做什么（与决议一致）

- 不实现 ERP 写回，不提供真实审批（同意/驳回）能力。
- 不把任何密钥（AppSecret 等）放入前端或源码库。
- 不把静态样例说成真实后端返回；不宣称真机已通过。
- 不自行定义与 RC2 / 后端 `TssErrorCode` 不一致的 JSON 结构、状态枚举或错误码。
