package com.viding.datahub.tss.wx.controller;

import com.viding.datahub.tss.wx.auth.AdminPrincipal;
import com.viding.datahub.tss.wx.auth.AdminTokenService;
import com.viding.datahub.tss.wx.auth.BindingRecord;
import com.viding.datahub.tss.wx.auth.BindingStore;
import com.viding.datahub.tss.wx.config.TssWxClock;
import com.viding.datahub.tss.wx.dto.AdminBinding;
import com.viding.datahub.tss.wx.dto.AdminBindingsData;
import com.viding.datahub.tss.wx.dto.AdminMappingsData;
import com.viding.datahub.tss.wx.dto.TssEnums;
import com.viding.datahub.tss.wx.envelope.ApiEnvelope;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import com.viding.datahub.tss.wx.mock.MockDataStore;
import com.viding.datahub.tss.wx.support.PagingSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * API-12 {@code GET /admin/v1/tss/bindings}、API-13 {@code GET /admin/v1/tss/mappings}（adminAuth）。
 *
 * <p>作用域约束：管理员只看到自己被授权企业（{@code entId}）范围内的绑定，
 * {@code auditInfo.scope} 恒为 {@code AUTHORIZED_ENT}，访问一律留审计日志。
 *
 * <p><b>范围声明（F5）</b>：RC2 仅定义 PC 绑定管理与字段映射两个端点，
 * <b>不等于</b>已覆盖 PRD §8 的全套 PC 页面（概览/提醒规则/数据连接/审计/系统配置）。
 * 那部分属下一阶段独立 Admin OpenAPI 契约，当前未冻结。
 */
@RestController
@RequestMapping(path = "/admin/v1/tss", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminTssController extends ApiResponseSupport {

    private static final Logger log = LoggerFactory.getLogger(AdminTssController.class);

    private final AdminTokenService adminTokens;
    private final BindingStore bindings;
    private final MockDataStore store;
    private final PagingSupport paging;

    public AdminTssController(TssWxClock clock,
                              AdminTokenService adminTokens,
                              BindingStore bindings,
                              MockDataStore store,
                              PagingSupport paging) {
        super(clock);
        this.adminTokens = adminTokens;
        this.bindings = bindings;
        this.store = store;
        this.paging = paging;
    }

    // ------------------------------------------------------------ API-12

    @GetMapping("/bindings")
    public ResponseEntity<ApiEnvelope<AdminBindingsData>> bindings(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        AdminPrincipal admin = adminTokens.resolve(authorization);
        log.info("[TSS-WX][AUDIT] admin bindings access: role={} scopeEnt={}", admin.role(), admin.entId());

        List<AdminBinding> items = new ArrayList<>();
        for (BindingRecord r : bindings.byEntId(admin.entId())) {
            items.add(new AdminBinding(r.bindingId(), r.userId(), r.entId(),
                    r.openidMasked() == null ? maskOpenid(r.openid()) : r.openidMasked(),
                    toAdminStatus(r.bindStatus()),
                    r.bindingVersion() == null ? 0 : r.bindingVersion(),
                    format(r.updatedAt())));
        }
        items.sort(Comparator.comparing(AdminBinding::bindingId));

        String filterHash = paging.filterHash("admin-bindings", admin.entId() == null ? "" : admin.entId());
        PagingSupport.PageResult<AdminBinding> page = paging.page(items, cursor, limit,
                "admin:" + (admin.entId() == null ? "-" : admin.entId()),
                admin.entId() == null ? "-" : admin.entId(), filterHash, store.sortAnchor());

        return ok(new AdminBindingsData(page.items(), page.page(),
                new com.viding.datahub.tss.wx.dto.AuditInfo(true,
                        com.viding.datahub.tss.wx.dto.AuditInfo.SCOPE_AUTHORIZED_ENT)));
    }

    // ------------------------------------------------------------ API-13

    @GetMapping("/mappings")
    public ResponseEntity<ApiEnvelope<AdminMappingsData>> mappings(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AdminPrincipal admin = adminTokens.resolve(authorization);
        log.info("[TSS-WX][AUDIT] admin mappings access: role={} scopeEnt={}", admin.role(), admin.entId());

        AdminMappingsData data = store.mappings();
        if (data == null) {
            throw new TssApiException(TssErrorCode.TSS_NOT_FOUND, "业务映射清单不可用");
        }
        return ok(data);
    }

    private static TssEnums.AdminBindStatus toAdminStatus(TssEnums.BindStatus status) {
        if (status == null) {
            return TssEnums.AdminBindStatus.UNBOUND;
        }
        return switch (status) {
            case BOUND -> TssEnums.AdminBindStatus.BOUND;
            case PENDING -> TssEnums.AdminBindStatus.PENDING;
            case DISABLED -> TssEnums.AdminBindStatus.DISABLED;
            case UNBOUND -> TssEnums.AdminBindStatus.UNBOUND;
        };
    }

    /** openid 脱敏兜底：只保留 o_ 前缀，其余以 * 替代，绝不输出原文。 */
    private static String maskOpenid(String openid) {
        if (openid == null || openid.isBlank()) {
            return "o_****";
        }
        return "o_****";
    }

    /** 时间输出统一截断到秒（与 RC2 样例精度一致，便于客户端解析与比对）。 */
    private String format(java.time.Instant instant) {
        if (instant == null) {
            return clock.isoNow();
        }
        java.time.OffsetDateTime t = instant.atZone(clock.zone()).toOffsetDateTime()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t);
    }
}
