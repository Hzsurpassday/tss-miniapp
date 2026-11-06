/** 本地存储薄封装：统一 key 前缀与异常兜底。 */

const PREFIX = 'tss:';

function k(key: string): string {
  return PREFIX + key;
}

export function get<T>(key: string, fallback: T): T {
  try {
    const v = wx.getStorageSync(k(key));
    return v === '' || v === null || v === undefined ? fallback : (v as T);
  } catch (e) {
    return fallback;
  }
}

export function set(key: string, value: unknown): void {
  try {
    wx.setStorageSync(k(key), value);
  } catch (e) {
    // 忽略写入失败（如存储配额不足），不影响主流程。
  }
}

export function remove(key: string): void {
  try {
    wx.removeStorageSync(k(key));
  } catch (e) {
    // 忽略。
  }
}
