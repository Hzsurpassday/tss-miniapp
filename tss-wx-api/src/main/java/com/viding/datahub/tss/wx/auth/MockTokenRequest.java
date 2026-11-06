package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DEV Mock 令牌颁发请求（<b>不属于 RC2 契约</b>，仅 DEV 联调支撑端点使用）。
 *
 * <p>请求体字段刻意保持最小：{@code entId} / {@code userId} 由联调方显式指定，
 * 因为该端点的作用就是「在本机伪造一个已绑定身份」；它的存在范围被 {@link MockTokenGate} 严格限制在 dev。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MockTokenRequest(
        String entId,
        String userId,
        BindStatus bindStatus,
        String displayName
) {
}
