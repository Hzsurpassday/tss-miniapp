# RC3 Python Mock 服务 · 部署操作单（安全修复版）

> 版本：1.0.0-rc3 + 控制面安全修复（2026-11-07）
> **本服务是契约 Mock / 联调服务，不含真实 ERP 通道，禁止作为生产系统使用，禁止接入生产数据。**

## 0. 本次修复了什么

| 层 | 修复 | 修复前风险 |
|---|---|---|
| B | `/__mock/*` 6 个端点强制 `POST` only | `GET /__mock/reset` 匿名即可触发，浏览器/链接预取可被动清场 |
| C | 控制面鉴权：默认仅 loopback 可访问；设 `TSS_MOCK_CTRL_TOKEN` 后须带 `X-Mock-Ctrl-Token`；拒绝返回 404 隐藏端点 | 公网匿名可任意改状态，可污染 `freshness` 使验收结论失真 |
| D | `ctrl_reset()` 保留 `STATE["rate"]` 与 `nonces` | 循环 reset 可自我解除限流，进而刷业务端点 |
| A | `nginx-protect.conf.example` 提供路径白名单 | 反代场景 loopback 判定失效，需部署层主防线 |

`python verify.py` 206/206 通过；13 条契约端点、错误码字典（20 条）零改动。

---

## 1. 已完成的实际部署（2026-11-07，线上 `39.106.179.158:9000`）

### 实际拓扑（与常见假设不同，务必先看清）

| 项 | 实际值 |
|---|---|
| 系统 | Ubuntu 24.04.5 LTS |
| 服务形态 | **Docker 容器** `tss-wx-mock-rc3`，镜像 `tss-contract-mock:1.0.0-rc3`，restart=`unless-stopped` |
| 端口 | `docker-proxy` 绑定 `0.0.0.0:9000` → 容器 `172.17.0.2:9000` |
| 容器内进程 | `python3 -u server.py`（非 root 用户 `tss`） |
| 部署源目录 | `/root/tss-wx-mock-rc3/`（含 `Dockerfile.rc3`） |
| 反代 | **OpenResty（1Panel 容器）**，生效配置 `/opt/1panel/apps/openresty/openresty/conf/http.d/tss-mock.conf`，仅作用于 `tss-mock.bestsoft.vip:80` |

> ⚠️ **关键**：反代配置里虽有 `location ~ ^/__mock/ { allow 127.0.0.1; deny all; }`，但 9000 端口由 `docker-proxy` 直接对公网开放，**完全绕过反代**。修复前实测公网 `GET /__mock/reset` = 200 就是这个原因。因此**代码层门禁才是本次止血主力**，不能只依赖 Nginx。

### 已执行的操作（最小侵入 + 可回滚）

1. 备份原部署：`/root/tss-mock-backup-20261107/`（原 `server.py` 75784 字节 / sha256 `993fc6b7…`、`README.md`、`verify.py`、`Dockerfile.rc3`、`container-inspect.json`）。
2. 镜像回滚点：`docker tag tss-contract-mock:1.0.0-rc3 tss-contract-mock:1.0.0-rc3-pre-security-fix`（镜像 ID `9f2deb1d5adb`）。
3. 完整修复包同步到 `/root/tss-wx-mock-rc3/`：`server.py` / `verify.py` / `README.md` / `nginx-protect.conf.example` / `DEPLOY.md` / `Dockerfile` / `docker-compose.yml`。
4. `docker cp` 修复版 `server.py` 进容器（sha256 `65e8f976…`，80844 字节），确认运行时用户可读。
5. `docker commit` 固化为镜像 `tss-contract-mock:1.0.0-rc3`（新 ID `25ecc5894e76`），保证重启/重建后修复仍在。
6. `docker restart tss-wx-mock-rc3`，启动日志出现 `[CTRL] 控制面门禁：未配置 TSS_MOCK_CTRL_TOKEN，/__mock/* 仅 loopback…`，令牌明文零回显。

### 公网验收实测（从外部打真实公网 IP，全部 PASS）

| 请求 | 期望 | 实测 |
|---|---|---|
| `GET /healthz` | 200 | **200** |
| `GET /__mock/reset` | 404 | **404** |
| `POST /__mock/reset` | 404 | **404** |
| `PUT /__mock/freshness` | 404 | **404** |
| `POST /wx/v1/auth/login` | 200 | **200** |
| `GET /wx/v1/me`（无鉴权） | 401 | **401** |

`/__mock/reset` 响应体为 `TSS_NOT_FOUND`；`healthz` 显示 `rcVersion=1.0.0-rc3, dataMode=MOCK, writeBackDisabled=true, erpReadChannelWired=false`。

### ⚠️ Docker 网络特有行为（运维必读）

经 `127.0.0.1:9101` 沙盒容器实测：

| 访问来源 | 容器看到的 client_address | 控制面结果 |
|---|---|---|
| 宿主经 `docker-proxy`（等同公网/运维本机走 9000） | 网关 `172.17.0.1`，**非 loopback** | **404**（拒绝） |
| 容器内部 `127.0.0.1:9000` | 真 loopback | **200**（放行） |
| 契约端点（任意来源） | — | 不受影响，正常 200 |

结论：部署后**控制面对宿主机和公网一律关闭**。若运维需要远程跑 `verify.py` 或改夹具状态，必须显式注入 `TSS_MOCK_CTRL_TOKEN` 并在请求带 `X-Mock-Ctrl-Token` 头（沙盒已验证：无头/错头 → 404，正确头 → 200，正确头但 GET → 仍 404）。

> 注意：`verify.py` 的 `call()` 不带该头。若生产环境注入了控制面令牌，远程自检会被 404 拦住；本地自检（不注入该变量、走 loopback）不受影响。**默认不注入该变量**是推荐配置。

### 同机反代残留风险：线上实测闭环（2026-11-07 22:10，主理人亲跑）

QA 提出的首要残留风险是：若 nginx 与 Mock 同机且 `proxy_pass http://127.0.0.1:9000`，后端
`client_address` 恒为 `127.0.0.1`，默认模式下控制面会对公网敞开。**线上拓扑确实命中该形态，
但实测未失效**，证据如下（全部为只读 / 零副作用探测）：

| 检查 | 实测结果 |
|---|---|
| OpenResty 网络模式 | `host`（`docker inspect -f '{{.HostConfig.NetworkMode}}'`） |
| upstream | `server 127.0.0.1:9000`（`tss-mock.conf:13-16`） |
| `/__mock/` nginx 规则 | `location ~ ^/__mock/ { allow 127.0.0.1; deny all; }`（:29-34）；`nginx -t` **通过** |
| 容器网络 | bridge，网关 `172.17.0.1`，容器 `172.17.0.2`；`0.0.0.0:9000->9000/tcp` |
| 宿主 `POST 127.0.0.1:9000/__mock/freshness`（经 docker-proxy） | **404 `TSS_NOT_FOUND`** |
| 宿主带 Host 头 `POST 127.0.0.1/__mock/freshness`（经 80 反代，正是同机反代路径） | **404 `TSS_NOT_FOUND`** |
| 容器内部 `POST 127.0.0.1:9000/__mock/freshness?value=FRESH` | **200 `SUCCESS`**（真 loopback 按设计放行） |
| 公网 `GET` / `POST` `39.106.179.158:9000/__mock/reset` | **404 / 404**；`GET /healthz` **200** |
| 宿主 GET / PUT / HEAD `127.0.0.1:9000/__mock/reset` | 404 / 404 / 501（HEAD 见 P3-1） |
| 探测副作用 | `POST` 把 freshness 设回其当前值 `FRESH`；探测后 `TSS_MOCK_FRESHNESS=FRESH`、`/healthz` 仍 `freshness=FRESH`，**状态零变化** |
| 线上程序哈希 | 容器内 `/app/server.py` 与宿主 `/root/tss-wx-mock-rc3/server.py` 均 `65e8f976…`，与本地一致 |

**结论**：请求经 `docker-proxy` 后容器看到的是网关 `172.17.0.1` 而非 loopback，代码层门禁未失效；
nginx `deny all` 另有一层兜底。**该残留风险在本部署形态下已闭环。**

**仍保留的约束**：换到「nginx 与 Mock 同一网络命名空间、直连后端进程」的部署形态时，
`client_address` 会变成真 loopback，必须重新验收并**强制启用** `nginx-protect.conf.example`
白名单。另注：`tss-mock.bestsoft.vip` 在服务器上 `getent hosts` 无解析记录、外部连通性探测为
`000`，当前联调走的是公网 IP:9000 直连，域名反代路径实际未承载流量，**不可视为已验证的公网防线**。

### 已知偏差与运维提示（P3，判文档化、不改已部署代码）

| 项 | 说明 | 处置 |
|---|---|---|
| P3-1 `HEAD /__mock/*` → 501 | 未实现 `do_HEAD`，由 `BaseHTTPRequestHandler` 直接回 501，不是统一信封 404。实测不进 `_handle()`、不触发控制面、会话仍有效 ⇒ fail-closed，非绕过 | 冻结 `server.py`，口径在 README 标注；若要求字面严格可补一行 `do_HEAD` |
| P3-2 UTF-8 控制面令牌不可用 | 请求头按 latin-1 解码，中文 / emoji 令牌无法匹配，表现为 404 fail-closed、不崩溃 | 运维统一用 **ASCII** `TSS_MOCK_CTRL_TOKEN` |
| `[ADMIN]` 临时令牌明文 | 未设 `TSS_ADMIN_TOKEN` 时启动日志打印临时管理员令牌明文（既有设计、README 已说明）；「令牌明文零回显」约束只针对**控制面令牌** | 显式注入 `TSS_ADMIN_TOKEN`，避免日志留存明文 |
| compose 文件与在跑容器不一致 | 服务器 `/root/tss-wx-mock-rc3/docker-compose.yml` 为 `container_name: tss-wx-mock`、`build: .`，且 `TSS_MOCK_SECRET` 是 `${...:?}` 必填；在跑容器名为 `tss-wx-mock-rc3` | **不要在该目录直接 `docker compose up -d`**（9000 端口冲突，且缺 `TSS_MOCK_SECRET` 直接失败）；继续用现有容器 / 镜像回滚方式 |
| 镜像绑定 | 在跑容器创建自旧镜像 ID `9f2deb1d5adb`（已 retag 为 `1.0.0-rc3-pre-security-fix`），修复版是 `docker cp` 进运行时的；`tss-contract-mock:1.0.0-rc3` 已固化指向新镜像 `25ecc5894e76` | 重建 / 重启用 `tss-contract-mock:1.0.0-rc3` 标签即可拿到修复版；运行时 cp、`commit` 镜像、Dockerfile 重建三条路径均已是修复版 |

### 回滚方法

```bash
# 代码回滚（保留现有容器配置）
docker cp /root/tss-mock-backup-20261107/server.py tss-wx-mock-rc3:/app/server.py
docker restart tss-wx-mock-rc3

# 或整镜像回滚
docker stop tss-wx-mock-rc3 && docker rm tss-wx-mock-rc3
docker run -d --name tss-wx-mock-rc3 --restart unless-stopped -p 9000:9000 \
  --env-file <从 container-inspect.json 恢复的环境变量> \
  tss-contract-mock:1.0.0-rc3-pre-security-fix
```

### 遗留风险（需老板/运维决策，本次未擅自改动）

1. **RC1 遗留硬编码弱管理员令牌**：容器环境变量 `TSS_ADMIN_TOKEN` 仍是 RC1 时期的可猜测固定串（此处不回显明文），与 RC3「移除硬编码永久密钥」的安全口径冲突。API-12/13 管理端用它鉴权。**建议尽快轮换为随机强令牌并由部署环境注入**，但轮换会使现有联调方持有的旧令牌失效，需协调后执行。
2. **9000 端口仍绑定 `0.0.0.0`**：代码层门禁已挡住控制面，但端口本身仍直接暴露公网。更彻底的做法是收紧到 `127.0.0.1:9000`、只让本机 OpenResty 反代对外——但这会让现有直连 `39.106.179.158:9000` 的联调方失效，需先确认联调方使用的地址。
3. 服务器上另有 RC1/RC2 遗留目录 `/root/tss-wx-mock/`（含 `.secret`、`.admintoken` 文件）与已 Exited 的 `tss-wx-mock` 容器，建议清理。
4. 生产数据测试所需外部依赖（真实 ERP 只读通道、微信 appid/secret、订阅模板 ID、管理端身份认证）仍未闭环，**本次部署不含、也不宣称支持生产数据**。

---

## 2. 全新部署（其他机器 / 从零搭建）

### 方式 A：Docker（推荐）

```bash
cd tss-mock
export TSS_MOCK_SECRET="$(openssl rand -base64 32)"
export TSS_ADMIN_TOKEN="$(openssl rand -base64 32)"
# 可选：如需从非本机访问控制面才设置；默认不设，控制面对外关闭
# export TSS_MOCK_CTRL_TOKEN="$(openssl rand -base64 32)"

docker compose up -d --build
docker logs tss-wx-mock 2>&1 | grep -E '\[READY\]|\[CTRL\]|\[ADMIN\]'
curl -s http://127.0.0.1:9000/healthz
```

`docker-compose.yml` 里 `TSS_MOCK_SECRET` 用 `:?` 强制显式注入，未设置会拒绝启动（fail-closed）。

### 方式 B：裸跑

```bash
export TSS_MOCK_SECRET="$(openssl rand -base64 32)"
export TSS_ADMIN_TOKEN="$(openssl rand -base64 32)"
export TSS_MOCK_PORT=9000
python3 -u server.py
```

裸跑时监听 `127.0.0.1` 的控制面请求属于真 loopback，会正常放行（与 Docker 端口映射行为不同）。

### 方式 C：反代场景（公网必配）

把 `nginx-protect.conf.example` 合并进站点配置后 `nginx -t && nginx -s reload`。要点：

- 只放行 13 条契约路径 + `/healthz`；`location ^~ /__mock/` 返回 404；兜底 `location /` 返回 404。
- **反代后后端看到的 `client_address` 恒为 `127.0.0.1` 或 docker 网关**，代码层 loopback 判定可能失效或过严，此时路径白名单是必要防线。
- **切勿只靠反代**：若容器端口仍绑 `0.0.0.0`，公网可绕过反代直连（本次线上就是这个情况）。务必同时收紧端口绑定或依赖代码层门禁。

### 部署后验收清单

```bash
B=http://<host>:9000
curl -s -o /dev/null -w "healthz=%{http_code}\n" "$B/healthz"                 # 期望 200
curl -s -o /dev/null -w "GET_reset=%{http_code}\n" "$B/__mock/reset"            # 期望 404
curl -s -o /dev/null -w "POST_reset=%{http_code}\n" -X POST "$B/__mock/reset"  # 期望 404
curl -s -o /dev/null -w "login=%{http_code}\n" -X POST -H 'Content-Type: application/json' \
  -d '{"code":"MOCK_BOUND"}' "$B/wx/v1/auth/login"                             # 期望 200
```

若 `GET_reset`/`POST_reset` 返回 200，说明**跑的还是旧进程**——检查残留进程占用端口，并确认镜像/容器内的 `server.py` 是 80844 字节、`CTRL_TOKEN_HEADER` 标记存在。

## 3. 红线

- `TSS_MOCK_SECRET` / `TSS_ADMIN_TOKEN` / `TSS_MOCK_CTRL_TOKEN` **禁止写入任何文件、代码仓库、交涉文档**；只由部署环境注入，日志不回显明文。
- 本服务 `writeBackDisabled: true`、`erpReadChannelWired: false`，`dataMode=ERP` 时业务端点一律 503 fail-closed。**不得把 Mock 成功宣称为 ERP 成功，不得接入生产数据。**
