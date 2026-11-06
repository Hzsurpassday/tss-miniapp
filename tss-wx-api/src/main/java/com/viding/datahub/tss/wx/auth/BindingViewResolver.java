package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.viding.datahub.tss.wx.dto.TssEnums.BindingStatusValue;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 绑定视图解析器：API-02 {@code /wx/v1/me} 与 API-10 {@code /wx/v1/bindings/status} 的<b>唯一</b>事实来源。
 *
 * <p><b>为什么必须收敛到一个类</b>：两个端点各自推导状态，必然出现「调用顺序影响观测结果」
 * （先调 status 看到 EXPIRED、先调 me 还是 PENDING）这类不可复现的问题。
 * 本类把「状态推进」与「状态翻译」放在<b>同一次调用</b>里完成，两个端点拿到的一定是同一份结果。
 *
 * <p><b>状态推进（{@code advance}）——申请单生命周期的唯一执行点</b>：
 * <ol>
 *   <li>过期推进：{@code PENDING_PC_CONFIRM} 且已过期 ⇒ 置 {@code EXPIRED}，
 *       并把绑定关系从 {@code PENDING} 回落 {@code UNBOUND}（与 Python RC3 一致；
 *       关系已是 {@code BOUND} 时不回退——解绑/停用之外的既有绑定不会被申请过期抹掉）；</li>
 *   <li>终态归档：申请流程一旦不在「需用户感知」的保留集合内（典型如 PC 确认通过 {@code BOUND}），
 *       即刻从存储移除；保留集合以 {@link BindingView#keepsRequestId} 为唯一定义处；</li>
 *   <li>关系终局：绑定关系已 {@code BOUND} / {@code DISABLED} 时，残留的待确认申请同样归档
 *       ——这就是<b>裁决 B</b> 的落地点：绑定成功瞬间申请单即终结，{@code bindRequestId} 归 null。</li>
 * </ol>
 *
 * <p><b>状态翻译</b>：先由绑定关系得出 {@code status}（{@code BOUND→BOUND}、{@code DISABLED→DISABLED}、
 * {@code PENDING→PENDING_PC_CONFIRM}、{@code UNBOUND→UNBOUND}），再由「尚未终结的申请单」覆盖为
 * {@code EXPIRED} / {@code PENDING_PC_CONFIRM}；最后由 {@link BindingView#meBindStatusFor} 单向映射出
 * API-02 的 {@code bindStatus}（<b>裁决 A</b>：申请中 ⇒ {@code PENDING}，不是 {@code UNBOUND}）。
 */
@Service
public class BindingViewResolver {

    private static final Logger log = LoggerFactory.getLogger(BindingViewResolver.class);

    private final BindingStore bindings;
    private final BindRequestStore bindRequests;

    public BindingViewResolver(BindingStore bindings, BindRequestStore bindRequests) {
        this.bindings = bindings;
        this.bindRequests = bindRequests;
    }

    /** 解析当前身份的绑定视图（不指定申请单）。 */
    public BindingView resolve(WxIdentity identity) {
        return resolve(identity, null);
    }

    /**
     * 解析当前身份的绑定视图。
     *
     * @param identity 服务端可信身份（openid 来源）
     * @param explicitBindRequestId 客户端显式指定的 {@code bindRequestId}；为空表示取当前申请单
     * @return 统一视图（满足 INV-A / INV-B / INV-C）
     * @throws TssApiException {@code TSS_NOT_FOUND}(404)：指定的申请单不存在、属于他人或已被归档
     */
    public BindingView resolve(WxIdentity identity, String explicitBindRequestId) {
        String openid = identity == null ? null : identity.openid();
        BindRequestRecord open = advance(openid);

        if (explicitBindRequestId != null && !explicitBindRequestId.isBlank()) {
            if (open == null || !explicitBindRequestId.equals(open.bindRequestId())) {
                // 不存在 / 属他人 / 已归档 —— 一律按「不存在」处理，不泄露其是否曾经存在
                throw new TssApiException(TssErrorCode.TSS_NOT_FOUND, "绑定申请不存在");
            }
        }
        BindingStatusValue status = translate(openid, open);
        boolean bound = status == BindingStatusValue.BOUND;
        String requestId = BindingView.keepsRequestId(status) && open != null
                ? open.bindRequestId() : null;

        BindingView view = new BindingView(BindingView.meBindStatusFor(status), status, requestId,
                bound ? identity.erpUserMasked() : null,
                bound ? identity.entDisplayName() : null,
                bound);
        if (!view.satisfiesInvariants()) {
            // 不变量被破坏时不做静默降级：打 WARN 留痕（测试路径另有断言直接让构建失败）
            log.warn("[TSS-WX] 绑定视图不变量被破坏: status={} bindRequestId={} meBindStatus={}",
                    status, requestId, view.meBindStatus());
        }
        return view;
    }

    // --------------------------------------------------------------- 状态推进

    /**
     * 推进申请单生命周期，并返回「当前尚未终结」的那一条（没有则返回 null）。
     *
     * <p>这是申请单生命周期的<b>唯一执行点</b>，不是展现层修饰——所有端点都必须经过它。
     */
    private BindRequestRecord advance(String openid) {
        if (openid == null) {
            return null;
        }
        BindRequestRecord rec = bindRequests.latestRaw(openid);
        if (rec == null) {
            return null;
        }
        Instant now = Instant.now();

        // ① 过期推进
        if (rec.status() == BindingStatusValue.PENDING_PC_CONFIRM && rec.isExpired(now)) {
            rec = rec.withStatus(BindingStatusValue.EXPIRED);
            bindRequests.save(rec);
            BindingRecord binding = bindings.findByOpenid(openid);
            if (binding != null && binding.bindStatus() == BindStatus.PENDING) {
                // 申请过期 ⇒ 「已提交、待确认」这一中间态不复存在，关系回落未绑定
                bindings.save(binding.withBindStatus(BindStatus.UNBOUND).withUpdatedAt(now));
            }
        }
        // ② 终态归档：保留集合（见 BindingView.keepsRequestId）之外的申请单即刻移除。
        //    保留集合 = {PENDING_PC_CONFIRM, EXPIRED, REJECTED}——三者都是「用户必须感知结果」的状态；
        //    BOUND 不在其中（PC 确认通过即流程终结，bindRequestId 归 null，裁决 B）。
        if (!BindingView.keepsRequestId(rec.status())) {
            bindRequests.remove(rec.bindRequestId());
            return null;
        }
        // ③ 关系终局 ⇒ 申请单即刻终结（裁决 B：绑定成功后 bindRequestId 恒为 null）
        BindingRecord binding = bindings.findByOpenid(openid);
        if (binding != null
                && (binding.bindStatus() == BindStatus.BOUND || binding.bindStatus() == BindStatus.DISABLED)) {
            bindRequests.remove(rec.bindRequestId());
            return null;
        }
        return rec;
    }

    // --------------------------------------------------------------- 状态翻译

    /** 由「绑定关系 + 尚未终结的申请单」翻译出 API-10 的 {@code status}。 */
    private BindingStatusValue translate(String openid, BindRequestRecord open) {
        BindingStatusValue rel = BindingStatusValue.UNBOUND;
        BindingRecord binding = openid == null ? null : bindings.findByOpenid(openid);
        if (binding != null && binding.bindStatus() != null) {
            rel = switch (binding.bindStatus()) {
                case BOUND -> BindingStatusValue.BOUND;
                case DISABLED -> BindingStatusValue.DISABLED;
                // 「已提交申请」必须有申请单作证：没有未终结的申请单时不得自称待确认，
                // 否则会造出 status=PENDING_PC_CONFIRM 却 bindRequestId=null 的自相矛盾态（违反 INV-B）
                case PENDING -> open == null ? BindingStatusValue.UNBOUND
                        : BindingStatusValue.PENDING_PC_CONFIRM;
                case UNBOUND -> BindingStatusValue.UNBOUND;
            };
        }
        if (open == null) {
            return rel;
        }
        return switch (open.status()) {
            // 申请流程结果优先表达：过期/被拒都是「关系未建立」的可解释原因（INV-A：me 仍为 UNBOUND）
            case EXPIRED -> BindingStatusValue.EXPIRED;
            case REJECTED -> BindingStatusValue.REJECTED;
            case PENDING_PC_CONFIRM -> (rel == BindingStatusValue.UNBOUND
                    || rel == BindingStatusValue.PENDING_PC_CONFIRM)
                    ? BindingStatusValue.PENDING_PC_CONFIRM : rel;
            default -> rel;
        };
    }
}
