package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.EventActionType;
import com.viding.datahub.tss.wx.dto.TssEnums.EventOutcome;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 {@code WorkflowEvent}：实际发生的审批流转事件。
 *
 * <p>RC2 明确要求「未证实的事件不得编造」——本结构只承载服务端确有证据的事件，
 * 不因 UI 需要而补齐臆测节点。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WorkflowEvent(
        String eventId,
        String nodeId,
        EventActionType actionType,
        String menuName,
        String operatorDisplay,
        String occurredAt,
        EventOutcome outcome,
        String serialNum,
        String comment
) {
}
