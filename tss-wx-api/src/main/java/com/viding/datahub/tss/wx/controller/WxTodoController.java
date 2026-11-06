package com.viding.datahub.tss.wx.controller;

import com.viding.datahub.tss.wx.auth.ClientIdentityGuard;
import com.viding.datahub.tss.wx.auth.WxAuthService;
import com.viding.datahub.tss.wx.auth.WxIdentity;
import com.viding.datahub.tss.wx.config.TssWxClock;
import com.viding.datahub.tss.wx.dto.DetailData;
import com.viding.datahub.tss.wx.dto.SummaryData;
import com.viding.datahub.tss.wx.dto.TodoItem;
import com.viding.datahub.tss.wx.dto.TodoListData;
import com.viding.datahub.tss.wx.dto.TssEnums;
import com.viding.datahub.tss.wx.envelope.ApiEnvelope;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import com.viding.datahub.tss.wx.mock.MockDataStore;
import com.viding.datahub.tss.wx.support.ConditionalGetSupport;
import com.viding.datahub.tss.wx.support.PagingSupport;
import com.viding.datahub.tss.wx.support.QuerySupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * API-03 {@code GET /wx/v1/todos/summary}、API-04 {@code GET /wx/v1/todos}、
 * API-05 {@code GET /wx/v1/todos/{taskNum}/detail}。
 *
 * <p>V1.0 只读：所有端点先按服务端身份做本人隔离（先过滤本人，再筛选），
 * 绝不存在「先全量查询再按客户端参数过滤」的路径。
 */
@RestController
@RequestMapping(path = "/wx/v1/todos", produces = MediaType.APPLICATION_JSON_VALUE)
public class WxTodoController extends ApiResponseSupport {

    private final WxAuthService authService;
    private final MockDataStore store;
    private final ClientIdentityGuard identityGuard;
    private final QuerySupport query;
    private final PagingSupport paging;
    private final ConditionalGetSupport conditional;

    public WxTodoController(TssWxClock clock,
                            WxAuthService authService,
                            MockDataStore store,
                            ClientIdentityGuard identityGuard,
                            QuerySupport query,
                            PagingSupport paging,
                            ConditionalGetSupport conditional) {
        super(clock);
        this.authService = authService;
        this.store = store;
        this.identityGuard = identityGuard;
        this.query = query;
        this.paging = paging;
        this.conditional = conditional;
    }

    // ------------------------------------------------------------ API-03

    @GetMapping("/summary")
    public ResponseEntity<ApiEnvelope<SummaryData>> summary(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        identityGuard.rejectIfClientIdentitySupplied(request);
        WxIdentity id = authService.requireBound(authorization);
        return ok(store.summary(id.entId(), id.userId()));
    }

    // ------------------------------------------------------------ API-04

    @GetMapping
    public ResponseEntity<ApiEnvelope<TodoListData>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "docType", required = false) String docTypeRaw,
            @RequestParam(value = "keyword", required = false) String keywordRaw,
            HttpServletRequest request) {
        identityGuard.rejectIfClientIdentitySupplied(request);
        WxIdentity id = authService.requireBound(authorization);

        TssEnums.DocType docType = query.docType(docTypeRaw);
        String keyword = query.keyword(keywordRaw);

        // ① 本人隔离（服务端身份） → ② 筛选 → ③ 稳定排序
        List<TodoItem> mine = store.todos(id.entId(), id.userId());
        List<TodoItem> filtered = new ArrayList<>(mine);
        if (docType != null) {
            filtered.removeIf(t -> t.docType() != docType);
        }
        if (keyword != null) {
            filtered.removeIf(t -> !(ConditionalGetSupport.contains(t.billNo(), keyword)
                    || ConditionalGetSupport.contains(t.title(), keyword)
                    || ConditionalGetSupport.contains(t.counterparty(), keyword)));
        }
        filtered.sort(Comparator.comparing(TodoItem::updatedAt).reversed()
                .thenComparing(TodoItem::taskNum));

        String filterHash = paging.filterHash("todos",
                docType == null ? "" : docType.name(), keyword == null ? "" : keyword);
        PagingSupport.PageResult<TodoItem> page = paging.page(filtered, cursor, limit,
                id.userId(), id.entId(), filterHash, store.sortAnchor());

        return ok(new TodoListData(page.items(), page.page(), store.meta()));
    }

    // ------------------------------------------------------------ API-05

    @GetMapping("/{taskNum}/detail")
    public ResponseEntity<ApiEnvelope<DetailData>> detail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("taskNum") String taskNum,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @RequestHeader(value = HttpHeaders.IF_MODIFIED_SINCE, required = false) String ifModifiedSince,
            HttpServletRequest request) {
        identityGuard.rejectIfClientIdentitySupplied(request);
        WxIdentity id = authService.requireBound(authorization);
        if (taskNum == null || taskNum.isBlank()) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "taskNum 不能为空");
        }

        // 资源存在性（全局）与可见性（本人）分别判定：不存在 404，存在但不属于本人 403
        DetailData detail = store.detailOrNull(taskNum);
        if (detail == null) {
            throw new TssApiException(TssErrorCode.TSS_NOT_FOUND, "单据不存在: " + taskNum);
        }
        if (!store.ownsDetail(taskNum, id.entId(), id.userId())) {
            // D-1 补充：把「跨企业」从笼统的 403 里分出来。
            //   单据归属企业 ≠ 会话授权企业 ⇒ 403 TSS_ENT_SCOPE_DENIED（作用域越界，换人也无效）；
            //   同企业但不属于本人     ⇒ 403 TSS_FORBIDDEN（资源归属，本就不在你的待办里）。
            String ownerEntId = store.detailOwnerEntId(taskNum);
            if (ownerEntId != null && !ownerEntId.equals(id.entId())) {
                throw new TssApiException(TssErrorCode.TSS_ENT_SCOPE_DENIED,
                        "该单据不属于当前授权企业，禁止跨企业查看");
            }
            throw new TssApiException(TssErrorCode.TSS_FORBIDDEN,
                    "该单据与当前用户无审批关系，禁止跨员工查看");
        }

        String etag = conditional.weakEtag(detail.approvalId(), detail.taskNum(),
                detail.workflow() == null ? "" : detail.workflow().dataVersion());
        String lastModified = conditional.toHttpDate(lastUpdatedAt(id.entId(), id.userId(), taskNum));

        if (conditional.notModified(ifNoneMatch, ifModifiedSince, etag, lastModified)) {
            // F4：命中条件 GET 返回 304 且无响应体
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.LAST_MODIFIED, lastModified)
                    .build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.LAST_MODIFIED, lastModified)
                .body(ApiEnvelope.success(withFreshMeta(detail),
                        com.viding.datahub.tss.wx.envelope.TraceContext.current(), clock.isoNow()));
    }

    /** 取该待办最近更新时间（用于 Last-Modified）；取不到则用当前时刻。 */
    private String lastUpdatedAt(String entId, String userId, String taskNum) {
        for (TodoItem t : store.todos(entId, userId)) {
            if (taskNum.equals(t.taskNum())) {
                return t.updatedAt();
            }
        }
        return clock.isoNow();
    }

    /** 把 meta.asOf 刷新为当前时刻（保留样例其它字段原值）。 */
    private DetailData withFreshMeta(DetailData d) {
        return new DetailData(d.approvalId(), d.taskNum(), d.docType(), d.base(), d.lines(),
                d.workflow(), d.sourceStatus(), d.availableActions(), store.meta());
    }
}
