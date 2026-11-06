package com.viding.datahub.tss.wx.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * DEV Mock 身份派生（仅用于 DEV Mock 会话，与真实微信 openid 无任何关系）。
 *
 * <p>由 {@code entId + userId} 稳定派生 openid，便于：
 * <ul>
 *   <li>重复颁发同一身份的 Mock 令牌时复用同一条绑定记录；</li>
 *   <li>DEV 预置夹具与 Mock 令牌指向同一身份，使契约测试的响应与 RC2 样例一致。</li>
 * </ul>
 * 隔离性由 {@link WxAuthService#MOCK_TOKEN_PREFIX} 与 {@link DevMockSessionStore} 保证，
 * 不依赖本类的命名。
 */
public final class MockIdentity {

    private MockIdentity() {
    }

    /** 由企业 + 用户稳定派生一个 DEV Mock openid。 */
    public static String openidFor(String entId, String userId) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] h = d.digest((entId + ":" + userId).getBytes(StandardCharsets.UTF_8));
            return "o_mock_" + HexFormat.of().formatHex(h).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
