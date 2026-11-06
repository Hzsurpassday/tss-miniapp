package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.DataMode;
import com.viding.datahub.tss.wx.dto.TssEnums.DocType;
import com.viding.datahub.tss.wx.dto.TssEnums.TodoStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 {@code TodoItem}（API-04 列表项）。
 *
 * <p>{@code amount} 为十进制金额字符串（如 {@code "19800.00"}），不用浮点，避免精度漂移。
 * {@code dataMode} 说明该项数据的来源模式（MOCK/ERP）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TodoItem(
        String approvalId,
        String taskNum,
        String instanceId,
        String billNo,
        DocType docType,
        String title,
        String counterparty,
        String amount,
        String currency,
        String currentNodeName,
        String createdAt,
        String updatedAt,
        TodoStatus status,
        DataMode dataMode
) {
}
