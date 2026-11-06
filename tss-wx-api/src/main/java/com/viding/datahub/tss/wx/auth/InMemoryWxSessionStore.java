package com.viding.datahub.tss.wx.auth;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 真实微信会话的内存实现（进程内有效，重启即失效）。 */
@Service
public class InMemoryWxSessionStore implements WxSessionStore {

    private final Map<String, WxSessionRecord> byToken = new ConcurrentHashMap<>();

    @Override
    public void save(WxSessionRecord record) {
        byToken.put(record.token(), record);
    }

    @Override
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

    @Override
    public void revoke(String token, String reason) {
        WxSessionRecord rec = byToken.get(token);
        if (rec != null) {
            byToken.put(token, new WxSessionRecord(rec.token(), rec.openid(), rec.unionid(), rec.appid(),
                    rec.createdAt(), rec.expiresAt(), Instant.now(), rec.mock()));
        }
    }

    @Override
    public void revokeByOpenid(String openid, String reason) {
        revokeByOpenidExcept(openid, null, reason);
    }

    @Override
    public void revokeByOpenidExcept(String openid, String keepToken, String reason) {
        Instant now = Instant.now();
        byToken.forEach((token, rec) -> {
            if (openid != null && openid.equals(rec.openid()) && !token.equals(keepToken)) {
                byToken.put(token, new WxSessionRecord(rec.token(), rec.openid(), rec.unionid(), rec.appid(),
                        rec.createdAt(), rec.expiresAt(), now, rec.mock()));
            }
        });
    }

    @Override
    public List<WxSessionRecord> all() {
        return new ArrayList<>(byToken.values());
    }
}
