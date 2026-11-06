package com.viding.datahub.tss.wx.controller;

import com.viding.datahub.tss.wx.auth.BindRequestRecord;
import com.viding.datahub.tss.wx.auth.BindRequestStore;
import com.viding.datahub.tss.wx.auth.BindingFixture;
import com.viding.datahub.tss.wx.auth.BindingRecord;
import com.viding.datahub.tss.wx.auth.BindingStore;
import com.viding.datahub.tss.wx.auth.BindingView;
import com.viding.datahub.tss.wx.auth.BindingViewResolver;
import com.viding.datahub.tss.wx.auth.DevOnlyCondition;
import com.viding.datahub.tss.wx.auth.MockIdentity;
import com.viding.datahub.tss.wx.auth.MockTokenGate;
import com.viding.datahub.tss.wx.auth.WxIdentity;
import com.viding.datahub.tss.wx.config.TssWxClock;
import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * DEV 专用绑定夹具端点——<b>不属于 RC2 的 13 个端点</b>，与 Python 侧 {@code ctrl_pc_confirm}
 * 属同一类「控制面」能力：契约明确 PC 确认动作不在小程序接口内，但联调/验收需要可复现的状态。
 *
 * <p>装配与运行时双重门禁（与 Mock 令牌颁发同规格）：
 * <ul>
 *   <li>装配层：{@link DevOnlyCondition} 不成立时本 Controller <b>根本不会被注册</b>（表现为 404）；</li>
 *   <li>运行时：每次调用都要过 {@link MockTokenGate} 的四重条件（dev profile + 显式开关 +
 *       环境自证 DEV_LOCAL + 来源为本机回环）。</li>
 * </ul>
 *
 * <p><b>诚实声明</b>：这些动作在真实系统里由 PC 管理端与 ERP 侧完成，这里只是把它们
 * 「按下快进键」，用于验证 {@code /wx/v1/me} 与 {@code /wx/v1/bindings/status} 的状态翻译是否正确。
 */
@RestController
@RequestMapping(path = "/dev-support/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Conditional(DevOnlyCondition.class)
public class DevBindingFixtureController {

    private static final Logger log = LoggerFactory.getLogger(DevBindingFixtureController.class);

    private static final String DEFAULT_ENT_ID = "E498AU5L35J";
    private static final String DEFAULT_USER_ID = "U4FXG07VRDI";

    private final BindRequestStore bindRequests;
    private final BindingStore bindings;
    private final BindingViewResolver viewResolver;
    private final MockTokenGate gate;
    private final TssWxClock clock;

    public DevBindingFixtureController(BindRequestStore bindRequests, BindingStore bindings,
                                       BindingViewResolver viewResolver, MockTokenGate gate,
                                       TssWxClock clock) {
        this.bindRequests = bindRequests;
        this.bindings = bindings;
        this.viewResolver = viewResolver;
        this.gate = gate;
        this.clock = clock;
    }

    @PostMapping(path = "/binding-fixture", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BindingFixture.Result apply(@RequestBody(required = false) BindingFixture.Request body,
                                       HttpServletRequest request) {
        gate.assertIssuable(request == null ? null : request.getRemoteAddr());
        if (body == null || body.action() == null) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "action 不能为空");
        }
        Instant now = Instant.now();
        String openid = resolveOpenid(body);

        switch (body.action()) {
            case PC_CONFIRM -> pcConfirm(body, openid, now);
            case EXPIRE -> expire(openid, now);
            case REJECT -> reject(openid);
            case DISABLE -> disable(body, openid, now);
        }
        log.warn("[TSS-WX][DEV-ONLY] 绑定夹具: action={} (非契约端点，禁止用于 TEST/PROD)", body.action());

        BindingRecord binding = bindings.findByOpenid(openid);
        WxIdentity identity = new WxIdentity(null, openid,
                binding == null ? null : binding.entId(),
                binding == null ? null : binding.userId(),
                binding == null ? null : binding.displayName(),
                binding == null ? BindStatus.UNBOUND : binding.bindStatus(),
                binding == null ? null : binding.bindingVersion(),
                binding == null ? null : binding.erpUserMasked(),
                binding == null ? null : binding.entDisplayName(),
                true);
        BindingView view = viewResolver.resolve(identity);
        BindRequestRecord remaining = bindRequests.latestRaw(openid);
        return new BindingFixture.Result(body.action(), maskOpenid(openid), view.bindRequestId(),
                view.meBindStatus(), view.status(), remaining == null, clock.isoNow());
    }

    // ------------------------------------------------------------ 各动作

    /** 模拟 PC 端确认通过：关系落成 BOUND，申请单即刻终结（裁决 B）。 */
    private void pcConfirm(BindingFixture.Request body, String openid, Instant now) {
        BindRequestRecord rec = currentRequest(openid, body.bindRequestId());
        if (rec == null) {
            throw new TssApiException(TssErrorCode.TSS_NOT_FOUND, "绑定申请不存在");
        }
        if (rec.status() == com.viding.datahub.tss.wx.dto.TssEnums.BindingStatusValue.EXPIRED
                || rec.isExpired(now)) {
            // 与 Python 侧 ctrl_pc_confirm 同口径：已过期的申请不可再被确认
            throw new TssApiException(TssErrorCode.TSS_BIND_INVITE_EXPIRED, "绑定申请已过期");
        }
        BindingRecord existing = bindings.findByOpenid(openid);
        String entId = firstNonBlank(body.entId(), existing == null ? null : existing.entId(), DEFAULT_ENT_ID);
        String userId = firstNonBlank(body.userId(), existing == null ? null : existing.userId(), DEFAULT_USER_ID);
        int version = existing == null ? 1
                : (existing.bindingVersion() == null ? 1 : existing.bindingVersion() + 1);
        bindings.save(new BindingRecord(
                existing == null ? "b_" + Math.abs(openid.hashCode()) : existing.bindingId(),
                openid, entId, userId, "DEV Mock 用户 " + userId, version, BindStatus.BOUND,
                "o_****", mask(userId), "DEV Mock 企业 " + entId, now));
        // 绑定成功 ⇒ 申请单终结（不是留在库里靠展现层遮掩）
        bindRequests.dropByOpenid(openid);
    }

    /** 让当前申请单立即过期：下一次解析即推进为 EXPIRED（关系若停在 PENDING 则回落 UNBOUND）。 */
    private void expire(String openid, Instant now) {
        BindRequestRecord rec = currentRequest(openid, null);
        if (rec == null) {
            throw new TssApiException(TssErrorCode.TSS_NOT_FOUND, "绑定申请不存在");
        }
        bindRequests.markExpired(rec.bindRequestId(), now);
    }

    /**
     * 让当前申请单被拒：REJECTED 属<b>保留态</b>，不归档、不自灭——
     * {@code bindRequestId} 稳定保留（{@code BindingView.OPEN_FLOW_STATUSES} 含 REJECTED，
     * {@code keepsRequestId()} 为真 ⇒ {@code advance()} 第②步不会把它归档），
     * 连续多次读 {@code /wx/v1/bindings/status} 都稳定返回同一 {@code bindRequestId} + REJECTED，
     * 且可按该 id 回查命中。这样用户才知道自己是被拒了，而不是"申请单凭空消失"。
     */
    private void reject(String openid) {
        BindRequestRecord rec = currentRequest(openid, null);
        if (rec == null) {
            throw new TssApiException(TssErrorCode.TSS_NOT_FOUND, "绑定申请不存在");
        }
        bindRequests.markRejected(rec.bindRequestId());
    }

    /** 把绑定关系置为停用（DISABLED），并终结其名下申请单。 */
    private void disable(BindingFixture.Request body, String openid, Instant now) {
        BindingRecord existing = bindings.findByOpenid(openid);
        String entId = firstNonBlank(body.entId(), existing == null ? null : existing.entId(), DEFAULT_ENT_ID);
        String userId = firstNonBlank(body.userId(), existing == null ? null : existing.userId(), DEFAULT_USER_ID);
        if (existing == null) {
            bindings.save(new BindingRecord("b_" + Math.abs(openid.hashCode()), openid, entId, userId,
                    "DEV Mock 用户 " + userId, 1, BindStatus.DISABLED, "o_****", mask(userId),
                    "DEV Mock 企业 " + entId, now));
        } else {
            bindings.save(existing.withBindStatus(BindStatus.DISABLED).withUpdatedAt(now));
        }
        bindRequests.dropByOpenid(openid);
    }

    // ------------------------------------------------------------ 工具方法

    /** 定位 openid：优先用显式 bindRequestId 反查，否则由 entId+userId 派生。 */
    private String resolveOpenid(BindingFixture.Request body) {
        if (body.bindRequestId() != null && !body.bindRequestId().isBlank()) {
            BindRequestRecord rec = bindRequests.findRaw(body.bindRequestId().trim());
            if (rec == null) {
                throw new TssApiException(TssErrorCode.TSS_NOT_FOUND, "绑定申请不存在");
            }
            return rec.openid();
        }
        String entId = body.entId() == null ? null : body.entId().trim();
        String userId = body.userId() == null ? null : body.userId().trim();
        if (entId == null || entId.isEmpty() || userId == null || userId.isEmpty()) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT,
                    "未指定 bindRequestId 时必须给出 entId 与 userId");
        }
        return MockIdentity.openidFor(entId, userId);
    }

    /** 取该身份当前持有的申请单；显式指定时必须匹配，否则 404。 */
    private BindRequestRecord currentRequest(String openid, String explicitBindRequestId) {
        if (explicitBindRequestId != null && !explicitBindRequestId.isBlank()) {
            BindRequestRecord rec = bindRequests.findRaw(explicitBindRequestId.trim());
            if (rec == null || !openid.equals(rec.openid())) {
                throw new TssApiException(TssErrorCode.TSS_NOT_FOUND, "绑定申请不存在");
            }
            return rec;
        }
        return bindRequests.latestRaw(openid);
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return "";
    }

    /** 脱敏：保留首 2 位与末 3 位，中间以 * 替代。 */
    private static String mask(String raw) {
        if (raw == null || raw.length() <= 5) {
            return raw == null ? null : "****";
        }
        return raw.substring(0, 2) + "****" + raw.substring(raw.length() - 3);
    }

    /** openid 一律不出现在响应里，只回固定掩码（与 AdminTssController 同规格）。 */
    private static String maskOpenid(String openid) {
        return "o_****";
    }
}
