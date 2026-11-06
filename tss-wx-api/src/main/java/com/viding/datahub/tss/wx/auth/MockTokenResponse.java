package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DEV Mock 令牌颁发响应（<b>不属于 RC2 契约</b>，不使用 RC2 包络）。
 *
 * <p>{@code devOnly=true} 与 {@code warning} 是刻意保留的显式标记：任何拿到该响应的调用方都无法
 * 把它误认为真实微信登录的结果。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MockTokenResponse(
        String sessionToken,
        int expiresIn,
        BindStatus bindStatus,
        String entId,
        String userId,
        boolean devOnly,
        String warning
) {
}
