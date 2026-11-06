package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.config.TssWxProperties;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DEV Mock 会话存储：与真实微信会话存储 {@link WxSessionStore} 完全隔离的独立容器。
 *
 * <p>只有在 {@link DevOnlyCondition} 成立时才会被注册；在 TEST/PROD 或开关关闭时该 Bean 不存在，
 * 因此 {@link WxAuthService} 根本无法解析任何 {@code mock.} 令牌。
 */
@Service
@Conditional(DevOnlyCondition.class)
public class DevMockSessionStore {

    private final long ttlSeconds;
    private final Map<String, WxSessionRecord> byToken = new ConcurrentHashMap<>();

    public DevMockSessionStore(TssWxProperties props) {
        this.ttlSeconds = props.getMockToken().getTtlSeconds();
    }

    public void save(WxSessionRecord record) {
        byToken.put(record.token(), record);
    }

    public WxSessionRecord find(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        WxSessionRecord rec = byToken.get(token);
        if (rec == null) {
            return null;
        }
        if (rec.isExpired(Instant.now())) {
            byToken.remove(token);
            return null;
        }
        return rec;
    }

    /**
     * 按令牌取会话，<b>已过期也返回原记录</b>（不做逐出、不做改写）。
     *
     * <p>为什么需要它：{@link #find} 会把过期会话直接当"不存在"逐出，于是
     * {@link WxAuthService#resolve} 只能给出 401 {@code TSS_UNAUTHORIZED}（"令牌无效"）——
     * 但真实语义是"令牌曾经有效、现在过期了"，应当回 401 {@code TSS_SESSION_EXPIRED}。
     * 二者在客户端的处理完全不同：前者应当重登录，后者应当提示"登录已过期"。
     * 因此鉴权路径走本方法，把过期与否交给 {@code assertUsable} 判定。
     */
    public WxSessionRecord findIncludingExpired(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return byToken.get(token);
    }

    public void revoke(String token) {
        WxSessionRecord rec = byToken.get(token);
        if (rec != null) {
            byToken.put(token, new WxSessionRecord(rec.token(), rec.openid(), rec.unionid(), rec.appid(),
                    rec.createdAt(), rec.expiresAt(), Instant.now(), true));
        }
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }
}
