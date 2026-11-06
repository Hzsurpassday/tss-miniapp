package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;

/**
 * 服务端解析后的可信身份。
 *
 * <p><b>本人数据隔离</b>：{@code userId} 与 {@code entId} 一律由服务端会话 + 绑定推导得出，
 * 绝不接受客户端提交的 userId / entId 作为授权依据（企业上下文：V1.0 单当前企业）。
 */
public record WxIdentity(
        String sessionToken,
        String openid,
        String entId,
        String userId,
        String displayName,
        BindStatus bindStatus,
        Integer bindingVersion,
        String erpUserMasked,
        String entDisplayName,
        boolean mockSession
) {

    /** 是否已完成有效绑定（未绑定不得访问业务数据端点）。 */
    public boolean isBound() {
        return bindStatus == BindStatus.BOUND && entId != null && userId != null;
    }
}
