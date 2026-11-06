/**
 * 数据新鲜度 / 来源模式提示条（F2）。
 *
 * 只读 `data.meta`：freshness（FRESH/DELAYED/UNAVAILABLE）、dataMode（MOCK/ERP）、
 * asOf、schemaVersion。旧 `dataFreshness` / `sourceMode` 已废弃，本组件不读取。
 */

import type { DataMeta } from '../../types/api';
import { FRESHNESS_TEXT, FRESHNESS_HINT, DATA_MODE_TEXT, freshnessClass, textOf } from '../../utils/dict';
import { formatDateTime } from '../../utils/format';

export interface FreshnessView {
  freshnessText: string;
  freshnessHint: string;
  freshnessClass: string;
  dataModeText: string;
  asOfText: string;
  schemaVersionText: string;
  extraText: string;
}

const UNKNOWN = '未知';

function buildView(meta: DataMeta | null, extra: string): FreshnessView | null {
  if (!meta) {
    return null;
  }
  return {
    freshnessText: textOf(FRESHNESS_TEXT, meta.freshness),
    freshnessHint: textOf(FRESHNESS_HINT, meta.freshness),
    freshnessClass: freshnessClass(meta.freshness),
    dataModeText: textOf(DATA_MODE_TEXT, meta.dataMode),
    asOfText: formatDateTime(meta.asOf),
    schemaVersionText: meta.schemaVersion || UNKNOWN,
    extraText: extra || ''
  };
}

Component<
  { view: FreshnessView | null },
  { meta: { type: ObjectConstructor; value: null }; extra: { type: StringConstructor; value: string } },
  { rebuild(): void }
>({
  properties: {
    meta: { type: Object, value: null },
    extra: { type: String, value: '' }
  },

  data: {
    view: null
  },

  observers: {
    'meta, extra': function (meta: DataMeta | null, extra: string): void {
      this.setData({ view: buildView(meta, extra) });
    }
  },

  methods: {
    rebuild: function (): void {
      this.setData({
        view: buildView(this.data.meta as DataMeta | null, String(this.data.extra || ''))
      });
    }
  }
});
