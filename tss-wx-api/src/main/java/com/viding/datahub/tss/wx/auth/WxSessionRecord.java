package com.viding.datahub.tss.wx.auth;

import java.time.Instant;

/**
 * 服务端会话记录。
 *
 * <p>约束：
 * <ul>
 *   <li>openid 只能由服务端 jscode2session 得到（真实会话）或 DEV Mock 派生（mock=true 时），
 *       客户端永不见到 openid；</li>
 *   <li>客户端只持有 {@code sessionToken}；身份解析一律以服务端记录为准，不接受任何字面推断。</li>
 * </ul>
 */
public record WxSessionRecord(
        String token,
        String openid,
        String unionid,
        String appid,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        boolean mock
) {
    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isValid(Instant now) {
        return !isRevoked() && !isExpired(now);
    }
}
