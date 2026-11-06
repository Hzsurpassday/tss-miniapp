package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * RC2 {@code ClaimRequest}（API-09 请求体）。
 *
 * <p>{@code claimNonce} 为客户端一次性幂等随机值：相同 nonce 用于不同 inviteCode 视为冲突。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ClaimRequest(
        String inviteCode,
        String claimNonce
) {
}
