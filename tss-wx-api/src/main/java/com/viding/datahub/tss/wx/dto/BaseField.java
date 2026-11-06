package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.FieldFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 {@code BaseField}：字段键值对。
 *
 * <p>{@code value} 在 RC2 中为 {@code oneOf[string|number|boolean] + nullable}，故用 {@code Object}
 * 承载，序列化时保持原始形态（字符串仍输出为字符串）。
 * {@code verified=false} 表示该字段的 ERP 来源列<b>尚未核实</b>，不得当作已验证事实展示。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BaseField(
        String key,
        String label,
        Object value,
        FieldFormat format,
        boolean verified
) {
}
