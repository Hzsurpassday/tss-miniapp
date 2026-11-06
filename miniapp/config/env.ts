/**
 * 环境配置：API base URL 切换。
 *
 * 决议要求 `TSS_API_BASE_URL` 可配。小程序无 process.env，因此提供三层可配来源（优先级从高到低）：
 *   1. 构建期注入：`scripts/gen-env.mjs` 读取环境变量 `TSS_API_BASE_URL` 生成 `config/generated.env.ts`；
 *   2. 运行期覆盖：storage 键 `tss:baseUrlOverride`（个人中心 → 开发者选项，仅 develop/trial 可见可改）；
 *   3. 环境预设：按 `wx.getAccountInfoSync().miniProgram.envVersion` 自动选择。
 *
 * 纪律（决议 B2）：
 *   - 默认指向远程 Mock `http://39.106.179.158:9000`（HTTP + IP，仅供开发者工具联调）。
 *   - 真机 / 体验版 / 生产（envVersion = trial / release / gray）**强制 HTTPS**，
 *     且必须是已备案并已配置为微信小程序合法 request 域名的地址（如 https://smallsp.bestsoft.vip）。
 *     非 HTTPS 的覆盖值在 release/trial 下会被忽略。
 */

import { BASE_URL_FROM_ENV } from './generated.env';

/** storage 覆盖键。 */
const STORAGE_KEY_BASE_URL = 'tss:baseUrlOverride';

/** base URL 预设。 */
export interface BaseUrlPreset {
  id: string;
  label: string;
  url: string;
  note: string;
  https: boolean;
}

/**
 * base URL 预设。**顺序即优先级**：索引 0 为开发态默认。
 *
 * 为什么开发态默认指向本机 8787：本机 `tss-wx-api`（dev profile）是唯一能稳定签发
 * **已绑定** Mock 会话、从而让六个页面看到真实内容（待办/详情/历史/消息）的后端。
 * 远程 `http://39.106.179.158:9000` 作为第二可选：它符合 RC2 包络，默认只给**未绑定**会话
 * （但用约定的 `MOCK_BOUND` 作为登录 code 也能取到已绑定会话，见 README 能力边界表）。
 */
export const BASE_URL_PRESETS: BaseUrlPreset[] = [
  {
    id: 'LOCAL_DEV',
    label: '本机后端 8787（开发默认）',
    url: 'http://127.0.0.1:8787',
    note: '需先启动 tss-wx-api（dev，--server.port=8787）；真机不可依赖 localhost。',
    https: false
  },
  {
    id: 'REMOTE_MOCK',
    label: '远程 Mock 9000',
    url: 'http://39.106.179.158:9000',
    note: 'HTTP + IP：仅开发者工具「不校验合法域名」下可用，真机不可用；默认未绑定会话。',
    https: false
  },
  {
    id: 'APPROVED_HTTPS',
    label: '已备案 HTTPS 域名',
    url: 'https://smallsp.bestsoft.vip',
    note: '真机/体验版/生产唯一允许形态；须先在小程序后台登记为合法 request 域名。',
    https: true
  }
];

/** 已备案 HTTPS 预设的索引（安全环境回退目标）。 */
const HTTPS_PRESET_INDEX = 2;

/** 默认（兜底）base URL。 */
export const DEFAULT_BASE_URL: string = BASE_URL_PRESETS[0].url;

/** 判断是否为 HTTPS 地址。 */
export function isHttpsUrl(url: string): boolean {
  return typeof url === 'string' && url.indexOf('https://') === 0;
}

/** 读取小程序运行环境版本，失败时回退 develop。 */
export function getEnvVersion(): 'develop' | 'trial' | 'release' | 'gray' {
  try {
    const info = wx.getAccountInfoSync();
    const v = info && info.miniProgram && info.miniProgram.envVersion;
    if (v === 'develop' || v === 'trial' || v === 'release' || v === 'gray') {
      return v;
    }
  } catch (e) {
    // 部分基础库/场景不支持，回退开发态。
  }
  return 'develop';
}

/** 是否为「必须 HTTPS」的环境（体验版/正式版/灰度）。 */
export function isSecureRequiredEnv(): boolean {
  return getEnvVersion() !== 'develop';
}

/** 归一化 URL：去掉结尾斜杠，避免拼接出双斜杠。 */
function normalizeUrl(url: string): string {
  let u = String(url || '').trim();
  while (u.length > 1 && u.charAt(u.length - 1) === '/') {
    u = u.substring(0, u.length - 1);
  }
  return u;
}

/** 读取运行期覆盖值（可能为空）。 */
function readOverride(): string {
  try {
    const v = wx.getStorageSync(STORAGE_KEY_BASE_URL);
    return typeof v === 'string' ? normalizeUrl(v) : '';
  } catch (e) {
    return '';
  }
}

/** 运行期覆盖：仅 develop 允许设置非 HTTPS；trial/release/gray 只允许 HTTPS。 */
export function setBaseUrlOverride(url: string): { ok: boolean; message: string } {
  const next = normalizeUrl(url);
  if (!next) {
    return { ok: false, message: 'base URL 不能为空' };
  }
  if (isSecureRequiredEnv() && !isHttpsUrl(next)) {
    return { ok: false, message: '体验版/正式版只允许已备案的 HTTPS 地址' };
  }
  wx.setStorageSync(STORAGE_KEY_BASE_URL, next);
  return { ok: true, message: '已切换 base URL，重启页面后生效' };
}

/** 清除运行期覆盖。 */
export function clearBaseUrlOverride(): void {
  try {
    wx.removeStorageSync(STORAGE_KEY_BASE_URL);
  } catch (e) {
    // 忽略：无 storage 权限时不影响主流程。
  }
}

/**
 * 解析当前生效的 base URL。
 * 优先级：构建期 TSS_API_BASE_URL > 运行期 storage 覆盖 > 环境预设。
 */
export function resolveBaseUrl(): string {
  const fromEnv = normalizeUrl(BASE_URL_FROM_ENV);
  if (fromEnv) {
    return fromEnv;
  }
  const override = readOverride();
  if (override) {
    if (isSecureRequiredEnv() && !isHttpsUrl(override)) {
      return BASE_URL_PRESETS[HTTPS_PRESET_INDEX].url;
    }
    return override;
  }
  if (isSecureRequiredEnv()) {
    return BASE_URL_PRESETS[HTTPS_PRESET_INDEX].url;
  }
  return DEFAULT_BASE_URL;
}

/** 当前 base URL 是否 HTTPS。用于 UI 提示「真机不可用」风险。 */
export function isCurrentBaseUrlHttps(): boolean {
  return isHttpsUrl(resolveBaseUrl());
}
