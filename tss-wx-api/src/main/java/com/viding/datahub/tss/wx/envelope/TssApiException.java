package com.viding.datahub.tss.wx.envelope;

/**
 * RC2 契约层业务异常：携带稳定 {@code TSS_*} 错误码与其对应 HTTP 状态，由
 * {@link TssEnvelopeAdvice} 统一转成统一包络。
 *
 * <p>约定：只要抛出本异常，HTTP 状态一定等于 {@link TssErrorCode#httpStatus()}，
 * 响应体 {@code data} 一定为 {@code null}，绝不出现「200 + 错误码」或「4xx + 正常数据」。
 */
public class TssApiException extends RuntimeException {

    private final TssErrorCode errorCode;

    public TssApiException(TssErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public TssApiException(TssErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TssApiException(TssErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public TssErrorCode errorCode() {
        return errorCode;
    }

    public int httpStatus() {
        return errorCode.httpStatus();
    }

    /** 便捷工厂：非法参数。 */
    public static TssApiException badRequest(String message) {
        return new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, message);
    }

    /** 便捷工厂：未认证。 */
    public static TssApiException unauthorized(String message) {
        return new TssApiException(TssErrorCode.TSS_UNAUTHORIZED, message);
    }

    /** 便捷工厂：无权访问。 */
    public static TssApiException forbidden(String message) {
        return new TssApiException(TssErrorCode.TSS_FORBIDDEN, message);
    }
}
