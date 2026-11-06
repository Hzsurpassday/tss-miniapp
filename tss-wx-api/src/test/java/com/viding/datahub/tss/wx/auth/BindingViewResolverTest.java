package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.viding.datahub.tss.wx.dto.TssEnums.BindingStatusValue;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绑定视图解析器的不变量测试（裁决 A / 裁决 B 的断言锁）。
 *
 * <p>这里不启容器，直接驱动 {@link BindingViewResolver}：状态翻译是纯函数式的推导，
 * 用内存存储即可覆盖全部六格映射与 INV-A / INV-B / INV-C，比 HTTP 用例更快也更难被绕过。
 */
@DisplayName("绑定视图解析器：INV-A / INV-B / INV-C")
class BindingViewResolverTest {

    private static final String OPENID = "o_unit_test_0001";

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
    @DisplayName("映射表：status → me.bindStatus 逐格核对（裁决 A）")
    void mapping_table_matches_ruling_a() {
        assertEquals(BindStatus.UNBOUND, BindingView.meBindStatusFor(BindingStatusValue.UNBOUND));
        assertEquals(BindStatus.PENDING, BindingView.meBindStatusFor(BindingStatusValue.PENDING_PC_CONFIRM));
        assertEquals(BindStatus.BOUND, BindingView.meBindStatusFor(BindingStatusValue.BOUND));
        assertEquals(BindStatus.UNBOUND, BindingView.meBindStatusFor(BindingStatusValue.EXPIRED));
        assertEquals(BindStatus.UNBOUND, BindingView.meBindStatusFor(BindingStatusValue.REJECTED));
        assertEquals(BindStatus.DISABLED, BindingView.meBindStatusFor(BindingStatusValue.DISABLED));
    }

    @Test
    @DisplayName("① 未绑定且无申请：UNBOUND / UNBOUND / bindRequestId=null")
    void unbound_no_request() {
        BindingView view = resolver.resolve(identity(BindStatus.UNBOUND));
        assertEquals(BindingStatusValue.UNBOUND, view.status());
        assertEquals(BindStatus.UNBOUND, view.meBindStatus());
        assertNull(view.bindRequestId());
        assertNull(view.erpUserMasked());
        assertFalse(view.bound());
        assertTrue(view.satisfiesInvariants());
    }

    @Test
    @DisplayName("② 申请中（无关系 + 待确认申请）：PENDING_PC_CONFIRM / me=PENDING / bindRequestId 非空")
    void claiming_without_relation() {
        BindRequestRecord rec = saveRequest(BindingStatusValue.PENDING_PC_CONFIRM, Instant.now().plusSeconds(600));
        BindingView view = resolver.resolve(identity(BindStatus.UNBOUND));
        assertEquals(BindingStatusValue.PENDING_PC_CONFIRM, view.status());
        assertEquals(BindStatus.PENDING, view.meBindStatus(), "裁决 A：申请中必须是 PENDING");
        assertEquals(rec.bindRequestId(), view.bindRequestId());
        assertTrue(view.satisfiesInvariants());
    }

    @Test
    @DisplayName("③ 关系 PENDING + 待确认申请：与②同一答案（me=PENDING）")
    void pending_relation_with_request() {
        saveBinding(BindStatus.PENDING);
        BindRequestRecord rec = saveRequest(BindingStatusValue.PENDING_PC_CONFIRM, Instant.now().plusSeconds(600));
        BindingView view = resolver.resolve(identity(BindStatus.PENDING));
        assertEquals(BindingStatusValue.PENDING_PC_CONFIRM, view.status());
        assertEquals(BindStatus.PENDING, view.meBindStatus());
        assertEquals(rec.bindRequestId(), view.bindRequestId());
        assertTrue(view.satisfiesInvariants());
    }

    @Test
    @DisplayName("④ 预置已绑定（无申请）：BOUND / BOUND / bindRequestId=null")
    void preset_bound_without_request() {
        saveBinding(BindStatus.BOUND);
        BindingView view = resolver.resolve(identity(BindStatus.BOUND));
        assertEquals(BindingStatusValue.BOUND, view.status());
        assertEquals(BindStatus.BOUND, view.meBindStatus());
        assertNull(view.bindRequestId());
        assertNotNull(view.erpUserMasked());
        assertNotNull(view.entDisplayName());
        assertTrue(view.bound());
        assertTrue(view.satisfiesInvariants());
    }

    @Test
    @DisplayName("⑤ 裁决 B：关系已 BOUND 时残留申请被即刻归档，bindRequestId=null（与④同形状）")
    void bound_archives_leftover_request() {
        saveBinding(BindStatus.BOUND);
        saveRequest(BindingStatusValue.PENDING_PC_CONFIRM, Instant.now().plusSeconds(600));
        BindingView view = resolver.resolve(identity(BindStatus.BOUND));
        assertEquals(BindingStatusValue.BOUND, view.status());
        assertNull(view.bindRequestId(), "裁决 B：绑定成功后 bindRequestId 必须归 null");
        assertTrue(view.satisfiesInvariants());
        assertNull(bindRequests.latestRaw(OPENID), "申请单必须真的被移除，而不是留在库里靠展现层遮掩");
    }

    @Test
    @DisplayName("⑥ 停用：DISABLED / DISABLED / bindRequestId=null")
    void disabled_relation() {
        saveBinding(BindStatus.DISABLED);
        saveRequest(BindingStatusValue.PENDING_PC_CONFIRM, Instant.now().plusSeconds(600));
        BindingView view = resolver.resolve(identity(BindStatus.DISABLED));
        assertEquals(BindingStatusValue.DISABLED, view.status());
        assertEquals(BindStatus.DISABLED, view.meBindStatus());
        assertNull(view.bindRequestId());
        assertNull(view.erpUserMasked(), "INV-C：非 BOUND 不得给出脱敏账号");
        assertTrue(view.satisfiesInvariants());
    }

    @Test
    @DisplayName("⑦ 申请过期：status=EXPIRED / me=UNBOUND / bindRequestId 保留，且关系回落 UNBOUND")
    void expired_request() {
        saveBinding(BindStatus.PENDING);
        BindRequestRecord rec = saveRequest(BindingStatusValue.PENDING_PC_CONFIRM, Instant.now().minusSeconds(1));
        BindingView view = resolver.resolve(identity(BindStatus.PENDING));
        assertEquals(BindingStatusValue.EXPIRED, view.status());
        assertEquals(BindStatus.UNBOUND, view.meBindStatus(), "裁决 A：EXPIRED↔UNBOUND");
        assertEquals(rec.bindRequestId(), view.bindRequestId(), "INV-B：EXPIRED 保留申请单号（需用户重发）");
        assertTrue(view.satisfiesInvariants());
        assertEquals(BindStatus.UNBOUND, bindings.findByOpenid(OPENID).bindStatus(),
                "申请过期后「已提交待确认」这一中间态不复存在，关系回落未绑定");
    }

    @Test
    @DisplayName("⑧ 申请被拒：REJECTED 必须可观测 ⇒ status=REJECTED / me=UNBOUND / bindRequestId 保留非空")
    void rejected_request() {
        saveBinding(BindStatus.PENDING);
        BindRequestRecord rec = saveRequest(BindingStatusValue.PENDING_PC_CONFIRM, Instant.now().plusSeconds(600));
        bindRequests.markRejected(rec.bindRequestId());
        BindingView view = resolver.resolve(identity(BindStatus.PENDING));
        assertEquals(BindingStatusValue.REJECTED, view.status(),
                "REJECTED 不得被 advance() 当场归档，否则 translate() 里的 REJECTED 分支永远走不到（契约死值）");
        assertEquals(BindStatus.UNBOUND, view.meBindStatus(), "INV-A：REJECTED↔UNBOUND");
        assertEquals(rec.bindRequestId(), view.bindRequestId(),
                "裁决 B 修订：REJECTED 保留申请单号——用户必须能看到「我被拒了」，而不是和没申请过一样");
        assertTrue(view.satisfiesInvariants());
        assertNotNull(bindRequests.latestRaw(OPENID), "REJECTED 不得被移除，否则「按下拒绝」与「没按过」无从区分");

        // 稳定态：连续两次解析形态一致（REJECTED 不是一拍即灭）
        BindingView again = resolver.resolve(identity(BindStatus.PENDING));
        assertEquals(BindingStatusValue.REJECTED, again.status());
        assertEquals(rec.bindRequestId(), again.bindRequestId());
        assertTrue(again.satisfiesInvariants());
    }

    @Test
    @DisplayName("⑨ 显式 bindRequestId：命中则回显；不命中 / 已被归档一律 404 TSS_NOT_FOUND")
    void explicit_bind_request_id() {
        BindRequestRecord rec = saveRequest(BindingStatusValue.PENDING_PC_CONFIRM, Instant.now().plusSeconds(600));
        BindingView hit = resolver.resolve(identity(BindStatus.UNBOUND), rec.bindRequestId());
        assertEquals(rec.bindRequestId(), hit.bindRequestId());

        TssApiException notFound = assertThrows(TssApiException.class,
                () -> resolver.resolve(identity(BindStatus.UNBOUND), "br_someone_else"));
        assertSame(TssErrorCode.TSS_NOT_FOUND, notFound.errorCode());

        saveBinding(BindStatus.BOUND);
        TssApiException archived = assertThrows(TssApiException.class,
                () -> resolver.resolve(identity(BindStatus.BOUND), rec.bindRequestId()));
        assertSame(TssErrorCode.TSS_NOT_FOUND, archived.errorCode(), "已归档的申请单同样按不存在处理");
    }

    @Test
    @DisplayName("⑩ Invariant 自检能识别被破坏的视图（用作测试的断言工具，生产路径不静默降级）")
    void invariant_self_check_detects_violation() {
        BindingView broken = new BindingView(BindStatus.UNBOUND, BindingStatusValue.PENDING_PC_CONFIRM,
                null, null, null, false);
        assertFalse(broken.satisfiesInvariants(), "status=PENDING 却 bindRequestId=null 应被识别为破坏 INV-B");
        BindingView ok = new BindingView(BindStatus.PENDING, BindingStatusValue.PENDING_PC_CONFIRM,
                "br_001", null, null, false);
        assertTrue(ok.satisfiesInvariants());
    }

    // ------------------------------------------------------------------ 夹具

    private WxIdentity identity(BindStatus bindStatus) {
        return new WxIdentity("tok", OPENID, "E1", "U1", "示例员工", bindStatus, 1,
                bindStatus == BindStatus.BOUND ? "U1****001" : null,
                bindStatus == BindStatus.BOUND ? "示例企业" : null, true);
    }

    private void saveBinding(BindStatus status) {
        bindings.save(new BindingRecord("b_unit", OPENID, "E1", "U1", "示例员工", 1, status,
                "o_****", "U1****001", "示例企业", Instant.now()));
    }

    private BindRequestRecord saveRequest(BindingStatusValue status, Instant expiresAt) {
        Instant now = Instant.now();
        BindRequestRecord rec = new BindRequestRecord(bindRequests.nextBindRequestId(), OPENID,
                "INV-EXAMPLE", "nonce_unit", status, now, expiresAt, null, null, null, null);
        bindRequests.save(rec);
        return rec;
    }
}
