/**
 * 包络解析（无依赖，可被 session / request 共用，避免循环引用）。
 *
 * 兼容规则：客户端必须忽略未知 JSON 字段。这里只读取 RC2 定义的
 * `code / message / data / traceId / timestamp`，其余字段（含服务端未来新增字段、
 * 以及历史遗留的 `requestId` / `serverTime` / `sourceMode` / `dataFreshness` 等）
 * 一律不读取、不校验、不报错。
 */

export interface ParsedEnvelope {
  code: string;
  message: string;
  data: unknown;
  traceId: string;
  timestamp: string;
}

function isPlainObject(raw: unknown): boolean {
  return typeof raw === 'object' && raw !== null && !Array.isArray(raw);
}

/** 是否为 RC2 包络形态（至少具备字符串 code）。 */
export function isEnvelopeLike(raw: unknown): boolean {
  return isPlainObject(raw) && typeof (raw as { code?: unknown }).code === 'string';
}

/** 宽容解析包络；非包络形态返回 null。 */
export function parseEnvelope(raw: unknown): ParsedEnvelope | null {
  if (!isEnvelopeLike(raw)) {
    return null;
  }
  const obj = raw as { [key: string]: unknown };
  const code = String(obj.code);
  const message = typeof obj.message === 'string' ? obj.message : '';
  const traceId = typeof obj.traceId === 'string' ? obj.traceId : '';
  const timestamp = typeof obj.timestamp === 'string' ? obj.timestamp : '';
  return {
    code: code,
    message: message,
    data: obj.data === undefined ? null : obj.data,
    traceId: traceId,
    timestamp: timestamp
  };
}

/** 成功判定：唯一成功码 SUCCESS。 */
export function isSuccessCode(code: string): boolean {
  return code === 'SUCCESS';
}
