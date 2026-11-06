/**
 * 本地静态样例兜底（**默认禁用**）。
 *
 * 诚实声明：以下数据**逐字取自** `C:\Users\Administrator\Desktop\api-examples-rc2.json`
 * （RC2 静态样例响应），**不是真实后端返回**，也不代表任何 ERP 实证结果。
 * 只有当页面显式开启「静态样例兜底」且真实请求失败时才使用，且 UI 必须展示
 * `STATIC_SAMPLE_BANNER` 提示条，禁止把样例说成真实数据。
 */

import type {
  MeData,
  SummaryData,
  TodoListData,
  DetailData,
  HistoryData,
  NotificationData
} from '../types/api';
import * as storage from './storage';

/** 样例来源标识，UI 必须原样展示。 */
export const STATIC_SAMPLE_SOURCE = 'STATIC_SAMPLE';

/** 样例提示条文案。 */
export const STATIC_SAMPLE_BANNER = '当前展示的是本地静态样例（api-examples-rc2.json），不是真实后端返回';

const KEY_ENABLED = 'dev.staticSampleFallback';

/** 是否启用静态样例兜底（默认 false）。 */
export function isStaticSampleEnabled(): boolean {
  return storage.get<boolean>(KEY_ENABLED, false) === true;
}

export function setStaticSampleEnabled(enabled: boolean): void {
  storage.set(KEY_ENABLED, !!enabled);
}

export interface StaticSample<T> {
  source: string;
  data: T;
}

function wrap<T>(data: T): StaticSample<T> {
  return { source: STATIC_SAMPLE_SOURCE, data: data };
}

/** API-02 样例。 */
export function sampleMe(): StaticSample<MeData> {
  return wrap<MeData>({
    userId: 'U4FXG07VRDI',
    entId: 'E498AU5L35J',
    displayName: '示例员工',
    bindStatus: 'BOUND',
    bindingVersion: 3,
    enterpriseSwitchEnabled: false
  });
}

/** API-03 样例。 */
export function sampleSummary(): StaticSample<SummaryData> {
  return wrap<SummaryData>({
    counts: {
      total: 1,
      purchaseInbound: 0,
      salesBusiness: 1,
      payment: 0,
      salesPricing: 0
    },
    lastFetchedAt: '2026-10-31T10:30:00+08:00',
    meta: {
      dataMode: 'MOCK',
      asOf: '2026-10-31T10:30:00+08:00',
      freshness: 'FRESH',
      schemaVersion: '1.0.0'
    }
  });
}

/** API-04 样例。 */
export function sampleTodoList(): StaticSample<TodoListData> {
  return wrap<TodoListData>({
    items: [
      {
        approvalId: 'a_355372_355377',
        taskNum: '355377',
        instanceId: '355372',
        billNo: '77971',
        docType: 'SALES_BUSINESS',
        title: '销售业务审批',
        counterparty: '示例客户',
        amount: '19800.00',
        currency: 'CNY',
        currentNodeName: '风控审核',
        createdAt: '2026-10-31T10:30:00+08:00',
        updatedAt: '2026-10-31T10:30:00+08:00',
        status: 'PENDING',
        dataMode: 'MOCK'
      }
    ],
    page: { nextCursor: null, hasMore: false, totalApprox: 1 },
    meta: {
      dataMode: 'MOCK',
      asOf: '2026-10-31T10:30:00+08:00',
      freshness: 'FRESH',
      schemaVersion: '1.0.0'
    }
  });
}

/** API-05 样例。 */
export function sampleDetail(): StaticSample<DetailData> {
  return wrap<DetailData>({
    approvalId: 'a_355372_355377',
    taskNum: '355377',
    docType: 'SALES_BUSINESS',
    base: [
      { key: 'billNo', label: '单据编号', value: '77971', format: 'TEXT', verified: true },
      { key: 'amount', label: '含税金额', value: '19800.00', format: 'AMOUNT', verified: false }
    ],
    lines: [
      {
        lineId: '1',
        fields: [
          { key: 'goodsSpec', label: '规格', value: 'Φ20', format: 'TEXT', verified: false },
          { key: 'weightTons', label: '重量（吨）', value: '12.500', format: 'WEIGHT_TON', verified: false }
        ]
      }
    ],
    workflow: {
      workflowId: 'dq.xskd',
      instanceId: '355372',
      currentNodeIds: ['risk'],
      nodes: [
        {
          nodeId: 'start',
          name: '提交',
          nodeType: 'START',
          status: 'COMPLETED',
          assignees: [],
          completedAt: '2026-10-31T10:30:00+08:00',
          branchGroup: null,
          parallelMode: 'NONE'
        },
        {
          nodeId: 'risk',
          name: '风控审核',
          nodeType: 'APPROVAL',
          status: 'ACTIVE',
          assignees: ['审批人甲'],
          completedAt: null,
          branchGroup: null,
          parallelMode: 'NONE'
        }
      ],
      edges: [{ from: 'start', to: 'risk', label: null }],
      graphType: 'LINEAR',
      dataVersion: '20261031-001',
      definitionVersion: 'v1',
      readOnly: true,
      events: [
        {
          eventId: 'evt_mock_1',
          nodeId: 'start',
          actionType: 'STARTED',
          menuName: '保存并提交',
          operatorDisplay: '示例员工',
          occurredAt: '2026-10-31T10:30:00+08:00',
          outcome: 'SUCCESS',
          serialNum: '355375',
          comment: null
        }
      ]
    },
    sourceStatus: {
      sourceName: 'SaleNotesMt',
      isFinalApproved: false,
      downstreamReceiptStatus: 'NOT_VERIFIED',
      inventoryChanged: null,
      lastVerifiedAt: null,
      notes: '销售业务，不等同于正式发票开具'
    },
    availableActions: ['VIEW'],
    meta: {
      dataMode: 'MOCK',
      asOf: '2026-10-31T10:30:00+08:00',
      freshness: 'FRESH',
      schemaVersion: '1.0.0'
    }
  });
}

/** API-06 样例。 */
export function sampleHistory(): StaticSample<HistoryData> {
  return wrap<HistoryData>({
    items: [
      {
        recordId: 'r_1',
        approvalId: 'a_355372_355377',
        taskNum: '355377',
        billNo: '77971',
        docType: 'SALES_BUSINESS',
        action: 'APPROVED',
        actionName: '审批完成',
        processedAt: '2026-10-31T10:30:00+08:00',
        operatorDisplay: '示例员工',
        resultVerified: true
      }
    ],
    page: { nextCursor: null, hasMore: false, totalApprox: 1 },
    meta: {
      dataMode: 'MOCK',
      asOf: '2026-10-31T10:30:00+08:00',
      freshness: 'FRESH',
      schemaVersion: '1.0.0'
    }
  });
}

/** API-07 样例。 */
export function sampleNotifications(): StaticSample<NotificationData> {
  return wrap<NotificationData>({
    items: [
      {
        id: 'n_1',
        title: '待办提醒',
        body: '您有一条销售业务审批待办',
        type: 'NEW_TODO',
        createdAt: '2026-10-31T10:30:00+08:00',
        readAt: null,
        approvalId: 'a_355372_355377'
      }
    ],
    page: { nextCursor: null, hasMore: false, totalApprox: 1 },
    meta: {
      dataMode: 'MOCK',
      asOf: '2026-10-31T10:30:00+08:00',
      freshness: 'FRESH',
      schemaVersion: '1.0.0'
    }
  });
}
