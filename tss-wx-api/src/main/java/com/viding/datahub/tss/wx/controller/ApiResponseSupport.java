package com.viding.datahub.tss.wx.controller;

import com.viding.datahub.tss.wx.config.TssWxClock;
import com.viding.datahub.tss.wx.envelope.ApiEnvelope;
import com.viding.datahub.tss.wx.envelope.TraceContext;
import org.springframework.http.ResponseEntity;

/**
 * 统一包络输出支持（F1）。
 *
 * <p>成功一律 {@code HTTP 200 + code=SUCCESS + data}；失败由 {@code TssEnvelopeAdvice} 统一处理，
 * 控制器只需抛 {@code TssApiException}，不自行拼装错误响应，避免状态与错误码不一致。
 */
public abstract class ApiResponseSupport {

    protected final TssWxClock clock;

    protected ApiResponseSupport(TssWxClock clock) {
        this.clock = clock;
    }

    /** 成功响应：HTTP 200 + 统一包络。 */
    protected <T> ResponseEntity<ApiEnvelope<T>> ok(T data) {
        return ResponseEntity.ok(ApiEnvelope.success(data, TraceContext.current(), clock.isoNow()));
    }
}
