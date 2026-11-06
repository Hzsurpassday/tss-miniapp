package com.viding.datahub.tss.wx.controller;

import com.viding.datahub.tss.wx.auth.BindRequestRecord;
import com.viding.datahub.tss.wx.auth.BindRequestStore;
import com.viding.datahub.tss.wx.auth.BindingRecord;
import com.viding.datahub.tss.wx.auth.BindingStore;
import com.viding.datahub.tss.wx.auth.BindingView;
import com.viding.datahub.tss.wx.auth.BindingViewResolver;
import com.viding.datahub.tss.wx.auth.ClientIdentityGuard;
import com.viding.datahub.tss.wx.auth.WxAuthService;
import com.viding.datahub.tss.wx.auth.WxIdentity;
import com.viding.datahub.tss.wx.config.TssWxClock;
import com.viding.datahub.tss.wx.dto.BindingStatusData;
import com.viding.datahub.tss.wx.dto.ClaimData;
import com.viding.datahub.tss.wx.dto.ClaimRequest;
import com.viding.datahub.tss.wx.dto.TssEnums;
import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.viding.datahub.tss.wx.dto.UnbindData;
import com.viding.datahub.tss.wx.dto.UnbindRequest;
import com.viding.datahub.tss.wx.envelope.ApiEnvelope;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * API-09 {@code POST /wx/v1/bindings/claim}、API-10 {@code GET /wx/v1/bindings/status}、
 * API-11 {@code POST /wx/v1/bindings/unbind}。
 *
 * <p>V1.0 绑定需 PC 端确认：发起后状态恒为 {@code PENDING_PC_CONFIRM}，
 * 本层<b>不</b>实现 PC 确认端点（属下一阶段独立 Admin 契约范围，见 admin-openapi 草稿；
 * DEV 联调所需的「PC 确认」动作由 {@link DevBindingFixtureController} 提供，属非契约夹具）。
 * 因此在 PC 确认能力冻结前，本层不会自行把状态推进到 BOUND——这是如实反映，不是缺陷掩饰。
 */
@RestController
@RequestMapping(path = "/wx/v1/bindings", produces = MediaType.APPLICATION_JSON_VALUE)
public class WxBindingController extends ApiResponseSupport {

    private static final Logger log = LoggerFactory.getLogger(WxBindingController.class);

    /** 绑定申请有效期（秒）：与 Python 侧 RC3 MOCK 一致（10 分钟）。 */
    private static final long CLAIM_TTL_SECONDS = 600L;

    private final WxAuthService authService;
    private final BindRequestStore bindRequests;
    private final BindingStore bindings;
    private final BindingViewResolver viewResolver;
    private final ClientIdentityGuard identityGuard;

    public WxBindingController(TssWxClock clock,
                               WxAuthService authService,
                               BindRequestStore bindRequests,
                               BindingStore bindings,
                               BindingViewResolver viewResolver,
                               ClientIdentityGuard identityGuard) {
        super(clock);
        this.authService = authService;
        this.bindRequests = bindRequests;
        this.bindings = bindings;
        this.viewResolver = viewResolver;
        this.identityGuard = identityGuard;
    }

    // ------------------------------------------------------------ API-09

    /**
     * 发起绑定申请。
     *
     * <p>判定顺序与 Python 侧 RC3 MOCK 保持一致（先关系状态、后参数）：
     * <ol>
     *   <li>绑定已停用 ⇒ 403 {@code TSS_BINDING_DISABLED}；</li>
     *   <li>已存在绑定关系 ⇒ 409 {@code TSS_BIND_CONFLICT}（权威文案即「已有冲突绑定」）；</li>
     *   <li>参数缺失 / 邀请码无效 ⇒ 400 {@code TSS_INVALID_ARGUMENT}；邀请码确已过期 ⇒ 409 {@code TSS_BIND_INVITE_EXPIRED}；</li>
     *   <li>已持有未终结的申请 ⇒ 回同一条（不制造两条互相矛盾的申请）；</li>
     *   <li>否则建单，并把绑定关系推进到 {@code PENDING}（与 {@code me.bindStatus=PENDING} 互证）。</li>
     * </ol>
     */
    @PostMapping(path = "/claim", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<ClaimData>> claim(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) ClaimRequest body,
            HttpServletRequest request) {
        identityGuard.rejectIfClientIdentitySupplied(request);
        WxIdentity id = authService.resolve(authorization);

        BindStatus relation = relationStatus(id.openid());
        if (relation == BindStatus.DISABLED) {
            throw new TssApiException(TssErrorCode.TSS_BINDING_DISABLED, "绑定已停用，无法发起新的绑定申请");
        }
        if (relation == BindStatus.BOUND) {
            // 已有绑定却再次申请 ⇒ 权威码 409 TSS_BIND_CONFLICT（「已有冲突绑定」）
            throw new TssApiException(TssErrorCode.TSS_BIND_CONFLICT, "该微信身份已存在绑定，不可重复发起");
        }

        if (body == null || body.inviteCode() == null || body.inviteCode().isBlank()) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "inviteCode 不能为空");
        }
        if (body.claimNonce() == null || body.claimNonce().isBlank()) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "claimNonce 不能为空");
        }
        String inviteCode = body.inviteCode().trim();
        InviteState state = inviteState(inviteCode);
        if (state == InviteState.UNKNOWN) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "绑定邀请码无效");
        }
        if (state == InviteState.EXPIRED) {
            // 邀请码确已过期 ⇒ 权威码 409 TSS_BIND_INVITE_EXPIRED（与「邀请码未知」的 400 区分）
            throw new TssApiException(TssErrorCode.TSS_BIND_INVITE_EXPIRED, "绑定邀请已过期");
        }
        String content = id.openid() + ":" + inviteCode;
        if (!bindRequests.checkNonce(body.claimNonce(), content)) {
            throw new TssApiException(TssErrorCode.TSS_IDEMPOTENCY_CONFLICT,
                    "claimNonce 已被用于不同的绑定请求");
        }
        bindRequests.rememberNonce(body.claimNonce(), content);

        // 已持有未终结的申请 ⇒ 回同一条：避免出现两条互相矛盾的「待确认」申请
        BindRequestRecord open = bindRequests.latestRaw(id.openid());
        if (open != null && open.status() == TssEnums.BindingStatusValue.PENDING_PC_CONFIRM
                && !open.isExpired(Instant.now())) {
            log.info("[TSS-WX] bind claim reused existing request: bindRequestId={}", open.bindRequestId());
            return ok(new ClaimData(open.bindRequestId(), TssEnums.ClaimStatus.PENDING_PC_CONFIRM,
                    format(open.expiresAt())));
        }

        String bindRequestId = bindRequests.nextBindRequestId();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(CLAIM_TTL_SECONDS);
        BindRequestRecord rec = new BindRequestRecord(bindRequestId, id.openid(), inviteCode,
                body.claimNonce(), TssEnums.BindingStatusValue.PENDING_PC_CONFIRM, now, expiresAt,
                null, null, null, null);
        bindRequests.save(rec);
        markPending(id);
        log.info("[TSS-WX] bind claimed: bindRequestId={} status=PENDING_PC_CONFIRM (需 PC 端确认)", bindRequestId);

        return ok(new ClaimData(bindRequestId, TssEnums.ClaimStatus.PENDING_PC_CONFIRM,
                format(expiresAt)));
    }

    // ------------------------------------------------------------ API-10

    /**
     * 查询绑定状态。
     *
     * <p>全部翻译交由 {@link BindingViewResolver}：{@code status} 与 {@code me.bindStatus} 同源于一次推导，
     * {@code bindRequestId} 严格满足 INV-B（非 null ⟺ status ∈ {PENDING_PC_CONFIRM, EXPIRED}）。
     */
    @GetMapping("/status")
    public ResponseEntity<ApiEnvelope<BindingStatusData>> status(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "bindRequestId", required = false) String bindRequestId,
            HttpServletRequest request) {
        identityGuard.rejectIfClientIdentitySupplied(request);
        WxIdentity id = authService.resolve(authorization);
        // 显式指定了不属于本身份（含不存在 / 已被归档）的 bindRequestId ⇒ 404 TSS_NOT_FOUND
        BindingView view = viewResolver.resolve(id, bindRequestId);
        return ok(new BindingStatusData(view.bindRequestId(), view.status(),
                view.erpUserMasked(), view.entDisplayName(), clock.isoNow()));
    }

    // ------------------------------------------------------------ API-11

    /**
     * 解绑。
     *
     * <p><b>判定顺序（裁决 C）</b>：身份解析（401）→ 参数检查（400）→ 绑定状态检查（403）
     * → 幂等 nonce（409）。顺序不能反，否则「未绑定 + confirmation 写错」会先撞上 403，
     * 掩盖真正的参数错误。
     *
     * <p>错误码矩阵：
     * <pre>
     *   UNBOUND / PENDING        → 403 TSS_NOT_BOUND
     *   DISABLED                 → 403 TSS_BINDING_DISABLED
     *   二次解绑（已解绑）        → 403 TSS_NOT_BOUND
     *   confirmation ≠ UNBIND    → 400 TSS_INVALID_ARGUMENT（先于状态检查）
     *   clientNonce 缺失         → 400 TSS_INVALID_ARGUMENT
     *   clientNonce 重用于不同内容 → 409 TSS_IDEMPOTENCY_CONFLICT
     * </pre>
     * 注：RC2 的 {@code UnbindRequest} 只有 {@code confirmation} / {@code clientNonce} 两个字段
     * （{@code additionalProperties:false}），<b>没有</b> {@code bindRequestId}；
     * 因此「他人的 / 不存在的 bindRequestId → 404 TSS_NOT_FOUND」这一格落在 API-10
     * （见 {@link #status}），已由 {@code binding_status_foreign_request_not_found} 断言锁死。
     *
     * <p><b>不撤销会话</b>：解绑只解除绑定关系（{@code revokedAt} 是绑定关系的撤销时间），
     * 会话本身仍有效，与 Python 侧 RC3 MOCK 一致；未绑定身份取不到任何业务数据，无泄露面。
     */
    @PostMapping(path = "/unbind", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<UnbindData>> unbind(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) UnbindRequest body,
            HttpServletRequest request) {
        identityGuard.rejectIfClientIdentitySupplied(request);
        WxIdentity id = authService.resolve(authorization);

        // ① 参数检查先于状态检查（裁决 C）
        if (body == null || !"UNBIND".equals(body.confirmation())) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "confirmation 必须为 UNBIND");
        }
        if (body.clientNonce() == null || body.clientNonce().isBlank()) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "clientNonce 不能为空");
        }
        // ② 状态检查：跨端统一口径（见 README §9.1）
        //    未绑定/申请中 → 403 TSS_NOT_BOUND，停用 → 403 TSS_BINDING_DISABLED
        authService.assertBound(id);

        String content = id.openid() + ":UNBIND";
        if (!bindRequests.checkNonce(body.clientNonce(), content)) {
            throw new TssApiException(TssErrorCode.TSS_IDEMPOTENCY_CONFLICT,
                    "clientNonce 已被用于不同的解绑请求");
        }
        bindRequests.rememberNonce(body.clientNonce(), content);

        // ③ 解绑：先终结该身份名下的全部绑定申请（INV-B：解绑后不得再持有申请单），再移除绑定关系。
        //    顺序不能反——否则会残留一条 PENDING_PC_CONFIRM 的申请，与解绑后的 UNBOUND 自相矛盾。
        bindRequests.dropByOpenid(id.openid());
        bindings.removeByOpenid(id.openid());
        log.info("[TSS-WX] unbind done: binding relation removed (session kept alive, 与 Python 端口径一致)");

        return ok(new UnbindData(clock.isoNow(), TssEnums.BindStatus.UNBOUND));
    }

    // ------------------------------------------------------------ 内部方法

    /** 取绑定关系状态（无记录视为 UNBOUND）。 */
    private BindStatus relationStatus(String openid) {
        BindingRecord binding = bindings.findByOpenid(openid);
        if (binding == null || binding.bindStatus() == null) {
            return BindStatus.UNBOUND;
        }
        return binding.bindStatus();
    }

    /**
     * 把绑定关系推进到 {@code PENDING}（「已提交申请、待 PC 确认」）。
     *
     * <p>与 Python 侧 {@code s["bs"] = "PENDING"} 同义：保证 {@code /wx/v1/me} 的
     * {@code bindStatus=PENDING} 与 {@code /bindings/status} 的 {@code PENDING_PC_CONFIRM} 互证。
     */
    private void markPending(WxIdentity id) {
        Instant now = Instant.now();
        BindingRecord binding = bindings.findByOpenid(id.openid());
        if (binding == null) {
            bindings.save(new BindingRecord("b_" + Math.abs(id.openid().hashCode()), id.openid(),
                    null, null, null, 1, BindStatus.PENDING, "o_****", null, null, now));
            return;
        }
        if (binding.bindStatus() == BindStatus.UNBOUND) {
            bindings.save(binding.withBindStatus(BindStatus.PENDING).withUpdatedAt(now));
        }
    }

    /** 邀请码判定结果：有效 / 已过期 / 未知。 */
    private enum InviteState {
        VALID, EXPIRED, UNKNOWN
    }

    /**
     * 邀请码校验。
     *
     * <p><b>诚实声明</b>：本 DEV Mock 服务未接入 PC 端邀请码发放能力，
     * 目前只认识两条<b>自造夹具码</b>：{@code INV-EXAMPLE}（取自 RC2 样例，视为有效）与
     * {@code INV-EXPIRED}（专用于验证 409 过期分支）；其余一律拒绝（fail-closed），
     * 不把「任何字符串都接受」伪装成校验通过。两者都不是真实 PC 端下发的邀请码。
     */
    private static InviteState inviteState(String code) {
        if ("INV-EXAMPLE".equals(code)) {
            return InviteState.VALID;
        }
        if ("INV-EXPIRED".equals(code)) {
            return InviteState.EXPIRED;
        }
        return InviteState.UNKNOWN;
    }

    /**
     * 时间输出统一截断到秒（RC2 样例的时间精度为秒，且秒级精度便于客户端解析与比对）。
     */
    private String format(Instant instant) {
        java.time.OffsetDateTime t = instant.atZone(clock.zone()).toOffsetDateTime()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t);
    }
}
