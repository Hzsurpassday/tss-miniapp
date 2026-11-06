package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** RC2 {@code Workflow.edges[]} 元素。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WorkflowEdge(
        String from,
        String to,
        String label
) {
}
