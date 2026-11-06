package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * RC2 {@code LoginRequest}（API-01 请求体）。
 *
 * <p>{@code additionalProperties:false}：这里显式要求「未知字段即失败」，
 * 用于阻止请求输入携带多余字段（兼容规则：服务端用它校验输入）。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record LoginRequest(
        String code
) {
}
