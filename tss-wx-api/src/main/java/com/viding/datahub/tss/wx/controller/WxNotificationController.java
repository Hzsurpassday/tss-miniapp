package com.viding.datahub.tss.wx.controller;

import com.viding.datahub.tss.wx.auth.ClientIdentityGuard;
import com.viding.datahub.tss.wx.auth.WxAuthService;
import com.viding.datahub.tss.wx.auth.WxIdentity;
import com.viding.datahub.tss.wx.config.TssWxClock;
import com.viding.datahub.tss.wx.config.TssWxProperties;
import com.viding.datahub.tss.wx.dto.ConsentData;
import com.viding.datahub.tss.wx.dto.ConsentRequest;
import com.viding.datahub.tss.wx.dto.NotificationData;
import com.viding.datahub.tss.wx.dto.NotificationItem;
import com.viding.datahub.tss.wx.dto.TssEnums.SubscriptionStatus;
import com.viding.datahub.tss.wx.envelope.ApiEnvelope;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import com.viding.datahub.tss.wx.mock.MockDataStore;
import com.viding.datahub.tss.wx.support.PagingSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API-07 {@code GET /wx/v1/notifications}、API-08 {@code POST /wx/v1/notifications/consent}。
 *
 * <p>API-08 只记录「用户意愿」，不谎报订阅结果：只有当模板 ID 落在服务端已知可用白名单内才算
 * {@code GRANTED}；部分命中为 {@code PARTIAL}；完全不在白名单为 {@code UNAVAILABLE}。
 */
@RestController
@RequestMapping(path = "/wx/v1/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
public class WxNotificationController extends ApiResponseSupport {

    private static final Logger log = LoggerFactory.getLogger(WxNotificationController.class);

    private final WxAuthService authService;
    private final MockDataStore store;
    private final ClientIdentityGuard identityGuard;
    private final PagingSupport paging;
    private final TssWxProperties props;

    public WxNotificationController(TssWxClock clock,
                                    WxAuthService authService,
                                    MockDataStore store,
                                    ClientIdentityGuard identityGuard,
                                    PagingSupport paging,
                                    TssWxProperties props) {
        super(clock);
        this.authService = authService;
        this.store = store;
        this.identityGuard = identityGuard;
        this.paging = paging;
        this.props = props;
    }

    // ------------------------------------------------------------ API-07

    @GetMapping
    public ResponseEntity<ApiEnvelope<NotificationData>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        identityGuard.rejectIfClientIdentitySupplied(request);
        WxIdentity id = authService.requireBound(authorization);

        List<NotificationItem> filtered = new ArrayList<>(store.notifications(id.entId(), id.userId()));
        filtered.sort(Comparator.comparing(NotificationItem::createdAt).reversed()
                .thenComparing(NotificationItem::id));

        String filterHash = paging.filterHash("notifications");
        PagingSupport.PageResult<NotificationItem> page = paging.page(filtered, cursor, limit,
                id.userId(), id.entId(), filterHash, store.sortAnchor());

        return ok(new NotificationData(page.items(), page.page(), store.meta()));
    }

    // ------------------------------------------------------------ API-08

    @PostMapping(path = "/consent", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<ConsentData>> consent(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) ConsentRequest body,
            HttpServletRequest request) {
        identityGuard.rejectIfClientIdentitySupplied(request);
        WxIdentity id = authService.requireBound(authorization);

        if (body == null) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "请求体不能为空");
        }
        if (body.templateIds() == null || body.templateIds().isEmpty()) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "templateIds 不能为空");
        }
        if (body.templateIds().stream().anyMatch(t -> t == null || t.isBlank())) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "templateIds 不能包含空值");
        }
        if (body.consented() == null) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "consented 不能为空");
        }

        SubscriptionStatus status = resolveStatus(body);
        log.info("[TSS-WX] consent recorded: openid-present={} templateIds={} consented={} -> {}",
                id.openid() != null, body.templateIds().size(), body.consented(), status);
        return ok(new ConsentData(status, clock.isoNow()));
    }

    /** 依据「服务端已知可用模板白名单」判定订阅状态，不虚构已生效。 */
    private SubscriptionStatus resolveStatus(ConsentRequest body) {
        if (!Boolean.TRUE.equals(body.consented())) {
            return SubscriptionStatus.DENIED;
        }
        Set<String> known = props.getSubscription().getKnownTemplateIds() == null
                ? Set.of()
                : props.getSubscription().getKnownTemplateIds().stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(String::trim)
                        .collect(Collectors.toSet());
        if (known.isEmpty()) {
            return SubscriptionStatus.UNAVAILABLE;
        }
        long hit = body.templateIds().stream().filter(known::contains).count();
        if (hit == 0) {
            return SubscriptionStatus.UNAVAILABLE;
        }
        return hit == body.templateIds().size() ? SubscriptionStatus.GRANTED : SubscriptionStatus.PARTIAL;
    }
}
