package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** RC2 {@code TodoListData}（API-04 响应 data）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TodoListData(
        List<TodoItem> items,
        PageMeta page,
        DataMeta meta
) {
}
