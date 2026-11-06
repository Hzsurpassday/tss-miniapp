package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.config.TssWxProperties;
import com.viding.datahub.tss.wx.dto.LoginData;
import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * API-01 真实微信登录。
 *
 * <p>链路：{@code wx.login code} → 服务端 jscode2session → openid（服务端唯一来源）
 * → 服务端签发随机不透明 {@code sessionToken} → 客户端只持有 token。
 *
 * <p>fail-closed：未配置 appid/secret 时抛 {@code TSS_NOT_CONFIGURED}(503)；
 * 微信拒绝时抛 {@code TSS_UNAUTHORIZED}(401)；网络失败抛 {@code TSS_WECHAT_UNAVAILABLE}(503)。
 * 与 DEV Mock 令牌链路完全隔离：本服务从不签发以 {@code mock.} 开头的令牌。
 */
@Service
public class WxLoginService {

    private static final Logger log = LoggerFactory.getLogger(WxLoginService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TssWxWechatClient wechatClient;
    private final WxSessionStore sessions;
    private final BindingStore bindings;
    private final TssWxProperties props;

    public WxLoginService(TssWxWechatClient wechatClient,
                          WxSessionStore sessions,
                          BindingStore bindings,
                          TssWxProperties props) {
        this.wechatClient = wechatClient;
        this.sessions = sessions;
        this.bindings = bindings;
        this.props = props;
    }

    /** 真实微信登录：返回会话令牌与绑定状态。 */
    public LoginData login(String code) {
        if (code == null || code.isBlank()) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "code 不能为空");
        }
        if (!props.getWechat().isUsable()) {
            throw new TssApiException(TssErrorCode.TSS_NOT_CONFIGURED,
                    "微信登录未配置（需要 enabled=true 且 appid/secret 齐备），真实登录不可用");
        }
        TssWxWechatClient.Code2SessionResult r = wechatClient.code2session(code);
        if (!r.ok() || r.openid() == null || r.openid().isBlank()) {
            log.warn("[TSS-WX] wx login rejected: errcode={} errmsg={}", r.errcode(), r.errmsg());
            throw new TssApiException(TssErrorCode.TSS_UNAUTHORIZED,
                    "微信登录被拒绝: " + (r.errcode() == null ? "UNKNOWN" : r.errcode()));
        }
        String token = newToken();
        long ttl = props.getWechat().getSessionTtlSeconds();
        Instant expiresAt = Instant.now().plusSeconds(ttl);
        sessions.save(new WxSessionRecord(token, r.openid(), r.unionid(), props.getWechat().getAppid(),
                Instant.now(), expiresAt, null, false));

        BindingRecord binding = bindings.findByOpenid(r.openid());
        BindStatus bindStatus = binding == null ? BindStatus.UNBOUND : binding.bindStatus();
        log.info("[TSS-WX] wx session issued: appid={} ttl={}s bindStatus={}",
                props.getWechat().getAppid(), ttl, bindStatus);
        return new LoginData(token, (int) Math.max(1, ttl), bindStatus, bindStatus != BindStatus.BOUND);
    }

    /** 服务端签发的随机不透明令牌（64 位 hex；与 {@code mock.} 前缀的 DEV 令牌形态互斥）。 */
    static String newToken() {
        String raw = UUID.randomUUID() + ":" + RANDOM.nextLong() + ":" + Instant.now().toEpochMilli();
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
