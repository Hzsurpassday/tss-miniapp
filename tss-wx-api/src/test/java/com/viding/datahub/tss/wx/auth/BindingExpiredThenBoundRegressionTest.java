package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.viding.datahub.tss.wx.dto.TssEnums.BindingStatusValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RC3 回归：「先有 EXPIRED 申请单、后又 BOUND」这一格必须落在 BOUND 上。
 *
 * <p><b>为什么单独立一个文件</b>：{@code BindingViewResolverTest} 已覆盖
 * 「BOUND + 残留 PENDING_PC_CONFIRM 申请」与「EXPIRED + PENDING 关系」两格，
 * 但「EXPIRED 申请 + BOUND 关系」是<b>两者交叉</b>的那一格，最容易出错：
 * 步骤 ①（过期推进）若不加甄别地把关系回落为 UNBOUND，就会把一个已经生效的绑定抹掉，
 * 用户会看到「昨天还能审批、今天被踢回未绑定」却没有任何解绑动作——这类 bug 只在交叉态出现。
 *
 * <p>本测试与 {@code BindingViewResolverTest} 不共享状态（各自独立的内存存储），可并行演进。
 */
@DisplayName("RC3 回归：EXPIRED 申请单 + 已 BOUND 关系")
class BindingExpiredThenBoundRegressionTest {

    private static final String OPENID = "o_expired_then_bound";
    private static final String OPENID_2 = "o_expired_advance_guard";

    private InMemoryBindingStore bindings;
    private BindRequestStore bindRequests;
    private BindingViewResolver resolver;

    @BeforeEach
    void setUp() {
        bindings = new InMemoryBindingStore();
        bindRequests = new BindRequestStore();
        resolver = new BindingViewResolver(bindings, bindRequests);
    }

    @Test
    @DisplayName("EXPIRED 申请 + BOUND 关系 ⇒ status=BOUND / me=BOUND / bindRequestId=null，且申请单真被归档")
    void expired_request_then_bound_relation() {
        Instant now = Instant.now();
        bindRequests.save(new BindRequestRecord("br_expired_001", OPENID, "INV-EXAMPLE",
                "nonce_expired_001", BindingStatusValue.EXPIRED,
                now.minusSeconds(3600), now.minusSeconds(60),
                "E1", "U1", null, null));
        bindings.save(new BindingRecord("b_expired_001", OPENID, "E1", "U1", "示例员工", 1,
                BindStatus.BOUND, "o_****", "U1****001", "示例企业", now));

        BindingView view = resolver.resolve(boundIdentity(OPENID));

        assertEquals(BindingStatusValue.BOUND, view.status(),
                "关系已 BOUND 时，残留的过期申请不得把 status 拽回 EXPIRED");
        assertEquals(BindStatus.BOUND, view.meBindStatus(),
                "裁决 A：status=BOUND ⇒ me.bindStatus=BOUND");
        assertNull(view.bindRequestId(),
                "裁决 B：绑定成功后 bindRequestId 必须归 null（INV-B）");
        assertTrue(view.satisfiesInvariants());
        assertNull(bindRequests.latestRaw(OPENID),
                "过期申请必须被真正归档移除，而不是留在库里靠展现层遮掩");

        // 幂等：再解析一次形状完全一致（状态推进不得产生「第二次调用结果不同」的观感）
        BindingView again = resolver.resolve(boundIdentity(OPENID));
        assertEquals(view.status(), again.status());
        assertEquals(view.meBindStatus(), again.meBindStatus());
        assertNull(again.bindRequestId());
    }

    @Test
    @DisplayName("过期推进只回落 PENDING 关系，绝不把已生效的 BOUND 抹成 UNBOUND")
    void expiry_advance_must_not_downgrade_bound_relation() {
        Instant now = Instant.now();
        bindRequests.save(new BindRequestRecord("br_pending_002", OPENID_2, "INV-EXAMPLE",
                "nonce_pending_002", BindingStatusValue.PENDING_PC_CONFIRM,
                now.minusSeconds(3600), now.minusSeconds(1),
                "E1", "U1", null, null));
        bindings.save(new BindingRecord("b_bound_002", OPENID_2, "E1", "U1", "示例员工", 1,
                BindStatus.BOUND, "o_****", "U1****001", "示例企业", now));

        BindingView view = resolver.resolve(boundIdentity(OPENID_2));

        assertEquals(BindingStatusValue.BOUND, view.status());
        assertEquals(BindStatus.BOUND, bindings.findByOpenid(OPENID_2).bindStatus(),
                "申请过期不得抹掉已生效的绑定：解绑/停用之外的既有绑定必须保留");
        assertNull(view.bindRequestId());
        assertNull(bindRequests.latestRaw(OPENID_2));
    }

    private static WxIdentity boundIdentity(String openid) {
        return new WxIdentity("tok", openid, "E1", "U1", "示例员工", BindStatus.BOUND, 1,
                "U1****001", "示例企业", true);
    }
}
