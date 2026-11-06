package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.DataMode;
import com.viding.datahub.tss.wx.dto.TssEnums.Freshness;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 {@code DataMeta}：数据模式与新鲜度。
 *
 * <p>F1：{@code dataMode} 只出现在 {@code data.meta.dataMode}，不再有顶层 {@code sourceMode}。
 * F2：新鲜度统一为 {@code meta.freshness}（{@code FRESH|DELAYED|UNAVAILABLE}），
 * 已移除旧的 {@code SummaryData.dataFreshness}。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataMeta(
        DataMode dataMode,
        String asOf,
        Freshness freshness,
        String schemaVersion
) {
}
