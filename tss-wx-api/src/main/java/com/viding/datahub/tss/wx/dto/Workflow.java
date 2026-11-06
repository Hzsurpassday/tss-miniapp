package com.viding.datahub.tss.wx.dto;

import com.viding.datahub.tss.wx.dto.TssEnums.GraphType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * RC2 {@code Workflow}（F4）：内嵌于详情，未设独立端点。
 *
 * <p>{@code readOnly}：V1.0 固定 {@code true}。契约决议特别声明：V1.0 不开放真实 APPROVE/REJECT 写回，
 * 该值不因 ERP 动作配置出现而变为 false。
 *
 * <p><b>两级版本号不得混用（易错点，特此写明）</b>：
 * <ul>
 *   <li>{@code definitionVersion} —— <b>流程定义</b>版本（审批流的形状：有哪些节点、边怎么连）。
 *       流程模板改版才变，与具体单据无关；</li>
 *   <li>{@code dataVersion} —— <b>数据内容</b>版本（这张单据当前的数据快照版本）。
 *       单据数据变化才变，是条件 GET 的 ETag 参与因子之一。</li>
 * </ul>
 * 二者既不可互相顶替，也不可只输出其中一个；另注意 RC2 中<b>没有</b> {@code workflowVersion} 字段，
 * 不得新增。
 *
 * <p>{@code events}：可为<b>空数组</b>，但字段本身<b>不得省略</b>（契约 {@code required}）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Workflow(
        String workflowId,
        String instanceId,
        List<String> currentNodeIds,
        List<WorkflowNode> nodes,
        List<WorkflowEdge> edges,
        GraphType graphType,
        String dataVersion,
        String definitionVersion,
        boolean readOnly,
        List<WorkflowEvent> events
) {
}
