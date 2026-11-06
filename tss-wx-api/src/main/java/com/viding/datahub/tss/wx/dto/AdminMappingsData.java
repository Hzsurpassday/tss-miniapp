package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.ValidationLevel;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** RC2 {@code AdminMappingsData}（API-13 响应 data）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminMappingsData(
        List<MappingDefinition> definitions,
        ValidationLevel validationLevel,
        String updatedAt
) {
}
