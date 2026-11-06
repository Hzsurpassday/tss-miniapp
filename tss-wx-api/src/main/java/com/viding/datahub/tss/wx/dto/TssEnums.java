package com.viding.datahub.tss.wx.dto;

/**
 * RC2 枚举集中定义处。
 *
 * <p>枚举名与取值严格照抄 {@code tss-openapi-v1.0-rc2.yaml} 的 schema enum，
 * 不做语义漂移：不允许为了「方便」新增取值或改写含义（兼容规则：变更枚举语义属破坏性变更，须走 /v2）。
 */
public final class TssEnums {

    private TssEnums() {
    }

    /** 单据类型（docType）。 */
    public enum DocType {
        PURCHASE_INBOUND, SALES_BUSINESS, PAYMENT, SALES_PRICING
    }

    /** 绑定状态（LoginData / MeData）。 */
    public enum BindStatus {
        UNBOUND, PENDING, BOUND, DISABLED
    }

    /** 待办状态。 */
    public enum TodoStatus {
        PENDING, COMPLETED, RETURNED, CANCELLED, UNKNOWN
    }

    /** 数据来源模式。 */
    public enum DataMode {
        MOCK, ERP
    }

    /** 数据新鲜度（F2：统一在 meta.freshness）。 */
    public enum Freshness {
        FRESH, DELAYED, UNAVAILABLE
    }

    /** 字段展示格式。 */
    public enum FieldFormat {
        TEXT, AMOUNT, WEIGHT_TON, DATE, DATETIME, BADGE
    }

    /** 工作流节点类型。 */
    public enum NodeType {
        START, APPROVAL, END, CC, UNKNOWN
    }

    /** 工作流节点状态。 */
    public enum NodeStatus {
        PENDING, ACTIVE, COMPLETED, RETURNED, SKIPPED, CANCELLED, UNKNOWN
    }

    /** 并行模式。 */
    public enum ParallelMode {
        NONE, ANY, ALL, SEQUENTIAL, UNKNOWN
    }

    /** 图类型。 */
    public enum GraphType {
        LINEAR, DAG, UNKNOWN
    }

    /** 下游单据状态。 */
    public enum DownstreamReceiptStatus {
        NOT_VERIFIED, NOT_CREATED, PARTIAL, COMPLETE, UNKNOWN
    }

    /** 历史事件动作类型。 */
    public enum EventActionType {
        APPROVED, REJECTED, RETURNED, SAVED, STARTED, UNKNOWN
    }

    /** 历史事件结果。 */
    public enum EventOutcome {
        SUCCESS, FAILED, UNKNOWN
    }

    /** 历史记录动作（与 EventActionType 语义相同但取值域不同，按 RC2 分别定义）。 */
    public enum HistoryAction {
        APPROVED, REJECTED, RETURNED, SAVED, UNKNOWN
    }

    /** 通知类型。 */
    public enum NotificationType {
        NEW_TODO, TASK_CHANGED, SYSTEM
    }

    /** 订阅状态。 */
    public enum SubscriptionStatus {
        GRANTED, DENIED, PARTIAL, UNAVAILABLE
    }

    /** 绑定申请状态（API-09 ClaimData）。 */
    public enum ClaimStatus {
        PENDING_PC_CONFIRM, BOUND, EXPIRED, REJECTED
    }

    /** 绑定状态（API-10 BindingStatusData，取值域比 BindStatus 更宽）。 */
    public enum BindingStatusValue {
        UNBOUND, PENDING_PC_CONFIRM, BOUND, EXPIRED, REJECTED, DISABLED
    }

    /** 详情可执行动作。V1.0 只读：只允许 VIEW 与经过验证的 OPEN_ERP。 */
    public enum AvailableAction {
        VIEW, OPEN_ERP
    }

    /** 字段映射校验等级。 */
    public enum ValidationLevel {
        ERP_VERIFIED, PARTIAL, PENDING
    }

    /** 管理端列表绑定状态。 */
    public enum AdminBindStatus {
        BOUND, UNBOUND, PENDING, DISABLED
    }
}
