package com.viding.datahub.tss.wx.envelope;

import com.viding.datahub.tss.wx.config.TssWxClock;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 非契约路径 / 容器级错误的统一包络出口。
 *
 * <p>问题：访问未实现的路径（例如 {@code GET /admin/v1/tss/overview}）时，Servlet 容器会把请求
 * 转发到 {@code /error}，由 Spring 默认的 {@code BasicErrorController} 输出
 * {@code {timestamp,status,error,path}} —— 那是 Spring 的形状，<b>不是</b> RC2 包络（F1）。
 * 客户端若按统一包络解析就会取不到 {@code code}，只能靠 HTTP 状态猜，属契约泄漏。
 *
 * <p>处理：本类实现 Boot 的 {@link ErrorController}（声明后默认 BasicErrorController 会退出装配），
 * 把一切走到 {@code /error} 的响应改成 {@code {code,message,data,traceId,timestamp}}，
 * 且 {@code code} 只取 {@link TssErrorCode} 的权威 20 个取值之一。
 *
 * <p><b>状态映射（只做「最接近的稳定码」映射，不自造状态码）</b>：
 * <pre>
 *   401 -&gt; TSS_UNAUTHORIZED      403 -&gt; TSS_FORBIDDEN        404 -&gt; TSS_NOT_FOUND
 *   409 -&gt; TSS_DATA_STALE        429 -&gt; TSS_RATE_LIMITED     5xx -&gt; TSS_INTERNAL_ERROR
 *   其余 4xx -&gt; TSS_INVALID_ARGUMENT （含 405：RC2 未声明 405，故用最接近的 400 语义码，
 *                                      HTTP 状态仍如实返回 405，不篡改）
 * </pre>
 */
@RestController
public class TssErrorPageController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(TssErrorPageController.class);

    private final TssWxClock clock;

    public TssErrorPageController(TssWxClock clock) {
        this.clock = clock;
    }

    /**
     * 处理转发到 {@code /error} 的请求，输出统一包络。
     *
     * @param request 容器转发的错误请求（携带 {@code jakarta.servlet.error.*} 属性）
     * @return 统一包络响应，HTTP 状态沿用容器判定的状态
     */
    @RequestMapping(path = "/error", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<Void>> handle(HttpServletRequest request) {
        int status = resolveStatus(request);
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        TssErrorCode code = mapStatus(status);
        String message = (status == 404)
                ? "资源不存在或路径未实现：" + (uri == null ? "" : uri)
                : code.defaultMessage();
        String traceId = TraceContext.current();
        log.warn("[TSS-WX] error page traceId={} status={} code={} uri={}", traceId, status, code.name(), uri);
        return ResponseEntity.status(status)
                .body(ApiEnvelope.failure(code, message, traceId, clock.isoNow()));
    }

    /** 取容器判定的 HTTP 状态；取不到按 500 处理。 */
    private static int resolveStatus(HttpServletRequest request) {
        Object v = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 500;
            }
        }
        return 500;
    }

    /** 把 HTTP 状态映射到权威错误码（见类注释的映射表）。 */
    private static TssErrorCode mapStatus(int status) {
        return switch (status) {
            case 401 -> TssErrorCode.TSS_UNAUTHORIZED;
            case 403 -> TssErrorCode.TSS_FORBIDDEN;
            case 404 -> TssErrorCode.TSS_NOT_FOUND;
            case 409 -> TssErrorCode.TSS_DATA_STALE;
            case 429 -> TssErrorCode.TSS_RATE_LIMITED;
            default -> {
                if (status >= 500) {
                    yield TssErrorCode.TSS_INTERNAL_ERROR;
                }
                yield TssErrorCode.TSS_INVALID_ARGUMENT;
            }
        };
    }
}
