package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 {@code SummaryData}（API-03 响应 data）。
 *
 * <p>F2：新鲜度不在本结构里，UI 一律读 {@code meta.freshness}；旧的 {@code dataFreshness} 已移除。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SummaryData(
        CountGroup counts,
        String lastFetchedAt,
        DataMeta meta
) {
}
