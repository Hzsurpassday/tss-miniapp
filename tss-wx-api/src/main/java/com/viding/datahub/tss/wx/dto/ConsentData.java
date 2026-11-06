package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.SubscriptionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/** RC2 {@code ConsentData}（API-08 响应 data）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConsentData(
        SubscriptionStatus subscriptionStatus,
        String recordedAt
) {
}
