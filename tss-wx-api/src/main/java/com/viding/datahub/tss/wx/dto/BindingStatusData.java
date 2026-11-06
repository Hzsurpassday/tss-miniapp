package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.BindingStatusValue;
import com.fasterxml.jackson.annotation.JsonInclude;

/** RC2 {@code BindingStatusData}（API-10 绑定状态响应 data）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BindingStatusData(
        String bindRequestId,
        BindingStatusValue status,
        String erpUserMasked,
        String entDisplayName,
        String checkedAt
) {
}
