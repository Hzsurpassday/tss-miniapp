/**
 * MP-040 消息：订阅意愿 + 通知列表。
 *
 * 订阅流程：先调用 wx.requestSubscribeMessage（需后台已申请模板 ID），再把用户意愿
 * 通过 API-08 记录到服务端。`SUBSCRIBE_TEMPLATE_IDS` 为空时不弹授权窗，只记录意愿，
 * **不伪造订阅结果**。
 *
 * 通知跳转：NotificationItem 只含 approvalId，无 taskNum；RC2 未提供「按 approvalId 取详情」
 * 端点，因此点击只弹出详情弹层并提供「去待办列表」，不臆造接口。
 */

import { getNotifications, postNotificationConsent, DEFAULT_LIMIT } from '../../utils/api';
import type {
  NotificationData,
  NotificationItem,
  DataMeta,
  ConsentData,
  SubscriptionStatus
} from '../../types/api';
import { NOTIFICATION_TYPE_TEXT, SUBSCRIPTION_STATUS_TEXT, textOf } from '../../utils/dict';
import { formatDateTime } from '../../utils/format';
import { errorText, errorTraceId, isBindingError } from '../../utils/page-utils';
import { STATIC_SAMPLE_BANNER, isStaticSampleEnabled, sampleNotifications } from '../../utils/mock';
import { SUBSCRIBE_TEMPLATE_IDS } from '../../config/app';

export interface NotificationItemView {
  id: string;
  title: string;
  body: string;
  typeText: string;
  createdAtText: string;
  readText: string;
  unread: boolean;
}

interface MessagesPageData {
  subscriptionStatusText: string;
  subscriptionRecordedAtText: string;
  consentBusy: boolean;
  templateHint: string;
  items: NotificationItemView[];
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

function toView(item: NotificationItem): NotificationItemView {
  return {
    id: item.id,
    title: item.title,
    body: item.body,
    typeText: textOf(NOTIFICATION_TYPE_TEXT, item.type),
    createdAtText: formatDateTime(item.createdAt),
    readText: item.readAt ? '已读 ' + formatDateTime(item.readAt) : '未读',
    unread: !item.readAt
  };
}

Page<
  MessagesPageData,
  {
    reload(): Promise<void>;
    fetchPage(reset: boolean): Promise<void>;
    merge(data: NotificationData, reset: boolean): void;
    loadMore(): void;
    onSubscribeTap(): void;
    recordConsent(consented: boolean, templateIds: string[]): void;
    onItemTap(e: { currentTarget: { dataset: { index: string | number } } }): void;
    onGoProfile(): void;
    onCopyTraceId(): void;
  }
>({
  data: {
    subscriptionStatusText: '未设置',
    subscriptionRecordedAtText: '',
    consentBusy: false,
    templateHint:
      SUBSCRIBE_TEMPLATE_IDS.length > 0
        ? '已配置 ' + SUBSCRIBE_TEMPLATE_IDS.length + ' 个订阅模板'
        : '未配置订阅模板 ID：仅记录意愿，不会弹出微信授权窗',
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
    if (reset) {
      this.setData({ loading: true, errorText: '', traceId: '', sampleBanner: '', needBinding: false });
    } else {
      if (!this.data.hasMore || this.data.loadingMore || this.data.loading) {
        return Promise.resolve();
      }
      this.setData({ loadingMore: true });
    }

    const query: { limit: number; cursor?: string } = { limit: DEFAULT_LIMIT };
    if (!reset && this.data.nextCursor) {
      query.cursor = this.data.nextCursor;
    }

    return getNotifications(query)
      .then((data: NotificationData) => {
        this.merge(data, reset);
      })
      .catch((e: unknown) => {
        // 未绑定：走绑定引导，绝不用空列表冒充「暂无通知」。
        if (isBindingError(e)) {
          this.setData({ needBinding: true, traceId: errorTraceId(e), items: [] });
          return;
        }
        if (isStaticSampleEnabled()) {
          this.setData({ sampleBanner: STATIC_SAMPLE_BANNER });
          this.merge(sampleNotifications().data, reset);
          return;
        }
        this.setData({ errorText: errorText(e), traceId: errorTraceId(e) });
      })
      .then(() => {
        this.setData({ loading: false, loadingMore: false });
      });
  },

  merge(data: NotificationData, reset: boolean): void {
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

  loadMore(): void {
    if (!this.data.hasMore) {
      return;
    }
    this.fetchPage(false);
  },

  onSubscribeTap(): void {
    if (this.data.consentBusy) {
      return;
    }
    const templateIds = SUBSCRIBE_TEMPLATE_IDS;
    if (templateIds.length === 0) {
      this.recordConsent(true, []);
      return;
    }
    this.setData({ consentBusy: true });
    wx.requestSubscribeMessage({
      tmplIds: templateIds,
      success: (res) => {
        const keys = Object.keys(res || {});
        let accepted: string[] = [];
        for (let i = 0; i < keys.length; i++) {
          if (res[keys[i]] === 'accept') {
            accepted.push(keys[i]);
          }
        }
        this.recordConsent(accepted.length > 0, templateIds);
      },
      fail: (err) => {
        console.warn('[tss] 订阅授权未完成：' + ((err && err.errMsg) || ''));
        // 授权失败也如实记录为未同意，不伪装成成功。
        this.recordConsent(false, templateIds);
      }
    });
  },

  recordConsent(consented: boolean, templateIds: string[]): void {
    this.setData({ consentBusy: true });
    postNotificationConsent({ templateIds: templateIds, consented: !!consented })
      .then((data: ConsentData) => {
        this.setData({
          subscriptionStatusText: textOf(SUBSCRIPTION_STATUS_TEXT, data.subscriptionStatus),
          subscriptionRecordedAtText: formatDateTime(data.recordedAt)
        });
      })
      .catch((e: unknown) => {
        this.setData({ errorText: errorText(e), traceId: errorTraceId(e) });
      })
      .then(() => {
        this.setData({ consentBusy: false });
      });
  },

  onItemTap(e: { currentTarget: { dataset: { index: string | number } } }): void {
    const index = Number(e.currentTarget.dataset.index);
    const item = this.data.items[index];
    if (!item) {
      return;
    }
    wx.showModal({
      title: item.title,
      content: item.body + '\n类型：' + item.typeText + '\n时间：' + item.createdAtText + '\n' + item.readText,
      confirmText: '去待办列表',
      cancelText: '关闭',
      success: (res) => {
        if (res.confirm) {
          wx.switchTab({ url: '/pages/todos/index' });
        }
      }
    });
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
