package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** RC2 {@code LineItem}：明细行。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LineItem(
        String lineId,
        List<BaseField> fields
) {
}
