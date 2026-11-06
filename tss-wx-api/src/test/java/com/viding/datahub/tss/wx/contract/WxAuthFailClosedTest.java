package com.viding.datahub.tss.wx.contract;

import com.viding.datahub.tss.wx.auth.MockTokenIssuer;
import com.viding.datahub.tss.wx.controller.DevMockTokenController;
import com.viding.datahub.tss.wx.support.TestHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实能力未配置时的 fail-closed 验证（B3 / 企业上下文 / 微信登录红线）。
 *
 * <p>本测试刻意<b>不</b>启用微信凭据，也<b>不</b>启用 Mock 令牌颁发器，用以证明：
 * <ul>
 *   <li>API-01 在未配置 appid/secret 时返回 {@code 503 TSS_NOT_CONFIGURED}，
 *       绝不退化成「把 token 当 openid」的占位登录；</li>
 *   <li>DEV Mock 令牌端点在开关关闭时<b>根本不存在</b>（404），而不是「存在但校验失败」。</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=dev",
                "datahub.tss.wx.wechat.enabled=false",
                "datahub.tss.wx.mock-token.enabled=false"
        })
@DisplayName("微信登录与 Mock 令牌的 fail-closed 验证")
class WxAuthFailClosedTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private ApplicationContext context;

    private TestHttp http;

    @BeforeEach
    void setUp() {
        http = new TestHttp(port);
    }

    @Test
    @DisplayName("API-01 未配置微信：503 TSS_NOT_CONFIGURED（不做占位降级）")
    void api01_wechat_not_configured() {
        TestHttp.Resp resp = http.post("/wx/v1/auth/login", "{\"code\":\"ANY_CODE\"}", null);
        assertEquals(503, resp.status());
        JsonNode root = parse(resp.body());
        assertEquals("TSS_NOT_CONFIGURED", root.get("code").asString());
        assertTrue(root.get("data").isNull());
        assertFalse("SUCCESS".equals(root.get("code").asString()), "未配置微信时绝不能返回 SUCCESS");
    }

    @Test
    @DisplayName("Mock 令牌端点在开关关闭时不存在：404（Bean 未注册，而非校验失败）")
    void mock_token_endpoint_absent() {
        assertEquals(0, context.getBeanNamesForType(MockTokenIssuer.class).length,
                "开关关闭时 MockTokenIssuer 不应被注册");
        assertEquals(0, context.getBeanNamesForType(DevMockTokenController.class).length,
                "开关关闭时 DevMockTokenController 不应被注册");
        TestHttp.Resp resp = http.post("/dev-support/v1/mock-token",
                "{\"entId\":\"E1\",\"userId\":\"U1\"}", null);
        assertEquals(404, resp.status());
    }

    @Test
    @DisplayName("任何 mock. 前缀令牌都无法解析（DEV 存储不存在）")
    void mock_token_cannot_resolve() {
        TestHttp.Resp resp = http.get("/wx/v1/me", "mock.deadbeefdeadbeefdeadbeefdeadbeef");
        assertEquals(401, resp.status());
        JsonNode root = parse(resp.body());
        assertEquals("TSS_UNAUTHORIZED", root.get("code").asString());
        assertTrue(root.get("data").isNull());
        assertFalse("SUCCESS".equals(root.get("code").asString()));
    }

    private JsonNode parse(String body) {
        try {
            return om.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON: " + body, e);
        }
    }
}
