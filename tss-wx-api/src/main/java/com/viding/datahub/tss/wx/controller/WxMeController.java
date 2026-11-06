package com.viding.datahub.tss.wx.controller;

import com.viding.datahub.tss.wx.auth.BindingView;
import com.viding.datahub.tss.wx.auth.BindingViewResolver;
import com.viding.datahub.tss.wx.auth.ClientIdentityGuard;
import com.viding.datahub.tss.wx.auth.WxAuthService;
import com.viding.datahub.tss.wx.auth.WxIdentity;
import com.viding.datahub.tss.wx.config.TssWxClock;
import com.viding.datahub.tss.wx.dto.MeData;
import com.viding.datahub.tss.wx.envelope.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API-02 {@code GET /wx/v1/me} 会话本人。
 *
 * <p>{@code userId} / {@code entId} 一律由服务端会话推导，不接受客户端提交；
 * 未绑定时为 null（不是错误——绑定流程需要这个状态）。
 *
 * <p><b>裁决 A</b>：{@code bindStatus} 由 {@link BindingViewResolver} 统一翻译，
 * 与 API-10 的 {@code status} 一一对应：
 * <pre>
 *   UNBOUND↔UNBOUND、PENDING_PC_CONFIRM↔PENDING、BOUND↔BOUND、
 *   EXPIRED↔UNBOUND、REJECTED↔UNBOUND、DISABLED↔DISABLED
 * </pre>
 * 因此「已提交申请、待 PC 确认」时 {@code bindStatus=PENDING}——
 * 若仍报 {@code UNBOUND}，{@code PENDING} 这个枚举值将永远取不到（死值），
 * 且与 {@code /bindings/status} 的 {@code PENDING_PC_CONFIRM} 互相打架。
 *
 * <p>{@code enterpriseSwitchEnabled} 恒为 false：V1.0 单当前企业，不展示企业切换入口（企业上下文）。
 */
@RestController
@RequestMapping(path = "/wx/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class WxMeController extends ApiResponseSupport {

    private final WxAuthService authService;
    private final BindingViewResolver viewResolver;
    private final ClientIdentityGuard identityGuard;

    public WxMeController(TssWxClock clock, WxAuthService authService,
                          BindingViewResolver viewResolver, ClientIdentityGuard identityGuard) {
        super(clock);
        this.authService = authService;
        this.viewResolver = viewResolver;
        this.identityGuard = identityGuard;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiEnvelope<MeData>> me(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  HttpServletRequest request) {
        identityGuard.rejectIfClientIdentitySupplied(request);
        WxIdentity id = authService.resolve(authorization);
        // 与 API-10 共用同一份推进后的状态：无论先调哪个端点，观测结果一致
        BindingView view = viewResolver.resolve(id);
        boolean bound = view.bound();
        MeData data = new MeData(
                bound ? id.userId() : null,
                bound ? id.entId() : null,
                bound ? id.displayName() : null,
                view.meBindStatus(),
                bound ? id.bindingVersion() : null,
                false);
        return ok(data);
    }
}
