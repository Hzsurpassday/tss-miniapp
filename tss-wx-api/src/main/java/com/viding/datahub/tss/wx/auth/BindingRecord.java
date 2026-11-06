package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;

import java.time.Instant;

/**
 * 微信身份 ↔ ERP 用户绑定记录。
 *
 * <p>V1.0 企业上下文：单当前企业，{@code entId} 由服务端有效绑定确定；
 * 不接受客户端提交的 entId 作为授权依据。
 *
 * <p>{@code openidMasked} / {@code erpUserMasked} 为管理端展示用的脱敏值，
 * 管理端接口不得输出可反推的原文。
 *
 * <p>{@code bindStatus} 既表达绑定关系（{@code UNBOUND} / {@code BOUND} / {@code DISABLED}），
 * 也表达「已提交申请、待 PC 确认」这一中间态（{@code PENDING}）；
 * 由 {@link BindingViewResolver} 统一翻译成 API-10 的 {@code status}，端点不得各自翻译。
 */
public record BindingRecord(
        String bindingId,
        String openid,
        String entId,
        String userId,
        String displayName,
        Integer bindingVersion,
        BindStatus bindStatus,
        String openidMasked,
        String erpUserMasked,
        String entDisplayName,
        Instant updatedAt
) {

    /** 返回绑定状态变更后的副本（其余字段不变）。 */
    public BindingRecord withBindStatus(BindStatus newStatus) {
        return new BindingRecord(bindingId, openid, entId, userId, displayName, bindingVersion,
                newStatus, openidMasked, erpUserMasked, entDisplayName, updatedAt);
    }

    /** 返回更新时间变更后的副本。 */
    public BindingRecord withUpdatedAt(Instant newUpdatedAt) {
        return new BindingRecord(bindingId, openid, entId, userId, displayName, bindingVersion,
                bindStatus, openidMasked, erpUserMasked, entDisplayName, newUpdatedAt);
    }
}
