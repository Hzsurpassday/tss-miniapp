package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 {@code AdminBindingsData.auditInfo}：管理端访问审计信息。
 *
 * <p>{@code scope=AUTHORIZED_ENT} 表示本次返回只覆盖该管理员被授权的企业范围，
 * 不因管理员请求而越权返回其它企业数据。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AuditInfo(
        boolean accessLogged,
        String scope
) {
    public static final String SCOPE_AUTHORIZED_ENT = "AUTHORIZED_ENT";
}
