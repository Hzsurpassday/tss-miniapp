# 时空智审 V1.0 RC2 契约适配层 —— 启动与验证说明

> **状态**：本轮的 `/wx/v1`（API-01..API-11）与 `/admin/v1/tss`（API-12..API-13）**已真实实现并跑通 13 端点契约测试**（**98/98** 通过）。
> 数据是 DEV Mock（`dataMode=MOCK`），**不代表已接入真实 ERP**。
> 真实微信登录未配置凭据时会返回 `503 TSS_NOT_CONFIGURED`（fail-closed，这是正确行为不是故障）。
> 对外错误码**只使用权威 20 条**（见 §8），分页多页流转有真实 HTTP 证据（见 §5.3）。

---

## 1. 模块位置与边界

| 项 | 值 |
| --- | --- |
| 模块路径 | `E:\my-project\datahub-v2-src\datahub-v2-src\tss-wx-api` |
| Java 包 | `com.viding.datahub.tss.wx.*` |
| 默认端口 | **8787**（契约修订决议 B2 指定的本机联调地址） |
| 本机地址 | `http://127.0.0.1:8787` |
| 契约依据 | `C:\Users\Administrator\Desktop\tss-openapi-v1.0-rc2.yaml`（唯一字段级标准） |
| 样例源 | `src/main/resources/mock/api-examples-rc2.json`（与桌面冻结样例同一份文件） |

**为什么是独立 Maven 模块**（重要，需知悉）：
既有 `datahub-backend` 启动依赖 MySQL / Flyway / Quartz / 后台管理会话与 `DATAHUB_MASTER_KEY`，
在本机没有这些依赖的情况下**无法启动**，也就无法「针对真实启动的服务」跑 13 端点契约测试（决议 B1 的硬性要求）。
因此本适配层作为同一父 POM 下的独立模块落地：

* 不修改、不替代 `datahub-backend` 任何类；不占用其 8080 端口；不复用其数据源；
* 不触碰旧 `TssApprovalController` 的执行/核销内部运维接口，也不改动 `TssMiniProgramController`（`/miniapp/v1/tss`）；
* 后续若要在生产复用，只需把 `WxSessionStore` / `BindingStore` 的内存实现替换为读
  `tss_wechat_session` / `tss_staff_binding` 的实现，接口不变。

---

## 2. 环境要求

| 组件 | 本机实测版本 | 说明 |
| --- | --- | --- |
| JDK | `C:\tss-build-tools\jdk21\jdk-21.0.13.0.2+1`（21） | **不要**用 `F:\DHERP\jdk`（那是 1.8，编译会失败） |
| Maven | `C:\tss-build-tools\maven\apache-maven-3.9.9` | 本机无 `mvnw`，也没把 mvn 加进 PATH |
| settings | `C:\tss-build-tools\settings.xml` | 阿里云镜像；当前仓库可离线构建 |

> 系统里 `java` / `mvn` **不在 PATH**（`java -version` 直接敲会报 command not found），
> 但 `JAVA_HOME` 指向 `F:\DHERP\jdk`（Java 8）。所以下面每条命令都**显式指定** JDK21 与 Maven 路径，不要省略。

---

## 3. 一步一条命令：构建

在 **Git Bash** 中执行：

```bash
# ① 固定工具链（每个新开终端都要执行一次）
export JAVA_HOME="/c/tss-build-tools/jdk21/jdk-21.0.13.0.2+1"
export MAVEN_HOME="/c/tss-build-tools/maven/apache-maven-3.9.9"
export PATH="$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH"

# ② 进入仓库根目录
cd /e/my-project/datahub-v2-src/datahub-v2-src

# ③ 编译（离线）
mvn -s "C:/tss-build-tools/settings.xml" -o -pl tss-wx-api -DskipTests compile

# ④ 跑全部测试（13 端点契约测试 + 门禁 + 游标 + 错误码清单），共 98 个
mvn -s "C:/tss-build-tools/settings.xml" -o -pl tss-wx-api test
```

预期结果：`Tests run: 98, Failures: 0, Errors: 0` + `BUILD SUCCESS`。
分布：`WxApiContractIT` 55 / `BindingViewResolverTest` 11 / `MockTokenGateTest` 13 / `CursorCodecTest` 10 / `TssErrorCodeTest` 4 / `WxAuthFailClosedTest` 3 / `AdminTokenConfigTest` 2。

---

## 4. 一步一条命令：启动（端口 8787）

> 本机离线仓库**缺少 `maven-jar-plugin`**，因此 `mvn package` 打不出 jar；
> 也**缺少 `spring-boot:run` 所需插件依赖**。下面的启动方式是把依赖 jar 收集到 `target/lib` 后直接用 `java` 启动，实测可用。

```bash
export JAVA_HOME="/c/tss-build-tools/jdk21/jdk-21.0.13.0.2+1"
export PATH="$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH"
cd /e/my-project/datahub-v2-src/datahub-v2-src

# ① 编译到 target/classes
mvn -s "C:/tss-build-tools/settings.xml" -o -pl tss-wx-api -DskipTests compile

# ② 收集依赖 jar（仅首次需要；已存在可跳过）
cd tss-wx-api
rm -rf target/lib && mkdir -p target/lib
find ~/.m2/repository -name "*.jar" ! -name "*sources*" ! -name "*javadoc*" -exec cp {} target/lib/ \;

# ③【重要】剔除本模块不声明、却会触发错误自动装配的 jar
#    - 含 security 的：会启用 Spring Security 默认链，导致全部请求 401 空响应
#    - 含 jdbc/flyway/quartz/mysql/mssql 的：会触发 DataSource 自动装配，因无 url 而启动失败
mkdir -p target/lib-disabled
for f in $(ls target/lib | grep -iE "security|jdbc|flyway|quartz|mysql|mssql|HikariCP|spring-boot-persistence"); do
  mv "target/lib/$f" "target/lib-disabled/" 2>/dev/null
done

# ④ 启动（dev profile + 打开 DEV Mock 令牌四重门禁）
"$JAVA_HOME/bin/java" \
  -cp "target/classes;target/lib/*" \
  com.viding.datahub.tss.wx.TssWxApiApplication \
  --spring.profiles.active=dev \
  --server.port=8787 \
  --datahub.tss.wx.mock-token.enabled=true \
  --datahub.tss.wx.mock-token.env-attestation=DEV_LOCAL
```

看到 `Started TssWxApiApplication` 即启动成功。**不要关掉这个终端**（关掉即停服）。

> ⚠️ **实测坑 1：必须显式传 `--server.port=8787`。**
> 某些终端环境会注入 `SERVER__PORT=63891` 之类的环境变量，Spring Boot 宽松绑定会把它
> 解析成 `server.port`，**优先级高于 `application.yml`**，导致服务静默起在 63891 而不是 8787。
> 命令行参数优先级最高，因此上面第 ④ 步显式带 `--server.port=8787` 是必需的。
>
> ⚠️ **实测坑 2：curl 需要 `--noproxy '*'`。**
> 若环境设置了 `HTTP_PROXY`，`curl http://127.0.0.1:8787/...` 会被代理拦截并返回 502。
> 本仓库的 `smoke-13.sh` 已内置 `--noproxy '*'`；手工 curl 请自行加上。

> 若有网络、能把 Maven 插件拉全，等价的常规启动方式是：
> `mvn -pl tss-wx-api spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments="--server.port=8787 --datahub.tss.wx.mock-token.enabled=true --datahub.tss.wx.mock-token.env-attestation=DEV_LOCAL"`

---

## 5. 验证

### 5.1 一键冒烟（推荐）

```bash
cd /e/my-project/datahub-v2-src/datahub-v2-src/tss-wx-api
BASE=http://127.0.0.1:8787 bash ./smoke-13.sh
```

脚本会先取 DEV Mock 令牌（已绑定身份 + 一枚未绑定身份用于 API-09），再依次调用 13 个端点
并打印 `<<HTTP 状态>>` 与响应体；末尾还包含负面用例（无凭据 401、错误管理端令牌 403）。

另有绑定状态专项脚本（输出八格矩阵 + 裁决 C 解绑错误码矩阵的原始响应体，见 §9.3）：

```bash
BASE=http://127.0.0.1:8787 bash ./smoke-binding-matrix.sh
```

分页专项见 `./smoke-pagination.sh`（用法见 §5.3）。

### 5.2 手工验证（逐条 curl）

```bash
B=http://127.0.0.1:8787

# 取 DEV Mock 令牌（非 RC2 端点，仅 DEV 支撑）
TOK=$(curl -s -X POST $B/dev-support/v1/mock-token \
  -H "Content-Type: application/json" \
  -d '{"entId":"E498AU5L35J","userId":"U4FXG07VRDI","bindStatus":"BOUND"}' \
  | sed -E 's/.*"sessionToken":"([^"]+)".*/\1/')

# API-02
curl -s -w "\n<<HTTP %{http_code}>>\n" $B/wx/v1/me -H "Authorization: Bearer $TOK"

# API-03 / 04 / 05 / 06 / 07
curl -s $B/wx/v1/todos/summary                -H "Authorization: Bearer $TOK"
curl -s "$B/wx/v1/todos?docType=SALES_BUSINESS" -H "Authorization: Bearer $TOK"
curl -s $B/wx/v1/todos/355377/detail          -H "Authorization: Bearer $TOK"
curl -s "$B/wx/v1/history?from=2026-10-01&to=2026-12-31" -H "Authorization: Bearer $TOK"
curl -s $B/wx/v1/notifications                -H "Authorization: Bearer $TOK"

# API-08 / 09 / 10 / 11
curl -s -X POST $B/wx/v1/notifications/consent -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" -d '{"templateIds":["wx_tpl_01"],"consented":true}'
curl -s -X POST $B/wx/v1/bindings/claim -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" -d '{"inviteCode":"INV-EXAMPLE","claimNonce":"nonce_001"}'
curl -s $B/wx/v1/bindings/status -H "Authorization: Bearer $TOK"
curl -s -X POST $B/wx/v1/bindings/unbind -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" -d '{"confirmation":"UNBIND","clientNonce":"nonce_002"}'

# API-12 / 13（PC 管理端，DEMO 令牌 dev-admin-token）
curl -s $B/admin/v1/tss/bindings -H "Authorization: Bearer dev-admin-token"
curl -s $B/admin/v1/tss/mappings -H "Authorization: Bearer dev-admin-token"
```

### 5.3 条件 GET（API-05 的 ETag / 304）

```bash
ET=$(curl -s -D - -o /dev/null $B/wx/v1/todos/355377/detail -H "Authorization: Bearer $TOK" \
     | grep -i '^etag:' | tr -d '\r' | sed 's/^[Ee][Tt][Aa][Gg]: //')
curl -s -o /dev/null -w "second=%{http_code}\n" $B/wx/v1/todos/355377/detail \
  -H "Authorization: Bearer $TOK" -H "If-None-Match: $ET"     # 期望 304
```

### 5.4 API-01 真实微信登录

未配置 `appid/secret` 时**必然**返回 503（不做任何占位降级）：

```bash
curl -s -w "\n<<HTTP %{http_code}>>\n" -X POST $B/wx/v1/auth/login \
  -H "Content-Type: application/json" -d '{"code":"WECHAT_LOGIN_CODE"}'
# => {"code":"TSS_NOT_CONFIGURED", ...}  <<HTTP 503>>
```

要打通真实登录，启动时把凭据传进去（**禁止写进代码库**）：

```bash
--datahub.tss.wx.wechat.enabled=true \
--datahub.tss.wx.wechat.appid=$TSS_WECHAT_APPID \
--datahub.tss.wx.wechat.secret=$TSS_WECHAT_SECRET
```

---

### 5.3 分页多页流转 + 非契约路径包络（真实 HTTP 证据）

```bash
cd /e/my-project/datahub-v2-src/datahub-v2-src/tss-wx-api
BASE=http://127.0.0.1:8787 bash ./smoke-pagination.sh
```

证据文件 `target/smoke-pagination.out`（本次实测）：

| 断言 | 实测结果 |
| --- | --- |
| ① `limit=1` 第 1 页返回非空 `nextCursor` 且 `hasMore=true` | ✅ `taskNum=355377`，`hasMore=true`，`totalApprox=3` |
| ② 用该游标取第 2 页得到下一项且不重复 | ✅ 第 2 页 `355378`、第 3 页 `355379`，三页互不重复，第 3 页 `hasMore=false`、`nextCursor=null` |
| ③ 改筛选后复用旧游标 → `TSS_INVALID_CURSOR` | ✅ `?docType=SALES_BUSINESS&cursor=…` 与 `?keyword=77971&cursor=…` 均返回 **400 `TSS_INVALID_CURSOR`** |
| ④ 非契约路径输出统一包络 | ✅ `GET /admin/v1/tss/overview` → **404 `TSS_NOT_FOUND`**，包络 `{code,message,data,traceId,timestamp}`，不再是 Spring 默认 `{timestamp,status,error,path}` |

#### 5.3.1 Mock 数据逐条来源（演示前必读，避免被误认为我方编造）

| 数据 | 来源 | 说明 |
| --- | --- | --- |
| 待办 `355377`（标题「销售业务审批」、往来单位「示例客户」） | **`api-examples-rc2.json` 冻结样例原文照搬** | 内容**未改一字**，不是我方编造；因此它没有「【测试数据】」前缀，这是照搬的结果，不是漏标 |
| 待办 `355378` / `355379` | 我方自造种子（`MockTestSeed`） | 标题与往来单位带 **【测试数据】** 前缀，`dataMode=MOCK` |
| 历史 `355101`~`355104` | 我方自造种子 | 已归档号段，与在办待办**完全错开**；`resultVerified` **一律 false** |
| 详情 `355377` / `355378` / `355379` | 样例原文 / 由样例派生 | 派生详情沿用样例结构，未核实字段保持 `verified=false` |

**两条必须说清的既定口径**：
1. **历史与待办单号不重叠（为演示自洽而剔除，非断言对方违规）**：
   冻结样例 `api-examples-rc2.json` 中，`355377` 同时出现在 API-04（PENDING 在办）与
   API-06（APPROVED 已审批）。
   **这不必然是缺陷**——契约 9.2 允许「同一 TASKNUM 存在多个候选人」：
   若那条 APPROVED 是**另一候选人**的已办记录，而当前用户仍在待办中，则属合法的多候选状态；
   只有它是**同一用户**自己已处理却又出现在自己待办里，才构成自相矛盾。
   样例未标注处理人归属，**我方目前无法断定属于哪一种**，按「只打能举证的」原则不将其列为缺陷。
   为避免演示时无法当众解释，我方采取的措施是：
   **不改写对方样例任何字段内容**，仅在 `MockDataSet` 中加一条明确的夹具自洽规则
   「历史不得包含在办单号」，把这条**暂无法归属**的记录从演示数据中剔除；
   自造种子一律使用归档号段（355101~355104），杜绝「同一单据既是待办又是已审批」的观感。
   该约束已由 `WxApiContractIT.mock_fixture_consistency` 断言锁死（重叠即红）。
   **【待澄清询问项（非指控）】**：样例中 `355377` 同时出现在 API-04(PENDING) 与 API-06(APPROVED)，
   是否为 9.2「同一 TASKNUM 多候选人」场景的合法设计？请说明处理人归属；
   若确认为合法多候选，我方按需恢复该条并改为按处理人维度区分展示。
2. **`resultVerified` 与 MOCK 口径**：我方自造的历史种子**一律 `resultVerified=false`**
   （MOCK 未经 ERP 回查，不能宣称「已回查证实」）。
   冻结样例原文中 `355377` 那条为 `true`（`api-examples-rc2.json:241`），属**对方样例原值**，
   我方既没有偷偷改成 false、也没有留着不说——它因上述第 1 条规则未进入历史列表；
   若对方澄清该值口径，再按其结论调整。

---

## 6. 配置项速查

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `server.port` | `8787` | 决议 B2 本机联调端口 |
| `datahub.tss.wx.data-mode` | `MOCK` | 真实 ERP 接入前**不得**改为 `ERP` |
| `datahub.tss.wx.schema-version` | `1.0.0` | 输出在 `meta.schemaVersion` |
| `datahub.tss.wx.clock-zone` | `Asia/Shanghai` | 所有 ISO-8601 时间的偏移基准 |
| `datahub.tss.wx.cursor.secret` | 空 ⇒ 启动时随机生成 | 为空时重启后旧游标失效（日志有明确提示） |
| `datahub.tss.wx.cursor.ttl-seconds` | `1800` | 游标有效期，超时 → `TSS_INVALID_CURSOR` |
| `datahub.tss.wx.wechat.*` | 全关 | 真实微信登录，未配置即 503 |
| `datahub.tss.wx.mock-token.enabled` | **false** | DEV Mock 令牌开关（默认关闭） |
| `datahub.tss.wx.mock-token.env-attestation` | 空 | **必须**等于 `DEV_LOCAL` 才允许颁发 |
| `datahub.tss.wx.admin.static-tokens` | **空（fail-closed）** | `<token>=<ROLE>|<entId>`；**必须由环境注入**，未注入则管理端全 401 |
| `datahub.tss.wx.subscription.known-template-ids` | `wx_tpl_01` | 不在白名单 ⇒ `UNAVAILABLE`，不谎报 `GRANTED` |

---

## 7. 对外错误码：权威 20 条（不多不少）

**硬约束**：对外 `code` 只能取下表 20 个取值之一；`message` 可以面向用户友好化，但 `code` 绝不自造。
该约束由 `TssErrorCodeTest` 断言锁死（条数 == 20 + 名称集合逐条比对 + HTTP 映射 + 可重试标记）。

| code | HTTP | 可重试 | 语义 |
| --- | --- | --- | --- |
| `TSS_INVALID_ARGUMENT` | 400 | 否 | 参数/请求体不符合契约（含枚举非法、limit 越界、未知字段） |
| `TSS_INVALID_CURSOR` | 400 | 否 | 游标被篡改/过期/与身份、筛选、排序锚点不匹配 |
| `TSS_UNAUTHORIZED` | 401 | 否 | 未登录、令牌无效、服务端不存在该会话 |
| `TSS_SESSION_EXPIRED` | 401 | 否 | 会话已过期或被撤销 |
| `TSS_NOT_BOUND` | 403 | 否 | 微信未绑定 ERP 员工 |
| `TSS_FORBIDDEN` | 403 | 否 | 无权查看该资源（含跨员工/跨企业） |
| `TSS_ENT_SCOPE_DENIED` | 403 | 否 | 越出授权企业作用域 |
| `TSS_BINDING_DISABLED` | 403 | 否 | 绑定已停用（含 ERP 用户状态失效） |
| `TSS_ADMIN_FORBIDDEN` | 403 | 否 | 管理端角色/作用域不足 |
| `TSS_WRITE_DISABLED` | 403 | 否 | V1.0 只读：禁止真实审批写回（本轮无写端点，保留码位） |
| `TSS_NOT_FOUND` | 404 | 否 | 资源不存在或不可见 |
| `TSS_BIND_INVITE_EXPIRED` | 409 | 否 | 绑定邀请已过期 |
| `TSS_BIND_CONFLICT` | 409 | 否 | 已存在冲突绑定（本轮无触发路径，保留码位） |
| `TSS_IDEMPOTENCY_CONFLICT` | 409 | 否 | 相同幂等键对应不同内容 |
| `TSS_DATA_STALE` | 409 | **是** | 数据版本已变化（本轮无触发路径，保留码位） |
| `TSS_RATE_LIMITED` | 429 | **是** | 请求超速率（本轮未接限流实现，保留码位） |
| `TSS_INTERNAL_ERROR` | 500 | **是** | 未分类内部错误，用 traceId 排查 |
| `TSS_WECHAT_UNAVAILABLE` | 503 | **是** | 微信 code2session 失败/超时 |
| `TSS_ERP_READ_UNAVAILABLE` | 503 | **是** | 声明 ERP 但只读通道不可用（拒绝返回空结果） |
| `TSS_NOT_CONFIGURED` | 503 | 否 | 服务能力未配置（微信凭据缺失、DEV Mock 颁发器未启用等） |

**来源与诚实声明**：任务单引用的 `error-codes.csv` 在交付环境**仍未找到**。
本清单改以团队内同一份权威清单为准：`mock-openapi/tss-contract-mock/server.py` 第 61~80 行的
`ERROR_CODES` 字典（20 条）。可交叉验证：该清单恰好复现「命中 11 / 缺失 9 / 多出 14」的比对结论。
此前多出的 14 个非权威码已**全部删除**，内部语义按 `TssErrorCode` 类注释的映射表收口到权威码
（HTTP 状态保持不变）。若后续拿到正式 CSV，仍需以 CSV 为准再核一遍。

---

## 8. 已知限制（如实列示，不得当作已完成）

1. **数据是 MOCK**：所有业务数据来自 `api-examples-rc2.json`，ERP 未接入；`meta.dataMode` 恒为 `MOCK`。
2. **API-01 在本机无法走真实微信**：没有 AppID/Secret（决议 B3 明确该依赖未到位）。
3. **PC 端绑定确认未实现（契约内）**：API-09 发起后恒为 `PENDING_PC_CONFIRM`，不会自动变 `BOUND`；
   PC 确认端点属下一阶段独立 Admin 契约（见 `docs/admin-openapi-v1.0-draft.yaml`）。
   为了让「PC 确认后已绑定 / 申请过期 / 停用」这三格可被真实 HTTP 复现与验收，另提供
   **非契约** DEV 夹具 `POST /dev-support/v1/binding-fixture`（见 §9.2 末尾说明），
   受装配门禁 + 四重运行时门禁保护，**不是** RC2 端点、不得对外宣称。
4. **管理端鉴权是 DEV 静态令牌**：RC2 只声明了 `adminAuth: http bearer`，未定义颁发机制。
   生产必须接真实 PC 会话 + RBAC；在替换并通过评审前不得宣称管理端鉴权已完备。
5. **分页样本是「自造测试数据」，不是真实 ERP 数据**：RC2 冻结样例每类只有 1 条，无法产生
   `nextCursor`，因此本模块在冻结样例之外追加了 2 条待办 + 4 条历史（`MockTestSeed`），
   合计 3 条待办 / 4 条历史（冻结样例那条 355377 因与在办待办同号被自洽规则剔除，见 §5.3.1），
   自造种子标题与往来单位一律以 **【测试数据】** 开头、`dataMode=MOCK`，
   并在代码注释中写明「非 ERP 真实导出」。多页流转已有真实 HTTP 证据（§5.3），
   但**这些数据不得被当作真实业务数据引用**。
6. **会话与绑定是内存实现**：进程重启即失效；生产需替换为数据库实现（`WxSessionStore` / `BindingStore` 接口已留好）。
7. **error-codes.csv 仍未找到**：任务单引用的 `C:\Users\Administrator\Desktop\error-codes.csv` 在交付环境中不存在
   （桌面与全盘检索均无命中）。当前权威 20 条来自 `mock-openapi/tss-contract-mock/server.py` 的
   `ERROR_CODES`（见 §7），**不是**来自正式 CSV；拿到正式 CSV 后需重新核对。
8. **未做速率限制**：`TSS_RATE_LIMITED` 已在错误码表中定义，但本轮未接入限流实现。

---

## 9. RC3 对齐核对（Java 侧逐项结论）

| # | RC3 要求 | 本侧结论 | 依据 |
| --- | --- | --- | --- |
| 1 | 工作流字段 | **本来就合规**（仅补注释） | API-05 实测 `workflow` 键 = `currentNodeIds/dataVersion/definitionVersion/edges/events/graphType/instanceId/nodes/readOnly/workflowId`；`readOnly=true`；**无** `workflowVersion`；`events` 字段存在（可为空数组）；每个 event 含 9 个字段（eventId/nodeId/actionType/menuName/operatorDisplay/occurredAt/outcome/serialNum/comment）。已在 `Workflow` javadoc 写明 `definitionVersion`（流程定义）与 `dataVersion`（数据内容）**两级版本不得混用**；`availableActions` 仅 `VIEW` |
| 2 | 筛选与分页 | **本来就合规** | 顺序确为「本人隔离 → 先筛选 → 再稳定排序 → 再游标分页」（`WxTodoController` / `WxHistoryController`）；游标绑定身份+筛选+排序锚点，改筛选复用旧 cursor → `400 TSS_INVALID_CURSOR`（三种改法均有实测）；`from>to` 或非法日期 → `400 TSS_INVALID_ARGUMENT` |
| 3 | 新鲜度 | **本来就合规** | summary 无 `dataFreshness`；`meta.freshness` 枚举仅 `FRESH / DELAYED / UNAVAILABLE`，**无 `STALE`**（`TssEnums.Freshness`） |
| 4 | 绑定状态一致性 | **已按裁决 A/B/C 重写为单一事实来源** | `/wx/v1/me` 与 `/wx/v1/bindings/status` 现在<b>共用同一个解析器</b> {@code BindingViewResolver}：状态推进与状态翻译在一次调用内完成，两个端点不可能再给出不同答案。详见 §9.3（八格矩阵含原始响应体）与 §9.1（跨端口径）。不变量 INV-A/INV-B/INV-C 由 `BindingViewResolverTest`（11 例）+ 契约测试（8 格）双锁 |
| 5 | 管理员令牌不得硬编码 | **已清理** | `application*.yml` 默认值改为**空**（fail-closed），令牌只能由 `TSS_WX_ADMIN_STATIC_TOKENS` 或启动参数注入；`TssWxProperties` 注释中的示例改为占位符；冒烟脚本改读环境变量且未注入时按预期 401。新增 `AdminTokenConfigTest` 扫描生产源码/配置/文档/脚本，出现明文令牌即红 |
| 6 | 补回归测试 | **已补** | 绑定五态（未绑定/申请中/待 PC 确认/已绑定/解绑后）+ INV-4（他人 `bindRequestId` → 404）+ 筛选五类（匹配/无匹配/非法日期/动作筛选/跨页） |

### 9.1 跨端统一口径（Java / Python 双端一致，不得各说各话）

| 场景 | 统一口径 | 选型理由（按权威 20 条语义，非凭感觉） |
| --- | --- | --- |
| **未绑定身份调用 `POST /wx/v1/bindings/unbind`** | **`403 TSS_NOT_BOUND`**（不用 `409 TSS_BIND_CONFLICT`） | `TSS_BIND_CONFLICT` 权威文案是「已有冲突绑定」——本场景**根本不存在绑定**，谈不上冲突，用它是语义错配；`TSS_NOT_BOUND` 权威文案是「当前微信尚未绑定员工」，与该场景完全吻合。两个码都在权威清单内，取语义更准的那个。**Python 侧已要求同步为 403**，双端必须一致，否则 RC3 验收「错误码与 HTTP 状态一致」会留口子 |
| 解绑后申请单处置 | **移除申请单**（不标记 EXPIRED） | RC3 INV-4：`bindRequestId` 非 null 仅当会话当前持有该申请单；解绑后回到 `UNBOUND`，不得残留 `PENDING_PC_CONFIRM` 与 `/me` 打架 |
| 查询他人 / 不存在 / 已归档的 `bindRequestId` | **`404 TSS_NOT_FOUND`** | 按「不存在」处理，不泄露该申请是否曾经存在（比 403 更保守） |
| `erpUserMasked` / `entDisplayName` | **仅 `status=BOUND` 时非空** | RC3 INV-1：`status=BOUND ⟺ me.bindStatus=BOUND ⟺ erpUserMasked` 非空 |
| **申请中（待 PC 确认）的 `/me.bindStatus`** | **`PENDING`**（不是 `UNBOUND`） | **裁决 A**：① RC3 要求 `/me` 与 `/bindings/status` 对同一事实给同一答案（`PENDING_PC_CONFIRM ↔ PENDING`）；② 若仍报 `UNBOUND`，`BindStatus.PENDING` 这个枚举值将永远取不到——契约里出现死值 |
| **PC 确认后已绑定的 `bindRequestId`** | **`null`**（与「预置已绑定」同形状） | **裁决 B**：`bindRequestId != null ⟺ status ∈ {PENDING_PC_CONFIRM, EXPIRED, REJECTED}`（REJECTED 为后续修订加入，见 §9.4）。绑定成功瞬间申请单即被<b>归档移除</b>（不是留在库里靠展现层遮掩），因此「PC 确认通过」与「从来没申请过」的响应完全一致 |
| **解绑是否撤销会话** | **不撤销**（只解除绑定关系，`revokedAt` 是关系撤销时间） | 与 Python 侧 RC3 MOCK 一致；如此「二次解绑」才能落到状态检查 `403 TSS_NOT_BOUND`，而不是被 `401 TSS_SESSION_EXPIRED` 抢先短路。未绑定身份取不到任何业务数据，无泄露面 |
| **已绑定身份再发起申请** | **`409 TSS_BIND_CONFLICT`** | 权威文案即「已有冲突绑定」，与该场景完全吻合；未绑定/申请中才走正常建单 |
| **解绑的判定顺序** | **参数检查（400）先于状态检查（403）** | 否则「未绑定 + confirmation 写错」会先撞 403，掩盖真正的参数错误 |

### 9.2 一处「已说明、可保留」的双端差异（本轮已收敛为 0 处实质差异）

**申请过期时的绑定关系回落（原差异，本轮已消除）**：
- Python 侧 RC3 MOCK：申请过期时把会话的 `PENDING` 回落为 `UNBOUND`；
- **我方原实现**：绑定关系不随申请过期回落。

本轮已按同一语义改写：申请过期时 `PENDING → UNBOUND` 回落（与 Python 完全一致），
同时保留「**已是 `BOUND` / `DISABLED` 的绑定关系不因申请过期被回退**」——这一点 Python 侧同样如此
（其 `advance_bind_state` 仅在 `bs == "PENDING"` 时回落），因此双端无差异。
申请被拒（`REJECTED`，仅 DEV 夹具可达）同样回落 `UNBOUND`，避免造出
「`status=PENDING_PC_CONFIRM` 却 `bindRequestId=null`」的自相矛盾态。

**当前保留的非契约能力（需 reviewers 知悉）**：`POST /dev-support/v1/binding-fixture`
（动作 `PC_CONFIRM` / `EXPIRE` / `REJECT` / `DISABLE`）。它是 DEV 联调夹具，与 Python 侧
`ctrl_pc_confirm` 同性质，**不在 RC2 的 13 个端点内**，受 `DevOnlyCondition` 装配门禁 +
`MockTokenGate` 四重运行时门禁保护。存在理由：契约明确「PC 确认」不在小程序接口内，
没有它就**无法用真实 HTTP 复现**「PC 确认后已绑定」「申请过期」「停用」这三格。

**管理端令牌注入方式**（RC3 后必需，否则管理端全 401 属预期）：
```bash
export TSS_WX_ADMIN_STATIC_TOKENS='<你的令牌>=ADMIN|E498AU5L35J'
# 或在启动参数里给：--datahub.tss.wx.admin.static-tokens='<你的令牌>=ADMIN|E498AU5L35J'
# 冒烟脚本读的是：export TSS_ADMIN_TOKEN='<你的令牌>'
```

### 9.3 绑定状态矩阵（真实 HTTP 原始响应体，一键复现）

复现命令（服务已在 8787 运行、四重门禁已开）：

```bash
cd /e/my-project/datahub-v2-src/datahub-v2-src/tss-wx-api
BASE=http://127.0.0.1:8787 bash ./smoke-binding-matrix.sh   # 输出落在 target/binding-matrix.out
```

以下为 2026-10-31 18:00（Asia/Shanghai）在同一进程上的实测逐字输出（完整版见 `target/binding-matrix.out`）。

| # | 场景 | `GET /wx/v1/me` → `bindStatus` | `GET /wx/v1/bindings/status` → `status` | `bindRequestId` | 裁决 |
| --- | --- | --- | --- | --- | --- |
| ① | 未绑定 | `UNBOUND` | `UNBOUND` | `null` | — |
| ② | 申请中（刚 claim） | **`PENDING`** | `PENDING_PC_CONFIRM` | `br_001` | **A** |
| ③ | 待 PC 确认（`?bindRequestId=br_001`） | **`PENDING`** | `PENDING_PC_CONFIRM` | `br_001` | **A** |
| ④ | 已绑定（预置，从未申请） | `BOUND` | `BOUND` | `null` | — |
| ⑤ | 已绑定（PC 确认后） | `BOUND` | `BOUND` | **`null`** | **B** |
| ⑥ | 解绑后 | `UNBOUND` | `UNBOUND` | `null` | — |
| ⑦ | 停用 | `DISABLED` | `DISABLED` | `null` | — |
| ⑧ | 申请过期 | `UNBOUND` | `EXPIRED` | `br_003` | **A** |

原始响应体（逐格）：

```jsonc
// ① 未绑定
GET /wx/v1/me              → {"bindStatus":"UNBOUND","userId":null,"entId":null,"bindingVersion":null,"enterpriseSwitchEnabled":false}
GET /wx/v1/bindings/status → {"bindRequestId":null,"status":"UNBOUND","erpUserMasked":null,"entDisplayName":null,"checkedAt":"..."}

// ② 申请中（先 POST /wx/v1/bindings/claim ⇒ {"bindRequestId":"br_001","status":"PENDING_PC_CONFIRM","expiresAt":"..."}）
GET /wx/v1/me              → {"bindStatus":"PENDING","userId":null,"entId":null,"bindingVersion":null,"enterpriseSwitchEnabled":false}
GET /wx/v1/bindings/status → {"bindRequestId":"br_001","status":"PENDING_PC_CONFIRM","erpUserMasked":null,"entDisplayName":null,"checkedAt":"..."}

// ③ 待 PC 确认（?bindRequestId=br_001）—— 与 ② 同一答案
GET /wx/v1/me              → {"bindStatus":"PENDING", ...}
GET /wx/v1/bindings/status → {"bindRequestId":"br_001","status":"PENDING_PC_CONFIRM", ...}

// ④ 已绑定（预置）
GET /wx/v1/me              → {"bindStatus":"BOUND","userId":"U7BOUND0001","entId":"E903BOUND","bindingVersion":1,...}
GET /wx/v1/bindings/status → {"bindRequestId":null,"status":"BOUND","erpUserMasked":"U7****001","entDisplayName":"DEV Mock 企业 E903BOUND","checkedAt":"..."}

// ⑤ 已绑定（PC 确认后）：claim ⇒ br_002；DEV 夹具 PC_CONFIRM ⇒ {"action":"PC_CONFIRM","bindRequestId":null,"bindStatus":"BOUND","status":"BOUND","requestArchived":true}
GET /wx/v1/me              → {"bindStatus":"BOUND","userId":"U6PCCONF001","entId":"E904PCCONF","bindingVersion":2,...}
GET /wx/v1/bindings/status → {"bindRequestId":null,"status":"BOUND","erpUserMasked":"U6****001","entDisplayName":"DEV Mock 企业 E904PCCONF","checkedAt":"..."}

// ⑥ 解绑后（unbind ⇒ {"revokedAt":"...","bindStatus":"UNBOUND"}）
GET /wx/v1/me              → {"bindStatus":"UNBOUND","userId":null,"entId":null,"bindingVersion":null,...}
GET /wx/v1/bindings/status → {"bindRequestId":null,"status":"UNBOUND","erpUserMasked":null,"entDisplayName":null,"checkedAt":"..."}
POST /wx/v1/bindings/unbind（二次） → {"code":"TSS_NOT_BOUND","message":"微信身份未完成绑定，无法访问业务数据","data":null,...}  HTTP 403

// ⑦ 停用
GET /wx/v1/me              → {"bindStatus":"DISABLED","userId":null,"entId":null,"bindingVersion":null,...}
GET /wx/v1/bindings/status → {"bindRequestId":null,"status":"DISABLED","erpUserMasked":null,"entDisplayName":null,"checkedAt":"..."}

// ⑧ 申请过期：claim ⇒ br_003；DEV 夹具 EXPIRE ⇒ {"action":"EXPIRE","bindRequestId":"br_003","bindStatus":"UNBOUND","status":"EXPIRED","requestArchived":false}
GET /wx/v1/me              → {"bindStatus":"UNBOUND","userId":null,"entId":null,"bindingVersion":null,...}
GET /wx/v1/bindings/status → {"bindRequestId":"br_003","status":"EXPIRED","erpUserMasked":null,"entDisplayName":null,"checkedAt":"..."}
```

**裁决 C：解绑错误码矩阵**（同为实测原始响应体）

| 场景 | 实测 `code` | HTTP |
| --- | --- | --- |
| 未绑定身份解绑 | `TSS_NOT_BOUND` | 403 |
| 申请中（`PENDING`）身份解绑 | `TSS_NOT_BOUND` | 403 |
| 停用身份解绑 | `TSS_BINDING_DISABLED` | 403 |
| 二次解绑（已解绑，会话仍有效） | `TSS_NOT_BOUND` | 403 |
| `confirmation ≠ UNBIND`（且身份未绑定） | `TSS_INVALID_ARGUMENT` | 400（参数检查先于状态检查，实测如此） |
| 他人 / 不存在 / 已归档的 `bindRequestId` | `TSS_NOT_FOUND` | 404（该格落在 **API-10**：RC2 的 `UnbindRequest` 只有 `confirmation` / `clientNonce` 两个字段，`additionalProperties:false`，**没有** `bindRequestId`） |

### 9.4 本轮并发修订：REJECTED 保留申请单号（需双端确认）

本轮我按裁决 B 落地时用的是 `bindRequestId != null ⟺ status ∈ {PENDING_PC_CONFIRM, EXPIRED}`，
`REJECTED` 归 null 并当场归档。模块内随后出现一处**修订**（`BindingView.keepsRequestId` 加入 `REJECTED`，
并以 `Rc3FixesIT#rejected_is_observable_and_stable` + `BindingViewResolverTest#rejected_request` 锁死），
理由写在代码注释里：**若 REJECTED 归 null，用户按下「拒绝」后查 status 看到的是 `UNBOUND + null`，
跟没按过一样，无从得知自己被拒；且 `translate()` 的 REJECTED 分支永远走不到，等于契约里留一个死值。**

当前构建以修订后口径为准（`Tests run: 109` 全绿），形态为 `me=UNBOUND + status=REJECTED + bindRequestId 非空`，
与 INV-A（REJECTED↔UNBOUND）自洽。

⚠️ **需 team-lead 拍板的双端差异**：Python 侧 RC3 MOCK 的
`KEEP_REQUEST_STATUSES = ("PENDING_PC_CONFIRM", "EXPIRED")` **仍不含 REJECTED**，
且其 MOCK 没有 reject 控制面（该状态在其侧不可达）。因此这一格目前**不会被对端观测到**，
但严格讲双端口径已经不一致——要么 Python 同步把 REJECTED 加入保留集合，要么双方共同确认
「REJECTED 在 V1.0 不可达、无需对齐」。**这不是我能单方面定的，故如实上报。**
