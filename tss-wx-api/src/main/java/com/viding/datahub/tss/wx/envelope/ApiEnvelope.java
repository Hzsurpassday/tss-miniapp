package com.viding.datahub.tss.wx.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 统一响应包络（F1）。
 *
 * <p>成功：{@code code="SUCCESS"}、HTTP 200、{@code data} 为 RC2 定义的业务结构。
 * 失败：{@code code} 为稳定 {@code TSS_*} 错误码、HTTP 为对应 4xx/5xx、{@code data} 恒为 {@code null}。
 *
 * <p>已废弃字段（F1）：{@code requestId} / {@code serverTime} / {@code sourceMode} 一律不再输出；
 * {@code dataMode} 只出现在 {@code data.meta.dataMode} 中。
 *
 * <p>{@code additionalProperties:false}：本结构只有这 5 个字段，不多不少。
 *
 * @param <T> RC2 定义的业务数据结构类型
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiEnvelope<T>(
        String code,
        String message,
        T data,
        String traceId,
        String timestamp
) {

    public static final String SUCCESS_CODE = "SUCCESS";
    public static final String SUCCESS_MESSAGE = "OK";

    /**
     * 构造成功包络。
     *
     * @param data     业务数据（RC2 结构，不可为 null；RC2 未定义「成功但无数据」的形态）
     * @param traceId  本次请求的追踪 ID
     * @param timestamp 带时区偏移的 ISO-8601 时间字符串，如 {@code 2026-10-31T10:30:00+08:00}
     */
    public static <T> ApiEnvelope<T> success(T data, String traceId, String timestamp) {
        return new ApiEnvelope<>(SUCCESS_CODE, SUCCESS_MESSAGE, data, traceId, timestamp);
    }

    /**
     * 构造失败包络。
     *
     * @param error    稳定错误码
     * @param message  面向调用方的简短说明（不泄露内部细节）
     * @param traceId  本次请求的追踪 ID
     * @param timestamp 带时区偏移的 ISO-8601 时间字符串
     */
    public static <T> ApiEnvelope<T> failure(TssErrorCode error, String message, String traceId, String timestamp) {
        return new ApiEnvelope<>(error.name(),
                message == null || message.isBlank() ? error.defaultMessage() : message,
                null, traceId, timestamp);
    }
}
