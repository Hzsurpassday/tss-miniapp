package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.viding.datahub.tss.wx.dto.TssEnums.BindingStatusValue;

import java.util.EnumSet;
import java.util.Set;

/**
 * 「绑定关系 + 申请流程」的统一视图（RC3）。
 *
 * <p>API-02 {@code /wx/v1/me} 与 API-10 {@code /wx/v1/bindings/status} <b>必须共用同一个推导结果</b>，
 * 否则就会出现「me 说未绑定、status 说待确认」这种不同粒度的答案——正是 RC3 要消灭的观感矛盾。
 *
 * <p><b>强不变量（由 {@code BindingViewResolverTest} 与契约测试断言锁死）</b>：
 * <pre>
 *   INV-A  me.bindStatus 与 status 一一映射（裁决 A）：
 *          UNBOUND↔UNBOUND、PENDING_PC_CONFIRM↔PENDING、BOUND↔BOUND、
 *          EXPIRED↔UNBOUND、REJECTED↔UNBOUND、DISABLED↔DISABLED
 *          理由①：RC3 要求 /me 与 /bindings/status 对同一事实给同一答案；
 *          理由②：若申请中仍报 UNBOUND，则 PENDING 这个枚举值永远取不到（死值）。
 *   INV-B  bindRequestId != null ⟺ status ∈ {PENDING_PC_CONFIRM, EXPIRED, REJECTED}（裁决 B 修订）
 *          BOUND / UNBOUND / DISABLED 一律 null。
 *          「PC 确认完成后已绑定」与「预置账号压根没申请过」必须是同一个响应形状。
 *          <b>为什么 REJECTED 要保留非空</b>：被拒是「申请流程已终结但用户必须知道结果」的状态，
 *          把它归到 null 会让 REJECTED 变成「一拍即灭」——按下拒绝后查 status 看到的是 UNBOUND+null，
 *          跟没按过一样，用户无从得知自己被拒；同时 translate() 里的 REJECTED 分支永远走不到，
 *          等于契约里留了一个死值。保留 id 后形态是 me=UNBOUND + status=REJECTED + id 非空，与 INV-A 自洽。
 *   INV-C  erpUserMasked / entDisplayName 非空 ⟺ status == BOUND
 * </pre>
 */
public record BindingView(
        /** API-02 {@code MeData.bindStatus}（枚举：UNBOUND / PENDING / BOUND / DISABLED）。 */
        BindStatus meBindStatus,
        /** API-10 {@code BindingStatusData.status}（绑定关系 + 申请流程合成后的状态）。 */
        BindingStatusValue status,
        /** API-10 {@code bindRequestId}；见 INV-B。 */
        String bindRequestId,
        /** 脱敏 ERP 账号；仅 BOUND 时非空（INV-C）。 */
        String erpUserMasked,
        /** 企业展示名；仅 BOUND 时非空（INV-C）。 */
        String entDisplayName,
        /** 是否已建立绑定关系（status == BOUND）。 */
        boolean bound
) {

    /**
     * 这三种申请状态仍「未终结」，可以对外暴露 {@code bindRequestId}（INV-B）。
     *
     * <p><b>这是单一事实来源</b>：{@link #keepsRequestId}、{@link #satisfiesInvariants} 以及
     * {@link BindingViewResolver#resolve} 的归档判断<b>全部派生自这一个集合</b>，
     * 因此调整「哪些状态保留 id」只需改这里，不存在三处各改一遍的漂移风险。
     */
    private static final Set<BindingStatusValue> OPEN_FLOW_STATUSES =
            EnumSet.of(BindingStatusValue.PENDING_PC_CONFIRM, BindingStatusValue.EXPIRED,
                    BindingStatusValue.REJECTED);

    /**
     * INV-A 的<b>唯一</b>映射实现：由 API-10 的 {@code status} 反推 API-02 的 {@code bindStatus}。
     *
     * <p>刻意做成 {@code status → me} 的单向函数而不是两张各自维护的表：只要两个端点都经过
     * {@link BindingViewResolver}，就不可能出现「改了一处忘了另一处」的漂移。
     *
     * @param status API-10 合成状态
     * @return API-02 的 {@code bindStatus}
     */
    public static BindStatus meBindStatusFor(BindingStatusValue status) {
        if (status == null) {
            return BindStatus.UNBOUND;
        }
        return switch (status) {
            case UNBOUND -> BindStatus.UNBOUND;
            case PENDING_PC_CONFIRM -> BindStatus.PENDING;
            case BOUND -> BindStatus.BOUND;
            case EXPIRED -> BindStatus.UNBOUND;
            case REJECTED -> BindStatus.UNBOUND;
            case DISABLED -> BindStatus.DISABLED;
        };
    }

    /** 该状态是否允许携带 {@code bindRequestId}（INV-B 的正向判定）。 */
    public static boolean keepsRequestId(BindingStatusValue status) {
        return status != null && OPEN_FLOW_STATUSES.contains(status);
    }

    /**
     * 不变量自检：任一格不满足即返回 false（供测试与 DEV 漂移探测使用）。
     *
     * <p>生产路径不抛异常——状态异常时应以「可观测、可告警」的方式暴露，而不是把 500 抛给用户；
     * 测试路径则把它当断言，任何一格破掉都会让构建失败。
     */
    public boolean satisfiesInvariants() {
        if (meBindStatus != meBindStatusFor(status)) {
            return false;
        }
        if (keepsRequestId(status) != (bindRequestId != null)) {
            return false;
        }
        return bound == (status == BindingStatusValue.BOUND)
                && (bound || (erpUserMasked == null && entDisplayName == null));
    }
}
