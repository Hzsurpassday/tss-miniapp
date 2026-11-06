package com.viding.datahub.tss.wx.controller;

import com.viding.datahub.tss.wx.auth.MockTokenIssuer;
import com.viding.datahub.tss.wx.auth.MockTokenRequest;
import com.viding.datahub.tss.wx.auth.MockTokenResponse;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV 专用 Mock 令牌颁发端点（B1）——<b>不属于 RC2 的 13 个端点</b>。
 *
 * <p>刻意放在 {@code /dev-support/**} 而不是 {@code /wx/v1/**}：
 * 避免把非契约端点混入 RC2 命名空间，也避免前端误以为它是正式登录能力。
 *
 * <p>四重门禁（见 {@link com.viding.datahub.tss.wx.auth.MockTokenGate}）：
 * dev profile + 显式开关 + 运行环境自证 DEV_LOCAL + 来源为本机回环。
 * 装配层面还叠加 {@link com.viding.datahub.tss.wx.auth.DevOnlyCondition}：
 * 条件不满足时本 Controller <b>根本不会被注册</b>，端点表现为 404。
 */
@RestController
@RequestMapping(path = "/dev-support/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Conditional(com.viding.datahub.tss.wx.auth.DevOnlyCondition.class)
public class DevMockTokenController {

    private final MockTokenIssuer issuer;

    public DevMockTokenController(MockTokenIssuer issuer) {
        this.issuer = issuer;
    }

    @PostMapping(path = "/mock-token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MockTokenResponse issue(@RequestBody(required = false) MockTokenRequest body,
                                   HttpServletRequest request) {
        if (body == null) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "请求体不能为空");
        }
        return issuer.issue(body, request == null ? null : request.getRemoteAddr());
    }
}
