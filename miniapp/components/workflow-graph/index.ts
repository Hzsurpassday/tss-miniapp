/**
 * 工作流动态只读渲染组件。
 *
 * 渲染依据：RC2 `Workflow` 的 nodes / edges / graphType / parallelMode / branchGroup / events。
 *
 * **红线（决议·特别声明）**：
 *   - `readOnly` 为 true（含服务端缺失时 fail-safe 视为 true）时，本组件
 *     **不包含、不渲染任何真实审批（同意 / 驳回）按钮**，WXML 中也没有相关节点。
 *   - 只展示流转事实，不提供任何 ERP 写回。
 */

import type { Workflow } from '../../types/api';
import { buildWorkflowView, type WorkflowView } from '../../utils/workflow';

Component<
  { view: WorkflowView | null },
  {
    workflow: { type: ObjectConstructor; value: null };
    showEvents: { type: BooleanConstructor; value: boolean };
  },
  { rebuild(): void }
>({
  properties: {
    workflow: { type: Object, value: null },
    showEvents: { type: Boolean, value: true }
  },

  data: {
    view: null
  },

  observers: {
    workflow: function (workflow: Workflow | null): void {
      this.setData({ view: workflow ? buildWorkflowView(workflow) : null });
    }
  },

  methods: {
    rebuild: function (): void {
      const wf = this.data.workflow as Workflow | null;
      this.setData({ view: wf ? buildWorkflowView(wf) : null });
    }
  }
});
