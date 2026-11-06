package com.viding.datahub.tss.wx.envelope;

/**
 * RC2 稳定错误码（{@code TSS_*}）与 HTTP 状态映射 —— <b>权威 20 条，不多不少</b>。
 *
 * <p><b>权威来源（可核验）</b>：本轮任务单引用的 {@code error-codes.csv} 在交付环境中
 * <b>未找到</b>（桌面与全盘检索均无命中）。本枚举改以团队内同一份权威清单为准：
 * {@code mock-openapi/tss-contract-mock/server.py} 中的 {@code ERROR_CODES} 字典
 * （第 61~80 行，20 条）。该清单与 team-lead 的比对结论<b>完全自洽</b>：
 * <ul>
 *   <li>命中 11 条：{@code TSS_UNAUTHORIZED} {@code TSS_SESSION_EXPIRED} {@code TSS_NOT_BOUND}
 *       {@code TSS_FORBIDDEN} {@code TSS_NOT_FOUND} {@code TSS_INVALID_CURSOR}
 *       {@code TSS_IDEMPOTENCY_CONFLICT} {@code TSS_RATE_LIMITED} {@code TSS_INTERNAL_ERROR}
 *       {@code TSS_WECHAT_UNAVAILABLE} {@code TSS_ADMIN_FORBIDDEN}；</li>
 *   <li>补齐此前缺失的 9 条：{@code TSS_INVALID_ARGUMENT} {@code TSS_ENT_SCOPE_DENIED}
 *       {@code TSS_BINDING_DISABLED} {@code TSS_BIND_INVITE_EXPIRED} {@code TSS_BIND_CONFLICT}
 *       {@code TSS_DATA_STALE} {@code TSS_ERP_READ_UNAVAILABLE} {@code TSS_NOT_CONFIGURED}
 *       {@code TSS_WRITE_DISABLED}；</li>
 *   <li>删除此前多出的 14 条非权威码，内部语义按下方「收口映射」落到权威码。</li>
 * </ul>
 *
 * <p><b>收口映射（内部语义 → 对外权威码，仅影响对外码值，HTTP 状态保持不变）</b>：
 * <pre>
 *   TSS_BAD_REQUEST            -&gt; TSS_INVALID_ARGUMENT   (400)
 *   TSS_WECHAT_CODE_INVALID    -&gt; TSS_INVALID_ARGUMENT   (400)
 *   TSS_BIND_CODE_INVALID      -&gt; TSS_INVALID_ARGUMENT   (400)  邀请码为空/未知
 *                              -&gt; TSS_BIND_INVITE_EXPIRED(409)  邀请码确已过期
 *   TSS_SESSION_INVALID        -&gt; TSS_UNAUTHORIZED       (401)
 *   TSS_WECHAT_LOGIN_REJECTED  -&gt; TSS_UNAUTHORIZED       (401)
 *   TSS_SESSION_REVOKED        -&gt; TSS_SESSION_EXPIRED    (401)
 *   TSS_BILL_NOT_FOUND         -&gt; TSS_NOT_FOUND          (404)
 *   TSS_BILL_NOT_ACCESSIBLE    -&gt; TSS_FORBIDDEN          (403)
 *   TSS_ENT_MISMATCH           -&gt; TSS_ENT_SCOPE_DENIED   (403)
 *   TSS_USER_INACTIVE          -&gt; TSS_BINDING_DISABLED   (403)  ERP 用户失效 ⇒ 绑定不可用
 *   TSS_WECHAT_NOT_CONFIGURED  -&gt; TSS_NOT_CONFIGURED     (503)
 *   TSS_MOCK_TOKEN_DISABLED    -&gt; TSS_NOT_CONFIGURED     (503)
 *   TSS_ERP_NOT_CONFIGURED     -&gt; TSS_ERP_READ_UNAVAILABLE(503) 声明 ERP 但未接入 ⇒ 拒绝返回空结果
 *   TSS_CONFLICT               -&gt; TSS_DATA_STALE         (409)
 * </pre>
 *
 * <p><b>硬约束</b>：对外 {@code code} 只能取本枚举的 20 个取值之一，绝不自造 {@code TSS_*}；
 * {@code message} 可面向用户友好化，{@code code} 必须落在清单内。
 * 该约束由 {@code TssErrorCodeTest} 断言锁死（条数 == 20 且名称集合逐条比对）。
 */
public enum TssErrorCode {

    // ------------------------------------------------------------------ 400
    /** 请求结构、查询参数或请求体不符合 RC2 约定（枚举值非法、limit 越界、from 晚于 to、未知字段等）。 */
    TSS_INVALID_ARGUMENT(400, "参数无效", false),
    /** 不透明游标签名校验失败、已过期、或与当前用户/企业/筛选条件/排序锚点不匹配。 */
    TSS_INVALID_CURSOR(400, "分页游标无效或过期", false),

    // ------------------------------------------------------------------ 401
    /** 未携带凭据、凭据类型不被接受，或服务端不存在该会话（不接受客户端自造身份）。 */
    TSS_UNAUTHORIZED(401, "未登录或令牌无效", false),
    /** 会话已过期或被撤销（解绑/重绑/风控）。 */
    TSS_SESSION_EXPIRED(401, "登录已过期", false),

    // ------------------------------------------------------------------ 403
    /** 微信身份未绑定到有效 ERP USERDOC.USERID，或绑定已解绑。 */
    TSS_NOT_BOUND(403, "当前微信尚未绑定员工", false),
    /** 当前身份无权访问该资源（含跨员工/跨企业查看他人单据）。 */
    TSS_FORBIDDEN(403, "无权查看该资源", false),
    /** 会话企业与绑定记录企业不一致，或请求越出授权企业作用域。 */
    TSS_ENT_SCOPE_DENIED(403, "无权访问企业数据", false),
    /** 绑定已停用（含 ERP USERDOC 用户状态失效导致绑定不可用）。 */
    TSS_BINDING_DISABLED(403, "绑定已停用", false),
    /** 管理端令牌有效但角色/企业作用域不足。 */
    TSS_ADMIN_FORBIDDEN(403, "非管理人员", false),
    /** V1.0 只读：当前版本禁止真实审批写回（保留码位，本轮无写端点）。 */
    TSS_WRITE_DISABLED(403, "当前版本禁止真实审批写回", false),

    // ------------------------------------------------------------------ 404
    /** 请求的资源不存在或对当前身份不可见。 */
    TSS_NOT_FOUND(404, "资源不存在或不可见", false),

    // ------------------------------------------------------------------ 409
    /** 绑定邀请码已过期（区别于「邀请码未知」的 400）。 */
    TSS_BIND_INVITE_EXPIRED(409, "绑定邀请已过期", false),
    /** 已存在冲突的绑定关系。 */
    TSS_BIND_CONFLICT(409, "已有冲突绑定", false),
    /** 相同幂等键（clientNonce / claimNonce）已被用于不同内容的请求。 */
    TSS_IDEMPOTENCY_CONFLICT(409, "相同幂等键内容不同", false),
    /** 数据版本已变化（可重试：重新拉取后再次提交）。 */
    TSS_DATA_STALE(409, "数据版本已变化", true),

    // ------------------------------------------------------------------ 429
    /** 请求超过允许速率（可重试：按 Retry-After 退避）。 */
    TSS_RATE_LIMITED(429, "请求频率过高", true),

    // ------------------------------------------------------------------ 500
    /** 未分类内部错误，用 traceId 排查。 */
    TSS_INTERNAL_ERROR(500, "服务异常", true),

    // ------------------------------------------------------------------ 503
    /** 微信 code2session 调用失败或超时（可重试）。 */
    TSS_WECHAT_UNAVAILABLE(503, "微信服务不可用或未配置", true),
    /** 声明了 ERP 数据源但只读通道不可用/未接入：拒绝返回可能被误读为「无待办」的空结果（可重试）。 */
    TSS_ERP_READ_UNAVAILABLE(503, "ERP只读数据不可用", true),
    /** 服务能力尚未配置（微信 appid/secret 缺失、DEV Mock 颁发器未启用等），fail-closed 不降级。 */
    TSS_NOT_CONFIGURED(503, "服务能力尚未配置", false);

    private final int httpStatus;
    private final String defaultMessage;
    private final boolean retryable;

    TssErrorCode(int httpStatus, String defaultMessage, boolean retryable) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
        this.retryable = retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /** 错误性质上是否可重试（实际重试仍受操作类型、幂等能力与总预算约束）。 */
    public boolean retryable() {
        return retryable;
    }

    /** 本枚举即权威主集，任何取值都属于主集。 */
    public boolean isCoreSet() {
        return true;
    }

    /**
     * 权威主集条目数（用于自检断言，防止后续误扩/误删）。
     *
     * <p>恒等于 {@link #values()}.length，且被 {@code TssErrorCodeTest} 断言为 20。
     */
    public static int coreSetSize() {
        int n = 0;
        for (TssErrorCode c : values()) {
            if (c.isCoreSet()) {
                n++;
            }
        }
        return n;
    }
}
