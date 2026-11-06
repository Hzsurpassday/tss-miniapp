/**
 * 展示层格式化工具。
 *
 * 核心纪律：
 *   - 金额 / 重量一律以**十进制字符串**呈现，禁止转浮点参与运算或重新计算，避免精度与编造风险。
 *   - 时间按服务端返回的带时区 ISO 8601 **原样拆解展示**，不做跨时区换算（避免显示成另一个时刻）。
 *   - 任何缺失值展示为占位符，绝不用 0、'—' 之外的具体数值编造。
 */

import type { BaseField, FieldFormat } from '../types/api';

/** 缺失值占位符（统一，便于 QA 与审计识别）。 */
export const MISSING_TEXT = '暂未提供';

/** 空值占位。 */
export const EMPTY_TEXT = '-';

/**
 * 千分位分组（纯字符串操作，不转数值）。
 * 例：'19800' -> '19,800'
 */
export function groupThousands(intPart: string): string {
  const digits = intPart.replace(/^\+/, '');
  if (digits.length <= 3) {
    return digits;
  }
  let result = '';
  let count = 0;
  for (let i = digits.length - 1; i >= 0; i--) {
    result = digits.charAt(i) + result;
    count++;
    if (count % 3 === 0 && i > 0) {
      result = ',' + result;
    }
  }
  return result;
}

/** 判断字符串是否为十进制数字形态（可带前导负号）。 */
function isDecimalString(text: string): boolean {
  const body = text.charAt(0) === '-' ? text.substring(1) : text;
  if (body.length === 0) {
    return false;
  }
  const dot = body.indexOf('.');
  if (dot < 0) {
    return /^\d+$/.test(body);
  }
  const intPart = body.substring(0, dot);
  const fracPart = body.substring(dot + 1);
  return /^\d+$/.test(intPart) && /^\d*$/.test(fracPart);
}

/**
 * 金额格式化：保持十进制字符串语义，仅做千分位与币种符号装饰。
 * 非法输入原样返回（不修正、不编造）。
 */
export function formatAmount(raw: string | number | null | undefined, currency?: string): string {
  if (raw === null || raw === undefined) {
    return MISSING_TEXT;
  }
  const text = String(raw).trim();
  if (text === '') {
    return MISSING_TEXT;
  }
  if (!isDecimalString(text)) {
    return text;
  }
  const negative = text.charAt(0) === '-';
  const body = negative ? text.substring(1) : text;
  const dot = body.indexOf('.');
  const intPart = dot >= 0 ? body.substring(0, dot) : body;
  const fracPart = dot >= 0 ? body.substring(dot + 1) : '';
  const grouped = groupThousands(intPart);
  const numeric = (negative ? '-' : '') + grouped + (fracPart !== '' ? '.' + fracPart : '');
  const symbol = currency === 'CNY' ? '¥' : '';
  return symbol + numeric;
}

/** 重量（吨）格式化：保留原始小数位，附加单位。 */
export function formatWeightTon(raw: string | number | null | undefined): string {
  if (raw === null || raw === undefined) {
    return MISSING_TEXT;
  }
  const text = String(raw).trim();
  if (text === '') {
    return MISSING_TEXT;
  }
  if (!isDecimalString(text)) {
    return text + ' 吨';
  }
  return text + ' 吨';
}

/**
 * ISO 8601 拆解（兼容 'YYYY-MM-DD HH:mm:ss' 与带时区偏移/毫秒写法）。
 * 返回 null 表示无法识别，调用方应展示占位符而不是猜一个时间。
 */
export interface IsoParts {
  year: string;
  month: string;
  day: string;
  hour: string;
  minute: string;
  second: string;
  /** 原始时区偏移文本（如 '+08:00'），仅用于展示。 */
  offset: string;
}

const ISO_RE = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?\s*(Z|[+-]\d{2}:?\d{2})?$/;

export function parseIsoParts(raw: string | null | undefined): IsoParts | null {
  if (!raw) {
    return null;
  }
  const text = String(raw).trim();
  const m = ISO_RE.exec(text);
  if (!m) {
    return null;
  }
  return {
    year: m[1],
    month: m[2],
    day: m[3],
    hour: m[4],
    minute: m[5],
    second: m[6] || '00',
    offset: m[7] || ''
  };
}

/** 日期：YYYY-MM-DD。 */
export function formatDate(raw: string | null | undefined): string {
  const p = parseIsoParts(raw);
  if (!p) {
    return raw ? String(raw) : MISSING_TEXT;
  }
  return p.year + '-' + p.month + '-' + p.day;
}

/** 日期时间：YYYY-MM-DD HH:mm（按服务端本地时刻展示，不做时区换算）。 */
export function formatDateTime(raw: string | null | undefined): string {
  const p = parseIsoParts(raw);
  if (!p) {
    return raw ? String(raw) : MISSING_TEXT;
  }
  return p.year + '-' + p.month + '-' + p.day + ' ' + p.hour + ':' + p.minute;
}

/** 时间：HH:mm。 */
export function formatTime(raw: string | null | undefined): string {
  const p = parseIsoParts(raw);
  if (!p) {
    return '';
  }
  return p.hour + ':' + p.minute;
}

/** 用于 from/to 日期选择器入参：YYYY-MM-DD。 */
export function toDateInputValue(raw: string | null | undefined): string {
  return formatDate(raw);
}

/** 今天（本地日历日）YYYY-MM-DD，仅用于日期选择器默认值，不作为业务判定。 */
export function todayInputValue(): string {
  const d = new Date();
  const y = String(d.getFullYear());
  const m = d.getMonth() + 1 < 10 ? '0' + (d.getMonth() + 1) : String(d.getMonth() + 1);
  const day = d.getDate() < 10 ? '0' + d.getDate() : String(d.getDate());
  return y + '-' + m + '-' + day;
}

/** 是否为「今天」的简单文本提示（按日期部分比较）。 */
export function isSameDay(raw: string | null | undefined, other: string | null | undefined): boolean {
  return formatDate(raw) === formatDate(other) && !!formatDate(raw);
}

/**
 * 按 BaseField.format 渲染字段值。
 * `verified === false` 时：值为空 -> 「字段暂未提供」；值非空 -> 原值 + 未核实标记（由调用方渲染 verified 标记）。
 */
export function formatFieldValue(field: BaseField): string {
  const v = field.value;
  if (v === null || v === undefined || v === '') {
    return field.verified ? EMPTY_TEXT : '字段暂未提供';
  }
  switch (field.format) {
    case 'AMOUNT':
      return formatAmount(v as string);
    case 'WEIGHT_TON':
      return formatWeightTon(v as string);
    case 'DATE':
      return formatDate(String(v));
    case 'DATETIME':
      return formatDateTime(String(v));
    case 'BADGE':
      return String(v);
    case 'TEXT':
    default:
      return String(v);
  }
}

/** 字段是否应显示为徽标。 */
export function isBadgeFormat(format: FieldFormat): boolean {
  return format === 'BADGE';
}

/** 生成一次性随机串（clientNonce / claimNonce）。不使用第三方库。 */
export function randomNonce(prefix: string): string {
  const t = String(Date.now());
  let r = '';
  for (let i = 0; i < 8; i++) {
    r += Math.floor(Math.random() * 10);
  }
  return prefix + '_' + t + '_' + r;
}

/** 安全取字符串（null/undefined -> ''）。 */
export function safeText(v: unknown): string {
  return v === null || v === undefined ? '' : String(v);
}
