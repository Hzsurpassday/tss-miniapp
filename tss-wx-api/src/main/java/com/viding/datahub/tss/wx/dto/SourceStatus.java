package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.DownstreamReceiptStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 {@code SourceStatus}：源单状态与下游回证实况。
 *
 * <p>{@code isFinalApproved} / {@code inventoryChanged} / {@code lastVerifiedAt} 允许为 null，
 * 表示「未核实」，不得由 UI 推断为 false 或「已完成」。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SourceStatus(
        String sourceName,
        Boolean isFinalApproved,
        DownstreamReceiptStatus downstreamReceiptStatus,
        Boolean inventoryChanged,
        String lastVerifiedAt,
        String notes
) {
}
