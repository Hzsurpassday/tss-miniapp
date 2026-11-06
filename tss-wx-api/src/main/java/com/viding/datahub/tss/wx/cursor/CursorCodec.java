package com.viding.datahub.tss.wx.cursor;

import com.viding.datahub.tss.wx.config.TssWxProperties;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 不透明签名游标的编解码器（F3）。
 *
 * <p>游标形态：{@code base64url(payload) + "." + base64url(HMAC-SHA256(secret, payload))}
 * <ul>
 *   <li>payload 内含：版本 / userId / entId / 筛选条件哈希 / 稳定排序锚点 / offset / 签发时间；</li>
 *   <li>验签失败、过期、用户或企业不匹配、筛选条件变化 ⇒ {@code TSS_INVALID_CURSOR}(400)；</li>
 *   <li>密钥未配置时进程内随机生成（重启后旧游标失效，属预期行为，并在日志明确提示）。</li>
 * </ul>
 */
@Component
public class CursorCodec {

    private static final Logger log = LoggerFactory.getLogger(CursorCodec.class);
    private static final int VERSION = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] secret;
    private final long ttlSeconds;

    public CursorCodec(TssWxProperties props) {
        String raw = props.getCursor().getSecret();
        if (raw == null || raw.isBlank()) {
            byte[] generated = new byte[32];
            RANDOM.nextBytes(generated);
            this.secret = generated;
            log.warn("[TSS-WX] 未配置 datahub.tss.wx.cursor.secret，已生成进程内随机密钥："
                    + "进程重启后此前签发的分页游标将全部失效（TSS_INVALID_CURSOR），属预期行为。");
        } else {
            this.secret = raw.getBytes(StandardCharsets.UTF_8);
        }
        this.ttlSeconds = props.getCursor().getTtlSeconds();
    }

    /** 生成筛选条件哈希（用于把游标绑定到具体筛选组合）。 */
    public String filterHash(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(p == null ? "∅" : p).append('');
        }
        return sha256Hex(sb.toString());
    }

    /** 编码：offset 为下一页起始下标。 */
    public String encode(PageCursor cursor) {
        String payload = VERSION
                + "|" + nz(cursor.userId())
                + "|" + nz(cursor.entId())
                + "|" + nz(cursor.filterHash())
                + "|" + nz(cursor.anchor())
                + "|" + cursor.offset()
                + "|" + cursor.issuedAtEpoch();
        return b64(payload) + "." + b64(hmac(payload));
    }

    /**
     * 解码并校验。
     *
     * @param raw        客户端传入的游标字符串（可为 null / 空，表示第一页）
     * @param userId     当前服务端解析出的 userId
     * @param entId      当前服务端解析出的 entId
     * @param filterHash 当前筛选组合哈希
     * @param anchor     当前稳定排序锚点
     * @return 解析出的 offset；raw 为空时返回 0
     */
    public int decode(String raw, String userId, String entId, String filterHash, String anchor) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String[] bits = raw.split("\\.", 2);
        if (bits.length != 2) {
            throw invalid("游标格式非法");
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(bits[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid("游标无法解码");
        }
        String expectedSig = b64(hmac(payload));
        if (!constantTimeEquals(expectedSig, bits[1])) {
            throw invalid("游标签名校验失败（可能被篡改）");
        }
        String[] f = payload.split("\\|", -1);
        if (f.length != 7 || !Integer.toString(VERSION).equals(f[0])) {
            throw invalid("游标版本不受支持");
        }
        if (!nz(userId).equals(f[1]) || !nz(entId).equals(f[2])) {
            throw invalid("游标与当前身份不匹配（禁止跨用户/跨企业续页）");
        }
        if (!nz(filterHash).equals(f[3])) {
            throw invalid("筛选条件已变化，必须丢弃旧游标重新翻页");
        }
        if (!nz(anchor).equals(f[4])) {
            throw invalid("排序锚点已变化，必须丢弃旧游标重新翻页");
        }
        int offset;
        long issuedAt;
        try {
            offset = Integer.parseInt(f[5]);
            issuedAt = Long.parseLong(f[6]);
        } catch (NumberFormatException e) {
            throw invalid("游标内容非法");
        }
        if (offset < 0) {
            throw invalid("游标内容非法");
        }
        long ageSeconds = (System.currentTimeMillis() / 1000) - issuedAt;
        if (ageSeconds > ttlSeconds || ageSeconds < -60) {
            throw invalid("游标已过期，请重新翻页");
        }
        return offset;
    }

    private static TssApiException invalid(String reason) {
        return new TssApiException(TssErrorCode.TSS_INVALID_CURSOR, reason);
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
