package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.config.TssWxProperties;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 微信 jscode2session 客户端（真实身份来源）。
 *
 * <p>真实实现走微信官方接口：
 * {@code GET https://api.weixin.qq.com/sns/jscode2session?appid=&secret=&js_code=&grant_type=authorization_code}
 * 只有返回中存在非空 openid 才视为成功；其余一律拒绝（fail-closed）。
 *
 * <p><b>安全红线</b>：未配置 appid/secret 时不存在可用实现，默认 Bean 直接抛
 * {@code TSS_NOT_CONFIGURED}（503），绝不退化成「把 token 当 openid」的占位逻辑。
 * 这与 DEV Mock 令牌颁发器完全隔离：Mock 令牌走 {@link DevMockSessionStore}，永不经过本接口。
 */
public interface TssWxWechatClient {

    Code2SessionResult code2session(String code);

    record Code2SessionResult(boolean ok, String openid, String unionid, String sessionKey,
                              String errcode, String errmsg) {
        public static Code2SessionResult ok(String openid, String unionid, String sessionKey) {
            return new Code2SessionResult(true, openid, unionid, sessionKey, null, null);
        }

        public static Code2SessionResult fail(String errcode, String errmsg) {
            return new Code2SessionResult(false, null, null, null, errcode, errmsg);
        }
    }

    /** 未配置微信参数时的默认实现：fail-closed。 */
    class NotConfiguredWechatClient implements TssWxWechatClient {
        private static final Logger log = LoggerFactory.getLogger(NotConfiguredWechatClient.class);

        @Override
        public Code2SessionResult code2session(String code) {
            log.warn("[TSS-WX][evidence=not-configured] wechat code2session unavailable; fail-closed");
            throw new TssApiException(TssErrorCode.TSS_NOT_CONFIGURED,
                    "微信 appid/secret 未配置，真实微信登录不可用（不做任何占位降级）");
        }
    }

    /** 真实 HTTP 实现。 */
    class HttpWechatClient implements TssWxWechatClient {
        private static final Logger log = LoggerFactory.getLogger(HttpWechatClient.class);

        private final TssWxProperties props;

        public HttpWechatClient(TssWxProperties props) {
            this.props = props;
        }

        @Override
        public Code2SessionResult code2session(String code) {
            if (code == null || code.isBlank()) {
                throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "wx.login code 为空");
            }
            String url = props.getWechat().getCode2SessionUrl()
                    + "?appid=" + enc(props.getWechat().getAppid())
                    + "&secret=" + enc(props.getWechat().getSecret())
                    + "&js_code=" + enc(code)
                    + "&grant_type=authorization_code";
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(props.getWechat().getTimeoutMs())).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMillis(props.getWechat().getTimeoutMs()))
                        .GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                String body = resp.body() == null ? "" : resp.body();
                String openid = extract(body, "openid");
                if (openid == null || openid.isBlank()) {
                    String errcode = extract(body, "errcode");
                    log.warn("[TSS-WX] wechat code2session rejected: errcode={} errmsg={}",
                            errcode, extract(body, "errmsg"));
                    return Code2SessionResult.fail(errcode == null ? "UNKNOWN" : errcode, extract(body, "errmsg"));
                }
                return Code2SessionResult.ok(openid, extract(body, "unionid"), extract(body, "session_key"));
            } catch (TssApiException e) {
                throw e;
            } catch (Exception e) {
                // 网络/解析异常一律视为「登录不可用」，绝不降级为「假设登录成功」
                throw new TssApiException(TssErrorCode.TSS_WECHAT_UNAVAILABLE,
                        "微信 code2session 调用失败: " + e.getClass().getSimpleName());
            }
        }

        private static String enc(String s) {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        }

        /** 极简 JSON 字符串字段提取（避免为一个字段引入完整 JSON 依赖路径差异）。 */
        private static String extract(String body, String key) {
            String needle = "\"" + key + "\"";
            int i = body.indexOf(needle);
            if (i < 0) {
                return null;
            }
            int colon = body.indexOf(':', i + needle.length());
            if (colon < 0) {
                return null;
            }
            int start = body.indexOf('"', colon);
            if (start < 0) {
                return null;
            }
            int end = body.indexOf('"', start + 1);
            if (end < 0) {
                return null;
            }
            return body.substring(start + 1, end);
        }
    }
}
