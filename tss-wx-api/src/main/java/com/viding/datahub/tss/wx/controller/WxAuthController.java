package com.viding.datahub.tss.wx.controller;

import com.viding.datahub.tss.wx.auth.ClientIdentityGuard;
import com.viding.datahub.tss.wx.auth.WxLoginService;
import com.viding.datahub.tss.wx.config.TssWxClock;
import com.viding.datahub.tss.wx.dto.LoginData;
import com.viding.datahub.tss.wx.dto.LoginRequest;
import com.viding.datahub.tss.wx.envelope.ApiEnvelope;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API-01 {@code POST /wx/v1/auth/login} 匿名登录。
 *
 * <p>V1.0 只读/绑定接口，不触发 ERP 审批写回。
 * 未配置微信 appid/secret 时返回 {@code 503 TSS_NOT_CONFIGURED}（fail-closed，不做占位降级）。
 */
@RestController
@RequestMapping(path = "/wx/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class WxAuthController extends ApiResponseSupport {

    private final WxLoginService loginService;
    private final ClientIdentityGuard identityGuard;

    public WxAuthController(TssWxClock clock, WxLoginService loginService, ClientIdentityGuard identityGuard) {
        super(clock);
        this.loginService = loginService;
        this.identityGuard = identityGuard;
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<LoginData>> login(@RequestBody(required = false) LoginRequest body,
                                                        HttpServletRequest request) {
        identityGuard.rejectIfClientIdentitySupplied(request);
        if (body == null || body.code() == null || body.code().isBlank()) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "请求体缺少 code");
        }
        return ok(loginService.login(body.code()));
    }
}
