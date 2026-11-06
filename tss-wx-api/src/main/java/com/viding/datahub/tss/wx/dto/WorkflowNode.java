package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.NodeStatus;
import com.viding.datahub.tss.wx.dto.TssEnums.NodeType;
import com.viding.datahub.tss.wx.dto.TssEnums.ParallelMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** RC2 {@code WorkflowNode}。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WorkflowNode(
        String nodeId,
        String name,
        NodeType nodeType,
        NodeStatus status,
        List<String> assignees,
        String completedAt,
        String branchGroup,
        ParallelMode parallelMode
) {
}
