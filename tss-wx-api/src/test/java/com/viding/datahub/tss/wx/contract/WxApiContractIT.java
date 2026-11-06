package com.viding.datahub.tss.wx.contract;

import com.viding.datahub.tss.wx.auth.TssWxWechatClient;
import com.viding.datahub.tss.wx.cursor.CursorCodec;
import com.viding.datahub.tss.wx.cursor.PageCursor;
import com.viding.datahub.tss.wx.dto.TssEnums;
import com.viding.datahub.tss.wx.mock.MockDataStore;
import com.viding.datahub.tss.wx.support.TestHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RC2 十三端点契约测试（B1 要求：针对<b>真实启动</b>的 MOCK 服务执行）。
 *
 * <p>本测试用 {@code RANDOM_PORT} 真正启动内嵌容器，再用 JDK HttpClient 直连
 * {@code 127.0.0.1:<port>} 打全部 13 个端点，校验：HTTP 状态、统一包络
 * （{@code code/message/data/traceId/timestamp} 五个字段、不多不少）、错误码映射、分页与筛选、
 * 游标签名、本人数据隔离、条件 GET。
 *
 * <p>身份来源：DEV 专用 Mock 令牌颁发器（受四重门禁约束）。它只在 dev profile + 显式开关 +
 * 环境自证 + 回环来源下可用，因此本测试必须在这四项都满足的前提下运行。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=dev",
                "datahub.tss.wx.mock-token.enabled=true",
                "datahub.tss.wx.mock-token.env-attestation=DEV_LOCAL",
                // RC3 红线：令牌不得硬编码在源码/配置文件中，故缺省配置为空，测试环境在此显式注入
                "datahub.tss.wx.admin.static-tokens=dev-admin-token=ADMIN|E498AU5L35J,dev-viewer-token=VIEWER|E498AU5L35J",
                "datahub.tss.wx.wechat.enabled=true",
                "datahub.tss.wx.wechat.appid=wxappid_for_contract_test",
                "datahub.tss.wx.wechat.secret=wxsecret_for_contract_test"
        })
@DisplayName("RC2 /wx/v1 + /admin/v1/tss 十三端点契约测试")
class WxApiContractIT {

    private static final String ENT_A = "E498AU5L35J";
    private static final String USER_A = "U4FXG07VRDI";
    private static final String ENT_B = "E777OTHER";
    private static final String USER_B = "U8OTHER00002";
    /** 专用于「未绑定」场景的身份：与样例归属身份分开，避免污染样例绑定的 bindingVersion。 */
    private static final String ENT_C = "E999UNBOUND";
    private static final String USER_C = "U7UNBOUND001";
    /** 专用于「解绑」场景的身份：解绑会销毁绑定，必须隔离，否则影响其它用例。 */
    private static final String ENT_D = "E555UNBIND";
    private static final String USER_D = "U6UNBIND0001";
    /** 专用于「解绑前发起过绑定申请」场景：验证解绑后残留申请被作废。 */
    private static final String ENT_E = "E222UNBIND2";
    private static final String USER_E = "U5UNBIND0002";
    /** 专用于「申请中（未绑定）」场景：未绑定身份刚发起 claim。 */
    private static final String ENT_F = "E111CLAIM";
    private static final String USER_F = "U4CLAIM00001";
    /** 专用于「已绑定且从未发起申请」场景：验证 status 不会因无申请而错报 UNBOUND。 */
    private static final String ENT_G = "E000BOUND2";
    private static final String USER_G = "U1BOUND00002";
    /** 专用于「发起绑定申请」场景：必须是未绑定身份（已绑定再申请 ⇒ 409 TSS_BIND_CONFLICT）。 */
    private static final String ENT_H = "E444CLAIM";
    private static final String USER_H = "U2CLAIM00002";
    /** 专用于「PC 确认通过后已绑定」场景（裁决 B：bindRequestId 归 null）。 */
    private static final String ENT_I = "E666PCCONF";
    private static final String USER_I = "U9PCCONF0001";
    /** 专用于「申请过期」场景。 */
    private static final String ENT_J = "E888EXPIRE";
    private static final String USER_J = "U8EXPIRE0001";
    /** 专用于「绑定停用」场景。 */
    private static final String ENT_K = "E777DISABLE";
    private static final String USER_K = "U7DISABLE001";
    private static final String ADMIN_TOKEN = "dev-admin-token";
    /** 令牌有效但角色不是 ADMIN：用于验证 403 TSS_ADMIN_FORBIDDEN 这条分支（否则该分支无用例覆盖）。 */
    private static final String VIEWER_TOKEN = "dev-viewer-token";
    private static final String SAMPLE_TASK_NUM = "355377";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private CursorCodec cursorCodec;

    @Autowired
    private MockDataStore store;

    private TestHttp http;

    @BeforeEach
    void setUp() {
        http = new TestHttp(port);
    }

    /**
     * 用桩替换真实微信客户端，使 API-01 能在无微信凭据的环境下走通完整链路。
     * 真实 {@code HttpWechatClient} 的 fail-closed 行为由 {@link WxAuthFailClosedTest} 单独验证。
     */
    @TestConfiguration
    static class StubWechatConfig {
        @Bean
        @Primary
        TssWxWechatClient stubWechatClient() {
            return code -> {
                if (code == null || code.isBlank()) {
                    return TssWxWechatClient.Code2SessionResult.fail("40001", "empty code");
                }
                return TssWxWechatClient.Code2SessionResult.ok("o_stub_" + code, null, "sk_stub");
            };
        }
    }

    // ================================================================ API-01

    @Test
    @DisplayName("API-01 登录成功：HTTP200 + SUCCESS 包络 + sessionToken")
    void api01_login_success() {
        TestHttp.Resp resp = http.post("/wx/v1/auth/login", "{\"code\":\"CONTRACT_TEST_CODE\"}", null);
        JsonNode data = assertSuccess(resp);
        assertTrue(data.get("sessionToken").asString().length() >= 32, "sessionToken 应为服务端签发的不透明令牌");
        assertFalse(data.get("sessionToken").asString().startsWith("mock."),
                "真实登录不得签发 mock. 前缀令牌");
        assertTrue(data.get("expiresIn").asInt() > 0);
        assertNotNull(data.get("bindStatus"));
        assertTrue(data.has("needBinding"));
        assertEquals(Set.of("sessionToken", "expiresIn", "bindStatus", "needBinding"), keys(data));
    }

    @Test
    @DisplayName("API-01 包络形状：仅 code/message/data/traceId/timestamp 五个字段")
    void api01_envelope_shape() {
        TestHttp.Resp resp = http.post("/wx/v1/auth/login", "{\"code\":\"SHAPE\"}", null);
        assertEquals(200, resp.status());
        assertEquals(Set.of("code", "message", "data", "traceId", "timestamp"), keys(parse(resp.body())));
        assertNotNull(resp.header("X-Trace-Id"));
    }

    // ================================================================ API-02

    @Test
    @DisplayName("API-02 已绑定身份：userId/entId 由服务端推导")
    void api02_me_bound() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        JsonNode data = assertSuccess(http.get("/wx/v1/me", token));
        assertEquals(USER_A, data.get("userId").asString());
        assertEquals(ENT_A, data.get("entId").asString());
        assertEquals("BOUND", data.get("bindStatus").asString());
        assertEquals(3, data.get("bindingVersion").asInt());
        assertFalse(data.get("enterpriseSwitchEnabled").asBoolean(), "V1.0 单当前企业，不开放切换");
        assertEquals(Set.of("userId", "entId", "displayName", "bindStatus", "bindingVersion",
                "enterpriseSwitchEnabled"), keys(data));
    }

    @Test
    @DisplayName("API-02 未认证：401 TSS_UNAUTHORIZED 且 data=null")
    void api02_me_unauthorized() {
        assertError(http.get("/wx/v1/me", null), 401, "TSS_UNAUTHORIZED");
    }

    @Test
    @DisplayName("API-02 无效令牌：401 TSS_UNAUTHORIZED")
    void api02_me_invalid_session() {
        assertError(http.get("/wx/v1/me", "definitely-not-a-real-token"), 401, "TSS_UNAUTHORIZED");
    }

    @Test
    @DisplayName("API-02 拒绝客户端提交 userId/entId：400 TSS_INVALID_ARGUMENT")
    void api02_me_rejects_client_identity() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        assertError(http.get("/wx/v1/me?userId=HACK&entId=" + ENT_B, token), 400, "TSS_INVALID_ARGUMENT");
    }

    // ================================================================ API-03

    @Test
    @DisplayName("API-03 待办统计：counts + meta.freshness，无 dataFreshness")
    void api03_summary() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        JsonNode data = assertSuccess(http.get("/wx/v1/todos/summary", token));
        JsonNode counts = data.get("counts");
        // 统计口径与列表一致：样例 1 条 + 自造测试数据 2 条 = 3 条（SALES_BUSINESS 2、PURCHASE_INBOUND 1）
        assertEquals(3, counts.get("total").asInt());
        assertEquals(2, counts.get("salesBusiness").asInt());
        assertEquals(1, counts.get("purchaseInbound").asInt());
        assertEquals(Set.of("total", "purchaseInbound", "salesBusiness", "payment", "salesPricing"),
                keys(counts));
        JsonNode meta = data.get("meta");
        assertEquals("MOCK", meta.get("dataMode").asString());
        assertEquals("FRESH", meta.get("freshness").asString());
        assertEquals("1.0.0", meta.get("schemaVersion").asString());
        assertEquals(Set.of("dataMode", "asOf", "freshness", "schemaVersion"), keys(meta));
        assertFalse(data.has("dataFreshness"), "F2：dataFreshness 已移除，UI 只读 meta.freshness");
        assertEquals(Set.of("counts", "lastFetchedAt", "meta"), keys(data));
    }

    @Test
    @DisplayName("API-03 未绑定身份访问业务数据：403 TSS_NOT_BOUND")
    void api03_not_bound() {
        String token = mockToken(ENT_C, USER_C, "UNBOUND");
        assertError(http.get("/wx/v1/todos/summary", token), 403, "TSS_NOT_BOUND");
    }

    // ================================================================ API-04

    @Test
    @DisplayName("API-04 待办列表：RC2 字段齐备 + 分页元信息")
    void api04_todos() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        JsonNode data = assertSuccess(http.get("/wx/v1/todos", token));
        assertEquals(3, data.get("items").size(), "样例 1 条 + 自造测试数据 2 条");
        JsonNode item = data.get("items").get(0);
        assertEquals(SAMPLE_TASK_NUM, item.get("taskNum").asString());
        assertEquals("SALES_BUSINESS", item.get("docType").asString());
        assertEquals("19800.00", item.get("amount").asString(), "金额必须是十进制字符串");
        assertEquals("CNY", item.get("currency").asString());
        assertEquals("PENDING", item.get("status").asString());
        assertEquals(Set.of("approvalId", "taskNum", "instanceId", "billNo", "docType", "title",
                "counterparty", "amount", "currency", "currentNodeName", "createdAt", "updatedAt",
                "status", "dataMode"), keys(item));
        JsonNode page = data.get("page");
        assertTrue(page.get("nextCursor").isNull());
        assertFalse(page.get("hasMore").asBoolean());
        assertEquals(3, page.get("totalApprox").asInt());
        assertEquals(Set.of("nextCursor", "hasMore", "totalApprox"), keys(page));
        assertEquals(Set.of("items", "page", "meta"), keys(data));
    }

    @Test
    @DisplayName("API-04 docType 筛选：命中 2 条 / 不命中 0 条")
    void api04_todos_doc_type_filter() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        assertEquals(2, assertSuccess(http.get("/wx/v1/todos?docType=SALES_BUSINESS", token))
                .get("items").size());
        assertEquals(0, assertSuccess(http.get("/wx/v1/todos?docType=PAYMENT", token))
                .get("items").size(), "PAYMENT 在测试数据中也应当不命中");
    }

    @Test
    @DisplayName("API-04 keyword 筛选：按单号命中")
    void api04_todos_keyword_filter() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        assertEquals(1, assertSuccess(http.get("/wx/v1/todos?keyword=77971", token)).get("items").size());
        assertEquals(0, assertSuccess(http.get("/wx/v1/todos?keyword=ZZZ_NOT_EXIST", token))
                .get("items").size());
    }

    @Test
    @DisplayName("API-04 非法 docType：400 TSS_INVALID_ARGUMENT")
    void api04_todos_invalid_doc_type() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        assertError(http.get("/wx/v1/todos?docType=NOT_A_TYPE", token), 400, "TSS_INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("API-04 limit 越界：400 TSS_INVALID_ARGUMENT")
    void api04_todos_limit_out_of_range() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        assertError(http.get("/wx/v1/todos?limit=101", token), 400, "TSS_INVALID_ARGUMENT");
        assertError(http.get("/wx/v1/todos?limit=0", token), 400, "TSS_INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("API-04 被篡改的游标：400 TSS_INVALID_CURSOR")
    void api04_todos_invalid_cursor() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        assertError(http.get("/wx/v1/todos?cursor=TAMPERED.NOPE", token), 400, "TSS_INVALID_CURSOR");
    }

    @Test
    @DisplayName("API-04 多页流转：limit=1 首页给 nextCursor / 翻页不重复 / 换筛选后旧游标失效")
    void api04_todos_multi_page_cursor_flow() {
        String token = mockToken(ENT_A, USER_A, "BOUND");

        // ① 第 1 页：必须给出非空 nextCursor 且 hasMore=true
        JsonNode page1 = assertSuccess(http.get("/wx/v1/todos?limit=1", token));
        assertEquals(1, page1.get("items").size());
        JsonNode meta1 = page1.get("page");
        assertTrue(meta1.get("hasMore").asBoolean(), "共 3 条、limit=1，首页 hasMore 必须为 true");
        assertTrue(!meta1.get("nextCursor").isNull(), "首页必须返回非空 nextCursor");
        String cursor1 = meta1.get("nextCursor").asString();
        String task1 = page1.get("items").get(0).get("taskNum").asString();
        assertEquals(SAMPLE_TASK_NUM, task1, "排序锚点为 updatedAt 倒序，样例单据排第一");

        // ② 第 2 页：用该 cursor 取到下一项，且不与第 1 页重复
        JsonNode page2 = assertSuccess(http.get("/wx/v1/todos?limit=1&cursor=" + cursor1, token));
        assertEquals(1, page2.get("items").size());
        String task2 = page2.get("items").get(0).get("taskNum").asString();
        assertNotEquals(task1, task2, "第 2 页不得与第 1 页重复");
        assertTrue(!page2.get("page").get("nextCursor").isNull());
        String cursor2 = page2.get("page").get("nextCursor").asString();

        // ③ 第 3 页：最后一页，hasMore=false 且 nextCursor=null
        JsonNode page3 = assertSuccess(http.get("/wx/v1/todos?limit=1&cursor=" + cursor2, token));
        String task3 = page3.get("items").get(0).get("taskNum").asString();
        assertNotEquals(task1, task3);
        assertNotEquals(task2, task3);
        assertFalse(page3.get("page").get("hasMore").asBoolean(), "第 3 页已是最后一页");
        assertTrue(page3.get("page").get("nextCursor").isNull(), "最后一页不得再给 nextCursor");
        assertEquals(3, Set.of(task1, task2, task3).size(), "三页合计 3 条，互不重复");

        // ④ 改筛选条件后复用旧游标 => 400 TSS_INVALID_CURSOR（F3：筛选变化必须丢弃旧 cursor）
        assertError(http.get("/wx/v1/todos?docType=SALES_BUSINESS&cursor=" + cursor1, token),
                400, "TSS_INVALID_CURSOR");
        assertError(http.get("/wx/v1/todos?keyword=77971&cursor=" + cursor1, token),
                400, "TSS_INVALID_CURSOR");
    }

    @Test
    @DisplayName("非契约路径：返回统一包络，而不是 Spring 默认错误体")
    void non_contract_path_returns_envelope() {
        TestHttp.Resp resp = http.get("/admin/v1/tss/overview", ADMIN_TOKEN);
        assertEquals(404, resp.status());
        JsonNode root = parse(resp.body());
        assertEquals(Set.of("code", "message", "data", "traceId", "timestamp"), keys(root),
                "未实现路径也必须输出 RC2 统一包络（F1），实际=" + resp.body());
        assertEquals("TSS_NOT_FOUND", root.get("code").asString());
        assertTrue(root.get("data").isNull());
        assertTrue(root.get("traceId").asString().startsWith("tr_"));
    }

    // ================================================================ API-05

    @Test
    @DisplayName("API-05 详情：workflow 内嵌、readOnly=true、availableActions 仅 VIEW")
    void api05_detail() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        TestHttp.Resp resp = http.get("/wx/v1/todos/" + SAMPLE_TASK_NUM + "/detail", token);
        JsonNode data = assertSuccess(resp);
        assertEquals(SAMPLE_TASK_NUM, data.get("taskNum").asString());

        JsonNode wf = data.get("workflow");
        assertTrue(wf.get("readOnly").asBoolean(), "V1.0 只读：readOnly 必须恒为 true");
        assertEquals("v1", wf.get("definitionVersion").asString());
        assertEquals("20261031-001", wf.get("dataVersion").asString());
        assertEquals("LINEAR", wf.get("graphType").asString());
        assertEquals(Set.of("workflowId", "instanceId", "currentNodeIds", "nodes", "edges",
                "graphType", "dataVersion", "definitionVersion", "readOnly", "events"), keys(wf));

        JsonNode actions = data.get("availableActions");
        assertEquals(1, actions.size());
        assertEquals("VIEW", actions.get(0).asString());

        JsonNode src = data.get("sourceStatus");
        assertEquals("NOT_VERIFIED", src.get("downstreamReceiptStatus").asString());
        assertFalse(src.get("isFinalApproved").asBoolean());
        assertEquals(Set.of("approvalId", "taskNum", "docType", "base", "lines", "workflow",
                "sourceStatus", "availableActions", "meta"), keys(data));

        assertNotNull(resp.header("ETag"), "详情必须提供 ETag");
        assertNotNull(resp.header("Last-Modified"), "详情必须提供 Last-Modified");
    }

    @Test
    @DisplayName("API-05 条件 GET 命中：304 且无响应体（F4 已实现 ETag/Last-Modified）")
    void api05_detail_304() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        String path = "/wx/v1/todos/" + SAMPLE_TASK_NUM + "/detail";
        TestHttp.Resp first = http.get(path, token);
        String etag = first.header("ETag");
        assertNotNull(etag);

        TestHttp.Resp second = http.get(path, token, TestHttp.headers("If-None-Match", etag));
        assertEquals(304, second.status(), "命中条件 GET 必须返回 304");
        assertTrue(second.body() == null || second.body().isEmpty(), "304 不得有响应体");
        assertNotNull(second.header("ETag"));
        assertNotNull(second.header("Last-Modified"));
    }

    @Test
    @DisplayName("API-05 单据不存在：404 TSS_NOT_FOUND")
    void api05_detail_not_found() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        assertError(http.get("/wx/v1/todos/0000000000/detail", token), 404, "TSS_NOT_FOUND");
    }

    @Test
    @DisplayName("API-05 他人单据：403 TSS_FORBIDDEN（本人数据隔离）")
    void api05_detail_other_user_forbidden() {
        String tokenB = mockToken(ENT_B, USER_B, "BOUND");
        // 跨企业查看他人单据：权威码 403 TSS_ENT_SCOPE_DENIED 比泛化的 TSS_FORBIDDEN 更精确，
        // 且属于权威 20 码之一，故用精确码而不是「能过就行」的泛化码
        assertError(http.get("/wx/v1/todos/" + SAMPLE_TASK_NUM + "/detail", tokenB),
                403, "TSS_ENT_SCOPE_DENIED");
    }

    // ================================================================ API-06

    @Test
    @DisplayName("API-06 历史：字段齐备 + action/from/to 筛选")
    void api06_history() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        JsonNode data = assertSuccess(http.get("/wx/v1/history", token));
        JsonNode item = data.get("items").get(0);
        assertEquals("APPROVED", item.get("action").asString());
        assertEquals("审批完成", item.get("actionName").asString());
        assertFalse(item.get("resultVerified").asBoolean(),
                "MOCK 数据未经 ERP 回查，自造种子一律 resultVerified=false，不得宣称已回查证实");
        assertEquals(Set.of("recordId", "approvalId", "taskNum", "billNo", "docType", "action",
                "actionName", "processedAt", "operatorDisplay", "resultVerified"), keys(item));
        assertEquals(2, assertSuccess(http.get("/wx/v1/history?action=APPROVED", token))
                .get("items").size(), "测试数据 2 条 APPROVED");
        assertEquals(2, assertSuccess(http.get("/wx/v1/history?action=REJECTED", token))
                .get("items").size(), "测试数据 2 条 REJECTED");
        assertEquals(4, assertSuccess(http.get("/wx/v1/history?from=2026-10-01&to=2026-12-31", token))
                .get("items").size());
        assertEquals(0, assertSuccess(http.get("/wx/v1/history?from=2026-11-01&to=2026-11-30", token))
                .get("items").size(), "11 月区间必须为空（测试数据全部落在 10 月）");
    }

    @Test
    @DisplayName("Mock 夹具自洽：历史单号与在办待办完全错开；历史一律 resultVerified=false（MOCK 未回查）")
    void mock_fixture_consistency() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        JsonNode todos = assertSuccess(http.get("/wx/v1/todos?limit=50", token)).get("items");
        JsonNode history = assertSuccess(http.get("/wx/v1/history?limit=50", token)).get("items");

        Set<String> openTaskNums = new HashSet<>();
        todos.forEach(t -> openTaskNums.add(t.get("taskNum").asString()));
        Set<String> doneTaskNums = new HashSet<>();
        history.forEach(h -> {
            doneTaskNums.add(h.get("taskNum").asString());
            assertFalse(h.get("resultVerified").asBoolean(),
                    "MOCK 数据不得宣称已回查证实：冲突记录=" + h.toString());
        });
        doneTaskNums.retainAll(openTaskNums);
        assertTrue(doneTaskNums.isEmpty(),
                "同一单号不得既在待办（PENDING）又在历史（已办结），冲突单号=" + doneTaskNums);
    }

    @Test
    @DisplayName("API-06 from 晚于 to / 非法日期：400 TSS_INVALID_ARGUMENT")
    void api06_history_bad_range() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        assertError(http.get("/wx/v1/history?from=2026-12-01&to=2026-10-01", token), 400, "TSS_INVALID_ARGUMENT");
        assertError(http.get("/wx/v1/history?from=not-a-date", token), 400, "TSS_INVALID_ARGUMENT");
    }

    // ================================================================ API-07

    @Test
    @DisplayName("API-07 通知列表：字段齐备")
    void api07_notifications() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        JsonNode data = assertSuccess(http.get("/wx/v1/notifications", token));
        JsonNode item = data.get("items").get(0);
        assertEquals("NEW_TODO", item.get("type").asString());
        assertTrue(item.get("readAt").isNull());
        assertEquals(Set.of("id", "title", "body", "type", "createdAt", "readAt", "approvalId"), keys(item));
        assertError(http.get("/wx/v1/notifications?limit=999", token), 400, "TSS_INVALID_ARGUMENT");
    }

    // ================================================================ API-08

    @Test
    @DisplayName("API-08 订阅意愿：白名单内 GRANTED")
    void api08_consent_granted() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        JsonNode data = assertSuccess(http.post("/wx/v1/notifications/consent",
                "{\"templateIds\":[\"wx_tpl_01\"],\"consented\":true}", token));
        assertEquals("GRANTED", data.get("subscriptionStatus").asString());
        assertEquals(Set.of("subscriptionStatus", "recordedAt"), keys(data));
    }

    @Test
    @DisplayName("API-08 拒绝订阅：DENIED")
    void api08_consent_denied() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        JsonNode data = assertSuccess(http.post("/wx/v1/notifications/consent",
                "{\"templateIds\":[\"wx_tpl_01\"],\"consented\":false}", token));
        assertEquals("DENIED", data.get("subscriptionStatus").asString());
    }

    @Test
    @DisplayName("API-08 未知模板不谎报：UNAVAILABLE")
    void api08_consent_unavailable() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        JsonNode data = assertSuccess(http.post("/wx/v1/notifications/consent",
                "{\"templateIds\":[\"wx_tpl_unknown\"],\"consented\":true}", token));
        assertEquals("UNAVAILABLE", data.get("subscriptionStatus").asString());
    }

    @Test
    @DisplayName("API-08 请求体多余字段被拒（additionalProperties:false 用于入参校验）")
    void api08_consent_unknown_field_rejected() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        assertError(http.post("/wx/v1/notifications/consent",
                "{\"templateIds\":[\"wx_tpl_01\"],\"consented\":true,\"bogus\":1}", token),
                400, "TSS_INVALID_ARGUMENT");
    }

    // ================================================================ API-09/10/11

    @Test
    @DisplayName("API-09 发起绑定：PENDING_PC_CONFIRM（未绑定身份）")
    void api09_claim() {
        String token = mockToken(ENT_H, USER_H, "UNBOUND");
        JsonNode data = assertSuccess(http.post("/wx/v1/bindings/claim",
                "{\"inviteCode\":\"INV-EXAMPLE\",\"claimNonce\":\"nonce_001\"}", token));
        assertEquals("PENDING_PC_CONFIRM", data.get("status").asString());
        assertNotNull(data.get("bindRequestId"));
        assertEquals(Set.of("bindRequestId", "status", "expiresAt"), keys(data));
        // 发起申请后 /me 必须变成 PENDING（裁决 A），而不是继续说 UNBOUND
        assertEquals("PENDING", assertSuccess(http.get("/wx/v1/me", token)).get("bindStatus").asString());
    }

    @Test
    @DisplayName("API-09 已绑定身份再申请：409 TSS_BIND_CONFLICT（「已有冲突绑定」）")
    void api09_claim_when_bound_conflict() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        assertError(http.post("/wx/v1/bindings/claim",
                "{\"inviteCode\":\"INV-EXAMPLE\",\"claimNonce\":\"nonce_bound_conflict\"}", token),
                409, "TSS_BIND_CONFLICT");
    }

    @Test
    @DisplayName("API-09 停用身份申请：403 TSS_BINDING_DISABLED")
    void api09_claim_when_disabled() {
        String token = mockToken(ENT_K, USER_K, "DISABLED");
        assertError(http.post("/wx/v1/bindings/claim",
                "{\"inviteCode\":\"INV-EXAMPLE\",\"claimNonce\":\"nonce_disabled_claim\"}", token),
                403, "TSS_BINDING_DISABLED");
    }

    @Test
    @DisplayName("API-09 无效邀请码：400 TSS_INVALID_ARGUMENT（不做「任何码都接受」的伪校验）")
    void api09_claim_invalid_code() {
        String token = mockToken(ENT_H, USER_H, "UNBOUND");
        assertError(http.post("/wx/v1/bindings/claim",
                "{\"inviteCode\":\"INV-BOGUS\",\"claimNonce\":\"nonce_x\"}", token),
                400, "TSS_INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("API-10 绑定状态：申请中 status=PENDING_PC_CONFIRM 且 bindRequestId 非空（INV-B 正向）")
    void api10_status() {
        String token = mockToken(ENT_H, USER_H, "UNBOUND");
        JsonNode claim = assertSuccess(http.post("/wx/v1/bindings/claim",
                "{\"inviteCode\":\"INV-EXAMPLE\",\"claimNonce\":\"nonce_api10\"}", token));
        String bindRequestId = claim.get("bindRequestId").asString();
        assertEquals("PENDING_PC_CONFIRM", claim.get("status").asString(), "申请本身停在待 PC 确认");

        JsonNode data = assertSuccess(http.get("/wx/v1/bindings/status?bindRequestId=" + bindRequestId, token));
        assertEquals(bindRequestId, data.get("bindRequestId").asString(), "待确认期间应回显申请单号");
        assertEquals("PENDING_PC_CONFIRM", data.get("status").asString());
        JsonNode me = assertSuccess(http.get("/wx/v1/me", token));
        assertEquals("PENDING", me.get("bindStatus").asString(), "裁决 A：申请中 /me 必须是 PENDING");
        assertEquals(Set.of("bindRequestId", "status", "erpUserMasked", "entDisplayName", "checkedAt"),
                keys(data));
    }

    @Test
    @DisplayName("API-10 不存在的 bindRequestId：404 TSS_NOT_FOUND（不泄露其是否曾经存在）")
    void api10_status_unknown_request_not_found() {
        String token = mockToken(ENT_H, USER_H, "UNBOUND");
        assertError(http.get("/wx/v1/bindings/status?bindRequestId=br_not_exist", token),
                404, "TSS_NOT_FOUND");
    }

    @Test
    @DisplayName("API-11 解绑：绑定关系撤销（会话仍有效，二次解绑落到 403 TSS_NOT_BOUND）")
    void api11_unbind() {
        String token = mockToken(ENT_D, USER_D, "BOUND");
        JsonNode data = assertSuccess(http.post("/wx/v1/bindings/unbind",
                "{\"confirmation\":\"UNBIND\",\"clientNonce\":\"nonce_api11\"}", token));
        assertEquals("UNBOUND", data.get("bindStatus").asString());
        assertEquals(Set.of("revokedAt", "bindStatus"), keys(data));
        // 解绑不撤销会话（与 Python 侧一致）：业务端点按「未绑定」拒绝，而不是 401
        assertError(http.get("/wx/v1/todos", token), 403, "TSS_NOT_BOUND");
    }

    @Test
    @DisplayName("API-11 confirmation 不等于 UNBIND：400 TSS_INVALID_ARGUMENT")
    void api11_unbind_bad_confirmation() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        assertError(http.post("/wx/v1/bindings/unbind",
                "{\"confirmation\":\"YES\",\"clientNonce\":\"nonce_bad\"}", token),
                400, "TSS_INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("裁决 C：解绑错误码矩阵（未绑定 / 申请中 / 停用 / 二次解绑 / 参数先于状态）")
    void unbind_error_code_matrix() {
        // ① 未绑定 → 403 TSS_NOT_BOUND
        String unbound = mockToken(ENT_C, USER_C, "UNBOUND");
        assertError(http.post("/wx/v1/bindings/unbind",
                "{\"confirmation\":\"UNBIND\",\"clientNonce\":\"nonce_m1\"}", unbound),
                403, "TSS_NOT_BOUND");
        // ② 申请中（PENDING，关系未建立）→ 403 TSS_NOT_BOUND
        String pending = mockToken(ENT_H, USER_H, "PENDING");
        assertError(http.post("/wx/v1/bindings/unbind",
                "{\"confirmation\":\"UNBIND\",\"clientNonce\":\"nonce_m2\"}", pending),
                403, "TSS_NOT_BOUND");
        // ③ 停用 → 403 TSS_BINDING_DISABLED（是既存绑定，只是被停用，不复用 NOT_BOUND）
        String disabled = mockToken(ENT_K, USER_K, "DISABLED");
        assertError(http.post("/wx/v1/bindings/unbind",
                "{\"confirmation\":\"UNBIND\",\"clientNonce\":\"nonce_m3\"}", disabled),
                403, "TSS_BINDING_DISABLED");
        // ④ 二次解绑（已解绑）→ 403 TSS_NOT_BOUND（会话仍有效，不被 401 抢先短路）
        String bound = mockToken(ENT_D, USER_D, "BOUND");
        assertSuccess(http.post("/wx/v1/bindings/unbind",
                "{\"confirmation\":\"UNBIND\",\"clientNonce\":\"nonce_m4\"}", bound));
        assertError(http.post("/wx/v1/bindings/unbind",
                "{\"confirmation\":\"UNBIND\",\"clientNonce\":\"nonce_m5\"}", bound),
                403, "TSS_NOT_BOUND");
        // ⑤ 参数检查先于状态检查：未绑定 + confirmation 写错 ⇒ 400，不是 403
        assertError(http.post("/wx/v1/bindings/unbind",
                "{\"confirmation\":\"YES\",\"clientNonce\":\"nonce_m6\"}", unbound),
                400, "TSS_INVALID_ARGUMENT");
        assertError(http.post("/wx/v1/bindings/unbind",
                "{\"confirmation\":\"UNBIND\"}", unbound),
                400, "TSS_INVALID_ARGUMENT");
    }

    // ================================================================ API-12/13

    @Test
    @DisplayName("API-12 管理端绑定列表：审计信息 + 企业作用域")
    void api12_admin_bindings() {
        JsonNode data = assertSuccess(http.get("/admin/v1/tss/bindings", ADMIN_TOKEN));
        assertEquals(Set.of("items", "page", "auditInfo"), keys(data));
        JsonNode audit = data.get("auditInfo");
        assertTrue(audit.get("accessLogged").asBoolean());
        assertEquals("AUTHORIZED_ENT", audit.get("scope").asString());
        assertEquals(Set.of("accessLogged", "scope"), keys(audit));
        assertTrue(data.get("items").size() >= 1);
        data.get("items").forEach(i -> assertEquals(ENT_A, i.get("entId").asString(),
                "管理员不得越权看到授权企业之外的数据"));
        assertEquals(Set.of("bindingId", "userId", "entId", "openidMasked", "bindStatus",
                "bindingVersion", "updatedAt"), keys(data.get("items").get(0)));
    }

    @Test
    @DisplayName("API-12 管理端鉴权 fail-closed：缺凭据 / 无效令牌均 401，令牌有效但角色非 ADMIN 才 403")
    void api12_admin_unauthorized() {
        // 缺凭据 ⇒ 401 TSS_UNAUTHORIZED
        assertError(http.get("/admin/v1/tss/bindings", null), 401, "TSS_UNAUTHORIZED");
        // 令牌无效 ⇒ 401 TSS_UNAUTHORIZED（D-7 撤销后回到此处：无效凭据与缺凭据同属 401）
        assertError(http.get("/admin/v1/tss/bindings", "wrong-admin-token"), 401, "TSS_UNAUTHORIZED");
        // 令牌有效但角色不是 ADMIN ⇒ 403 TSS_ADMIN_FORBIDDEN（这是 403 的唯一触发路径）
        assertError(http.get("/admin/v1/tss/bindings", VIEWER_TOKEN), 403, "TSS_ADMIN_FORBIDDEN");
    }

    @Test
    @DisplayName("API-13 业务映射：validationLevel 与字段核实状态如实输出")
    void api13_admin_mappings() {
        JsonNode data = assertSuccess(http.get("/admin/v1/tss/mappings", ADMIN_TOKEN));
        assertEquals("PARTIAL", data.get("validationLevel").asString());
        JsonNode def = data.get("definitions").get(0);
        assertEquals("PURCHASE_INBOUND", def.get("docType").asString());
        assertEquals("PARTIAL", def.get("validationLevel").asString());
        assertFalse(def.get("fields").get(0).get("verified").asBoolean(), "未核实字段不得标为已核实");
        assertTrue(def.get("fields").get(0).get("sourceColumn").isNull(), "未确定来源列应为 null");
        assertEquals(Set.of("definitions", "validationLevel", "updatedAt"), keys(data));
    }

    // ============================================== 本人数据隔离 / 游标绑定

    @Test
    @DisplayName("本人数据隔离：另一身份看不到样例数据（空列表而非报错）")
    void isolation_other_user_empty() {
        String tokenB = mockToken(ENT_B, USER_B, "BOUND");
        assertEquals(0, assertSuccess(http.get("/wx/v1/todos", tokenB)).get("items").size());
        assertEquals(0, assertSuccess(http.get("/wx/v1/todos/summary", tokenB))
                .get("counts").get("total").asInt());
        assertEquals(0, assertSuccess(http.get("/wx/v1/history", tokenB)).get("items").size());
        assertEquals(0, assertSuccess(http.get("/wx/v1/notifications", tokenB)).get("items").size());
    }

    @Test
    @DisplayName("游标绑定身份：A 的游标给 B 使用 => 400 TSS_INVALID_CURSOR")
    void cursor_bound_to_identity() {
        String tokenA = mockToken(ENT_A, USER_A, "BOUND");
        String tokenB = mockToken(ENT_B, USER_B, "BOUND");

        String filterHash = cursorCodec.filterHash("todos", "", "");
        String cursorForA = cursorCodec.encode(new PageCursor(0, store.sortAnchor(), filterHash,
                USER_A, ENT_A, System.currentTimeMillis() / 1000));
        assertEquals(200, http.get("/wx/v1/todos?cursor=" + cursorForA, tokenA).status());
        assertError(http.get("/wx/v1/todos?cursor=" + cursorForA, tokenB), 400, "TSS_INVALID_CURSOR");
    }

    @Test
    @DisplayName("游标绑定筛选：筛选变化后旧游标失效（F3 改变筛选必须丢弃旧 cursor）")
    void cursor_bound_to_filter() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        String hashNoFilter = cursorCodec.filterHash("todos", "", "");
        String cursorNoFilter = cursorCodec.encode(new PageCursor(0, store.sortAnchor(), hashNoFilter,
                USER_A, ENT_A, System.currentTimeMillis() / 1000));
        assertEquals(200, http.get("/wx/v1/todos?cursor=" + cursorNoFilter, token).status());
        assertError(http.get("/wx/v1/todos?docType=SALES_BUSINESS&cursor=" + cursorNoFilter, token),
                400, "TSS_INVALID_CURSOR");
    }

    @Test
    @DisplayName("时间格式：带时区 ISO-8601（+08:00）")
    void timestamp_has_offset() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        JsonNode root = parse(http.get("/wx/v1/todos/summary", token).body());
        String ts = root.get("timestamp").asString();
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}"),
                "timestamp 必须是带时区偏移的 ISO-8601，实际=" + ts);
        String asOf = root.get("data").get("meta").get("asOf").asString();
        assertTrue(asOf.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}"),
                "asOf 必须是带时区偏移的 ISO-8601，实际=" + asOf);
    }

    @Test
    @DisplayName("契约红线：workflow.readOnly 恒为 true，绝不出现 APPROVE/REJECT 动作")
    void read_only_contract() {
        String token = mockToken(ENT_A, USER_A, "BOUND");
        JsonNode data = assertSuccess(http.get("/wx/v1/todos/" + SAMPLE_TASK_NUM + "/detail", token));
        assertTrue(data.get("workflow").get("readOnly").asBoolean());
        for (JsonNode a : data.get("availableActions")) {
            assertTrue(Set.of(TssEnums.AvailableAction.VIEW.name(), TssEnums.AvailableAction.OPEN_ERP.name())
                            .contains(a.asString()),
                    "availableActions 只允许 VIEW / OPEN_ERP，实际=" + a.asString());
            assertNotEquals("APPROVE", a.asString());
            assertNotEquals("REJECT", a.asString());
        }
    }

    // ============================================ RC3：绑定状态五态一致性（/me 与 /bindings/status）

    @Test
    @DisplayName("RC3 绑定状态① 未绑定：/me=UNBOUND 且 status=UNBOUND（无 bindRequestId）")
    void binding_state_1_unbound() {
        String token = mockToken(ENT_C, USER_C, "UNBOUND");
        JsonNode me = assertSuccess(http.get("/wx/v1/me", token));
        assertEquals("UNBOUND", me.get("bindStatus").asString(), "/me 应如实反映未绑定");

        JsonNode st = assertSuccess(http.get("/wx/v1/bindings/status", token));
        assertEquals("UNBOUND", st.get("status").asString());
        assertTrue(st.get("bindRequestId").isNull(), "没有任何申请时不得臆造 bindRequestId");
    }

    @Test
    @DisplayName("裁决 A 绑定状态② 申请中：/me=PENDING 且 status=PENDING_PC_CONFIRM（bindRequestId 非空）")
    void binding_state_2_claiming() {
        String token = mockToken(ENT_F, USER_F, "UNBOUND");
        JsonNode claim = assertSuccess(http.post("/wx/v1/bindings/claim",
                "{\"inviteCode\":\"INV-EXAMPLE\",\"claimNonce\":\"nonce_state2\"}", token));
        String bindRequestId = claim.get("bindRequestId").asString();
        assertEquals("PENDING_PC_CONFIRM", claim.get("status").asString());

        JsonNode me = assertSuccess(http.get("/wx/v1/me", token));
        assertEquals("PENDING", me.get("bindStatus").asString(),
                "裁决 A：申请中 /me 必须是 PENDING；若仍报 UNBOUND，PENDING 枚举将永远取不到（死值）");

        JsonNode st = assertSuccess(http.get("/wx/v1/bindings/status", token));
        assertEquals("PENDING_PC_CONFIRM", st.get("status").asString());
        assertEquals(bindRequestId, st.get("bindRequestId").asString(), "默认应返回最近一次申请");
        assertTrue(st.get("erpUserMasked").isNull(), "INV-C：未真正绑定不得给出脱敏 ERP 账号");
    }

    @Test
    @DisplayName("裁决 A 绑定状态③ 待 PC 确认：按 bindRequestId 精确查询，状态与②一致")
    void binding_state_3_pending_pc_confirm_by_id() {
        String token = mockToken(ENT_F, USER_F, "UNBOUND");
        JsonNode claim = assertSuccess(http.post("/wx/v1/bindings/claim",
                "{\"inviteCode\":\"INV-EXAMPLE\",\"claimNonce\":\"nonce_state3\"}", token));
        String bindRequestId = claim.get("bindRequestId").asString();

        JsonNode st = assertSuccess(http.get("/wx/v1/bindings/status?bindRequestId=" + bindRequestId, token));
        assertEquals(bindRequestId, st.get("bindRequestId").asString());
        assertEquals("PENDING_PC_CONFIRM", st.get("status").asString(),
                "V1.0 无 PC 确认端点，状态必须如实停在待确认，不得自行推进到 BOUND");

        JsonNode me = assertSuccess(http.get("/wx/v1/me", token));
        assertEquals("PENDING", me.get("bindStatus").asString());
    }

    @Test
    @DisplayName("RC3 INV-4：查询他人的 bindRequestId 按不存在处理（404，不泄露其是否存在）")
    void binding_status_foreign_request_not_found() {
        String tokenA = mockToken(ENT_A, USER_A, "BOUND");
        String tokenF = mockToken(ENT_F, USER_F, "UNBOUND");
        JsonNode claimF = assertSuccess(http.post("/wx/v1/bindings/claim",
                "{\"inviteCode\":\"INV-EXAMPLE\",\"claimNonce\":\"nonce_foreign\"}", tokenF));
        String foreignId = claimF.get("bindRequestId").asString();
        assertError(http.get("/wx/v1/bindings/status?bindRequestId=" + foreignId, tokenA),
                404, "TSS_NOT_FOUND");
    }

    @Test
    @DisplayName("RC3 绑定状态④ 已绑定：/me=BOUND 且 status=BOUND（无申请也不得错报 UNBOUND）")
    void binding_state_4_bound() {
        String token = mockToken(ENT_G, USER_G, "BOUND");
        JsonNode me = assertSuccess(http.get("/wx/v1/me", token));
        assertEquals("BOUND", me.get("bindStatus").asString());

        JsonNode st = assertSuccess(http.get("/wx/v1/bindings/status", token));
        assertEquals("BOUND", st.get("status").asString(),
                "已绑定却无任何申请时，status 必须仍为 BOUND，不得回退成 UNBOUND（status 与 bindRequestId 是两个语义）");
        assertTrue(st.get("bindRequestId").isNull(), "没有申请流程时 bindRequestId 为 null 是正确的");
    }

    @Test
    @DisplayName("裁决 B 绑定状态⑤ PC 确认后已绑定：status=BOUND 且 bindRequestId=null（与预置已绑定同形状）")
    void binding_state_5_pc_confirmed_bound() {
        String token = mockToken(ENT_I, USER_I, "UNBOUND");
        JsonNode claim = assertSuccess(http.post("/wx/v1/bindings/claim",
                "{\"inviteCode\":\"INV-EXAMPLE\",\"claimNonce\":\"nonce_state5\"}", token));
        String bindRequestId = claim.get("bindRequestId").asString();
        assertEquals("PENDING_PC_CONFIRM", claim.get("status").asString());

        // 模拟 PC 端确认通过（DEV 夹具端点，非 RC2 契约端点）
        JsonNode fixture = parse(http.post("/dev-support/v1/binding-fixture",
                "{\"action\":\"PC_CONFIRM\",\"entId\":\"" + ENT_I + "\",\"userId\":\"" + USER_I + "\"}", null)
                .body());
        assertEquals("BOUND", fixture.get("status").asString());
        assertTrue(fixture.get("requestArchived").asBoolean(), "绑定成功瞬间申请单即被终结");

        JsonNode me = assertSuccess(http.get("/wx/v1/me", token));
        assertEquals("BOUND", me.get("bindStatus").asString());

        JsonNode st = assertSuccess(http.get("/wx/v1/bindings/status", token));
        assertEquals("BOUND", st.get("status").asString());
        assertTrue(st.get("bindRequestId").isNull(),
                "裁决 B：绑定成功后 bindRequestId 必须归 null（PC 确认后与预置已绑定形状一致）");
        assertFalse(st.get("erpUserMasked").isNull(), "INV-C：BOUND 时应给出脱敏 ERP 账号");
        // 申请单已终结 ⇒ 再按原 id 查询应 404（不泄露其曾经存在）
        assertError(http.get("/wx/v1/bindings/status?bindRequestId=" + bindRequestId, token),
                404, "TSS_NOT_FOUND");
    }

    @Test
    @DisplayName("RC3 绑定状态⑥ 解绑后：/me=UNBOUND 且 status=UNBOUND（残留申请被作废，不得回 PENDING）")
    void binding_state_6_after_unbind() {
        String token = mockToken(ENT_E, USER_E, "UNBOUND");
        // 先发起申请并让 PC 端确认通过，制造「解绑前确实持有过申请单」的场景
        assertSuccess(http.post("/wx/v1/bindings/claim",
                "{\"inviteCode\":\"INV-EXAMPLE\",\"claimNonce\":\"nonce_state6\"}", token));
        parse(http.post("/dev-support/v1/binding-fixture",
                "{\"action\":\"PC_CONFIRM\",\"entId\":\"" + ENT_E + "\",\"userId\":\"" + USER_E + "\"}", null)
                .body());
        assertSuccess(http.post("/wx/v1/bindings/unbind",
                "{\"confirmation\":\"UNBIND\",\"clientNonce\":\"nonce_state6_unbind\"}", token));
        // 解绑不撤销会话：同一令牌即可继续观察状态（与 Python 侧一致）
        assertError(http.get("/wx/v1/todos", token), 403, "TSS_NOT_BOUND");

        JsonNode me = assertSuccess(http.get("/wx/v1/me", token));
        assertEquals("UNBOUND", me.get("bindStatus").asString());

        JsonNode st = assertSuccess(http.get("/wx/v1/bindings/status", token));
        assertEquals("UNBOUND", st.get("status").asString(),
                "解绑后残留的待确认申请必须被作废，不得再报 PENDING_PC_CONFIRM 与 /me 打架");
        assertTrue(st.get("bindRequestId").isNull());
    }

    @Test
    @DisplayName("RC3 绑定状态⑦ 停用：/me=DISABLED 且 status=DISABLED（bindRequestId=null）")
    void binding_state_7_disabled() {
        String token = mockToken(ENT_K, USER_K, "DISABLED");
        JsonNode me = assertSuccess(http.get("/wx/v1/me", token));
        assertEquals("DISABLED", me.get("bindStatus").asString());

        JsonNode st = assertSuccess(http.get("/wx/v1/bindings/status", token));
        assertEquals("DISABLED", st.get("status").asString());
        assertTrue(st.get("bindRequestId").isNull(), "INV-B：停用态不携带申请单号");
        // 停用身份不得访问业务数据（403 TSS_BINDING_DISABLED）
        assertError(http.get("/wx/v1/todos", token), 403, "TSS_BINDING_DISABLED");
    }

    @Test
    @DisplayName("RC3 绑定状态⑧ 申请过期：/me=UNBOUND 且 status=EXPIRED（bindRequestId 保留，需用户重发）")
    void binding_state_8_expired() {
        String token = mockToken(ENT_J, USER_J, "UNBOUND");
        JsonNode claim = assertSuccess(http.post("/wx/v1/bindings/claim",
                "{\"inviteCode\":\"INV-EXAMPLE\",\"claimNonce\":\"nonce_state8\"}", token));
        String bindRequestId = claim.get("bindRequestId").asString();
        assertEquals("PENDING", assertSuccess(http.get("/wx/v1/me", token)).get("bindStatus").asString());

        // 让申请单立即过期（DEV 夹具，避免等待 10 分钟 TTL）
        parse(http.post("/dev-support/v1/binding-fixture",
                "{\"action\":\"EXPIRE\",\"entId\":\"" + ENT_J + "\",\"userId\":\"" + USER_J + "\"}", null)
                .body());

        JsonNode me = assertSuccess(http.get("/wx/v1/me", token));
        assertEquals("UNBOUND", me.get("bindStatus").asString(),
                "裁决 A：EXPIRED↔UNBOUND，申请过期后关系回落未绑定");

        JsonNode st = assertSuccess(http.get("/wx/v1/bindings/status", token));
        assertEquals("EXPIRED", st.get("status").asString());
        assertEquals(bindRequestId, st.get("bindRequestId").asString(),
                "INV-B：EXPIRED 仍属「未终结、需用户感知并重发」，保留申请单号");
        assertTrue(st.get("erpUserMasked").isNull());
    }

    @Test
    @DisplayName("RC3 筛选五类：匹配 / 无匹配 / 非法日期 / 动作筛选 / 跨页")
    void filters_five_cases() {
        String token = mockToken(ENT_A, USER_A, "BOUND");

        // ① 匹配：keyword 命中单号
        assertEquals(1, assertSuccess(http.get("/wx/v1/todos?keyword=77971", token))
                .get("items").size());
        // ② 无匹配：keyword 命中不到任何单据（应是空列表而不是错误）
        assertEquals(0, assertSuccess(http.get("/wx/v1/todos?keyword=ZZZ_NOT_EXIST", token))
                .get("items").size());
        // ③ 非法日期 / from 晚于 to：400 TSS_INVALID_ARGUMENT（不做静默降级）
        assertError(http.get("/wx/v1/history?from=not-a-date", token), 400, "TSS_INVALID_ARGUMENT");
        assertError(http.get("/wx/v1/history?from=2026-12-01&to=2026-10-01", token),
                400, "TSS_INVALID_ARGUMENT");
        // ④ 动作筛选
        assertEquals(2, assertSuccess(http.get("/wx/v1/history?action=APPROVED", token))
                .get("items").size());
        assertEquals(2, assertSuccess(http.get("/wx/v1/history?action=REJECTED", token))
                .get("items").size());
        // ⑤ 跨页：history 共 4 条，limit=2 分两页取满且不重复
        JsonNode p1 = assertSuccess(http.get("/wx/v1/history?limit=2", token));
        assertEquals(2, p1.get("items").size());
        assertTrue(p1.get("page").get("hasMore").asBoolean());
        String c = p1.get("page").get("nextCursor").asString();
        JsonNode p2 = assertSuccess(http.get("/wx/v1/history?limit=2&cursor=" + c, token));
        assertEquals(2, p2.get("items").size());
        assertFalse(p2.get("page").get("hasMore").asBoolean());
        Set<String> ids = new java.util.LinkedHashSet<>();
        p1.get("items").forEach(i -> ids.add(i.get("recordId").asString()));
        p2.get("items").forEach(i -> ids.add(i.get("recordId").asString()));
        assertEquals(4, ids.size(), "两页合计 4 条且不重复");
    }

    // ================================================================ 工具方法

    /** 通过 DEV 专用颁发器取一枚会话令牌（四重门禁须全部满足）。 */
    private String mockToken(String entId, String userId, String bindStatus) {
        String body = "{\"entId\":\"" + entId + "\",\"userId\":\"" + userId
                + "\",\"bindStatus\":\"" + bindStatus + "\"}";
        TestHttp.Resp resp = http.post("/dev-support/v1/mock-token", body, null);
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
        assertEquals("OK", root.get("message").asString());
        assertEquals(Set.of("code", "message", "data", "traceId", "timestamp"), keys(root),
                "包络字段必须且只能是 5 个（F1）");
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
        assertEquals(Set.of("code", "message", "data", "traceId", "timestamp"), keys(root),
                "失败包络同样必须是 5 个字段");
        assertTrue(root.get("traceId").asString().startsWith("tr_"));
    }

    /** 取对象节点的字段名集合（有序，便于失败时比对）。 */
    private Set<String> keys(JsonNode node) {
        Set<String> out = new TreeSet<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    /** 占位引用，避免 unused import 告警。 */
    @SuppressWarnings("unused")
    private static Map<String, String> unused() {
        return Map.of();
    }
}
