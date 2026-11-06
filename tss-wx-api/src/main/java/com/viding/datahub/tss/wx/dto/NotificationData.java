package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** RC2 {@code NotificationData}（API-07 响应 data）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NotificationData(
        List<NotificationItem> items,
        PageMeta page,
        DataMeta meta
) {
}
