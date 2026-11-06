package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * RC2 {@code UnbindRequest}（API-11 请求体）。
 *
 * <p>{@code confirmation} 必须为字面量 {@code UNBIND}，防止误触与 CSRF 式重放。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UnbindRequest(
        String confirmation,
        String clientNonce
) {
}
