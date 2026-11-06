package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 {@code MappingDefinition.fields[]} 元素。
 *
 * <p>{@code sourceColumn=null} 表示该字段尚未对应到确定的 ERP 来源列；
 * {@code verified=false} 表示该对应关系未经核实。两者都不得被 UI 当作既成事实。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MappingField(
        String key,
        String label,
        String sourceColumn,
        boolean nullable,
        boolean verified
) {
}
