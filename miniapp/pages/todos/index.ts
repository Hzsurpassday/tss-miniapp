/**
 * MP-011 待办列表。
 *
 * 能力：docType 分类、keyword 搜索、下拉刷新、cursor 分页上拉加载。
 * 纪律（F3）：cursor 为服务端签名不透明游标，绑定用户/企业/筛选/排序锚点；
 * 一旦改变 docType 或 keyword，立即丢弃旧 cursor 并重新拉取，绝不复用。
 */

import { getTodoList, DEFAULT_LIMIT } from '../../utils/api';
import type { TodoListData, TodoItem, DocType, DataMeta } from '../../types/api';
import { DOC_TYPE_TEXT, TODO_STATUS_TEXT, todoStatusClass, textOf } from '../../utils/dict';
import { formatAmount, formatDateTime } from '../../utils/format';
import { errorText, errorTraceId, isBindingError } from '../../utils/page-utils';
import { STATIC_SAMPLE_BANNER, isStaticSampleEnabled, sampleTodoList } from '../../utils/mock';

export interface TodoItemView {
  approvalId: string;
  taskNum: string;
  docTypeText: string;
  title: string;
  billNo: string;
  counterpartyText: string;
  amountText: string;
  currentNodeName: string;
  updatedAtText: string;
  statusText: string;
  statusClass: string;
}

interface TabItem {
  key: string;
  label: string;
}

interface TodosData {
  tabs: TabItem[];
  docType: string;
  keyword: string;
  items: TodoItemView[];
  meta: DataMeta | null;
  nextCursor: string;
  hasMore: boolean;
  totalApproxText: string;
  loading: boolean;
  loadingMore: boolean;
  errorText: string;
  traceId: string;
  sampleBanner: string;
  emptyVisible: boolean;
  emptyText: string;
  needBinding: boolean;
}

const ALL_TABS: TabItem[] = [
  { key: '', label: '全部' },
  { key: 'PURCHASE_INBOUND', label: '采购入库' },
  { key: 'SALES_BUSINESS', label: '销售业务' },
  { key: 'PAYMENT', label: '付款' },
  { key: 'SALES_PRICING', label: '销售定价' }
];

function toView(item: TodoItem): TodoItemView {
  return {
    approvalId: item.approvalId,
    taskNum: item.taskNum,
    docTypeText: textOf(DOC_TYPE_TEXT, item.docType),
    title: item.title,
    billNo: item.billNo,
    counterpartyText: item.counterparty || '未提供',
    amountText: item.amount === null ? '未提供' : formatAmount(item.amount, item.currency),
    currentNodeName: item.currentNodeName,
    updatedAtText: formatDateTime(item.updatedAt),
    statusText: textOf(TODO_STATUS_TEXT, item.status),
    statusClass: todoStatusClass(item.status)
  };
}

Page<
  TodosData,
  {
    reload(): Promise<void>;
    fetchPage(reset: boolean): Promise<void>;
    merge(data: TodoListData, reset: boolean): void;
    onTabTap(e: { currentTarget: { dataset: { key: string } } }): void;
    onKeywordInput(e: { detail: { value: string } }): void;
    onKeywordConfirm(): void;
    onClearKeyword(): void;
    onItemTap(e: { currentTarget: { dataset: { tasknum: string } } }): void;
    loadMore(): void;
    onGoProfile(): void;
    onCopyTraceId(): void;
  }
>({
  data: {
    tabs: ALL_TABS,
    docType: '',
    keyword: '',
    items: [],
    meta: null,
    nextCursor: '',
    hasMore: false,
    totalApproxText: '',
    loading: false,
    loadingMore: false,
    errorText: '',
    traceId: '',
    sampleBanner: '',
    emptyVisible: false,
    emptyText: '暂无待办',
    needBinding: false
  },

  onLoad(options: { docType?: string }): void {
    if (options && options.docType) {
      this.setData({ docType: String(options.docType) });
    }
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

  /** 重新拉取：清空列表与 cursor。 */
  reload(): Promise<void> {
    this.setData({ nextCursor: '', hasMore: false, items: [] });
    return this.fetchPage(true);
  },

  /** 拉取一页。reset=true 表示首页（清空并展示 loading）。 */
  fetchPage(reset: boolean): Promise<void> {
    if (reset) {
      this.setData({ loading: true, errorText: '', traceId: '', sampleBanner: '', needBinding: false });
    } else {
      if (!this.data.hasMore || this.data.loadingMore || this.data.loading) {
        return Promise.resolve();
      }
      this.setData({ loadingMore: true });
    }

    const query: { limit: number; cursor?: string; docType?: DocType; keyword?: string } = {
      limit: DEFAULT_LIMIT
    };
    if (!reset && this.data.nextCursor) {
      query.cursor = this.data.nextCursor;
    }
    if (this.data.docType) {
      query.docType = this.data.docType as DocType;
    }
    const keyword = this.data.keyword ? this.data.keyword.trim() : '';
    if (keyword) {
      query.keyword = keyword;
    }

    return getTodoList(query)
      .then((data: TodoListData) => {
        this.merge(data, reset);
      })
      .catch((e: unknown) => {
        // 未绑定：走绑定引导，绝不用空列表冒充「零待办」，也不用静态样例顶替。
        if (isBindingError(e)) {
          this.setData({ needBinding: true, traceId: errorTraceId(e), items: [] });
          return;
        }
        if (isStaticSampleEnabled()) {
          this.setData({ sampleBanner: STATIC_SAMPLE_BANNER });
          this.merge(sampleTodoList().data, reset);
          return;
        }
        this.setData({ errorText: errorText(e), traceId: errorTraceId(e) });
      })
      .then(() => {
        this.setData({ loading: false, loadingMore: false });
      });
  },

  merge(data: TodoListData, reset: boolean): void {
    const views = (data.items || []).map(toView);
    const merged = reset ? views : this.data.items.concat(views);
    const page = data.page;
    this.setData({
      items: merged,
      meta: data.meta,
      nextCursor: page && page.nextCursor ? page.nextCursor : '',
      hasMore: !!(page && page.hasMore),
      totalApproxText: page && page.totalApprox !== null ? String(page.totalApprox) : '不保证精确',
      emptyVisible: merged.length === 0,
      emptyText: this.data.keyword ? '没有匹配的待办' : '暂无待办'
    });
  },

  onTabTap(e: { currentTarget: { dataset: { key: string } } }): void {
    const key = e.currentTarget.dataset.key;
    if (key === this.data.docType) {
      return;
    }
    // 筛选变更 -> 丢弃旧 cursor（F3）
    this.setData({ docType: key, nextCursor: '', items: [] });
    this.fetchPage(true);
  },

  onKeywordInput(e: { detail: { value: string } }): void {
    this.setData({ keyword: e.detail.value });
  },

  onKeywordConfirm(): void {
    this.setData({ nextCursor: '', items: [] });
    this.fetchPage(true);
  },

  onClearKeyword(): void {
    this.setData({ keyword: '', nextCursor: '', items: [] });
    this.fetchPage(true);
  },

  onItemTap(e: { currentTarget: { dataset: { tasknum: string } } }): void {
    const taskNum = e.currentTarget.dataset.tasknum;
    wx.navigateTo({ url: '/pages/detail/index?taskNum=' + encodeURIComponent(taskNum) });
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
