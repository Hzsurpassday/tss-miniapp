package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.ClaimStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/** RC2 {@code ClaimData}（API-09 发起绑定响应 data）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ClaimData(
        String bindRequestId,
        ClaimStatus status,
        String expiresAt
) {
}
