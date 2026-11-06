package com.viding.datahub.tss.wx.envelope;

import com.viding.datahub.tss.wx.config.TssWxClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把异常统一转成 RC2 包络（F1）：HTTP 状态 + {@code TSS_*} 稳定错误码 + {@code data=null}。
 *
 * <p>注意 HTTP 405 未出现在 RC2 各端点的响应声明中，此处按「最接近的稳定码」处理：
 * 状态用 405、{@code code} 用 {@code TSS_INVALID_ARGUMENT}，并在日志留痕，不臆造新的 RC2 状态码。
 */
@RestControllerAdvice(basePackages = "com.viding.datahub.tss.wx.controller")
public class TssEnvelopeAdvice {

    private static final Logger log = LoggerFactory.getLogger(TssEnvelopeAdvice.class);

    private final TssWxClock clock;

    public TssEnvelopeAdvice(TssWxClock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(TssApiException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleTssApi(TssApiException ex) {
        String traceId = TraceContext.current();
        if (ex.httpStatus() >= 500) {
            log.error("[TSS-WX] error traceId={} code={} msg={}", traceId, ex.errorCode().name(), ex.getMessage(), ex);
        } else {
            log.warn("[TSS-WX] reject traceId={} code={} msg={}", traceId, ex.errorCode().name(), ex.getMessage());
        }
        return ResponseEntity.status(ex.httpStatus())
                .body(ApiEnvelope.failure(ex.errorCode(), ex.getMessage(), traceId, clock.isoNow()));
    }

    /**
     * 请求体不可读：RC2 请求 schema 均为 {@code additionalProperties:false}，
     * 出现未知字段或类型不匹配即 400（而不是静默忽略）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return badRequest("请求体不符合契约：存在未知字段、缺少必需字段或字段类型不匹配");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("缺少必需参数: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("参数类型不合法: " + ex.getName());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return badRequest("不支持的 Content-Type");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String traceId = TraceContext.current();
        log.warn("[TSS-WX] method not allowed traceId={} method={}", traceId, ex.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiEnvelope.failure(TssErrorCode.TSS_INVALID_ARGUMENT,
                        "不支持的请求方法: " + ex.getMethod(), traceId, clock.isoNow()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return badRequest(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope<Void>> handleUnknown(Exception ex) {
        String traceId = TraceContext.current();
        log.error("[TSS-WX] unhandled traceId={}", traceId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiEnvelope.failure(TssErrorCode.TSS_INTERNAL_ERROR,
                        TssErrorCode.TSS_INTERNAL_ERROR.defaultMessage(), traceId, clock.isoNow()));
    }

    private ResponseEntity<ApiEnvelope<Void>> badRequest(String message) {
        String traceId = TraceContext.current();
        log.warn("[TSS-WX] bad request traceId={} msg={}", traceId, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiEnvelope.failure(TssErrorCode.TSS_INVALID_ARGUMENT, message, traceId, clock.isoNow()));
    }
}
