/**
 * 页面层通用工具：错误呈现与 traceId 透传展示。
 */

import { isTssClientError } from './request';
import { userMessageOf, LOCAL_ERROR, isBindingErrorCode } from './errors';

export function errorText(e: unknown): string {
  if (isTssClientError(e)) {
    return e.message + '（' + e.code + '）';
  }
  if (typeof e === 'object' && e !== null && (e as { code?: string }).code) {
    const anyErr = e as { code: string; message?: string };
    return userMessageOf(anyErr.code, anyErr.message || '');
  }
  return userMessageOf(LOCAL_ERROR.NETWORK, '');
}

/** 取 traceId（无则空串），用于页面展示以便与后端日志对齐。 */
export function errorTraceId(e: unknown): string {
  if (isTssClientError(e)) {
    return e.traceId || '';
  }
  if (typeof e === 'object' && e !== null && (e as { traceId?: string }).traceId) {
    return (e as { traceId?: string }).traceId || '';
  }
  return '';
}

/** 取错误码（无则空串）。 */
export function errorCode(e: unknown): string {
  if (isTssClientError(e)) {
    return e.code;
  }
  if (typeof e === 'object' && e !== null && (e as { code?: string }).code) {
    return (e as { code?: string }).code || '';
  }
  return '';
}

/**
 * 是否为「需要绑定」类错误，页面应引导绑定而不是显示错误页或空列表。
 * 判定统一交给 utils/errors.ts（权威 20 条 + 非权威码兼容分类），避免页面各自硬编码。
 */
export function isBindingError(e: unknown): boolean {
  return isBindingErrorCode(errorCode(e));
}

/** 拷贝 traceId 到剪贴板，便于反馈排查。 */
export function copyTraceId(traceId: string): void {
  if (!traceId) {
    return;
  }
  wx.setClipboardData({ data: traceId });
}
