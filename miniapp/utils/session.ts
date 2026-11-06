/**
 * 会话管理。
 *
 * 纪律：
 *   - sessionToken 由后端 code2Session 后签发，前端只做存储与透传，不解析、不自造身份。
 *   - 前端绝不提交 userId / entId 作为授权依据；entId 由服务端绑定推导（V1.0 单当前企业）。
 *   - **Mock 会话码**是与微信真实登录完全隔离的开发期通道：
 *     默认禁用、storage 开关存储、且 `envVersion !== 'develop'` 时硬禁用（fail-closed）。
 */

import { API_PATH } from '../types/api';
import type { LoginData, BindStatus } from '../types/api';
import { resolveBaseUrl, isSecureRequiredEnv } from '../config/env';
import { rawRequest } from './http';
import { parseEnvelope, isSuccessCode } from './envelope';
import { LOCAL_ERROR, userMessageOf } from './errors';
import * as storage from './storage';

const KEY_TOKEN = 'session.token';
const KEY_EXPIRES_AT = 'session.expiresAt';
const KEY_BIND_STATUS = 'session.bindStatus';
const KEY_MOCK_ENABLED = 'dev.mockSessionEnabled';
const KEY_MOCK_CODE = 'dev.mockSessionCode';
const KEY_DEV_TOKEN_ENABLED = 'dev.devMockTokenEnabled';
const KEY_DEV_TOKEN_ENT = 'dev.devMockTokenEntId';
const KEY_DEV_TOKEN_USER = 'dev.devMockTokenUserId';

/**
 * DEV 专用 Mock 令牌颁发端点。
 * **不属于 RC2 的 13 个端点**（后端刻意放在 /dev-support/** 而非 /wx/v1/**），
 * 响应也不使用 RC2 包络。仅在本机 dev profile + 四重门禁通过时可用（fail-closed）。
 */
const DEV_MOCK_TOKEN_PATH = '/dev-support/v1/mock-token';

/** DEV Mock 令牌默认身份（与本机夹具一致）。 */
export const DEFAULT_DEV_TOKEN_ENT_ID = 'E498AU5L35J';
export const DEFAULT_DEV_TOKEN_USER_ID = 'U4FXG07VRDI';

/** 默认 Mock 会话码（仅开发期，用于向 Mock 服务换取 BOUND 会话）。 */
export const DEFAULT_MOCK_SESSION_CODE = 'MOCK_BOUND';

/** 提前多少毫秒认为会话过期（避免边界上发出必然失败的请求）。 */
const EXPIRE_BUFFER_MS = 30 * 1000;

interface SessionSnapshot {
  token: string;
  expiresAt: number;
  bindStatus: BindStatus | '';
}

let memorySession: SessionSnapshot | null = null;
let inFlight: Promise<string> | null = null;

function readFromStorage(): SessionSnapshot | null {
  const token = storage.get<string>(KEY_TOKEN, '');
  if (!token) {
    return null;
  }
  const expiresAt = storage.get<number>(KEY_EXPIRES_AT, 0);
  const bindStatus = storage.get<BindStatus | ''>(KEY_BIND_STATUS, '');
  return { token: token, expiresAt: expiresAt, bindStatus: bindStatus };
}

function writeToStorage(snapshot: SessionSnapshot): void {
  storage.set(KEY_TOKEN, snapshot.token);
  storage.set(KEY_EXPIRES_AT, snapshot.expiresAt);
  storage.set(KEY_BIND_STATUS, snapshot.bindStatus);
}

function clearStorage(): void {
  storage.remove(KEY_TOKEN);
  storage.remove(KEY_EXPIRES_AT);
  storage.remove(KEY_BIND_STATUS);
}

/** 应用启动时恢复本地会话（不触发网络请求）。 */
export function restoreSession(): void {
  memorySession = readFromStorage();
}

/** 同步读取当前令牌（可能为空）。 */
export function getTokenSync(): string {
  if (memorySession && memorySession.token) {
    return memorySession.token;
  }
  const restored = readFromStorage();
  if (restored) {
    memorySession = restored;
    return restored.token;
  }
  return '';
}

/** 当前会话是否仍有效（不触发网络）。 */
export function hasValidSession(): boolean {
  const s = memorySession || readFromStorage();
  if (!s || !s.token) {
    return false;
  }
  return s.expiresAt > Date.now() + EXPIRE_BUFFER_MS;
}

/** 最近一次登录得到的绑定状态（可能为空）。 */
export function getCachedBindStatus(): BindStatus | '' {
  const s = memorySession || readFromStorage();
  return s ? s.bindStatus : '';
}

/** Mock 会话通道是否可用（仅 develop）。 */
export function isMockSessionAvailable(): boolean {
  return !isSecureRequiredEnv();
}

/** Mock 会话开关（默认关闭）。 */
export function isMockSessionEnabled(): boolean {
  if (!isMockSessionAvailable()) {
    return false;
  }
  return storage.get<boolean>(KEY_MOCK_ENABLED, false) === true;
}

export function setMockSessionEnabled(enabled: boolean): void {
  if (!isMockSessionAvailable()) {
    return;
  }
  storage.set(KEY_MOCK_ENABLED, !!enabled);
}

export function getMockSessionCode(): string {
  return storage.get<string>(KEY_MOCK_CODE, DEFAULT_MOCK_SESSION_CODE) || DEFAULT_MOCK_SESSION_CODE;
}

export function setMockSessionCode(code: string): void {
  storage.set(KEY_MOCK_CODE, code);
}

/** DEV Mock 令牌通道是否可用（仅 develop）。 */
export function isDevMockTokenAvailable(): boolean {
  return !isSecureRequiredEnv();
}

export function isDevMockTokenEnabled(): boolean {
  if (!isDevMockTokenAvailable()) {
    return false;
  }
  return storage.get<boolean>(KEY_DEV_TOKEN_ENABLED, false) === true;
}

export function setDevMockTokenEnabled(enabled: boolean): void {
  if (!isDevMockTokenAvailable()) {
    return;
  }
  storage.set(KEY_DEV_TOKEN_ENABLED, !!enabled);
}

export function getDevMockTokenEntId(): string {
  return storage.get<string>(KEY_DEV_TOKEN_ENT, DEFAULT_DEV_TOKEN_ENT_ID) || DEFAULT_DEV_TOKEN_ENT_ID;
}

export function setDevMockTokenEntId(entId: string): void {
  storage.set(KEY_DEV_TOKEN_ENT, entId);
}

export function getDevMockTokenUserId(): string {
  return storage.get<string>(KEY_DEV_TOKEN_USER, DEFAULT_DEV_TOKEN_USER_ID) || DEFAULT_DEV_TOKEN_USER_ID;
}

export function setDevMockTokenUserId(userId: string): void {
  storage.set(KEY_DEV_TOKEN_USER, userId);
}

/** DEV Mock 令牌响应（非 RC2 包络，后端刻意保留 devOnly / warning 显式标记）。 */
interface DevMockTokenResponse {
  sessionToken: string;
  expiresIn: number;
  bindStatus: BindStatus;
  entId: string;
  userId: string;
  devOnly: boolean;
  warning: string;
}

/**
 * 通过本机 DEV 支撑端点换取已绑定 Mock 会话。
 * 仅用于联调本机 tss-wx-api：其 API-01 需要真实 appid/secret，未配置时会 fail-closed。
 */
export function devMockTokenLogin(entId: string, userId: string): Promise<LoginData> {
  const url = resolveBaseUrl() + DEV_MOCK_TOKEN_PATH;
  return rawRequest({
    url: url,
    method: 'POST',
    header: { 'content-type': 'application/json' },
    data: { entId: entId, userId: userId, bindStatus: 'BOUND' }
  }).then((res) => {
    const body = res.data as DevMockTokenResponse | null;
    if (!body || !body.sessionToken || body.devOnly !== true) {
      throw {
        isTssClientError: true,
        code: LOCAL_ERROR.PARSE,
        message: 'DEV Mock 令牌端点未返回可用令牌（可能未通过四重门禁或不是 dev profile）',
        traceId: '',
        httpStatus: res.statusCode,
        path: DEV_MOCK_TOKEN_PATH
      };
    }
    console.warn('[tss][DEV-ONLY] 已通过 ' + DEV_MOCK_TOKEN_PATH + ' 取得 Mock 会话：' + String(body.warning || ''));
    return {
      sessionToken: body.sessionToken,
      expiresIn: body.expiresIn,
      bindStatus: body.bindStatus,
      needBinding: body.bindStatus !== 'BOUND'
    } as LoginData;
  });
}

/** 直接调用 API-01，避免与 request.ts 形成循环依赖。 */
function callLogin(code: string): Promise<LoginData> {
  const url = resolveBaseUrl() + API_PATH.LOGIN;
  return rawRequest({
    url: url,
    method: 'POST',
    header: { 'content-type': 'application/json' },
    data: { code: code }
  }).then((res) => {
    const env = parseEnvelope(res.data);
    if (!env) {
      throw {
        isTssClientError: true,
        code: LOCAL_ERROR.PARSE,
        message: userMessageOf(LOCAL_ERROR.PARSE, ''),
        traceId: '',
        httpStatus: res.statusCode,
        path: API_PATH.LOGIN
      };
    }
    if (!isSuccessCode(env.code) || !env.data) {
      throw {
        isTssClientError: true,
        code: env.code,
        message: userMessageOf(env.code, env.message),
        traceId: env.traceId,
        httpStatus: res.statusCode,
        path: API_PATH.LOGIN
      };
    }
    return env.data as LoginData;
  });
}

/** 微信真实登录：wx.login -> code -> API-01。 */
export function wxLogin(): Promise<LoginData> {
  return new Promise<string>((resolve, reject) => {
    wx.login({
      success: (res) => {
        if (res && res.code) {
          resolve(res.code);
        } else {
          reject({
            isTssClientError: true,
            code: LOCAL_ERROR.NO_SESSION,
            message: 'wx.login 未返回 code',
            traceId: '',
            httpStatus: 0,
            path: API_PATH.LOGIN
          });
        }
      },
      fail: (err) => {
        reject({
          isTssClientError: true,
          code: LOCAL_ERROR.NETWORK,
          message: userMessageOf(LOCAL_ERROR.NETWORK, (err && err.errMsg) || ''),
          traceId: '',
          httpStatus: 0,
          path: API_PATH.LOGIN
        });
      }
    });
  }).then(callLogin);
}

/** 开发期 Mock 会话码登录（与真实登录隔离）。 */
export function mockLogin(mockCode: string): Promise<LoginData> {
  return callLogin(mockCode || DEFAULT_MOCK_SESSION_CODE);
}

function persist(login: LoginData): string {
  const snapshot: SessionSnapshot = {
    token: login.sessionToken,
    expiresAt: Date.now() + Math.max(1, login.expiresIn) * 1000,
    bindStatus: login.bindStatus
  };
  memorySession = snapshot;
  writeToStorage(snapshot);
  return snapshot.token;
}

function doLogin(): Promise<string> {
  // 1) 本机 DEV Mock 令牌（非 RC2 端点，仅 develop + 显式开关）
  if (isDevMockTokenEnabled()) {
    return devMockTokenLogin(getDevMockTokenEntId(), getDevMockTokenUserId()).then(persist);
  }
  // 2) Mock 会话码（走 RC2 API-01，服务端需接受约定的 code）
  if (isMockSessionEnabled()) {
    return mockLogin(getMockSessionCode()).then(persist);
  }
  // 3) 微信真实登录
  return wxLogin().then(persist);
}

/**
 * 保证拿到可用令牌；并发调用共享同一个登录请求。
 * 已过期或无会话时自动登录。
 */
export function ensureSession(): Promise<string> {
  if (hasValidSession()) {
    return Promise.resolve((memorySession as SessionSnapshot).token);
  }
  if (inFlight) {
    return inFlight;
  }
  inFlight = doLogin()
    .then((token: string) => {
      inFlight = null;
      return token;
    })
    .catch((e: unknown) => {
      inFlight = null;
      throw e;
    });
  return inFlight;
}

/** 服务端判定会话失效时调用：清空本地会话。 */
export function onSessionUnauthorized(): void {
  memorySession = null;
  clearStorage();
}

/** 退出登录。 */
export function logout(): void {
  memorySession = null;
  clearStorage();
}

/** 更新缓存的绑定状态（API-02 拉取后调用）。 */
export function updateBindStatus(bindStatus: BindStatus): void {
  if (memorySession) {
    memorySession.bindStatus = bindStatus;
    writeToStorage(memorySession);
    return;
  }
  const restored = readFromStorage();
  if (restored) {
    restored.bindStatus = bindStatus;
    memorySession = restored;
    writeToStorage(restored);
  }
}
