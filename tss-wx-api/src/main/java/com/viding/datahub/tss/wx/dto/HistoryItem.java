package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.DocType;
import com.viding.datahub.tss.wx.dto.TssEnums.HistoryAction;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 {@code HistoryItem}（API-06 列表项）。
 *
 * <p>{@code resultVerified=false} 表示「该结论尚未被回查证实」，UI 不得当作既成事实展示。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record HistoryItem(
        String recordId,
        String approvalId,
        String taskNum,
        String billNo,
        DocType docType,
        HistoryAction action,
        String actionName,
        String processedAt,
        String operatorDisplay,
        boolean resultVerified
) {
}
