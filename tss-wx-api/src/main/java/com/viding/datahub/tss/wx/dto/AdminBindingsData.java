package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** RC2 {@code AdminBindingsData}（API-12 响应 data）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminBindingsData(
        List<AdminBinding> items,
        PageMeta page,
        AuditInfo auditInfo
) {
}
