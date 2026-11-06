package com.viding.datahub.tss.wx.envelope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权威错误码清单锁死测试。
 *
 * <p>目的：把「对外只发权威 20 条」这条硬约束变成可执行的断言。
 * 之前 {@code TssErrorCode.coreSetSize()} 没有任何断言，所以枚举被误扩到 21 条、
 * 混进 14 个非权威码也没人发现——本测试就是为此补的护栏。
 */
@DisplayName("权威 TSS_* 错误码清单（20 条）锁死")
class TssErrorCodeTest {

    /** 权威 20 条（与 mock-openapi/tss-contract-mock/server.py 的 ERROR_CODES 一致）。 */
    private static final Set<String> AUTHORITATIVE = Set.of(
            "TSS_INVALID_ARGUMENT",
            "TSS_INVALID_CURSOR",
            "TSS_UNAUTHORIZED",
            "TSS_SESSION_EXPIRED",
            "TSS_NOT_BOUND",
            "TSS_FORBIDDEN",
            "TSS_ENT_SCOPE_DENIED",
            "TSS_BINDING_DISABLED",
            "TSS_ADMIN_FORBIDDEN",
            "TSS_WRITE_DISABLED",
            "TSS_NOT_FOUND",
            "TSS_BIND_INVITE_EXPIRED",
            "TSS_BIND_CONFLICT",
            "TSS_IDEMPOTENCY_CONFLICT",
            "TSS_DATA_STALE",
            "TSS_RATE_LIMITED",
            "TSS_INTERNAL_ERROR",
            "TSS_WECHAT_UNAVAILABLE",
            "TSS_ERP_READ_UNAVAILABLE",
            "TSS_NOT_CONFIGURED");

    /** 权威 HTTP 映射（code → status）。 */
    private static final Set<String> EXPECTED_STATUS = Set.of(
            "TSS_INVALID_ARGUMENT=400",
            "TSS_INVALID_CURSOR=400",
            "TSS_UNAUTHORIZED=401",
            "TSS_SESSION_EXPIRED=401",
            "TSS_NOT_BOUND=403",
            "TSS_FORBIDDEN=403",
            "TSS_ENT_SCOPE_DENIED=403",
            "TSS_BINDING_DISABLED=403",
            "TSS_ADMIN_FORBIDDEN=403",
            "TSS_WRITE_DISABLED=403",
            "TSS_NOT_FOUND=404",
            "TSS_BIND_INVITE_EXPIRED=409",
            "TSS_BIND_CONFLICT=409",
            "TSS_IDEMPOTENCY_CONFLICT=409",
            "TSS_DATA_STALE=409",
            "TSS_RATE_LIMITED=429",
            "TSS_INTERNAL_ERROR=500",
            "TSS_WECHAT_UNAVAILABLE=503",
            "TSS_ERP_READ_UNAVAILABLE=503",
            "TSS_NOT_CONFIGURED=503");

    /** 权威可重试标记（只有这 5 条性质上可重试）。 */
    private static final Set<String> RETRYABLE = Set.of(
            "TSS_DATA_STALE", "TSS_RATE_LIMITED", "TSS_INTERNAL_ERROR",
            "TSS_WECHAT_UNAVAILABLE", "TSS_ERP_READ_UNAVAILABLE");

    @Test
    @DisplayName("枚举条数必须正好 20（不多不少）")
    void size_is_exactly_twenty() {
        assertEquals(20, TssErrorCode.values().length,
                "对外错误码只允许权威 20 条，实际 " + TssErrorCode.values().length + " 条");
        assertEquals(20, TssErrorCode.coreSetSize(), "coreSetSize() 必须等于 20");
    }

    @Test
    @DisplayName("枚举取值集合与权威清单逐条相等")
    void names_match_authoritative_set() {
        Set<String> actual = new LinkedHashSet<>();
        for (TssErrorCode c : TssErrorCode.values()) {
            actual.add(c.name());
        }
        assertEquals(new TreeSet<>(AUTHORITATIVE), new TreeSet<>(actual),
                "存在非权威码或缺失权威码：\n多出=" + diff(actual, AUTHORITATIVE)
                        + "\n缺失=" + diff(AUTHORITATIVE, actual));
    }

    @Test
    @DisplayName("HTTP 状态映射与权威清单一致")
    void http_status_matches() {
        Set<String> actual = new LinkedHashSet<>();
        for (TssErrorCode c : TssErrorCode.values()) {
            actual.add(c.name() + "=" + c.httpStatus());
            assertTrue(c.defaultMessage() != null && !c.defaultMessage().isBlank(),
                    c.name() + " 必须有默认 message");
            assertTrue(c.isCoreSet(), c.name() + " 必须属于权威主集");
        }
        assertEquals(new TreeSet<>(EXPECTED_STATUS), new TreeSet<>(actual),
                "HTTP 映射不符：\n期望=" + new TreeSet<>(EXPECTED_STATUS) + "\n实际=" + new TreeSet<>(actual));
    }

    @Test
    @DisplayName("可重试标记与权威清单一致")
    void retryable_matches() {
        Set<String> actual = new LinkedHashSet<>();
        for (TssErrorCode c : TssErrorCode.values()) {
            if (c.retryable()) {
                actual.add(c.name());
            }
        }
        assertEquals(new TreeSet<>(RETRYABLE), new TreeSet<>(actual));
    }

    /** 求差集（便于失败信息可读）。 */
    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }
}
