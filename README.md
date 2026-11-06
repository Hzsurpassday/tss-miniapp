# 时空智审 V1.0（RC3）交付仓库

本仓库包含「时空智审」V1.0（RC3）的两套工程：

| 目录 | 工程 | 说明 |
|------|------|------|
| `miniapp/` | 微信小程序（原生 + TypeScript） | 6 个页面 + 4 个组件，封装 11 个 `/wx/v1` 接口；V1.0 为**只读**版本，无审批写回能力。 |
| `tss-wx-api/` | Java 接口后端（Spring Boot） | RC3 接口程序，含小程序业务 API、fail-closed 鉴权、契约 Mock 与测试。 |

## 安全说明

- 本仓库**不含任何硬编码密钥/凭据**。后端所有密钥（微信 AppSecret、游标密钥等）均通过环境变量注入：
  `tss-wx-api/src/main/resources/application.yml` 中使用 `${TSS_WX_WECHAT_SECRET:}` / `${TSS_WX_CURSOR_SECRET:}`，默认空值，运行时由部署环境提供。
- 小程序前端不持有任何密钥，AppSecret 仅存在于服务端。

## 目录速览

### `miniapp/`
微信开发者工具导入 `miniapp/` 目录即可。详见 `miniapp/README.md`（含启动、base URL 切换、真机 HTTPS 限制等）。

### `tss-wx-api/`
Spring Boot 工程，详见 `tss-wx-api/README-启动与验证.md`。
- 构建：`mvn package`
- 测试：`mvn test`（含契约测试 `WxApiContractIT` / `Rc3FixesIT` 与 fail-closed 用例）
