package com.viding.datahub.tss.wx.contract;

import com.viding.datahub.tss.wx.mock.MockTestSeed;
import com.viding.datahub.tss.wx.support.TestHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RC3 补漏项的运行时证据（D-6 / D-1 补充 / 空 events 夹具 / freshness 可配置）。
 *
 * <p>与 {@link WxApiContractIT} 的区别：本类注入了<b>非默认</b>的
 * {@code datahub.tss.wx.freshness=DELAYED}，用来证明 freshness 确实来自配置而不是写死；
 * 同时覆盖跨企业与同企业他人两种 403 的分流。
 *
 * <p>身份来源同样是 DEV 专用 Mock 令牌颁发器（四重门禁），仅在 dev 联调下可用。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=dev",
                "datahub.tss.wx.mock-token.enabled=true",
                "datahub.tss.wx.mock-token.env-attestation=DEV_LOCAL",
                // RC3 红线：令牌不得硬编码在源码/配置文件中，故缺省配置为空，测试环境在此显式注入
                "datahub.tss.wx.admin.static-tokens=dev-admin-token=ADMIN|E498AU5L35J",
                // freshness 非默认值：证明它来自配置而非写死 FRESH
                "datahub.tss.wx.freshness=DELAYED"
        })
@DisplayName("RC3 补漏项：空 events 夹具 / freshness 可配置 / 跨企业 403 分流 / 解绑后 me")
class Rc3FixesIT {

    private static final String ENT_A = "E498AU5L35J";
    private static final String USER_A = "U4FXG07VRDI";
    private static final String ENT_OTHER = "E777OTHER";
    private static final String USER_OTHER = "U8OTHER00002";
    /** 同企业他人：entId 与样例归属企业相同，userId 不同。 */
    private static final String USER_SAME_ENT_OTHER = "U3OTHER0001";
    /** 解绑场景：解绑会销毁绑定，必须隔离身份。 */
    private static final String ENT_UNBIND = "E555UNBIND";
    private static final String USER_UNBIND = "U6UNBIND0001";
    /** REJECTED 场景：验证「申请被拒」是可观测的稳定状态，不是一拍即灭。 */
    private static final String ENT_REJECT = "E333REJECT";
    private static final String USER_REJECT = "U2REJECT0001";
    /** 会话过期场景：验证 TSS_SESSION_EXPIRED 可显式构造（默认实例下无触发路径）。 */
    private static final String ENT_EXPIRE_SESSION = "E222EXPIRESESS";
    private static final String USER_EXPIRE_SESSION = "U1EXPIRESESS1";
    /** 会话撤销场景：REVOKE 与 EXPIRE 都要落到 TSS_SESSION_EXPIRED。 */
    private static final String ENT_REVOKE_SESSION = "E111REVOKESESS";
    private static final String USER_REVOKE_SESSION = "U0REVOKESESS1";
    private static final String SAMPLE_TASK_NUM = "355377";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper om;

    private TestHttp http;

    @BeforeEach
    void setUp() {
        http = new TestHttp(port);
    }

    @Test
    @DisplayName("空 events 夹具：字段必现且为空数组（不是省略字段、不是 null）")
    void empty_events_fixture() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        JsonNode data = assertSuccess(http.get("/wx/v1/todos/" + MockTestSeed.EMPTY_EVENTS_TASK_NUM
                + "/detail", token));
        JsonNode events = data.get("workflow").get("events");
        assertTrue(events.isArray(), "RC2：events 字段不得省略，必须是数组，实际=" + events);
        assertEquals(0, events.size(), "本夹具的语义就是「流程尚无流转事件」");

        // 对照：冻结样例的详情 events 非空，证明「空」不是因为解析丢了字段
        JsonNode sample = assertSuccess(http.get("/wx/v1/todos/" + SAMPLE_TASK_NUM + "/detail", token));
        assertTrue(sample.get("workflow").get("events").size() > 0,
                "冻结样例详情的 events 必须非空，否则无法证明空 events 是夹具刻意构造的");
    }

    @Test
    @DisplayName("freshness 来自配置：本上下文注入 DELAYED，meta 必须如实输出 DELAYED")
    void freshness_comes_from_config() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        JsonNode data = assertSuccess(http.get("/wx/v1/todos", token));
        assertEquals("DELAYED", data.get("meta").get("freshness").asString(),
                "freshness 写死 FRESH 会让前端「数据延迟」分支永远走不到（等于没测）");
        assertEquals("MOCK", data.get("meta").get("dataMode").asString());
    }

    @Test
    @DisplayName("D-1 补充：跨企业查看他人单据 ⇒ 403 TSS_ENT_SCOPE_DENIED（不是笼统的 403）")
    void cross_enterprise_detail_is_ent_scope_denied() {
        String token = mockToken(ENT_OTHER, USER_OTHER, "BOUND");
        assertError(http.get("/wx/v1/todos/" + SAMPLE_TASK_NUM + "/detail", token),
                403, "TSS_ENT_SCOPE_DENIED");
    }

    @Test
    @DisplayName("同企业他人单据仍是 403 TSS_FORBIDDEN：作用域没越界，只是资源不属于本人")
    void same_enterprise_other_user_is_forbidden() {
        String token = mockToken(ENT_A, USER_SAME_ENT_OTHER, "BOUND");
        assertError(http.get("/wx/v1/todos/" + SAMPLE_TASK_NUM + "/detail", token),
                403, "TSS_FORBIDDEN");
    }

    @Test
    @DisplayName("D-6：解绑后 /wx/v1/me 仍是 200 SUCCESS 且 bindStatus=UNBOUND（会话未被撤销）")
    void unbind_then_me_is_200_unbound() {
        String token = mockToken(ENT_UNBIND, USER_UNBIND, "BOUND");
        JsonNode before = assertSuccess(http.get("/wx/v1/me", token));
        assertEquals("BOUND", before.get("bindStatus").asString());

        JsonNode unbound = assertSuccess(http.post("/wx/v1/bindings/unbind",
                "{\"confirmation\":\"UNBIND\",\"clientNonce\":\"nonce_rc3_unbind\"}", token));
        assertEquals("UNBOUND", unbound.get("bindStatus").asString());

        JsonNode me = assertSuccess(http.get("/wx/v1/me", token));
        assertEquals("UNBOUND", me.get("bindStatus").asString(),
                "解绑只解除绑定关系，会话仍有效 ⇒ me 必须是 200 SUCCESS，不是 401");
        assertTrue(me.get("userId").isNull(), "未绑定不得回显 userId");
        assertTrue(me.get("entId").isNull(), "未绑定不得回显 entId");
        assertFalse(me.get("enterpriseSwitchEnabled").asBoolean(), "V1.0 单企业，无切换入口");

        // 业务端点按「未绑定」拒绝（403），而不是被 401 抢先短路
        assertError(http.get("/wx/v1/todos", token), 403, "TSS_NOT_BOUND");
    }

    @Test
    @DisplayName("REJECTED 必须可观测：连续两次 status 都是 REJECTED 且 id 非空；me=UNBOUND；按 id 回查不 404")
    void rejected_is_observable_and_stable() {
        String token = mockToken(ENT_REJECT, USER_REJECT, "UNBOUND");
        JsonNode claim = assertSuccess(http.post("/wx/v1/bindings/claim",
                "{\"inviteCode\":\"INV-EXAMPLE\",\"claimNonce\":\"nonce_rc3_reject\"}", token));
        String bindRequestId = claim.get("bindRequestId").asString();

        // DEV 夹具：PC 端拒绝（非契约端点，仅 DEV 四重门禁下可用；该端点直接回 DTO，不走统一包络）
        TestHttp.Resp fixtureResp = http.post("/dev-support/v1/binding-fixture",
                "{\"action\":\"REJECT\",\"entId\":\"" + ENT_REJECT + "\",\"userId\":\"" + USER_REJECT + "\"}",
                null);
        assertEquals(200, fixtureResp.status(), "REJECT 夹具调用失败：" + fixtureResp.body());
        JsonNode rejected = parse(fixtureResp.body());
        assertEquals("REJECTED", rejected.get("status").asString(),
                "夹具应把申请推进到 REJECTED，实际=" + fixtureResp.body());

        // 判据 ①：连续两次查询都必须是 REJECTED 且 id 非空（稳定状态，不是一拍即灭）
        JsonNode first = assertSuccess(http.get("/wx/v1/bindings/status", token));
        JsonNode second = assertSuccess(http.get("/wx/v1/bindings/status", token));
        assertEquals("REJECTED", first.get("status").asString(), "实际=" + first);
        assertEquals("REJECTED", second.get("status").asString(), "第二次查询就变了 ⇒ REJECTED 被当场归档，实际=" + second);
        assertEquals(bindRequestId, first.get("bindRequestId").asString());
        assertEquals(bindRequestId, second.get("bindRequestId").asString());
        assertFalse(first.get("bindRequestId").isNull(), "裁决 B 修订：REJECTED 必须保留申请单号");

        // 判据 ②：me 仍是 UNBOUND（INV-A：REJECTED↔UNBOUND）
        JsonNode me = assertSuccess(http.get("/wx/v1/me", token));
        assertEquals("UNBOUND", me.get("bindStatus").asString());

        // 判据 ③：用该 bindRequestId 回查必须命中同一条，不得 404
        JsonNode byId = assertSuccess(http.get("/wx/v1/bindings/status?bindRequestId=" + bindRequestId, token));
        assertEquals(bindRequestId, byId.get("bindRequestId").asString());
        assertEquals("REJECTED", byId.get("status").asString());
    }

    @Test
    @DisplayName("TSS_SESSION_EXPIRED 可显式构造：过期会话打任何端点都 401（不返回任何业务数据）")
    void expired_session_is_session_expired() {
        String token = mockToken(ENT_EXPIRE_SESSION, USER_EXPIRE_SESSION, "BOUND");
        // 过期之前必须可用，否则"过期"这条断言没有意义
        assertSuccess(http.get("/wx/v1/me", token));

        TestHttp.Resp fix = http.post("/dev-support/v1/expire-session",
                "{\"sessionToken\":\"" + token + "\"}", null);
        assertEquals(200, fix.status(), "会话夹具 EXPIRE 失败：" + fix.body());

        // fail-closed：业务端点与状态端点一律 401，绝不允许返回空列表 + SUCCESS
        for (String path : new String[]{"/wx/v1/me", "/wx/v1/todos", "/wx/v1/history",
                "/wx/v1/todos/summary", "/wx/v1/notifications", "/wx/v1/bindings/status"}) {
            assertError(http.get(path, token), 401, "TSS_SESSION_EXPIRED");
        }
    }

    @Test
    @DisplayName("REVOKE 同样落 401 TSS_SESSION_EXPIRED；不存在的令牌仍是 401 TSS_UNAUTHORIZED（不得改道）")
    void revoked_session_and_unknown_token_are_distinct() {
        String token = mockToken(ENT_REVOKE_SESSION, USER_REVOKE_SESSION, "BOUND");
        TestHttp.Resp fix = http.post("/dev-support/v1/revoke-session",
                "{\"sessionToken\":\"" + token + "\"}", null);
        assertEquals(200, fix.status(), "会话夹具 REVOKE 失败：" + fix.body());
        assertError(http.get("/wx/v1/todos", token), 401, "TSS_SESSION_EXPIRED");

        // 「找到但已失效」与「压根没找到」必须分得开：不存在的令牌不得被改道成 SESSION_EXPIRED
        assertError(http.get("/wx/v1/todos", "mock.definitely-not-issued"), 401, "TSS_UNAUTHORIZED");
    }

    @Test
    @DisplayName("DEV 写回守卫探针：GET/POST 都返回 403 TSS_WRITE_DISABLED（不是只有 POST，也不是 405）")
    void dev_write_guard_probe() {
        // 只注册 POST 的话，GET 会拿到 405，而 405 会被映射成 TSS_INVALID_ARGUMENT——
        // 那是"参数问题"的语义，与"本版本禁止写回"完全不符，故这里两种 method 都断言。
        assertError(http.get("/dev-support/v1/tss/write-guard-probe", null),
                403, "TSS_WRITE_DISABLED");
        assertError(http.post("/dev-support/v1/tss/write-guard-probe", "{}", null),
                403, "TSS_WRITE_DISABLED");
    }

    // ================================================================ 工具方法

    /** 通过 DEV 专用颁发器取一枚会话令牌（四重门禁须全部满足）。 */
    private String mockToken(String entId, String userId, String bindStatus) {
        TestHttp.Resp resp = http.post("/dev-support/v1/mock-token",
                "{\"entId\":\"" + entId + "\",\"userId\":\"" + userId
                        + "\",\"bindStatus\":\"" + bindStatus + "\"}", null);
        assertEquals(200, resp.status(), "DEV Mock 令牌颁发失败（四重门禁未通过？）：" + resp.body());
        return parse(resp.body()).get("sessionToken").asString();
    }

    private JsonNode parse(String body) {
        try {
            return om.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON: " + body, e);
        }
    }

    /** 断言成功包络并回传 data 节点。 */
    private JsonNode assertSuccess(TestHttp.Resp resp) {
        assertEquals(200, resp.status(), "期望 HTTP 200，实际 " + resp.status() + " body=" + resp.body());
        JsonNode root = parse(resp.body());
        assertEquals("SUCCESS", root.get("code").asString());
        assertEquals(Set.of("code", "message", "data", "traceId", "timestamp"), keys(root));
        JsonNode data = root.get("data");
        assertFalse(data.isNull(), "成功响应 data 不得为 null");
        return data;
    }

    /** 断言失败包络：HTTP 状态、错误码、data=null、包络形状。 */
    private void assertError(TestHttp.Resp resp, int httpStatus, String code) {
        assertEquals(httpStatus, resp.status(),
                "期望 HTTP " + httpStatus + "，实际 " + resp.status() + " body=" + resp.body());
        JsonNode root = parse(resp.body());
        assertEquals(code, root.get("code").asString(), "错误码不符，响应=" + resp.body());
        assertTrue(root.get("data").isNull(), "失败响应 data 必须为 null");
        assertEquals(Set.of("code", "message", "data", "traceId", "timestamp"), keys(root));
    }

    /** 取对象节点的字段名集合。 */
    private Set<String> keys(JsonNode node) {
        Set<String> out = new TreeSet<>();
        node.propertyNames().forEach(out::add);
        return out;
    }
}
