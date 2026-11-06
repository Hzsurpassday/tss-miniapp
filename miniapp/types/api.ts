/**
 * 时空智审 API v1.0-rc2 —— TypeScript 类型镜像。
 *
 * 生成方式：**人工依据唯一字段级标准 `tss-openapi-v1.0-rc2.yaml` 逐 schema 转写**，
 * 并与后端 `tss-wx-api` 的 `dto/*.java`（30 个 RC2 镜像 DTO）与 `dto/TssEnums.java` 逐字段核对，
 * 保证前后端零漂移。本文件**不得**凭记忆或臆测新增/改名/删字段。
 *
 * 兼容规则（契约修订决议·兼容规则）：
 *   - 客户端必须忽略未知 JSON 字段：本层只声明已知字段，运行时不做 `additionalProperties` 校验，
 *     服务端新增可选字段不会导致解析失败。
 *   - `additionalProperties:false` 仅用于校验服务端输出与防止请求输入多余字段。
 *
 * 包络固定为 `{ code, message, data, traceId, timestamp }`；成功码 `SUCCESS`。
 * 已废弃：旧 `requestId` / `serverTime` / `sourceMode`；`dataMode` 位于 `data.meta.dataMode`。
 */

// ---------------------------------------------------------------------------
// 枚举（与 TssEnums.java 同名同值域）
// ---------------------------------------------------------------------------

/** 单据类型（docType）。 */
export type DocType = 'PURCHASE_INBOUND' | 'SALES_BUSINESS' | 'PAYMENT' | 'SALES_PRICING';

/** 绑定状态（LoginData / MeData）。 */
export type BindStatus = 'UNBOUND' | 'PENDING' | 'BOUND' | 'DISABLED';

/** 待办状态。 */
export type TodoStatus = 'PENDING' | 'COMPLETED' | 'RETURNED' | 'CANCELLED' | 'UNKNOWN';

/** 数据来源模式（位于 data.meta.dataMode）。 */
export type DataMode = 'MOCK' | 'ERP';

/** 数据新鲜度（F2：统一在 meta.freshness）。 */
export type Freshness = 'FRESH' | 'DELAYED' | 'UNAVAILABLE';

/** 字段展示格式（BaseField.format）。 */
export type FieldFormat = 'TEXT' | 'AMOUNT' | 'WEIGHT_TON' | 'DATE' | 'DATETIME' | 'BADGE';

/** 工作流节点类型。 */
export type NodeType = 'START' | 'APPROVAL' | 'END' | 'CC' | 'UNKNOWN';

/** 工作流节点状态。 */
export type NodeStatus =
  | 'PENDING'
  | 'ACTIVE'
  | 'COMPLETED'
  | 'RETURNED'
  | 'SKIPPED'
  | 'CANCELLED'
  | 'UNKNOWN';

/** 并行模式。 */
export type ParallelMode = 'NONE' | 'ANY' | 'ALL' | 'SEQUENTIAL' | 'UNKNOWN';

/** 工作流图类型。 */
export type GraphType = 'LINEAR' | 'DAG' | 'UNKNOWN';

/** 下游单据状态。 */
export type DownstreamReceiptStatus =
  | 'NOT_VERIFIED'
  | 'NOT_CREATED'
  | 'PARTIAL'
  | 'COMPLETE'
  | 'UNKNOWN';

/** 历史事件动作类型。 */
export type EventActionType =
  | 'APPROVED'
  | 'REJECTED'
  | 'RETURNED'
  | 'SAVED'
  | 'STARTED'
  | 'UNKNOWN';

/** 历史事件结果。 */
export type EventOutcome = 'SUCCESS' | 'FAILED' | 'UNKNOWN';

/** 历史记录动作（与 EventActionType 语义相同但取值域不同，按 RC2 分别定义）。 */
export type HistoryAction = 'APPROVED' | 'REJECTED' | 'RETURNED' | 'SAVED' | 'UNKNOWN';

/** 通知类型。 */
export type NotificationType = 'NEW_TODO' | 'TASK_CHANGED' | 'SYSTEM';

/** 订阅状态。 */
export type SubscriptionStatus = 'GRANTED' | 'DENIED' | 'PARTIAL' | 'UNAVAILABLE';

/** 绑定申请状态（API-09 ClaimData）。 */
export type ClaimStatus = 'PENDING_PC_CONFIRM' | 'BOUND' | 'EXPIRED' | 'REJECTED';

/** 绑定状态（API-10 BindingStatusData，取值域比 BindStatus 更宽）。 */
export type BindingStatusValue =
  | 'UNBOUND'
  | 'PENDING_PC_CONFIRM'
  | 'BOUND'
  | 'EXPIRED'
  | 'REJECTED'
  | 'DISABLED';

/** 详情可执行动作。V1.0 只读：只允许 VIEW 与经过验证的 OPEN_ERP。 */
export type AvailableAction = 'VIEW' | 'OPEN_ERP';

/** 字段映射校验等级。 */
export type ValidationLevel = 'ERP_VERIFIED' | 'PARTIAL' | 'PENDING';

/** 管理端列表绑定状态。 */
export type AdminBindStatus = 'BOUND' | 'UNBOUND' | 'PENDING' | 'DISABLED';

/** 币种（RC2 仅 CNY）。 */
export type Currency = 'CNY';

/** 解绑确认枚举（API-11 请求）。 */
export type UnbindConfirmation = 'UNBIND';

// ---------------------------------------------------------------------------
// 包络
// ---------------------------------------------------------------------------

/**
 * 统一包络。`data` 在错误时为 `null`。
 * 注意：`SUCCESS` 为唯一成功码；其余为稳定 `TSS_*` 错误码（见 utils/errors.ts）。
 */
export interface Envelope<TData> {
  code: string;
  message: string;
  data: TData | null;
  traceId: string;
  timestamp: string;
}

/** 成功包络（data 非空）。 */
export interface SuccessEnvelope<TData> extends Envelope<TData> {
  code: 'SUCCESS';
  data: TData;
}

// ---------------------------------------------------------------------------
// 公共结构
// ---------------------------------------------------------------------------

/** 分页元信息。`nextCursor` 为服务端签名不透明游标，客户端不可解析。 */
export interface PageMeta {
  nextCursor: string | null;
  hasMore: boolean;
  totalApprox: number | null;
}

/** 数据元信息（F1/F2）。 */
export interface DataMeta {
  dataMode: DataMode;
  asOf: string;
  freshness: Freshness;
  schemaVersion: string;
}

// ---------------------------------------------------------------------------
// API-01 登录 / API-02 会话本人
// ---------------------------------------------------------------------------

export interface LoginRequest {
  /** 微信 wx.login 短效 code，不可复用。 */
  code: string;
}

export interface LoginData {
  sessionToken: string;
  expiresIn: number;
  bindStatus: BindStatus;
  needBinding: boolean;
}

export interface MeData {
  userId: string | null;
  entId: string | null;
  displayName: string | null;
  bindStatus: BindStatus;
  bindingVersion: number | null;
  enterpriseSwitchEnabled: boolean;
}

// ---------------------------------------------------------------------------
// API-03 待办统计 / API-04 待办列表
// ---------------------------------------------------------------------------

export interface CountGroup {
  total: number;
  purchaseInbound: number;
  salesBusiness: number;
  payment: number;
  salesPricing: number;
}

export interface SummaryData {
  counts: CountGroup;
  lastFetchedAt: string;
  meta: DataMeta;
}

export interface TodoItem {
  approvalId: string;
  taskNum: string;
  instanceId: string;
  billNo: string;
  docType: DocType;
  title: string;
  counterparty: string | null;
  /** 十进制金额字符串，如 1200.00。前端不得转为浮点参与计算。 */
  amount: string | null;
  currency: Currency;
  currentNodeName: string;
  createdAt: string;
  updatedAt: string;
  status: TodoStatus;
  dataMode: DataMode;
}

export interface TodoListData {
  items: TodoItem[];
  page: PageMeta;
  meta: DataMeta;
}

/** API-04 查询参数（F3：改变筛选必须丢弃旧 cursor）。 */
export interface TodoListQuery {
  cursor?: string;
  limit?: number;
  docType?: DocType;
  keyword?: string;
}

// ---------------------------------------------------------------------------
// API-05 待办详情
// ---------------------------------------------------------------------------

/** 动态字段。verified=false 表示字段未经 ERP 实证，前端不得编造值。 */
export interface BaseField {
  key: string;
  label: string;
  value: string | number | boolean | null;
  format: FieldFormat;
  verified: boolean;
}

export interface LineItem {
  lineId: string;
  fields: BaseField[];
}

export interface WorkflowEdge {
  from: string;
  to: string;
  label: string | null;
}

export interface WorkflowNode {
  nodeId: string;
  name: string;
  nodeType: NodeType;
  status: NodeStatus;
  /** 用户显示名或已脱敏标识。 */
  assignees: string[];
  completedAt: string | null;
  branchGroup: string | null;
  parallelMode: ParallelMode;
}

export interface WorkflowEvent {
  eventId: string;
  nodeId: string;
  actionType: EventActionType;
  menuName: string;
  operatorDisplay: string | null;
  occurredAt: string;
  outcome: EventOutcome;
  serialNum: string | null;
  comment: string | null;
}

export interface Workflow {
  workflowId: string;
  instanceId: string;
  currentNodeIds: string[];
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
  graphType: GraphType;
  dataVersion: string;
  definitionVersion: string;
  /** V1.0 固定 true，不可因 ERP 动作配置出现而开放直接审批。 */
  readOnly: boolean;
  /** 按 occurredAt 升序的实际审批流转事件，未证实的事件不得编造。 */
  events: WorkflowEvent[];
}

export interface SourceStatus {
  sourceName: string;
  isFinalApproved: boolean | null;
  downstreamReceiptStatus: DownstreamReceiptStatus;
  inventoryChanged: boolean | null;
  lastVerifiedAt: string | null;
  notes: string | null;
}

export interface DetailData {
  approvalId: string;
  taskNum: string;
  docType: DocType;
  base: BaseField[];
  lines: LineItem[];
  workflow: Workflow;
  sourceStatus: SourceStatus;
  availableActions: AvailableAction[];
  meta: DataMeta;
}

// ---------------------------------------------------------------------------
// API-06 历史
// ---------------------------------------------------------------------------

export interface HistoryItem {
  recordId: string;
  approvalId: string;
  taskNum: string;
  billNo: string;
  docType: DocType;
  action: HistoryAction;
  actionName: string;
  processedAt: string;
  operatorDisplay: string;
  resultVerified: boolean;
}

export interface HistoryData {
  items: HistoryItem[];
  page: PageMeta;
  meta: DataMeta;
}

/** API-06 查询参数（F3）。 */
export interface HistoryQuery {
  cursor?: string;
  limit?: number;
  docType?: DocType;
  action?: HistoryAction;
  /** 开始日期（包含），YYYY-MM-DD。 */
  from?: string;
  /** 结束日期（包含），YYYY-MM-DD；from 不得晚于 to。 */
  to?: string;
}

// ---------------------------------------------------------------------------
// API-07 通知 / API-08 订阅意愿
// ---------------------------------------------------------------------------

export interface NotificationItem {
  id: string;
  title: string;
  body: string;
  type: NotificationType;
  createdAt: string;
  readAt: string | null;
  approvalId: string | null;
}

export interface NotificationData {
  items: NotificationItem[];
  page: PageMeta;
  meta: DataMeta;
}

export interface NotificationQuery {
  cursor?: string;
  limit?: number;
}

export interface ConsentRequest {
  templateIds: string[];
  consented: boolean;
}

export interface ConsentData {
  subscriptionStatus: SubscriptionStatus;
  recordedAt: string;
}

// ---------------------------------------------------------------------------
// API-09 发起绑定 / API-10 绑定状态 / API-11 解绑
// ---------------------------------------------------------------------------

export interface ClaimRequest {
  inviteCode: string;
  /** 客户端一次性幂等随机值。 */
  claimNonce: string;
}

export interface ClaimData {
  bindRequestId: string;
  status: ClaimStatus;
  expiresAt: string;
}

export interface BindingStatusData {
  bindRequestId: string | null;
  status: BindingStatusValue;
  erpUserMasked: string | null;
  entDisplayName: string | null;
  checkedAt: string;
}

export interface UnbindRequest {
  confirmation: UnbindConfirmation;
  clientNonce: string;
}

export interface UnbindData {
  revokedAt: string;
  bindStatus: 'UNBOUND';
}

// ---------------------------------------------------------------------------
// API-12 / API-13：PC 管理端（adminAuth）。小程序不调用，仅保留类型镜像。
// ---------------------------------------------------------------------------

export interface AuditInfo {
  accessLogged: boolean;
  scope: string;
}

export interface AdminBinding {
  bindingId: string;
  userId: string;
  entId: string;
  openidMasked: string;
  bindStatus: AdminBindStatus;
  bindingVersion: number;
  updatedAt: string;
}

export interface AdminBindingsData {
  items: AdminBinding[];
  page: PageMeta;
  auditInfo: AuditInfo;
}

export interface MappingField {
  key: string;
  label: string;
  sourceColumn: string | null;
  nullable: boolean;
  verified: boolean;
}

export interface MappingDefinition {
  docType: DocType;
  formId: string | null;
  displayName: string;
  validationLevel: ValidationLevel;
  fields: MappingField[];
}

export interface AdminMappingsData {
  definitions: MappingDefinition[];
  validationLevel: ValidationLevel;
  updatedAt: string;
}

// ---------------------------------------------------------------------------
// 端点路径常量（与 RC2 paths 一一对应，避免手写字符串漂移）
// ---------------------------------------------------------------------------

export const API_PATH = {
  LOGIN: '/wx/v1/auth/login',
  ME: '/wx/v1/me',
  TODO_SUMMARY: '/wx/v1/todos/summary',
  TODO_LIST: '/wx/v1/todos',
  TODO_DETAIL: '/wx/v1/todos/{taskNum}/detail',
  HISTORY: '/wx/v1/history',
  NOTIFICATIONS: '/wx/v1/notifications',
  NOTIFICATION_CONSENT: '/wx/v1/notifications/consent',
  BINDING_CLAIM: '/wx/v1/bindings/claim',
  BINDING_STATUS: '/wx/v1/bindings/status',
  BINDING_UNBIND: '/wx/v1/bindings/unbind',
  ADMIN_BINDINGS: '/admin/v1/tss/bindings',
  ADMIN_MAPPINGS: '/admin/v1/tss/mappings'
} as const;
