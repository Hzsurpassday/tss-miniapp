package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 {@code MeData}（API-02 响应 data）。
 *
 * <p>{@code userId} / {@code entId} 一律由服务端可信会话推导：未绑定时为 null，
 * 绝不接受客户端提交的 userId / entId 作为授权依据（企业上下文：V1.0 单当前企业）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MeData(
        String userId,
        String entId,
        String displayName,
        BindStatus bindStatus,
        Integer bindingVersion,
        boolean enterpriseSwitchEnabled
) {
}
