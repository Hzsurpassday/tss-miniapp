/**
 * 13 个 RC2 端点的封装（小程序作用域：仅 /wx/v1 的 11 个；/admin/v1 属 PC 管理端 adminAuth，
 * 小程序不调用，仅保留类型镜像）。
 *
 * 纪律：
 *   - 全部只读/绑定语义，V1.0 不实现任何 ERP 写回。
 *   - cursor 为不透明签名游标，改变筛选条件必须丢弃旧 cursor（F3），调用方负责。
 */

import { API_PATH } from '../types/api';
import type {
  LoginData,
  LoginRequest,
  MeData,
  SummaryData,
  TodoListData,
  TodoListQuery,
  DetailData,
  HistoryData,
  HistoryQuery,
  NotificationData,
  NotificationQuery,
  ConsentData,
  ConsentRequest,
  ClaimData,
  ClaimRequest,
  BindingStatusData,
  UnbindData,
  UnbindRequest
} from '../types/api';
import { requestData, requestRaw } from './request';
import type { ApiResponse } from './request';

/** 默认分页大小（RC2 limit: 1..100，默认 20）。 */
export const DEFAULT_LIMIT = 20;

/** API-01 匿名登录（不走统一 request，避免递归 ensureSession）。 */
export const login = (body: LoginRequest): Promise<LoginData> =>
  requestData<LoginData>({
    path: API_PATH.LOGIN,
    method: 'POST',
    body: body,
    auth: false
  });

/** API-02 会话本人。 */
export const getMe = (): Promise<MeData> =>
  requestData<MeData>({ path: API_PATH.ME, method: 'GET' });

/** API-03 本人待办统计。 */
export const getTodoSummary = (): Promise<SummaryData> =>
  requestData<SummaryData>({ path: API_PATH.TODO_SUMMARY, method: 'GET' });

/** API-04 本人待办列表。 */
export const getTodoList = (query: TodoListQuery): Promise<TodoListData> =>
  requestData<TodoListData>({ path: API_PATH.TODO_LIST, method: 'GET', query: query as unknown as { [key: string]: unknown } });

/** API-05 待办详情（支持条件 GET；304 由调用方使用缓存）。 */
export const getTodoDetail = (
  taskNum: string,
  conditional?: { ifNoneMatch?: string; ifModifiedSince?: string }
): Promise<ApiResponse<DetailData>> => {
  const header: { [key: string]: string } = {};
  if (conditional && conditional.ifNoneMatch) {
    header['If-None-Match'] = conditional.ifNoneMatch;
  }
  if (conditional && conditional.ifModifiedSince) {
    header['If-Modified-Since'] = conditional.ifModifiedSince;
  }
  return requestRaw<DetailData>({
    path: API_PATH.TODO_DETAIL.replace('{taskNum}', encodeURIComponent(taskNum)),
    method: 'GET',
    header: header
  });
};

/** API-06 本人历史。 */
export const getHistory = (query: HistoryQuery): Promise<HistoryData> =>
  requestData<HistoryData>({ path: API_PATH.HISTORY, method: 'GET', query: query as unknown as { [key: string]: unknown } });

/** API-07 通知列表。 */
export const getNotifications = (query: NotificationQuery): Promise<NotificationData> =>
  requestData<NotificationData>({
    path: API_PATH.NOTIFICATIONS,
    method: 'GET',
    query: query as unknown as { [key: string]: unknown }
  });

/** API-08 订阅消息意愿记录。 */
export const postNotificationConsent = (body: ConsentRequest): Promise<ConsentData> =>
  requestData<ConsentData>({
    path: API_PATH.NOTIFICATION_CONSENT,
    method: 'POST',
    body: body
  });

/** API-09 发起绑定。 */
export const claimBinding = (body: ClaimRequest): Promise<ClaimData> =>
  requestData<ClaimData>({ path: API_PATH.BINDING_CLAIM, method: 'POST', body: body });

/** API-10 绑定状态。 */
export const getBindingStatus = (): Promise<BindingStatusData> =>
  requestData<BindingStatusData>({ path: API_PATH.BINDING_STATUS, method: 'GET' });

/** API-11 解绑。 */
export const unbind = (body: UnbindRequest): Promise<UnbindData> =>
  requestData<UnbindData>({ path: API_PATH.BINDING_UNBIND, method: 'POST', body: body });
