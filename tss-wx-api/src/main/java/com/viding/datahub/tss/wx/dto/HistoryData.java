package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** RC2 {@code HistoryData}（API-06 响应 data）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record HistoryData(
        List<HistoryItem> items,
        PageMeta page,
        DataMeta meta
) {
}
