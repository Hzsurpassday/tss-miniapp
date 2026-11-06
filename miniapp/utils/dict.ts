/**
 * 枚举 → 中文展示文案映射。
 *
 * 纪律：文案只做**展示层翻译**，不得改变枚举语义；未知枚举值统一显示「未知」并保留原值，
 * 绝不猜测或映射成相近含义。
 */

import type {
  DocType,
  BindStatus,
  TodoStatus,
  DataMode,
  Freshness,
  NodeType,
  NodeStatus,
  ParallelMode,
  GraphType,
  DownstreamReceiptStatus,
  EventActionType,
  EventOutcome,
  HistoryAction,
  NotificationType,
  SubscriptionStatus,
  ClaimStatus,
  BindingStatusValue,
  AvailableAction,
  ValidationLevel
} from '../types/api';

const UNKNOWN_TEXT = '未知';

/** 单据类型（四业务分类）。 */
export const DOC_TYPE_TEXT: { [key: string]: string } = {
  PURCHASE_INBOUND: '采购入库',
  SALES_BUSINESS: '销售业务',
  PAYMENT: '付款',
  SALES_PRICING: '销售定价'
};

/** 单据类型完整名称（用于详情页标题 MP-020~023）。 */
export const DOC_TYPE_FULL_TEXT: { [key: string]: string } = {
  PURCHASE_INBOUND: '采购入库审批',
  SALES_BUSINESS: '销售业务审批',
  PAYMENT: '付款审批',
  SALES_PRICING: '销售定价审批'
};

export const BIND_STATUS_TEXT: { [key: string]: string } = {
  UNBOUND: '未绑定',
  PENDING: '待确认',
  BOUND: '已绑定',
  DISABLED: '已停用'
};

export const TODO_STATUS_TEXT: { [key: string]: string } = {
  PENDING: '待处理',
  COMPLETED: '已完成',
  RETURNED: '已退回',
  CANCELLED: '已取消',
  UNKNOWN: UNKNOWN_TEXT
};

export const DATA_MODE_TEXT: { [key: string]: string } = {
  MOCK: 'Mock 数据',
  ERP: 'ERP 数据'
};

/** 数据新鲜度（F2：UI 只读 meta.freshness）。 */
export const FRESHNESS_TEXT: { [key: string]: string } = {
  FRESH: '数据新鲜',
  DELAYED: '数据延迟',
  UNAVAILABLE: '数据不可用'
};

export const FRESHNESS_HINT: { [key: string]: string } = {
  FRESH: '数据在阈值内',
  DELAYED: '已超过更新阈值，可能不是最新',
  UNAVAILABLE: '无可信更新，请勿据此决策'
};

export const NODE_TYPE_TEXT: { [key: string]: string } = {
  START: '发起',
  APPROVAL: '审批',
  END: '结束',
  CC: '抄送',
  UNKNOWN: UNKNOWN_TEXT
};

export const NODE_STATUS_TEXT: { [key: string]: string } = {
  PENDING: '未开始',
  ACTIVE: '处理中',
  COMPLETED: '已完成',
  RETURNED: '已退回',
  SKIPPED: '已跳过',
  CANCELLED: '已取消',
  UNKNOWN: UNKNOWN_TEXT
};

/**
 * 并行模式：ANY=或签，ALL=会签（PRD 口径）。
 *
 * `UNKNOWN` / 契约外取值一律显示「未标注」而不是「未知」——在这类只读展示里
 * 「未知」会被读成"数据出问题了"，实际只是服务端没给标记，两者不是一回事。
 */
export const PARALLEL_MODE_TEXT: { [key: string]: string } = {
  NONE: '',
  ANY: '或签',
  ALL: '会签',
  SEQUENTIAL: '依次审批',
  UNKNOWN: '未标注'
};

export const GRAPH_TYPE_TEXT: { [key: string]: string } = {
  LINEAR: '线性流程',
  DAG: '分支流程',
  UNKNOWN: '结构未知'
};

export const DOWNSTREAM_RECEIPT_TEXT: { [key: string]: string } = {
  NOT_VERIFIED: '未核销',
  NOT_CREATED: '未生成',
  PARTIAL: '部分生成',
  COMPLETE: '已完整生成',
  UNKNOWN: UNKNOWN_TEXT
};

export const EVENT_ACTION_TEXT: { [key: string]: string } = {
  APPROVED: '同意',
  REJECTED: '驳回',
  RETURNED: '退回',
  SAVED: '保存',
  STARTED: '提交',
  UNKNOWN: UNKNOWN_TEXT
};

export const EVENT_OUTCOME_TEXT: { [key: string]: string } = {
  SUCCESS: '成功',
  FAILED: '失败',
  UNKNOWN: UNKNOWN_TEXT
};

export const HISTORY_ACTION_TEXT: { [key: string]: string } = {
  APPROVED: '同意',
  REJECTED: '驳回',
  RETURNED: '退回',
  SAVED: '保存',
  UNKNOWN: UNKNOWN_TEXT
};

export const NOTIFICATION_TYPE_TEXT: { [key: string]: string } = {
  NEW_TODO: '新待办',
  TASK_CHANGED: '任务变更',
  SYSTEM: '系统通知'
};

export const SUBSCRIPTION_STATUS_TEXT: { [key: string]: string } = {
  GRANTED: '已同意接收',
  DENIED: '已拒绝',
  PARTIAL: '部分同意',
  UNAVAILABLE: '不可用'
};

export const CLAIM_STATUS_TEXT: { [key: string]: string } = {
  PENDING_PC_CONFIRM: '等待 PC 端确认',
  BOUND: '已绑定',
  EXPIRED: '已过期',
  REJECTED: '已拒绝'
};

export const BINDING_STATUS_VALUE_TEXT: { [key: string]: string } = {
  UNBOUND: '未绑定',
  PENDING_PC_CONFIRM: '等待 PC 端确认',
  BOUND: '已绑定',
  EXPIRED: '已过期',
  REJECTED: '已拒绝',
  DISABLED: '已停用'
};

/** V1.0 可用动作：仅 VIEW 与经核实的 OPEN_ERP，前端不得臆造 APPROVE/REJECT。 */
export const AVAILABLE_ACTION_TEXT: { [key: string]: string } = {
  VIEW: '仅可查看',
  OPEN_ERP: '在 ERP 中打开'
};

export const VALIDATION_LEVEL_TEXT: { [key: string]: string } = {
  ERP_VERIFIED: '已实证',
  PARTIAL: '部分实证',
  PENDING: '待实证'
};

/** 通用取值器：未知值返回 '未知'。 */
export function textOf(map: { [key: string]: string }, value: string | null | undefined): string {
  if (!value) {
    return UNKNOWN_TEXT;
  }
  return map[value] || UNKNOWN_TEXT;
}

export function docTypeText(v: DocType | string | null | undefined): string {
  return textOf(DOC_TYPE_TEXT, v);
}

export function docTypeFullText(v: DocType | string | null | undefined): string {
  return textOf(DOC_TYPE_FULL_TEXT, v);
}

/** 四业务分类卡（首页）顺序固定。 */
export const DOC_TYPE_CARDS: Array<{ key: DocType; label: string; countKey: keyof CountGroupShape }> = [
  { key: 'PURCHASE_INBOUND', label: '采购入库', countKey: 'purchaseInbound' },
  { key: 'SALES_BUSINESS', label: '销售业务', countKey: 'salesBusiness' },
  { key: 'PAYMENT', label: '付款', countKey: 'payment' },
  { key: 'SALES_PRICING', label: '销售定价', countKey: 'salesPricing' }
];

/** 仅供首页分类卡取数使用（与 CountGroup 字段同名）。 */
interface CountGroupShape {
  total: number;
  purchaseInbound: number;
  salesBusiness: number;
  payment: number;
  salesPricing: number;
}

/** 待办状态 → 样式类名（WXML 绑定用）。 */
export function todoStatusClass(status: TodoStatus | string | null | undefined): string {
  switch (status) {
    case 'PENDING':
      return 'st-pending';
    case 'COMPLETED':
      return 'st-completed';
    case 'RETURNED':
      return 'st-returned';
    case 'CANCELLED':
      return 'st-cancelled';
    default:
      return 'st-unknown';
  }
}

/** 新鲜度 → 样式类名。 */
export function freshnessClass(freshness: Freshness | string | null | undefined): string {
  switch (freshness) {
    case 'FRESH':
      return 'fr-fresh';
    case 'DELAYED':
      return 'fr-delayed';
    case 'UNAVAILABLE':
      return 'fr-unavailable';
    default:
      return 'fr-unknown';
  }
}

/** 节点状态 → 样式类名。 */
export function nodeStatusClass(status: NodeStatus | string | null | undefined): string {
  switch (status) {
    case 'ACTIVE':
      return 'ns-active';
    case 'COMPLETED':
      return 'ns-completed';
    case 'PENDING':
      return 'ns-pending';
    case 'RETURNED':
      return 'ns-returned';
    case 'SKIPPED':
      return 'ns-skipped';
    case 'CANCELLED':
      return 'ns-cancelled';
    default:
      return 'ns-unknown';
  }
}

/** 历史事件动作 → 样式类名。 */
export function historyActionClass(action: HistoryAction | string | null | undefined): string {
  switch (action) {
    case 'APPROVED':
      return 'ac-approved';
    case 'REJECTED':
      return 'ac-rejected';
    case 'RETURNED':
      return 'ac-returned';
    case 'SAVED':
      return 'ac-saved';
    default:
      return 'ac-unknown';
  }
}
