package com.viding.datahub.tss.wx.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.viding.datahub.tss.wx.dto.TssEnums.BindingStatusValue;

/**
 * DEV 专用绑定夹具的请求体 / 返回结果（<b>不属于 RC2 契约</b>）。
 *
 * <p>存在理由：RC2 冻结的 13 个端点里<b>没有</b> PC 端确认端点（契约明确该动作由 PC/后端另行实现），
 * 因此「PC 确认通过后」「申请已过期」「绑定被停用」这几格在纯契约路径下无法被真实 HTTP 复现。
 * 为了让五态/八态矩阵可复现、可验收，这里提供一个与 Python 侧 {@code ctrl_pc_confirm}
 * 同性质的控制面夹具——同样<b>不是契约端点</b>，同样只在 DEV 环境装配。
 */
public final class BindingFixture {

    private BindingFixture() {
    }

    /** 夹具动作。 */
    public enum Action {
        /** 模拟 PC 端确认通过：绑定关系落成 BOUND，申请单即刻终结（裁决 B）。 */
        PC_CONFIRM,
        /** 让当前申请单立即过期（无需等待 10 分钟 TTL）。 */
        EXPIRE,
        /** 让当前申请单被 PC 端拒绝（终态 ⇒ 归档 ⇒ bindRequestId 归 null）。 */
        REJECT,
        /** 把绑定关系置为 DISABLED（停用）。 */
        DISABLE
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Request(
            Action action,
            String entId,
            String userId,
            String bindRequestId
    ) {
    }

    /**
     * 夹具执行结果：{@code bindStatus} / {@code status} / {@code bindRequestId}
     * 直接取自 {@link BindingViewResolver} 的解析结果，即「客户端随后调用
     * {@code /wx/v1/me} 与 {@code /wx/v1/bindings/status} 会看到的同一份值」。
     */
    public record Result(
            Action action,
            String openidMasked,
            String bindRequestId,
            BindStatus bindStatus,
            BindingStatusValue status,
            boolean requestArchived,
            String at
    ) {
    }
}
