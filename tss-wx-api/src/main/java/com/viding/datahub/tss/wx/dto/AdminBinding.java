package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.AdminBindStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 {@code AdminBinding}（API-12 列表项）。
 *
 * <p>{@code openidMasked} 必须脱敏输出；管理端不得获得可反推的微信 openid 原文。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminBinding(
        String bindingId,
        String userId,
        String entId,
        String openidMasked,
        AdminBindStatus bindStatus,
        int bindingVersion,
        String updatedAt
) {
}
