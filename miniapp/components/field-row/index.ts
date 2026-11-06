/**
 * 动态字段行组件（BaseField 渲染）。
 *
 * 纪律：
 *   - 按 `format`（TEXT/AMOUNT/WEIGHT_TON/DATE/DATETIME/BADGE）渲染；
 *   - 金额/重量保持**十进制字符串**呈现，不转浮点；
 *   - `verified=false` 且值为空 → 显示「字段暂未提供」（PRD 口径），绝不编造假值；
 *   - `verified=false` 但值非空 → 原值展示并追加「未核实」标记，不把未实证数据伪装成已实证。
 */

import type { BaseField } from '../../types/api';
import { formatFieldValue, MISSING_TEXT } from '../../utils/format';

export interface FieldRowView {
  label: string;
  display: string;
  format: string;
  verified: boolean;
  isMissing: boolean;
  isBadge: boolean;
  showUnverified: boolean;
}

function buildView(field: BaseField | null): FieldRowView | null {
  if (!field) {
    return null;
  }
  const display = formatFieldValue(field);
  const empty = field.value === null || field.value === undefined || field.value === '';
  return {
    label: field.label || field.key || '',
    display: display,
    format: field.format || 'TEXT',
    verified: !!field.verified,
    isMissing: display === MISSING_TEXT || (empty && !field.verified),
    isBadge: field.format === 'BADGE',
    showUnverified: !field.verified && !empty
  };
}

Component<
  { view: FieldRowView | null },
  { field: { type: ObjectConstructor; value: null } },
  { rebuild(): void }
>({
  properties: {
    field: { type: Object, value: null }
  },

  data: {
    view: null
  },

  observers: {
    field: function (field: BaseField | null): void {
      this.setData({ view: buildView(field) });
    }
  },

  methods: {
    rebuild: function (): void {
      this.setData({ view: buildView(this.data.field as BaseField | null) });
    }
  }
});
