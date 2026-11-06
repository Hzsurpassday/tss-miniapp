/** wx.request 的 Promise 薄封装（不含任何业务逻辑）。 */

export interface RawResponse {
  statusCode: number;
  data: unknown;
  header: { [key: string]: unknown };
}

export interface RawRequestOptions {
  url: string;
  method: WxRequestMethod;
  header?: { [key: string]: string };
  data?: unknown;
  timeout?: number;
}

/** 默认超时（毫秒）。 */
export const DEFAULT_TIMEOUT_MS = 15000;

/** 网络层失败（wx.request fail 回调）。 */
export interface NetworkFailure {
  isNetworkFailure: true;
  errMsg: string;
}

export function isNetworkFailure(e: unknown): boolean {
  return (
    typeof e === 'object' &&
    e !== null &&
    (e as { isNetworkFailure?: boolean }).isNetworkFailure === true
  );
}

/**
 * 发起一次 HTTP 请求。
 * 注意：`dataType` 固定 json 由调用方处理文本；304 响应无 body，data 可能为 ''。
 */
export function rawRequest(options: RawRequestOptions): Promise<RawResponse> {
  return new Promise<RawResponse>((resolve, reject) => {
    wx.request({
      url: options.url,
      method: options.method,
      header: options.header || {},
      data: options.data as never,
      timeout: options.timeout || DEFAULT_TIMEOUT_MS,
      success: (res) => {
        resolve({
          statusCode: res.statusCode,
          data: res.data,
          header: (res.header || {}) as { [key: string]: unknown }
        });
      },
      fail: (err) => {
        reject({
          isNetworkFailure: true,
          errMsg: (err && err.errMsg) || 'request:fail'
        } as NetworkFailure);
      }
    });
  });
}

/** 把查询对象拼成 query string；跳过 undefined/null/空串。忽略未知键风险由调用方控制。 */
export function buildQueryString(query?: { [key: string]: unknown }): string {
  if (!query) {
    return '';
  }
  const parts: string[] = [];
  const keys = Object.keys(query);
  for (let i = 0; i < keys.length; i++) {
    const key = keys[i];
    const value = query[key];
    if (value === undefined || value === null || value === '') {
      continue;
    }
    parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(value)));
  }
  if (parts.length === 0) {
    return '';
  }
  return '?' + parts.join('&');
}
