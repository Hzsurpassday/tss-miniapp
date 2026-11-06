package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 会话令牌 → 可信身份（本人数据隔离的唯一入口）。
 *
 * <p>解析顺序：
 * <ol>
 *   <li>真实微信会话存储（服务端签发，唯一 openid 来源）；</li>
 *   <li>仅当令牌带 DEV Mock 前缀 {@code mock.} 且 DEV Mock 存储当前可用时，才走 Mock 存储；
 *       在 TEST/PROD（或开关关闭）下该存储根本不存在，因此无法解析——fail-closed；</li>
 *   <li>其余情况一律 {@code TSS_UNAUTHORIZED}(401)，不做任何字面推断。</li>
 * </ol>
 */
@Service
public class WxAuthService {

    /** DEV Mock 令牌前缀：与真实令牌（64 位 hex）形态互斥，杜绝混淆。 */
    public static final String MOCK_TOKEN_PREFIX = "mock.";

    private final WxSessionStore realSessions;
    private final BindingStore bindings;
    private final ObjectProvider<DevMockSessionStore> mockSessions;

    public WxAuthService(WxSessionStore realSessions,
                         BindingStore bindings,
                         ObjectProvider<DevMockSessionStore> mockSessions) {
        this.realSessions = realSessions;
        this.bindings = bindings;
        this.mockSessions = mockSessions;
    }

    /**
     * 解析 Bearer 令牌为可信身份。
     *
     * @param rawAuthorization {@code Authorization} 头原值（{@code Bearer <token>}）
     */
    public WxIdentity resolve(String rawAuthorization) {
        String token = extractBearer(rawAuthorization);
        if (token == null || token.isBlank()) {
            throw new TssApiException(TssErrorCode.TSS_UNAUTHORIZED, "缺少或无效的 Authorization: Bearer 会话令牌");
        }
        Instant now = Instant.now();

        WxSessionRecord real = realSessions.find(token);
        if (real != null) {
            assertUsable(real, now);
            return build(real);
        }
        if (token.startsWith(MOCK_TOKEN_PREFIX)) {
            DevMockSessionStore mockStore = mockSessions.getIfAvailable();
            if (mockStore == null) {
                // TEST/PROD 或开关关闭：Mock 存储不存在 ⇒ 不可能解析出 Mock 身份
                throw new TssApiException(TssErrorCode.TSS_UNAUTHORIZED,
                        "会话令牌无效（DEV Mock 会话在当前环境不可用）");
            }
            // 用「含已过期」的取法：已过期必须落成 401 TSS_SESSION_EXPIRED（"登录已过期"），
            // 而不是被逐出后退化成 TSS_UNAUTHORIZED（"令牌无效"）——后者会让客户端误判为
            // "从没登录过"，从而做不出"登录已过期"这个正确提示。DEV 夹具
            // （/dev-support/v1/expire-session 与 /dev-support/v1/revoke-session）正是靠这条路径
            // 构造出 TSS_SESSION_EXPIRED。
            WxSessionRecord mockRec = mockStore.findIncludingExpired(token);
            if (mockRec == null) {
                throw new TssApiException(TssErrorCode.TSS_UNAUTHORIZED, "会话令牌无效");
            }
            assertUsable(mockRec, now);
            return build(mockRec);
        }
        throw new TssApiException(TssErrorCode.TSS_UNAUTHORIZED, "会话令牌无效");
    }

    /** 要求已绑定（API-03..API-08 使用）。 */
    public WxIdentity requireBound(String rawAuthorization) {
        WxIdentity id = resolve(rawAuthorization);
        assertBound(id);
        return id;
    }

    /**
     * 对<b>已解析</b>的身份做绑定状态检查（裁决 C 的落地点）。
     *
     * <p>拆成独立方法是为了让 API-11 能按「参数检查先于状态检查」的顺序编排：
     * 先解析身份（401）→ 校验 {@code confirmation} / {@code clientNonce}（400）→ 再检查绑定状态（403）。
     *
     * <p>错误码矩阵（与 Python 侧 RC3 MOCK 完全一致）：
     * <pre>
     *   UNBOUND / PENDING（申请中，关系未建立） → 403 TSS_NOT_BOUND
     *   DISABLED（绑定存在但被停用）           → 403 TSS_BINDING_DISABLED
     * </pre>
     * 刻意不用 409 {@code TSS_BIND_CONFLICT}：其权威文案是「已有冲突绑定」，
     * 而未绑定场景<b>根本不存在绑定</b>，谈不上冲突，用它是语义错配。
     */
    public void assertBound(WxIdentity id) {
        if (id.bindStatus() == BindStatus.DISABLED) {
            // 绑定被停用（含 ERP USERDOC 用户状态失效导致绑定不可用）：403 权威码 TSS_BINDING_DISABLED
            throw new TssApiException(TssErrorCode.TSS_BINDING_DISABLED,
                    "绑定已停用，无法访问业务数据");
        }
        if (!id.isBound()) {
            throw new TssApiException(TssErrorCode.TSS_NOT_BOUND,
                    "微信身份未完成绑定，无法访问业务数据");
        }
    }

    /**
     * 撤销当前会话。
     *
     * <p><b>当前调用方：DEV 夹具 {@code POST /dev-support/v1/revoke-session}</b>
     * （见 {@code DevSessionFixtureController#revoke}）。在 RC2 的 13 个契约端点里它<b>没有</b>调用方，
     * 这是刻意保留的会话管理能力：RC2 未定义登出端点，
     * 且 API-11 解绑按跨端统一口径（见 README §9.1）<b>不再撤销会话</b>——
     * 解绑只解除绑定关系，会话仍有效，因此「二次解绑」能落到状态检查
     * 403 {@code TSS_NOT_BOUND}（裁决 C），而不是被 401 抢先短路。
     * 未绑定身份本就取不到任何业务数据，故不存在数据泄露面。
     */
    public void revoke(WxIdentity identity) {
        if (identity == null || identity.sessionToken() == null) {
            return;
        }
        if (identity.mockSession()) {
            DevMockSessionStore mockStore = mockSessions.getIfAvailable();
            if (mockStore != null) {
                mockStore.revoke(identity.sessionToken());
            }
            return;
        }
        realSessions.revoke(identity.sessionToken(), "USER_UNBIND");
    }

    private static void assertUsable(WxSessionRecord rec, Instant now) {
        if (rec.isRevoked()) {
            throw new TssApiException(TssErrorCode.TSS_SESSION_EXPIRED, "会话已被撤销");
        }
        if (rec.isExpired(now)) {
            throw new TssApiException(TssErrorCode.TSS_SESSION_EXPIRED, "会话已过期");
        }
    }

    private WxIdentity build(WxSessionRecord rec) {
        BindingRecord b = bindings.findByOpenid(rec.openid());
        if (b == null) {
            return new WxIdentity(rec.token(), rec.openid(), null, null, null,
                    BindStatus.UNBOUND, null, null, null, rec.mock());
        }
        return new WxIdentity(rec.token(), rec.openid(), b.entId(), b.userId(), b.displayName(),
                b.bindStatus(), b.bindingVersion(), b.erpUserMasked(), b.entDisplayName(), rec.mock());
    }

    /** 从 {@code Authorization: Bearer <token>} 中取令牌；格式不符返回 null。 */
    static String extractBearer(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if (v.length() > 7 && v.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return v.substring(7).trim();
        }
        return null;
    }
}
