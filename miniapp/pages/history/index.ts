/**
 * MP-030 历史记录。
 *
 * 筛选（F3）：action（APPROVED/REJECTED/RETURNED/SAVED/UNKNOWN）、from、to（YYYY-MM-DD，含端点）。
 * 分页：不透明 cursor，筛选变更立即丢弃旧 cursor。
 */

import { getHistory, DEFAULT_LIMIT } from '../../utils/api';
import type { HistoryData, HistoryItem, DataMeta, HistoryAction, DocType } from '../../types/api';
import { DOC_TYPE_TEXT, HISTORY_ACTION_TEXT, historyActionClass, textOf } from '../../utils/dict';
import { formatDateTime, todayInputValue } from '../../utils/format';
import { errorText, errorTraceId, isBindingError } from '../../utils/page-utils';
import { STATIC_SAMPLE_BANNER, isStaticSampleEnabled, sampleHistory } from '../../utils/mock';

export interface HistoryItemView {
  recordId: string;
  taskNum: string;
  billNo: string;
  docTypeText: string;
  actionText: string;
  actionClass: string;
  actionName: string;
  processedAtText: string;
  operatorText: string;
  resultVerifiedText: string;
  resultVerifiedClass: string;
}

interface OptionItem {
  key: string;
  label: string;
}

interface HistoryPageData {
  docTypeTabs: OptionItem[];
  docType: string;
  actionOptions: OptionItem[];
  actionIndex: number;
  action: string;
  from: string;
  to: string;
  today: string;
  items: HistoryItemView[];
  meta: DataMeta | null;
  nextCursor: string;
  hasMore: boolean;
  loading: boolean;
  loadingMore: boolean;
  errorText: string;
  traceId: string;
  sampleBanner: string;
  emptyVisible: boolean;
  needBinding: boolean;
}

const DOC_TYPE_TABS: OptionItem[] = [
  { key: '', label: '全部' },
  { key: 'PURCHASE_INBOUND', label: '采购入库' },
  { key: 'SALES_BUSINESS', label: '销售业务' },
  { key: 'PAYMENT', label: '付款' },
  { key: 'SALES_PRICING', label: '销售定价' }
];

const ACTION_OPTIONS: OptionItem[] = [
  { key: '', label: '全部动作' },
  { key: 'APPROVED', label: '同意' },
  { key: 'REJECTED', label: '驳回' },
  { key: 'RETURNED', label: '退回' },
  { key: 'SAVED', label: '保存' },
  { key: 'UNKNOWN', label: '未知' }
];

function toView(item: HistoryItem): HistoryItemView {
  return {
    recordId: item.recordId,
    taskNum: item.taskNum,
    billNo: item.billNo,
    docTypeText: textOf(DOC_TYPE_TEXT, item.docType),
    actionText: textOf(HISTORY_ACTION_TEXT, item.action),
    actionClass: historyActionClass(item.action),
    actionName: item.actionName,
    processedAtText: formatDateTime(item.processedAt),
    operatorText: item.operatorDisplay || '未提供',
    resultVerifiedText: item.resultVerified ? '已实证' : '未实证',
    resultVerifiedClass: item.resultVerified ? 'tss-tag success' : 'tss-tag gray'
  };
}

Page<
  HistoryPageData,
  {
    reload(): Promise<void>;
    fetchPage(reset: boolean): Promise<void>;
    merge(data: HistoryData, reset: boolean): void;
    onDocTypeTab(e: { currentTarget: { dataset: { key: string } } }): void;
    onActionChange(e: { detail: { value: string | number } }): void;
    onFromChange(e: { detail: { value: string } }): void;
    onToChange(e: { detail: { value: string } }): void;
    onResetFilter(): void;
    loadMore(): void;
    onGoProfile(): void;
    onCopyTraceId(): void;
  }
>({
  data: {
    docTypeTabs: DOC_TYPE_TABS,
    docType: '',
    actionOptions: ACTION_OPTIONS,
    actionIndex: 0,
    action: '',
    from: '',
    to: '',
    today: '',
    items: [],
    meta: null,
    nextCursor: '',
    hasMore: false,
    loading: false,
    loadingMore: false,
    errorText: '',
    traceId: '',
    sampleBanner: '',
    emptyVisible: false,
    needBinding: false
  },

  onLoad(): void {
    this.setData({ today: todayInputValue() });
    this.reload();
  },

  onPullDownRefresh(): void {
    this.reload().then(() => {
      wx.stopPullDownRefresh();
    });
  },

  onReachBottom(): void {
    this.loadMore();
  },

  reload(): Promise<void> {
    this.setData({ nextCursor: '', hasMore: false, items: [] });
    return this.fetchPage(true);
  },

  fetchPage(reset: boolean): Promise<void> {
    const from = this.data.from || '';
    const to = this.data.to || '';
    if (from && to && from > to) {
      this.setData({ errorText: '开始日期不得晚于结束日期' });
      return Promise.resolve();
    }

    if (reset) {
      this.setData({ loading: true, errorText: '', traceId: '', sampleBanner: '', needBinding: false });
    } else {
      if (!this.data.hasMore || this.data.loadingMore || this.data.loading) {
        return Promise.resolve();
      }
      this.setData({ loadingMore: true });
    }

    const query: {
      limit: number;
      cursor?: string;
      docType?: DocType;
      action?: HistoryAction;
      from?: string;
      to?: string;
    } = { limit: DEFAULT_LIMIT };

    if (!reset && this.data.nextCursor) {
      query.cursor = this.data.nextCursor;
    }
    if (this.data.docType) {
      query.docType = this.data.docType as DocType;
    }
    if (this.data.action) {
      query.action = this.data.action as HistoryAction;
    }
    if (from) {
      query.from = from;
    }
    if (to) {
      query.to = to;
    }

    return getHistory(query)
      .then((data: HistoryData) => {
        this.merge(data, reset);
      })
      .catch((e: unknown) => {
        // 未绑定：走绑定引导，绝不用空列表冒充「无历史记录」。
        if (isBindingError(e)) {
          this.setData({ needBinding: true, traceId: errorTraceId(e), items: [] });
          return;
        }
        if (isStaticSampleEnabled()) {
          this.setData({ sampleBanner: STATIC_SAMPLE_BANNER });
          this.merge(sampleHistory().data, reset);
          return;
        }
        this.setData({ errorText: errorText(e), traceId: errorTraceId(e) });
      })
      .then(() => {
        this.setData({ loading: false, loadingMore: false });
      });
  },

  merge(data: HistoryData, reset: boolean): void {
    const views = (data.items || []).map(toView);
    const merged = reset ? views : this.data.items.concat(views);
    const page = data.page;
    this.setData({
      items: merged,
      meta: data.meta,
      nextCursor: page && page.nextCursor ? page.nextCursor : '',
      hasMore: !!(page && page.hasMore),
      emptyVisible: merged.length === 0
    });
  },

  onDocTypeTab(e: { currentTarget: { dataset: { key: string } } }): void {
    const key = e.currentTarget.dataset.key;
    if (key === this.data.docType) {
      return;
    }
    this.setData({ docType: key, nextCursor: '', items: [] });
    this.fetchPage(true);
  },

  onActionChange(e: { detail: { value: string | number } }): void {
    const index = Number(e.detail.value);
    const option = ACTION_OPTIONS[index] || ACTION_OPTIONS[0];
    this.setData({ actionIndex: index, action: option.key, nextCursor: '', items: [] });
    this.fetchPage(true);
  },

  onFromChange(e: { detail: { value: string } }): void {
    this.setData({ from: e.detail.value, nextCursor: '', items: [] });
    this.fetchPage(true);
  },

  onToChange(e: { detail: { value: string } }): void {
    this.setData({ to: e.detail.value, nextCursor: '', items: [] });
    this.fetchPage(true);
  },

  onResetFilter(): void {
    this.setData({
      docType: '',
      action: '',
      actionIndex: 0,
      from: '',
      to: '',
      nextCursor: '',
      items: []
    });
    this.fetchPage(true);
  },

  loadMore(): void {
    if (!this.data.hasMore) {
      return;
    }
    this.fetchPage(false);
  },

  onCopyTraceId(): void {
    if (!this.data.traceId) {
      return;
    }
    wx.setClipboardData({ data: this.data.traceId });
  },

  onGoProfile(): void {
    wx.switchTab({ url: '/pages/profile/index' });
  }
});
