package com.viding.datahub.tss.wx.controller;

import com.viding.datahub.tss.wx.auth.DevMockSessionStore;
import com.viding.datahub.tss.wx.auth.DevOnlyCondition;
import com.viding.datahub.tss.wx.auth.MockTokenGate;
import com.viding.datahub.tss.wx.auth.WxAuthService;
import com.viding.datahub.tss.wx.auth.WxIdentity;
import com.viding.datahub.tss.wx.auth.WxSessionRecord;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DEV 专用<b>会话构造</b>端点（非 RC2 端点，与 {@link DevBindingFixtureController} 同类）。
 *
 * <p><b>为什么要有这一条</b>：{@code TSS_SESSION_EXPIRED} 在契约里存在、代码里也有分支，
 * 但默认实例下<b>根本没有触发路径</b>——RC2 的 13 个契约端点里 {@code revoke()} 一个调用方都没有
 * （解绑按跨端统一口径不撤销会话，见 {@link WxAuthService#revoke(WxIdentity)} 的 Javadoc），
 * 夹具里也没有过期会话。
 * 结果就是这个码"双方静态对齐、但永远打不出来"，QA 复测验不了，交付出去也是纸面上的码。
 * 本端点把它变成可显式构造的：{@code /expire-session} 与 Python 侧
 * {@code /__mock/expire-session} 对称；{@code /revoke-session} 为 <b>Java 独有 DEV 能力</b>
 * （Python 侧无对应夹具，记为 N/A，不为了对称去给 Python 补一个假 revoke）。
 *
 * <p><b>定性（必须随代码传播，不得改写）</b>：本码属于「<b>需显式构造方可触发</b>」那一组，
 * 与故障注入类错误码同类，**不得写成"已验证"**。默认实例（不调本端点）下它依然打不出来——
 * 这是刻意保持的：不能为了"可测"就让默认行为凭空过期。
 *
 * <p><b>只做构造，不改判定</b>：错误码判定仍在 {@code WxAuthService.assertUsable} 里，
 * 本端点只负责把会话变成"已失效"形态——{@code /expire-session} 把 {@code expiresAt} 推到过去，
 * {@code /revoke-session} 直接走 {@link WxAuthService#revoke(WxIdentity)}（顺带把这条
 * 原本零调用方的会话管理能力变成 DEV 夹具可达）。
 *
 * <p><b>门禁</b>：装配层 {@link DevOnlyCondition}（不成立则本 Controller 根本不注册，表现 404）+
 * 运行时 {@link MockTokenGate} 四重条件，与 Mock 令牌颁发同规格。
 */
@RestController
@RequestMapping(path = "/dev-support/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Conditional(DevOnlyCondition.class)
public class DevSessionFixtureController {

    private static final Logger log = LoggerFactory.getLogger(DevSessionFixtureController.class);

    private final DevMockSessionStore mockSessions;
    private final WxAuthService authService;
    private final MockTokenGate gate;
    private final TssWxClock clock;

    public DevSessionFixtureController(DevMockSessionStore mockSessions, WxAuthService authService,
                                       MockTokenGate gate, TssWxClock clock) {
        this.mockSessions = mockSessions;
        this.authService = authService;
        this.gate = gate;
        this.clock = clock;
    }

    /**
     * 把会话构造成<b>已过期</b>：{@code expiresAt} 推到当前时刻之前（保留 {@code revokedAt} 原值）。
     *
     * @param body    {@code {"sessionToken":"..."}}
     * @param request 用于门禁的来源地址判定
     * @return 构造结果 {@code {action, openidMasked, expired, revoked, at}}
     */
    @PostMapping(path = "/expire-session", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> expire(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        WxSessionRecord rec = requireRecord(body, request);
        Instant now = Instant.now();
        mockSessions.save(new WxSessionRecord(rec.token(), rec.openid(), rec.unionid(), rec.appid(),
                rec.createdAt(), now.minusSeconds(1), rec.revokedAt(), rec.mock()));
        log.warn("[TSS-WX][DEV-ONLY] 会话夹具: action=EXPIRE (非契约端点，禁止用于 TEST/PROD)");
        return result("EXPIRE", mockSessions.findIncludingExpired(rec.token()), now);
    }

    /**
     * 把会话构造成<b>已撤销</b>：走 {@link WxAuthService#revoke(WxIdentity)}，与"撤销"这条
     * 真实代码路径完全同源（不是在本类里另写一份置位逻辑）。
     *
     * @param body    {@code {"sessionToken":"..."}}
     * @param request 用于门禁的来源地址判定
     * @return 构造结果 {@code {action, openidMasked, expired, revoked, at}}
     */
    @PostMapping(path = "/revoke-session", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> revoke(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        WxSessionRecord rec = requireRecord(body, request);
        Instant now = Instant.now();
        // 只为了让 revoke() 走完整调用链：该方法仅读取 sessionToken 与 mockSession 两个字段，
        // 其余身份字段在此不参与判定，故按记录原样带上 openid、绑定信息留空。
        authService.revoke(new WxIdentity(rec.token(), rec.openid(), null, null, null,
                BindStatus.UNBOUND, null, null, null, rec.mock()));
        log.warn("[TSS-WX][DEV-ONLY] 会话夹具: action=REVOKE (非契约端点，禁止用于 TEST/PROD)");
        return result("REVOKE", mockSessions.findIncludingExpired(rec.token()), now);
    }

    /**
     * 门禁 + 参数校验 + 取会话记录（含已过期），取不到即抛错。
     *
     * @param body    请求体，必须含非空的 {@code sessionToken}
     * @param request 当前请求（取来源地址做门禁判定）
     * @return 命中的会话记录，非 {@code null}
     */
    private WxSessionRecord requireRecord(Map<String, Object> body, HttpServletRequest request) {
        gate.assertIssuable(request == null ? null : request.getRemoteAddr());
        if (body == null) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "请求体不能为空");
        }
        String token = body.get("sessionToken") == null ? "" : String.valueOf(body.get("sessionToken")).trim();
        if (token.isEmpty()) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "sessionToken 不能为空");
        }
        WxSessionRecord rec = mockSessions.findIncludingExpired(token);
        if (rec == null) {
            throw new TssApiException(TssErrorCode.TSS_NOT_FOUND, "会话不存在（仅 DEV Mock 会话可被构造）");
        }
        return rec;
    }

    /**
     * 统一响应体：把构造后的失效形态如实回显，便于 QA 对照断言。
     *
     * @param action 固定为 {@code EXPIRE} / {@code REVOKE}
     * @param rec    构造后的会话记录
     * @param now    判定时刻
     * @return 响应 Map
     */
    private Map<String, Object> result(String action, WxSessionRecord rec, Instant now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("openidMasked", "o_****");
        out.put("expired", rec == null ? false : rec.isExpired(now));
        out.put("revoked", rec == null ? false : rec.isRevoked());
        out.put("at", clock.isoNow());
        return out;
    }
}
