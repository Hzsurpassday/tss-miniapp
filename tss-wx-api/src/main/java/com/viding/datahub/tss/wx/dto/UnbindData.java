package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/** RC2 {@code UnbindData}（API-11 解绑响应 data）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UnbindData(
        String revokedAt,
        BindStatus bindStatus
) {
}
