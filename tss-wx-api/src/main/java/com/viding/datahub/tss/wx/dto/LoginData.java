package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/** RC2 {@code LoginData}（API-01 响应 data）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LoginData(
        String sessionToken,
        int expiresIn,
        BindStatus bindStatus,
        boolean needBinding
) {
}
