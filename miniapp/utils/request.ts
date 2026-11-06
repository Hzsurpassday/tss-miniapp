/**
 * 统一 API 请求服务。
 *
 * 职责：
 *   1. 自动附加 `Authorization: Bearer <sessionToken>`（sessionToken 由服务端 code2Session 后签发）；
 *   2. 统一解析/校验 RC2 包络 `{ code, message, data, traceId, timestamp }`，成功码 `SUCCESS`；
 *   3. 错误码映射（utils/errors.ts，与后端 TssErrorCode 一致）与 `traceId` 日志透传；
 *   4. 宽容解析：忽略未知 JSON 字段，服务端新增字段不会导致崩溃；
 *   5. 401 自动失效本地会话并通知重新登录。
 *
 * 纪律：前端绝不提交 userId / entId 作为授权依据（V1.0 单当前企业由服务端绑定推导）。
 */

import { resolveBaseUrl } from '../config/env';
import { parseEnvelope, isSuccessCode } from './envelope';
import { rawRequest, buildQueryString, isNetworkFailure, DEFAULT_TIMEOUT_MS } from './http';
import { LOCAL_ERROR, userMessageOf, isAuthErrorCode, isBindingErrorCode } from './errors';
import { ensureSession, onSessionUnauthorized } from './session';

export interface ApiRequestOptions {
  /** 以 '/' 开头的路径片段，如 '/wx/v1/todos'。 */
  path: string;
  method?: 'GET' | 'POST';
  /** query 参数；undefined/null/空串会被丢弃。 */
  query?: { [key: string]: unknown };
  /** JSON 请求体。 */
  body?: unknown;
  /** 是否携带会话令牌，默认 true。 */
  auth?: boolean;
  /** 附加请求头（如 If-None-Match / If-Modified-Since）。 */
  header?: { [key: string]: string };
  timeout?: number;
  /** true 则不弹出错误 toast，由页面自行处理。默认 false。 */
  silent?: boolean;
}

export interface ApiResponse<T> {
  data: T;
  traceId: string;
  timestamp: string;
  statusCode: number;
  header: { [key: string]: unknown };
  /** HTTP 304：数据未变更，无 body。仅条件 GET 会出现。 */
  notModified: boolean;
}

/** 客户端侧错误对象（不使用 class extends Error，兼容 ES5 转译）。 */
export interface TssClientError {
  isTssClientError: true;
  code: string;
  message: string;
  traceId: string;
  httpStatus: number;
  path: string;
}

export function isTssClientError(e: unknown): e is TssClientError {
  return (
    typeof e === 'object' && e !== null && (e as { isTssClientError?: boolean }).isTssClientError === true
  );
}

function makeError(
  code: string,
  serverMessage: string,
  traceId: string,
  httpStatus: number,
  path: string
): TssClientError {
  return {
    isTssClientError: true,
    code: code,
    message: userMessageOf(code, serverMessage),
    traceId: traceId,
    httpStatus: httpStatus,
    path: path
  };
}

/** traceId 日志透传：所有请求/响应统一打印，便于与后端日志对齐。 */
function logTrace(method: string, path: string, traceId: string, statusCode: number): void {
  console.info('[tss] ' + method + ' ' + path + ' -> ' + statusCode + ' traceId=' + traceId);
}

/**
 * 发起请求并返回解包后的 data。
 * 特殊处理：HTTP 304 返回 notModified=true 且 data 为 null 断言为 never —— 由调用方使用缓存。
 */
export function requestRaw<T>(options: ApiRequestOptions): Promise<ApiResponse<T>> {
  const method = options.method || 'GET';
  const baseUrl = resolveBaseUrl();
  const url = baseUrl + options.path + buildQueryString(options.query);

  const header: { [key: string]: string } = { 'content-type': 'application/json' };
  if (options.header) {
    const keys = Object.keys(options.header);
    for (let i = 0; i < keys.length; i++) {
      header[keys[i]] = options.header[keys[i]];
    }
  }

  const auth = options.auth !== false;

  const send = (token: string): Promise<ApiResponse<T>> => {
    if (auth && token) {
      header.Authorization = 'Bearer ' + token;
    }
    return rawRequest({
      url: url,
      method: method,
      header: header,
      data: options.body,
      timeout: options.timeout || DEFAULT_TIMEOUT_MS
    }).then((res) => {
      // F4：304 无 body，不解析包络。
      if (res.statusCode === 304) {
        logTrace(method, options.path, '-', 304);
        return {
          data: null as never,
          traceId: '',
          timestamp: '',
          statusCode: 304,
          header: res.header,
          notModified: true
        };
      }

      const env = parseEnvelope(res.data);
      if (!env) {
        const err = makeError(LOCAL_ERROR.PARSE, '', '', res.statusCode, options.path);
        console.error('[tss] 无法解析包络', res.statusCode, res.data);
        if (!options.silent) {
          wx.showToast({ title: err.message, icon: 'none' });
        }
        throw err;
      }

      logTrace(method, options.path, env.traceId, res.statusCode);

      if (isSuccessCode(env.code)) {
        return {
          data: env.data as T,
          traceId: env.traceId,
          timestamp: env.timestamp,
          statusCode: res.statusCode,
          header: res.header,
          notModified: false
        };
      }

      // 服务端返回 TSS_* 错误码。
      if (isAuthErrorCode(env.code)) {
        onSessionUnauthorized();
      }
      const err = makeError(env.code, env.message, env.traceId, res.statusCode, options.path);
      console.warn('[tss] 业务错误 ' + err.code + ' traceId=' + err.traceId);
      // 未绑定/绑定不可用：由页面渲染绑定引导（PRD 口径），此处不弹 toast，
      // 避免与页面引导重复，也避免页面被当成「零待办」。
      if (!options.silent && !isBindingErrorCode(env.code)) {
        wx.showToast({ title: err.message, icon: 'none' });
      }
      throw err;
    });
  };

  const bootstrap = auth ? ensureSession() : Promise.resolve('');

  return bootstrap.then(send).catch((e: unknown) => {
    if (isTssClientError(e)) {
      throw e;
    }
    if (isNetworkFailure(e)) {
      const networkFailure = e as { errMsg?: string };
      const msg = networkFailure.errMsg || '';
      const code = msg.indexOf('timeout') >= 0 ? LOCAL_ERROR.TIMEOUT : LOCAL_ERROR.NETWORK;
      const err = makeError(code, '', '', 0, options.path);
      console.error('[tss] 网络失败 ' + msg + ' url=' + url);
      if (!options.silent) {
        wx.showToast({ title: err.message, icon: 'none' });
      }
      throw err;
    }
    // ensureSession 失败（如 wx.login 被拒）也统一成客户端错误抛出。
    if (typeof e === 'object' && e !== null && (e as TssClientError).isTssClientError) {
      throw e;
    }
    const err = makeError(LOCAL_ERROR.NO_SESSION, '', '', 0, options.path);
    console.error('[tss] 会话不可用', e);
    throw err;
  });
}

/** 常用简写：只取 data。 */
export function requestData<T>(options: ApiRequestOptions): Promise<T> {
  return requestRaw<T>(options).then((res) => res.data);
}
