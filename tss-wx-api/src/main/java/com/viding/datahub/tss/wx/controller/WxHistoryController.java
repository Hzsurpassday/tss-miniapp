package com.viding.datahub.tss.wx.controller;

import com.viding.datahub.tss.wx.auth.ClientIdentityGuard;
import com.viding.datahub.tss.wx.auth.WxAuthService;
import com.viding.datahub.tss.wx.auth.WxIdentity;
import com.viding.datahub.tss.wx.config.TssWxClock;
import com.viding.datahub.tss.wx.dto.HistoryData;
import com.viding.datahub.tss.wx.dto.HistoryItem;
import com.viding.datahub.tss.wx.dto.TssEnums;
import com.viding.datahub.tss.wx.envelope.ApiEnvelope;
import com.viding.datahub.tss.wx.mock.MockDataStore;
import com.viding.datahub.tss.wx.support.PagingSupport;
import com.viding.datahub.tss.wx.support.QuerySupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * API-06 {@code GET /wx/v1/history} 本人历史。
 *
 * <p>F3：支持 {@code docType} / {@code action} / {@code from} / {@code to} 筛选；
 * {@code from} 与 {@code to} 均按服务端业务时区解释且为闭区间，from 不得晚于 to。
 */
@RestController
@RequestMapping(path = "/wx/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class WxHistoryController extends ApiResponseSupport {

    private final WxAuthService authService;
    private final MockDataStore store;
    private final ClientIdentityGuard identityGuard;
    private final QuerySupport query;
    private final PagingSupport paging;
    private final TssWxClock clock;

    public WxHistoryController(TssWxClock clock,
                               WxAuthService authService,
                               MockDataStore store,
                               ClientIdentityGuard identityGuard,
                               QuerySupport query,
                               PagingSupport paging) {
        super(clock);
        this.clock = clock;
        this.authService = authService;
        this.store = store;
        this.identityGuard = identityGuard;
        this.query = query;
        this.paging = paging;
    }

    @GetMapping("/history")
    public ResponseEntity<ApiEnvelope<HistoryData>> history(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "docType", required = false) String docTypeRaw,
            @RequestParam(value = "action", required = false) String actionRaw,
            @RequestParam(value = "from", required = false) String fromRaw,
            @RequestParam(value = "to", required = false) String toRaw,
            HttpServletRequest request) {
        identityGuard.rejectIfClientIdentitySupplied(request);
        WxIdentity id = authService.requireBound(authorization);

        TssEnums.DocType docType = query.docType(docTypeRaw);
        TssEnums.HistoryAction action = query.action(actionRaw);
        LocalDate from = query.date(fromRaw, "from");
        LocalDate to = query.date(toRaw, "to");
        query.assertRange(from, to);

        List<HistoryItem> filtered = new ArrayList<>(store.history(id.entId(), id.userId()));
        if (docType != null) {
            filtered.removeIf(h -> h.docType() != docType);
        }
        if (action != null) {
            filtered.removeIf(h -> h.action() != action);
        }
        if (from != null || to != null) {
            filtered.removeIf(h -> !inRange(h.processedAt(), from, to));
        }
        filtered.sort(Comparator.comparing(HistoryItem::processedAt).reversed()
                .thenComparing(HistoryItem::recordId));

        String filterHash = paging.filterHash("history",
                docType == null ? "" : docType.name(),
                action == null ? "" : action.name(),
                from == null ? "" : from.toString(),
                to == null ? "" : to.toString());
        PagingSupport.PageResult<HistoryItem> page = paging.page(filtered, cursor, limit,
                id.userId(), id.entId(), filterHash, store.sortAnchor());

        return ok(new HistoryData(page.items(), page.page(), store.meta()));
    }

    /** 闭区间日期判断，按服务端业务时区解释 processedAt。 */
    private boolean inRange(String processedAt, LocalDate from, LocalDate to) {
        if (processedAt == null || processedAt.isBlank()) {
            return false;
        }
        LocalDate day;
        try {
            day = OffsetDateTime.parse(processedAt.trim())
                    .atZoneSameInstant(clock.zone())
                    .toLocalDate();
        } catch (RuntimeException e) {
            return false;
        }
        if (from != null && day.isBefore(from)) {
            return false;
        }
        return to == null || !day.isAfter(to);
    }
}
