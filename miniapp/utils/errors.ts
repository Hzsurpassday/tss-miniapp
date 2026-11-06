/**
 * RC2 稳定错误码 `TSS_*` 的客户端映射。
 *
 * **权威来源变更（重要）**：契约决议引用的 `error-codes.csv` 已从桌面删除；本文件改为以
 * team-lead 保存并下发的**权威 20 条清单**为准（code / HTTP 状态 / 是否可重试）。
 *
 * 权威清单（20 条，逐条照抄）：
 *   TSS_INVALID_ARGUMENT 400/否 | TSS_INVALID_CURSOR 400/否 | TSS_UNAUTHORIZED 401/否 |
 *   TSS_SESSION_EXPIRED 401/否 | TSS_NOT_BOUND 403/否 | TSS_FORBIDDEN 403/否 |
 *   TSS_ENT_SCOPE_DENIED 403/否 | TSS_BINDING_DISABLED 403/否 | TSS_ADMIN_FORBIDDEN 403/否 |
 *   TSS_NOT_FOUND 404/否 | TSS_BIND_INVITE_EXPIRED 409/否 | TSS_BIND_CONFLICT 409/否 |
 *   TSS_IDEMPOTENCY_CONFLICT 409/否 | TSS_DATA_STALE 409/是 | TSS_RATE_LIMITED 429/是 |
 *   TSS_WECHAT_UNAVAILABLE 503/是 | TSS_ERP_READ_UNAVAILABLE 503/是 | TSS_NOT_CONFIGURED 503/否 |
 *   TSS_INTERNAL_ERROR 500/是 | TSS_WRITE_DISABLED 403/否
 *
 * 诚实声明：权威清单**只提供 code / HTTP / retryable，未提供文案**。因此
 * `defaultMessage` 是**客户端兜底文案**，真实展示优先使用服务端 `message`；
 * 若服务端返回的是泛化文案（如「请求未完成」）才回退到兜底文案。
 *
 * **非权威码清单的现状（已核实，勿再沿用旧说法）**：后端 `TssErrorCode.java` 已把错误码
 * **收口到权威 20 条**（`TSS_WECHAT_NOT_CONFIGURED` → `TSS_NOT_CONFIGURED`、
 * `TSS_SESSION_INVALID` → `TSS_UNAUTHORIZED` 等 9 条映射，另有 14 条非权威码删除）。
 * 本轮 QA 已验证 `error-codes.csv` × `server.py ERRORS` × `TssErrorCode.java`
 * **三方 20/20 全等、差异 0 条**。
 *
 * 因此 `NON_AUTHORITATIVE_CODES` 的性质是**历史残留防御清单**，不是「待修正项」：
 * 正常情况下这些码**不会再出现**；保留它们只为一件事——万一真收到了，前端仍能
 * 按「需重登录 / 需引导绑定 / 其它」正确分类，不至于因未知码而崩溃或误判。
 * 展示一律用服务端 `message`，或「请求未完成（原码）」，客户端**不为其臆造语义**。
 */

export interface TssErrorSpec {
  code: string;
  httpStatus: number;
  /** 是否可重试（权威清单第三列）。 */
  retryable: boolean;
  /** 客户端兜底文案；服务端 message 优先。 */
  defaultMessage: string;
}

/** 权威 20 条。 */
export const TSS_ERROR_SPECS: TssErrorSpec[] = [
  { code: 'TSS_INVALID_ARGUMENT', httpStatus: 400, retryable: false, defaultMessage: '请求参数或请求体不符合契约' },
  { code: 'TSS_INVALID_CURSOR', httpStatus: 400, retryable: false, defaultMessage: '分页游标无效或已过期' },
  { code: 'TSS_UNAUTHORIZED', httpStatus: 401, retryable: false, defaultMessage: '未认证或凭据缺失' },
  { code: 'TSS_SESSION_EXPIRED', httpStatus: 401, retryable: false, defaultMessage: '会话已过期，请重新登录' },
  { code: 'TSS_NOT_BOUND', httpStatus: 403, retryable: false, defaultMessage: '微信身份尚未绑定 ERP 员工' },
  { code: 'TSS_FORBIDDEN', httpStatus: 403, retryable: false, defaultMessage: '当前身份无权执行该操作' },
  { code: 'TSS_ENT_SCOPE_DENIED', httpStatus: 403, retryable: false, defaultMessage: '企业上下文不在授权范围内' },
  { code: 'TSS_BINDING_DISABLED', httpStatus: 403, retryable: false, defaultMessage: '绑定已停用，请联系管理员' },
  { code: 'TSS_ADMIN_FORBIDDEN', httpStatus: 403, retryable: false, defaultMessage: '管理端权限不足' },
  { code: 'TSS_NOT_FOUND', httpStatus: 404, retryable: false, defaultMessage: '资源不存在' },
  { code: 'TSS_BIND_INVITE_EXPIRED', httpStatus: 409, retryable: false, defaultMessage: '绑定邀请码已过期' },
  { code: 'TSS_BIND_CONFLICT', httpStatus: 409, retryable: false, defaultMessage: '已有冲突绑定' },
  { code: 'TSS_IDEMPOTENCY_CONFLICT', httpStatus: 409, retryable: false, defaultMessage: '幂等键冲突' },
  { code: 'TSS_DATA_STALE', httpStatus: 409, retryable: true, defaultMessage: '数据已变更，请刷新后重试' },
  { code: 'TSS_RATE_LIMITED', httpStatus: 429, retryable: true, defaultMessage: '请求过于频繁，请稍后再试' },
  { code: 'TSS_WECHAT_UNAVAILABLE', httpStatus: 503, retryable: true, defaultMessage: '微信登录服务暂时不可用' },
  { code: 'TSS_ERP_READ_UNAVAILABLE', httpStatus: 503, retryable: true, defaultMessage: 'ERP 只读数据源暂时不可用' },
  { code: 'TSS_NOT_CONFIGURED', httpStatus: 503, retryable: false, defaultMessage: '服务未配置完成' },
  { code: 'TSS_INTERNAL_ERROR', httpStatus: 500, retryable: true, defaultMessage: '请求未完成，请用 traceId 排查' },
  { code: 'TSS_WRITE_DISABLED', httpStatus: 403, retryable: false, defaultMessage: 'V1.0 为只读版本，不提供该写操作' }
];

/**
 * **历史残留防御清单**：后端已收口到权威 20 条，正常不再出现。
 *
 * 保留的唯一理由：万一收到，仍能正确分类（auth / binding / other），
 * 使前端不因未知码崩溃或把「需引导绑定」误判成「需重登录」。
 * 仅提供 HTTP 状态用于分类；不提供客户端文案，避免臆造语义。
 */
export interface NonAuthoritativeCode {
  code: string;
  httpStatus: number;
  /** 归类：auth=需重新登录；binding=需引导绑定；other=其它。 */
  kind: 'auth' | 'binding' | 'other';
  /** 建议对应的权威码（仅用于排查提示，不参与展示）。 */
  shouldBe: string;
}

export const NON_AUTHORITATIVE_CODES: NonAuthoritativeCode[] = [
  { code: 'TSS_BAD_REQUEST', httpStatus: 400, kind: 'other', shouldBe: 'TSS_INVALID_ARGUMENT' },
  { code: 'TSS_WECHAT_CODE_INVALID', httpStatus: 400, kind: 'auth', shouldBe: 'TSS_INVALID_ARGUMENT' },
  { code: 'TSS_BIND_CODE_INVALID', httpStatus: 400, kind: 'other', shouldBe: 'TSS_INVALID_ARGUMENT' },
  { code: 'TSS_SESSION_INVALID', httpStatus: 401, kind: 'auth', shouldBe: 'TSS_UNAUTHORIZED' },
  { code: 'TSS_SESSION_REVOKED', httpStatus: 401, kind: 'auth', shouldBe: 'TSS_SESSION_EXPIRED' },
  { code: 'TSS_WECHAT_LOGIN_REJECTED', httpStatus: 401, kind: 'auth', shouldBe: 'TSS_UNAUTHORIZED' },
  { code: 'TSS_ENT_MISMATCH', httpStatus: 403, kind: 'binding', shouldBe: 'TSS_ENT_SCOPE_DENIED' },
  { code: 'TSS_USER_INACTIVE', httpStatus: 403, kind: 'binding', shouldBe: 'TSS_BINDING_DISABLED' },
  { code: 'TSS_BILL_NOT_ACCESSIBLE', httpStatus: 403, kind: 'other', shouldBe: 'TSS_FORBIDDEN' },
  { code: 'TSS_BILL_NOT_FOUND', httpStatus: 404, kind: 'other', shouldBe: 'TSS_NOT_FOUND' },
  { code: 'TSS_CONFLICT', httpStatus: 409, kind: 'other', shouldBe: 'TSS_BIND_CONFLICT' },
  { code: 'TSS_WECHAT_NOT_CONFIGURED', httpStatus: 503, kind: 'other', shouldBe: 'TSS_NOT_CONFIGURED 或 TSS_WECHAT_UNAVAILABLE' },
  { code: 'TSS_ERP_NOT_CONFIGURED', httpStatus: 503, kind: 'other', shouldBe: 'TSS_NOT_CONFIGURED' },
  { code: 'TSS_MOCK_TOKEN_DISABLED', httpStatus: 403, kind: 'other', shouldBe: 'TSS_NOT_CONFIGURED' }
];

const SPEC_MAP: { [code: string]: TssErrorSpec } = (function build(): {
  [code: string]: TssErrorSpec;
} {
  const map: { [code: string]: TssErrorSpec } = {};
  for (let i = 0; i < TSS_ERROR_SPECS.length; i++) {
    map[TSS_ERROR_SPECS[i].code] = TSS_ERROR_SPECS[i];
  }
  return map;
})();

const NON_AUTH_MAP: { [code: string]: NonAuthoritativeCode } = (function build(): {
  [code: string]: NonAuthoritativeCode;
} {
  const map: { [code: string]: NonAuthoritativeCode } = {};
  for (let i = 0; i < NON_AUTHORITATIVE_CODES.length; i++) {
    map[NON_AUTHORITATIVE_CODES[i].code] = NON_AUTHORITATIVE_CODES[i];
  }
  return map;
})();

/** 本地（非服务端返回）错误码，不参与 TSS_* 映射。 */
export const LOCAL_ERROR = {
  NETWORK: 'TSS_CLIENT_NETWORK_ERROR',
  PARSE: 'TSS_CLIENT_PARSE_ERROR',
  TIMEOUT: 'TSS_CLIENT_TIMEOUT',
  ABORT: 'TSS_CLIENT_ABORT',
  NO_SESSION: 'TSS_CLIENT_NO_SESSION'
} as const;

/** 是否为权威清单内的码。 */
export function isAuthoritativeCode(code: string): boolean {
  return !!SPEC_MAP[code];
}

/** 取权威兜底文案（非权威码返回空串，交由服务端 message 或通用兜底）。 */
export function defaultMessageOf(code: string): string {
  const spec = SPEC_MAP[code];
  return spec ? spec.defaultMessage : '';
}

/** 是否可重试（非权威码一律 false，避免误自动重试）。 */
export function isRetryable(code: string): boolean {
  const spec = SPEC_MAP[code];
  return !!spec && spec.retryable;
}

/**
 * 是否需要重新登录（401 类 + 待修正清单中归类为 auth 的码）。
 *
 * **口径（与后端 RC3 一致，勿改回）**：
 *  - 401（`TSS_UNAUTHORIZED` / `TSS_SESSION_EXPIRED`）= 凭据缺失或会话失效 ⇒ 走重登录；
 *  - 403 的**绑定类**码（`TSS_NOT_BOUND` / `TSS_BINDING_DISABLED` / `TSS_ENT_SCOPE_DENIED`）
 *    是「会话有效、但没有绑定关系」⇒ **不重登录**，走绑定引导（见 {@link isBindingErrorCode}）。
 *    典型场景：解绑后会话仍有效，`me` 返回 200 + `bindStatus=UNBOUND`，业务端点 403 `TSS_NOT_BOUND`。
 *    把它们当成 401 会让用户陷入「重登录 → 还是没绑定 → 再重登录」的死循环。
 */
export function isAuthErrorCode(code: string): boolean {
  if (code === 'TSS_UNAUTHORIZED' || code === 'TSS_SESSION_EXPIRED') {
    return true;
  }
  const nonAuth = NON_AUTH_MAP[code];
  return !!nonAuth && nonAuth.kind === 'auth';
}

/** 是否为「未绑定/绑定不可用」类错误，UI 应引导绑定而不是当作普通错误或空数据。 */
export function isBindingErrorCode(code: string): boolean {
  if (
    code === 'TSS_NOT_BOUND' ||
    code === 'TSS_BINDING_DISABLED' ||
    code === 'TSS_ENT_SCOPE_DENIED'
  ) {
    return true;
  }
  const nonAuth = NON_AUTH_MAP[code];
  return !!nonAuth && nonAuth.kind === 'binding';
}

/** 用于排查提示：非权威码应改为哪个权威码（空串表示已是权威码）。 */
export function shouldBeCode(code: string): string {
  const nonAuth = NON_AUTH_MAP[code];
  return nonAuth ? nonAuth.shouldBe : '';
}

const GENERIC_MESSAGES: { [key: string]: boolean } = {
  请求未完成: true,
  '请求未完成，请用 traceId 排查': false
};

/**
 * 面向用户的错误文案。
 * 纪律：不掩盖错误，也不编造原因；未知码原样透出 code，便于与 traceId 一起排查。
 */
export function userMessageOf(code: string, serverMessage: string): string {
  const message = (serverMessage || '').trim();
  if (message && !GENERIC_MESSAGES[message]) {
    return message;
  }
  const fallback = defaultMessageOf(code);
  if (fallback) {
    return fallback;
  }
  if (code === LOCAL_ERROR.NETWORK) {
    return '网络请求失败，请检查网络或后端服务地址';
  }
  if (code === LOCAL_ERROR.PARSE) {
    return '服务端响应无法解析';
  }
  if (code === LOCAL_ERROR.TIMEOUT) {
    return '请求超时';
  }
  if (code === LOCAL_ERROR.NO_SESSION) {
    return '会话不存在，请重新登录';
  }
  if (NON_AUTH_MAP[code]) {
    return '请求未完成（' + code + '）';
  }
  return '请求未完成（' + code + '）';
}
