/**
 * MP-020~023 待办详情（四类单据共用同一页面，按 docType 动态渲染）。
 *
 * 渲染：
 *   - `base[]` / `lines[].fields[]` 动态字段（BaseField），按 format 渲染，verified=false 不编造值；
 *   - `workflow` 交给只读工作流组件（nodes/edges/graphType/parallelMode/branchGroup/events）；
 *   - `sourceStatus` 状态说明；
 *   - `availableActions` **只按后端返回**渲染（VIEW / OPEN_ERP），前端绝不臆造 APPROVE/REJECT；
 *   - 条件 GET：仅在服务端提供 ETag/Last-Modified 时发送，304 复用缓存并提示。
 *
 * 红线：本页不存在任何「同意 / 驳回」按钮。
 */

import { getTodoDetail } from '../../utils/api';
import type {
  DetailData,
  BaseField,
  LineItem,
  Workflow,
  SourceStatus,
  DataMeta
} from '../../types/api';
import { docTypeFullText, DOWNSTREAM_RECEIPT_TEXT, textOf } from '../../utils/dict';
import { formatDateTime } from '../../utils/format';
import { buildActionViews, type ActionView } from '../../utils/workflow';
import { errorText, errorTraceId, isBindingError } from '../../utils/page-utils';
import { conditionalHeaders, readCache, writeCache } from '../../utils/detail-cache';
import { STATIC_SAMPLE_BANNER, isStaticSampleEnabled, sampleDetail } from '../../utils/mock';
import { READONLY_NOTICE } from '../../config/app';

interface LineView {
  lineId: string;
  title: string;
  fields: BaseField[];
}

interface SourceStatusView {
  sourceNameText: string;
  finalApprovedText: string;
  downstreamText: string;
  inventoryText: string;
  lastVerifiedAtText: string;
  notesText: string;
  hasNotes: boolean;
}

interface DetailPageData {
  taskNum: string;
  loading: boolean;
  titleText: string;
  approvalIdText: string;
  docTypeText: string;
  baseFields: BaseField[];
  lines: LineView[];
  workflow: Workflow | null;
  sourceStatus: SourceStatusView | null;
  actions: ActionView[];
  meta: DataMeta | null;
  notModified: boolean;
  errorText: string;
  traceId: string;
  sampleBanner: string;
  readonlyNotice: string;
  noBaseFields: boolean;
  noLines: boolean;
  needBinding: boolean;
}

function triStateText(value: boolean | null, trueText: string, falseText: string): string {
  if (value === null || value === undefined) {
    return '未提供';
  }
  return value ? trueText : falseText;
}

function buildSourceStatusView(s: SourceStatus): SourceStatusView {
  return {
    sourceNameText: s.sourceName || '未提供',
    finalApprovedText: triStateText(s.isFinalApproved, '已终审', '未终审'),
    downstreamText: textOf(DOWNSTREAM_RECEIPT_TEXT, s.downstreamReceiptStatus),
    inventoryText: triStateText(s.inventoryChanged, '库存已变更', '库存未变更'),
    lastVerifiedAtText: s.lastVerifiedAt ? formatDateTime(s.lastVerifiedAt) : '未提供',
    notesText: s.notes || '',
    hasNotes: !!s.notes
  };
}

function buildLines(lines: LineItem[]): LineView[] {
  const views: LineView[] = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    views.push({
      lineId: line.lineId,
      title: '明细 ' + line.lineId,
      fields: line.fields || []
    });
  }
  return views;
}

Page<
  DetailPageData,
  {
    load(): Promise<void>;
    applyDetail(detail: DetailData): void;
    onActionTap(e: { currentTarget: { dataset: { action: string } } }): void;
    onGoProfile(): void;
    onCopyTraceId(): void;
  }
>({
  data: {
    taskNum: '',
    loading: false,
    titleText: '待办详情',
    approvalIdText: '',
    docTypeText: '',
    baseFields: [],
    lines: [],
    workflow: null,
    sourceStatus: null,
    actions: [],
    meta: null,
    notModified: false,
    errorText: '',
    traceId: '',
    sampleBanner: '',
    readonlyNotice: READONLY_NOTICE,
    noBaseFields: true,
    noLines: true,
    needBinding: false
  },

  onLoad(options: { taskNum?: string }): void {
    const taskNum = options && options.taskNum ? String(options.taskNum) : '';
    this.setData({ taskNum: taskNum });
    if (!taskNum) {
      this.setData({ errorText: '缺少 taskNum 参数' });
      return;
    }
    wx.setNavigationBarTitle({ title: '详情 ' + taskNum });
    this.load();
  },

  onPullDownRefresh(): void {
    this.load().then(() => {
      wx.stopPullDownRefresh();
    });
  },

  load(): Promise<void> {
    const taskNum = this.data.taskNum;
    this.setData({
      loading: true,
      errorText: '',
      traceId: '',
      sampleBanner: '',
      notModified: false,
      needBinding: false
    });

    return getTodoDetail(taskNum, conditionalHeaders(taskNum))
      .then((res) => {
        if (res.notModified) {
          const cached = readCache(taskNum);
          if (cached) {
            this.setData({ notModified: true });
            this.applyDetail(cached.data);
            return;
          }
          // 304 但本地无缓存：按契约没有 body，只能提示重新拉取。
          this.setData({ errorText: '服务端返回 304 但本地无缓存，请重新拉取' });
          return;
        }
        writeCache(taskNum, res.data, res.header);
        this.applyDetail(res.data);
      })
      .catch((e: unknown) => {
        // 未绑定：走绑定引导，绝不用静态样例顶替真实单据。
        if (isBindingError(e)) {
          this.setData({ needBinding: true, traceId: errorTraceId(e) });
          return;
        }
        if (isStaticSampleEnabled()) {
          this.setData({ sampleBanner: STATIC_SAMPLE_BANNER });
          this.applyDetail(sampleDetail().data);
          return;
        }
        this.setData({ errorText: errorText(e), traceId: errorTraceId(e) });
      })
      .then(() => {
        this.setData({ loading: false });
      });
  },

  applyDetail(detail: DetailData): void {
    const baseFields = detail.base || [];
    const lines = buildLines(detail.lines || []);
    this.setData({
      titleText: docTypeFullText(detail.docType),
      approvalIdText: detail.approvalId || '未提供',
      docTypeText: docTypeFullText(detail.docType),
      baseFields: baseFields,
      lines: lines,
      workflow: detail.workflow || null,
      sourceStatus: detail.sourceStatus ? buildSourceStatusView(detail.sourceStatus) : null,
      actions: buildActionViews(detail.availableActions),
      meta: detail.meta || null,
      noBaseFields: baseFields.length === 0,
      noLines: lines.length === 0
    });
  },

  onActionTap(e: { currentTarget: { dataset: { action: string } } }): void {
    const action = e.currentTarget.dataset.action;
    if (action === 'OPEN_ERP') {
      wx.showModal({
        title: '在 ERP 中处理',
        content:
          'V1.0 小程序为只读版本，不提供 ERP 跳转链接与写回能力。请在电脑端 ERP 中打开该单据并完成审批。',
        showCancel: false
      });
      return;
    }
    if (action === 'VIEW') {
      wx.showToast({ title: '该单据仅可查看', icon: 'none' });
      return;
    }
    // 未知动作：忽略，不臆造行为。
    console.warn('[tss] 未识别的动作：' + String(action));
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
