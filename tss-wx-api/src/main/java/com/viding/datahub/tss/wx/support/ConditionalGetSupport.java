package com.viding.datahub.tss.wx.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 详情条件 GET 支持（F4）。
 *
 * <p>本协议层<b>已实现</b> ETag / Last-Modified：命中则返回 {@code 304} 且无响应体。
 * （若未实现的后端必须如实返回 200，不得假装具备缓存能力。）
 *
 * <p>ETag 由 {@code approvalId|taskNum|dataVersion} 派生，弱校验子（{@code W/"..."}）；
 * 工作流数据版本变化时 ETag 必然变化。
 */
@Component
public class ConditionalGetSupport {

    private static final Logger log = LoggerFactory.getLogger(ConditionalGetSupport.class);

    /** 弱 ETag：内容派生，数据版本变了就一定变。 */
    public String weakEtag(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(p == null ? "" : p).append('|');
        }
        return "W/\"" + sha1Hex(sb.toString()) + "\"";
    }

    /** 把 ISO-8601 时间串转成 HTTP 日期格式（RFC 1123，UTC）。 */
    public String toHttpDate(String isoDateTime) {
        OffsetDateTime t = parse(isoDateTime);
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(t.atZoneSameInstant(ZoneOffset.UTC));
    }

    /**
     * 判断是否命中条件 GET。
     *
     * @param ifNoneMatch     {@code If-None-Match} 头
     * @param ifModifiedSince {@code If-Modified-Since} 头
     * @param etag            当前 ETag
     * @param lastModifiedHttpDate 当前 Last-Modified（RFC 1123）
     */
    public boolean notModified(String ifNoneMatch, String ifModifiedSince,
                               String etag, String lastModifiedHttpDate) {
        if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
            String client = ifNoneMatch.trim();
            // 弱比较：去掉 W/ 前缀后比对不透明部分
            String a = stripWeak(client);
            String b = stripWeak(etag);
            if (a.equals(b)) {
                return true;
            }
            if ("*".equals(a)) {
                return true;
            }
        }
        if ((ifNoneMatch == null || ifNoneMatch.isBlank())
                && ifModifiedSince != null && !ifModifiedSince.isBlank()) {
            try {
                OffsetDateTime since = OffsetDateTime.parse(ifModifiedSince.trim(),
                        DateTimeFormatter.RFC_1123_DATE_TIME);
                OffsetDateTime last = OffsetDateTime.parse(lastModifiedHttpDate,
                        DateTimeFormatter.RFC_1123_DATE_TIME);
                return !last.isAfter(since);
            } catch (DateTimeParseException e) {
                log.warn("[TSS-WX] 无法解析 If-Modified-Since: {}", ifModifiedSince);
                return false;
            }
        }
        return false;
    }

    private static String stripWeak(String raw) {
        String v = raw.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        return v;
    }

    private static OffsetDateTime parse(String iso) {
        if (iso == null || iso.isBlank()) {
            return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        }
        try {
            return OffsetDateTime.parse(iso.trim()).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        } catch (DateTimeParseException e) {
            log.warn("[TSS-WX] 无法解析时间串 '{}'，回退为当前时刻", iso);
            return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        }
    }

    private static String sha1Hex(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(d.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /** 大小写不敏感的包含判断（keyword 搜索用）。 */
    public static boolean contains(String haystack, String needle) {
        if (haystack == null || needle == null) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
