package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.DocType;
import com.viding.datahub.tss.wx.dto.TssEnums.ValidationLevel;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * RC2 {@code MappingDefinition}（API-13 业务映射定义）。
 *
 * <p>{@code validationLevel} 反映该业务类型的 ERP 字段核实程度：
 * {@code ERP_VERIFIED} 已核实 / {@code PARTIAL} 部分核实 / {@code PENDING} 待核实。
 * 未核实的字段 {@code verified=false}，不得在 UI 上伪装成已实证。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MappingDefinition(
        DocType docType,
        String formId,
        String displayName,
        ValidationLevel validationLevel,
        List<MappingField> fields
) {
}
