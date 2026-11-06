/**
 * 工作流动态只读渲染的视图模型构建。
 *
 * 渲染规则（RC2 + 决议 F4 + 特别声明）：
 *   - `graphType`：LINEAR 单链；DAG 分支图；UNKNOWN 按兜底展示（不做结构猜测，仅按 nodes 顺序平铺）。
 *   - `branchGroup` + `parallelMode`：同 branchGroup 的节点渲染为一个并行分组；
 *     ANY=或签、ALL=会签、SEQUENTIAL=依次审批、NONE=不标注。
 *   - `edges`：用于在节点之间展示连线与 label；不据此重排节点顺序（顺序以 nodes 为准）。
 *   - `events[]`：按 occurredAt 升序展示处理人/时间/结果/意见；缺失则不展示，绝不编造事件。
 *   - `readOnly`：**任何情况下不得渲染真实审批（同意/驳回）按钮**。
 *
 * 缺失字段防御（已实测，仅针对远程 Mock）：
 *   远程 Mock（http://39.106.179.158:9000）的 `/wx/v1/todos/{taskNum}/detail` 响应中，
 *   `workflow` 缺少 RC2 要求的 `definitionVersion` / `readOnly` / `events` 三个必填字段，
 *   另多返回非 RC2 字段 `workflowVersion`。**这是对方服务的单方缺陷。**
 *
 *   本机 `tss-wx-api`（我方实现）**不存在该问题**：`dto/Workflow.java` 含
 *   `dataVersion` / `definitionVersion` / `readOnly`，并有独立 `dto/WorkflowEvent.java`；
 *   其 `resources/mock/api-examples-rc2.json` 的 API-05 workflow 四个字段齐全，
 *   全模块无 `workflowVersion`。因此打本机时本文件的缺失分支不会触发。
 *
 *   无论如何，防御逻辑都保留：
 *     - `readOnly` 缺失 → **fail-safe 视为 true**（只允许只读，绝不暴露审批入口）；
 *     - `definitionVersion` 缺失 → 显示「服务端未提供」；
 *     - `events` 缺失 → 时间轴区域显示「服务端未返回流转事件」，不编造任何事件。
 *   同时在 `missingFields` 中列出缺失项，UI 明确提示，便于联调定位。
 */

import type {
  Workflow,
  WorkflowNode,
  WorkflowEdge,
  WorkflowEvent,
  AvailableAction
} from '../types/api';
import { formatDateTime } from './format';
import {
  NODE_STATUS_TEXT,
  NODE_TYPE_TEXT,
  PARALLEL_MODE_TEXT,
  GRAPH_TYPE_TEXT,
  EVENT_ACTION_TEXT,
  EVENT_OUTCOME_TEXT,
  nodeStatusClass,
  textOf
} from './dict';

/** 单个节点的渲染模型。 */
export interface WorkflowNodeView {
  nodeId: string;
  name: string;
  nodeTypeText: string;
  /** 节点类型样式类：抄送（CC）与审批节点视觉上必须可区分，不能混在一起看。 */
  nodeTypeClass: string;
  /** 是否为抄送节点（nodeType=CC）：抄送不是审批动作，UI 不得与审批节点同观感。 */
  isCc: boolean;
  status: string;
  statusText: string;
  statusClass: string;
  assigneeText: string;
  hasAssignees: boolean;
  completedAtText: string;
  /** 原值（NONE / ANY / ALL / SEQUENTIAL / UNKNOWN）；分组级标注按原值判断，不按文案。 */
  parallelMode: string;
  parallelModeText: string;
  branchGroup: string | null;
  isCurrent: boolean;
}

/**
 * 并行/分支分组的渲染模型。
 *
 * <p>`branchGroup` 在现有夹具里恒为 null，但契约允许它是任意字符串——
 * 服务端一旦真的下发分组值，走的就全是没被真实数据验证过的路径。
 * 因此分组逻辑<b>不假设恒为 null</b>：分组键加前缀区分主干/分支（防止服务端下发
 * `__main__` 时与主干撞键），空串按主干处理。
 */
export interface WorkflowGroupView {
  /** branchGroup 原值；null 表示主干（无分支标记）。 */
  branchGroup: string | null;
  /** WXML wx:key 用的稳定键（主干为 '@main'，分支为 '#<原值>'）。 */
  groupKey: string;
  groupLabel: string;
  parallelMode: string;
  parallelModeText: string;
  showParallelTag: boolean;
  nodes: WorkflowNodeView[];
}

/** 流转事件（时间轴）的渲染模型。 */
export interface WorkflowEventView {
  eventId: string;
  nodeId: string;
  nodeName: string;
  actionText: string;
  actionClass: string;
  operatorText: string;
  occurredAtText: string;
  outcomeText: string;
  serialNumText: string;
  commentText: string;
  hasComment: boolean;
  menuNameText: string;
}

/** 工作流完整渲染模型。 */
export interface WorkflowView {
  workflowId: string;
  instanceId: string;
  graphTypeText: string;
  graphType: string;
  /**
   * graphType 的降级说明（空串表示无降级）。
   *
   * 本组件<b>不做拓扑布局</b>：`DAG` 按 branchGroup 分组后<b>顺序</b>展示，
   * `UNKNOWN` 按 nodes 顺序平铺——两者都是降级渲染，必须如实标注，不得谎称"完整支持分支图"。
   */
  graphTypeNote: string;
  /** 是否处于降级渲染（DAG / UNKNOWN / 缺失）。 */
  graphTypeDegraded: boolean;
  definitionVersionText: string;
  dataVersionText: string;
  /** 只读：true 时严禁出现真实审批按钮。 */
  readOnly: boolean;
  /** RC2 要求但服务端未返回的字段列表（如实展示，便于联调）。 */
  missingFields: string[];
  /** missingFields 的展示文本（WXML 不支持函数调用，需预计算）。 */
  missingFieldsText: string;
  groups: WorkflowGroupView[];
  events: WorkflowEventView[];
  hasEvents: boolean;
  eventCountText: string;
  currentNodeNames: string;
  nodeCount: number;
}

/** 可用动作渲染模型：只按后端返回渲染，前端绝不臆造。 */
export interface ActionView {
  action: AvailableAction;
  text: string;
  primary: boolean;
}

const PROVIDED_BY_SERVER = '服务端未提供';

/** 主干分组键：与分支键前缀 '#...' 不同形，杜绝服务端下发 '__main__' 时撞键。 */
const MAIN_GROUP_KEY = '@main';
/** 分支分组键前缀。 */
const BRANCH_GROUP_PREFIX = '#';

/**
 * graphType 的降级说明（空串表示无降级）。
 *
 * <p>现有夹具只出现过 `LINEAR`，`DAG` / `UNKNOWN` 从未被真实数据验证过。
 * 按 fail-safe 处理：未知一律按"顺序平铺"这种最保守的可渲染形态，并在 UI 上明说是降级——
 * 宁可说「结构未知」，也不画一张猜出来的流程图。
 */
const GRAPH_TYPE_NOTE: { [key: string]: string } = {
  LINEAR: '',
  DAG: '按分组顺序展示，未做拓扑布局',
  UNKNOWN: '流程结构未知，按节点顺序展示'
};

/** 契约内已知取值（用于区分「契约内的 UNKNOWN」与「契约外的野值」）。 */
const KNOWN_GRAPH_TYPES: { [key: string]: boolean } = {
  LINEAR: true,
  DAG: true,
  UNKNOWN: true
};

/**
 * parallelMode 文案（UI 死值兜底）。
 *
 * <p>`ANY` / `ALL` / `SEQUENTIAL` / `UNKNOWN` 同样从未被真实数据触发过，文案一律给全；
 * 契约外的野值也按「未标注」处理，既不显示"未知"吓人，也不臆造语义。
 */
function parallelModeTextOf(mode: string | null | undefined): string {
  if (!mode || mode === 'NONE') {
    return '';
  }
  if (mode === 'ANY' || mode === 'ALL' || mode === 'SEQUENTIAL' || mode === 'UNKNOWN') {
    return textOf(PARALLEL_MODE_TEXT as { [key: string]: string }, mode);
  }
  return '未标注';
}

/** 该 parallelMode 是否值得打标签（NONE 与 UNKNOWN 不打，避免噪音与误解）。 */
function isParallelTagged(mode: string | null | undefined): boolean {
  return mode === 'ANY' || mode === 'ALL' || mode === 'SEQUENTIAL';
}

/** 节点类型样式类：抄送必须与审批节点区分开。 */
function nodeTypeClassOf(nodeType: string | null | undefined): string {
  switch (nodeType) {
    case 'CC':
      return 'nt-cc';
    case 'START':
      return 'nt-start';
    case 'END':
      return 'nt-end';
    case 'APPROVAL':
      return 'nt-approval';
    default:
      return 'nt-unknown';
  }
}

/**
 * readOnly 判定：RC2 声明必填且 V1.0 固定 true。
 * 缺失时按 fail-safe 视为 true（宁可只读，也不误开审批入口）。
 */
export function resolveReadOnly(workflow: Workflow): boolean {
  return workflow.readOnly !== false;
}

function assigneeText(assignees: string[]): string {
  if (!assignees || assignees.length === 0) {
    return '';
  }
  return assignees.join('、');
}

function buildNodeView(node: WorkflowNode, currentSet: { [id: string]: boolean }): WorkflowNodeView {
  const isCurrent = currentSet[node.nodeId] === true;
  return {
    nodeId: node.nodeId,
    name: node.name,
    nodeTypeText: textOf(NODE_TYPE_TEXT as { [key: string]: string }, node.nodeType),
    nodeTypeClass: nodeTypeClassOf(node.nodeType),
    isCc: node.nodeType === 'CC',
    status: node.status,
    statusText: textOf(NODE_STATUS_TEXT as { [key: string]: string }, node.status),
    statusClass: nodeStatusClass(node.status),
    assigneeText: assigneeText(node.assignees),
    hasAssignees: !!node.assignees && node.assignees.length > 0,
    completedAtText: node.completedAt ? formatDateTime(node.completedAt) : '',
    /** 原值保留：分组级标注需要判断原始 parallelMode，而不是判断文案。 */
    parallelMode: node.parallelMode || 'NONE',
    parallelModeText: parallelModeTextOf(node.parallelMode),
    branchGroup: node.branchGroup,
    isCurrent: isCurrent
  };
}

/**
 * 按 branchGroup 分组；无分支时返回单一分组。
 *
 * <p>不假设 `branchGroup` 恒为 null：分组键用 `'#' + 原值`，主干用 `'@main'`，
 * 二者前缀不同形，因此服务端真的下发 `__main__` 也不会与主干撞成一组。
 * 空串 / null / undefined 一律按主干处理。
 */
function buildGroups(nodes: WorkflowNode[], currentSet: { [id: string]: boolean }): WorkflowGroupView[] {
  const groups: WorkflowGroupView[] = [];
  const indexMap: { [key: string]: number } = {};

  for (let i = 0; i < nodes.length; i++) {
    const node = nodes[i];
    const view = buildNodeView(node, currentSet);
    const raw = node.branchGroup;
    const hasGroup = typeof raw === 'string' && raw.trim() !== '';
    const key = hasGroup ? BRANCH_GROUP_PREFIX + raw : MAIN_GROUP_KEY;
    if (indexMap[key] === undefined) {
      indexMap[key] = groups.length;
      groups.push({
        branchGroup: hasGroup ? raw : null,
        groupKey: key,
        groupLabel: hasGroup ? String(raw) : '',
        parallelMode: node.parallelMode || 'NONE',
        parallelModeText: parallelModeTextOf(node.parallelMode),
        showParallelTag: isParallelTagged(node.parallelMode),
        nodes: []
      });
    }
    groups[indexMap[key]].nodes.push(view);
  }

  // 组内首节点若是 NONE，但后续节点带有并行标记，则整组按该标记标注（语义不改写）。
  for (let g = 0; g < groups.length; g++) {
    const group = groups[g];
    if (group.showParallelTag) {
      continue;
    }
    for (let n = 0; n < group.nodes.length; n++) {
      const nodeMode = group.nodes[n].parallelMode;
      if (isParallelTagged(nodeMode)) {
        group.parallelMode = nodeMode;
        group.parallelModeText = group.nodes[n].parallelModeText;
        group.showParallelTag = true;
        break;
      }
    }
  }

  return groups;
}

/** 事件排序：按 occurredAt 升序（服务端已保证升序，此处做稳定兜底，不改写内容）。 */
function sortEvents(events: WorkflowEvent[]): WorkflowEvent[] {
  const copy = events.slice();
  copy.sort((a, b) => {
    const ta = Date.parse(a.occurredAt);
    const tb = Date.parse(b.occurredAt);
    const na = isNaN(ta) ? 0 : ta;
    const nb = isNaN(tb) ? 0 : tb;
    return na - nb;
  });
  return copy;
}

function buildEventViews(
  events: WorkflowEvent[],
  nodeNameById: { [id: string]: string }
): WorkflowEventView[] {
  const sorted = sortEvents(events);
  const views: WorkflowEventView[] = [];
  for (let i = 0; i < sorted.length; i++) {
    const e = sorted[i];
    const actionText = textOf(EVENT_ACTION_TEXT as { [key: string]: string }, e.actionType);
    views.push({
      eventId: e.eventId,
      nodeId: e.nodeId,
      nodeName: nodeNameById[e.nodeId] || '',
      actionText: actionText,
      actionClass: 'ev-' + String(e.actionType || 'UNKNOWN').toLowerCase(),
      operatorText: e.operatorDisplay || '未提供',
      occurredAtText: formatDateTime(e.occurredAt),
      outcomeText: textOf(EVENT_OUTCOME_TEXT as { [key: string]: string }, e.outcome),
      serialNumText: e.serialNum || '',
      commentText: e.comment || '',
      hasComment: !!e.comment,
      menuNameText: e.menuName || ''
    });
  }
  return views;
}

/** 构建工作流渲染模型。服务端缺字段时不抛错，改为如实标注缺失。 */
export function buildWorkflowView(workflow: Workflow): WorkflowView {
  const nodes: WorkflowNode[] = Array.isArray(workflow.nodes) ? workflow.nodes : [];
  const edges: WorkflowEdge[] = Array.isArray(workflow.edges) ? workflow.edges : [];
  const currentIds: string[] = Array.isArray(workflow.currentNodeIds) ? workflow.currentNodeIds : [];

  const currentSet: { [id: string]: boolean } = {};
  for (let i = 0; i < currentIds.length; i++) {
    currentSet[currentIds[i]] = true;
  }

  const nodeNameById: { [id: string]: string } = {};
  for (let i = 0; i < nodes.length; i++) {
    nodeNameById[nodes[i].nodeId] = nodes[i].name;
  }

  // 说明：edges 仅用于连线与分支提示展示，节点顺序严格以 nodes 为准，不据此重排。
  const groups = buildGroups(nodes, currentSet);

  const missingFields: string[] = [];
  if (edges.length === 0 && nodes.length > 1) {
    missingFields.push('edges');
  }
  if (typeof workflow.definitionVersion !== 'string' || workflow.definitionVersion === '') {
    missingFields.push('definitionVersion');
  }
  if (typeof workflow.readOnly !== 'boolean') {
    missingFields.push('readOnly');
  }
  if (!Array.isArray(workflow.events)) {
    missingFields.push('events');
  }
  if (!Array.isArray(workflow.currentNodeIds)) {
    missingFields.push('currentNodeIds');
  }

  const events = Array.isArray(workflow.events) ? workflow.events : [];
  const eventViews = buildEventViews(events, nodeNameById);

  const currentNodeNames: string[] = [];
  for (let i = 0; i < currentIds.length; i++) {
    const name = nodeNameById[currentIds[i]];
    currentNodeNames.push(name || currentIds[i]);
  }

  // graphType：未知/缺失/契约外野值一律按 UNKNOWN 走降级（顺序平铺 + 明说结构未知），绝不猜结构。
  const rawGraphType = typeof workflow.graphType === 'string' ? workflow.graphType : 'UNKNOWN';
  const knownGraph = KNOWN_GRAPH_TYPES[rawGraphType] === true;
  const graphTypeText = knownGraph && rawGraphType !== 'UNKNOWN'
    ? textOf(GRAPH_TYPE_TEXT as { [key: string]: string }, rawGraphType)
    : textOf(GRAPH_TYPE_TEXT as { [key: string]: string }, 'UNKNOWN');
  const graphTypeNote = knownGraph ? GRAPH_TYPE_NOTE[rawGraphType] : GRAPH_TYPE_NOTE.UNKNOWN;

  return {
    workflowId: workflow.workflowId || PROVIDED_BY_SERVER,
    instanceId: workflow.instanceId || PROVIDED_BY_SERVER,
    graphType: rawGraphType,
    graphTypeText: graphTypeText,
    graphTypeNote: graphTypeNote,
    graphTypeDegraded: graphTypeNote !== '',
    definitionVersionText:
      typeof workflow.definitionVersion === 'string' && workflow.definitionVersion !== ''
        ? workflow.definitionVersion
        : PROVIDED_BY_SERVER,
    dataVersionText: workflow.dataVersion || PROVIDED_BY_SERVER,
    readOnly: resolveReadOnly(workflow),
    missingFields: missingFields,
    missingFieldsText: missingFields.join('、'),
    groups: groups,
    events: eventViews,
    hasEvents: eventViews.length > 0,
    eventCountText: eventViews.length > 0 ? String(eventViews.length) + ' 条' : '无',
    currentNodeNames: currentNodeNames.join('、'),
    nodeCount: nodes.length
  };
}

/**
 * 可用动作渲染：只渲染后端返回的 VIEW / OPEN_ERP；其余值忽略并记录日志，绝不臆造。
 */
export function buildActionViews(actions: AvailableAction[] | null | undefined): ActionView[] {
  const views: ActionView[] = [];
  if (!actions || actions.length === 0) {
    return views;
  }
  for (let i = 0; i < actions.length; i++) {
    const action = actions[i];
    if (action === 'VIEW') {
      views.push({ action: action, text: '仅可查看', primary: false });
    } else if (action === 'OPEN_ERP') {
      views.push({ action: action, text: '在 ERP 中打开', primary: true });
    } else {
      console.warn('[tss] 忽略未知 availableAction：' + String(action));
    }
  }
  return views;
}
