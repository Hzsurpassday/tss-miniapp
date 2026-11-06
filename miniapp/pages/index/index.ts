/**
 * MP-010 首页：待办概览。
 *
 * 展示：总待办数、四业务分类卡（采购入库/销售业务/付款/销售定价）、数据新鲜度（meta.freshness）。
 * 纪律：
 *   - 企业上下文由服务端绑定推导，本页无任何企业切换入口；
 *   - 未出现任何审批写回入口；
 *   - 请求失败且开启「静态样例兜底」时才使用本地样例，并显著标注来源。
 */

import { getTodoSummary, getMe } from '../../utils/api';
import type { SummaryData, MeData, DataMeta, DocType } from '../../types/api';
import { DOC_TYPE_CARDS, BIND_STATUS_TEXT, textOf } from '../../utils/dict';
import { formatDateTime } from '../../utils/format';
import { errorText, errorTraceId, isBindingError } from '../../utils/page-utils';
import { resolveBaseUrl, isCurrentBaseUrlHttps } from '../../config/env';
import { STATIC_SAMPLE_BANNER, isStaticSampleEnabled, sampleSummary, sampleMe } from '../../utils/mock';
import { READONLY_NOTICE } from '../../config/app';

export interface CategoryCard {
  docType: DocType;
  label: string;
  count: number;
}

interface IndexData {
  loading: boolean;
  total: number;
  cards: CategoryCard[];
  lastFetchedAtText: string;
  meta: DataMeta | null;
  displayName: string;
  bindStatusText: string;
  entIdText: string;
  needBinding: boolean;
  errorText: string;
  traceId: string;
  sampleBanner: string;
  baseUrl: string;
  https: boolean;
  readonlyNotice: string;
}

Page<
  IndexData,
  {
    load(): Promise<void>;
    applySummary(summary: SummaryData): void;
    applyMe(me: MeData): void;
    onCardTap(e: { currentTarget: { dataset: { doctype: string } } }): void;
    onGoTodos(): void;
    onCopyTraceId(): void;
    onGoProfile(): void;
  }
>({
  data: {
    loading: false,
    total: 0,
    cards: [],
    lastFetchedAtText: '',
    meta: null,
    displayName: '',
    bindStatusText: '',
    entIdText: '',
    needBinding: false,
    errorText: '',
    traceId: '',
    sampleBanner: '',
    baseUrl: '',
    https: false,
    readonlyNotice: READONLY_NOTICE
  },

  onLoad(): void {
    this.setData({ baseUrl: resolveBaseUrl(), https: isCurrentBaseUrlHttps() });
  },

  onShow(): void {
    this.load();
  },

  onPullDownRefresh(): void {
    this.load().then(() => {
      wx.stopPullDownRefresh();
    });
  },

  load(): Promise<void> {
    this.setData({ loading: true, errorText: '', traceId: '', sampleBanner: '', needBinding: false });

    const useSample = isStaticSampleEnabled();

    const meTask = getMe()
      .catch((e: unknown) => {
        if (useSample) {
          return sampleMe().data;
        }
        this.setData({ errorText: errorText(e), traceId: errorTraceId(e) });
        return null;
      })
      .then((me: MeData | null) => {
        if (me) {
          this.applyMe(me);
        }
      });

    const summaryTask = getTodoSummary()
      .catch((e: unknown) => {
        // 未绑定优先走绑定引导，不用静态样例顶替「已绑定」的数据。
        if (isBindingError(e)) {
          this.setData({ needBinding: true, traceId: errorTraceId(e) });
          return null;
        }
        if (useSample) {
          this.setData({ sampleBanner: STATIC_SAMPLE_BANNER });
          return sampleSummary().data;
        }
        if (!this.data.errorText) {
          this.setData({ errorText: errorText(e), traceId: errorTraceId(e) });
        }
        return null;
      })
      .then((summary: SummaryData | null) => {
        if (summary) {
          this.applySummary(summary);
        }
      });

    return Promise.all([meTask, summaryTask]).then(() => {
      this.setData({ loading: false });
    });
  },

  applySummary(summary: SummaryData): void {
    const counts = summary.counts;
    const cards: CategoryCard[] = [];
    for (let i = 0; i < DOC_TYPE_CARDS.length; i++) {
      const card = DOC_TYPE_CARDS[i];
      cards.push({
        docType: card.key,
        label: card.label,
        count: (counts as unknown as { [key: string]: number })[card.countKey as string] || 0
      });
    }
    this.setData({
      total: counts.total || 0,
      cards: cards,
      lastFetchedAtText: formatDateTime(summary.lastFetchedAt),
      meta: summary.meta
    });
  },

  applyMe(me: MeData): void {
    this.setData({
      displayName: me.displayName || '未提供',
      bindStatusText: textOf(BIND_STATUS_TEXT, me.bindStatus),
      entIdText: me.entId || '由服务端绑定推导',
      needBinding: me.bindStatus !== 'BOUND'
    });
  },

  onCardTap(e: { currentTarget: { dataset: { doctype: string } } }): void {
    const docType = e.currentTarget.dataset.doctype;
    wx.navigateTo({ url: '/pages/todos/index?docType=' + docType });
  },

  onGoTodos(): void {
    wx.switchTab({ url: '/pages/todos/index' });
  },

  onGoProfile(): void {
    wx.switchTab({ url: '/pages/profile/index' });
  },

  onCopyTraceId(): void {
    if (!this.data.traceId) {
      return;
    }
    wx.setClipboardData({ data: this.data.traceId });
  }
});
