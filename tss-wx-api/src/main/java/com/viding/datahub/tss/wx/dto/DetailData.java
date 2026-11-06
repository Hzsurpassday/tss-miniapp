package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.AvailableAction;
import com.viding.datahub.tss.wx.dto.TssEnums.DocType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * RC2 {@code DetailData}（API-05 响应 data）。
 *
 * <p>{@code availableActions}：V1.0 只读，只输出 {@code VIEW}；
 * {@code OPEN_ERP} 只有在跳转链路经过验证后才会输出，未验证则一律不返回（决议特别声明）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DetailData(
        String approvalId,
        String taskNum,
        DocType docType,
        List<BaseField> base,
        List<LineItem> lines,
        Workflow workflow,
        SourceStatus sourceStatus,
        List<AvailableAction> availableActions,
        DataMeta meta
) {
}
