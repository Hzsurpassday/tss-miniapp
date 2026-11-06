/**
 * 详情条件 GET 缓存（F4）。
 *
 * 纪律：
 *   - 仅当服务端返回了 ETag / Last-Modified 才启用条件 GET；**未实现条件 GET 的后端总是返回 200**，
 *     本模块不会假装有缓存能力。
 *   - 304 无 body：复用上次缓存的 data，并在 UI 上标注「数据未变更（304）」。
 *   - 缓存仅用于展示加速，不作为授权或新鲜度依据。
 */

import type { DetailData } from '../types/api';
import * as storage from './storage';

const KEY_PREFIX = 'detailCache:';
/** 内存缓存有效期（毫秒）：仅用于避免无限期展示陈旧数据。 */
const MAX_AGE_MS = 10 * 60 * 1000;

export interface DetailCacheEntry {
  taskNum: string;
  etag: string;
  lastModified: string;
  data: DetailData;
  cachedAt: number;
}

function keyOf(taskNum: string): string {
  return KEY_PREFIX + taskNum;
}

export function readCache(taskNum: string): DetailCacheEntry | null {
  const raw = storage.get<DetailCacheEntry | null>(keyOf(taskNum), null);
  if (!raw || !raw.data) {
    return null;
  }
  if (Date.now() - raw.cachedAt > MAX_AGE_MS) {
    storage.remove(keyOf(taskNum));
    return null;
  }
  return raw;
}

export function writeCache(
  taskNum: string,
  data: DetailData,
  header: { [key: string]: unknown }
): void {
  const etag = typeof header.ETag === 'string' ? header.ETag : typeof header.etag === 'string' ? header.etag : '';
  const lastModified =
    typeof header['Last-Modified'] === 'string'
      ? header['Last-Modified']
      : typeof header['last-modified'] === 'string'
      ? header['last-modified']
      : '';
  if (!etag && !lastModified) {
    // 服务端未提供校验器：不写入缓存（不假装支持条件 GET）。
    return;
  }
  storage.set(keyOf(taskNum), {
    taskNum: taskNum,
    etag: etag,
    lastModified: lastModified,
    data: data,
    cachedAt: Date.now()
  } as DetailCacheEntry);
}

export function clearCache(taskNum: string): void {
  storage.remove(keyOf(taskNum));
}

/** 构造条件 GET 请求头；无校验器时返回 undefined。 */
export function conditionalHeaders(
  taskNum: string
): { ifNoneMatch?: string; ifModifiedSince?: string } | undefined {
  const entry = readCache(taskNum);
  if (!entry) {
    return undefined;
  }
  const result: { ifNoneMatch?: string; ifModifiedSince?: string } = {};
  if (entry.etag) {
    result.ifNoneMatch = entry.etag;
  }
  if (entry.lastModified) {
    result.ifModifiedSince = entry.lastModified;
  }
  return result;
}
