package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.NotificationType;
import com.fasterxml.jackson.annotation.JsonInclude;

/** RC2 {@code NotificationItem}（API-07 列表项）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NotificationItem(
        String id,
        String title,
        String body,
        NotificationType type,
        String createdAt,
        String readAt,
        String approvalId
) {
}
