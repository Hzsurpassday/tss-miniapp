package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.dto.TssEnums;
import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * DEV 专用 Mock 令牌颁发器（B1）。
 *
 * <p>与真实微信登录的隔离体现在四个层面：
 * <ol>
 *   <li><b>装配隔离</b>：本 Bean 仅在 {@link DevOnlyCondition} 成立时注册（dev + 显式开关 + 环境自证）；</li>
 *   <li><b>存储隔离</b>：写入 {@link DevMockSessionStore}，与 {@link WxSessionStore} 毫无交集；</li>
 *   <li><b>形态隔离</b>：令牌带 {@code mock.} 前缀，与真实令牌（64 位 hex）不可能混淆；</li>
 *   <li><b>运行时隔离</b>：每次颁发都重新校验 {@link MockTokenGate} 的四重条件（含回环来源）。</li>
 * </ol>
 * 任何一环不满足即拒绝，且拒绝信息中不含任何可被利用的细节。
 */
@Service
@Conditional(DevOnlyCondition.class)
public class MockTokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(MockTokenIssuer.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DevMockSessionStore mockSessions;
    private final BindingStore bindings;
    private final BindRequestStore bindRequests;
    private final MockTokenGate gate;
    private final ObjectProvider<InMemoryBindingStore> idProvider;

    public MockTokenIssuer(DevMockSessionStore mockSessions, BindingStore bindings,
                           BindRequestStore bindRequests, MockTokenGate gate,
                           ObjectProvider<InMemoryBindingStore> idProvider) {
        this.mockSessions = mockSessions;
        this.bindings = bindings;
        this.bindRequests = bindRequests;
        this.gate = gate;
        this.idProvider = idProvider;
    }

    /**
     * 颁发一枚 DEV Mock 会话令牌。
     *
     * @param request    请求（entId / userId / bindStatus）
     * @param remoteAddr 请求来源 IP（必须回环）
     */
    public MockTokenResponse issue(MockTokenRequest request, String remoteAddr) {
        // 运行时四重门禁：缺一即抛 TSS_NOT_CONFIGURED
        gate.assertIssuable(remoteAddr);

        if (request == null || request.entId() == null || request.entId().isBlank()
                || request.userId() == null || request.userId().isBlank()) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT, "entId 与 userId 不能为空");
        }
        BindStatus bindStatus = request.bindStatus() == null ? BindStatus.BOUND : request.bindStatus();
        String entId = request.entId().trim();
        String userId = request.userId().trim();

        String openid = MockIdentity.openidFor(entId, userId);
        String token = WxAuthService.MOCK_TOKEN_PREFIX + randomHex(32);
        long ttl = Math.max(60, mockSessions.ttlSeconds());
        Instant now = Instant.now();
        mockSessions.save(new WxSessionRecord(token, openid, null, "DEV_MOCK",
                now, now.plusSeconds(ttl), null, true));

        String displayName = (request.displayName() == null || request.displayName().isBlank())
                ? "DEV Mock 用户 " + userId : request.displayName().trim();
        // 幂等：同一 openid 且绑定状态未变时复用既有绑定（保留 DEV 预置夹具的 bindingVersion 等字段）
        BindingRecord existing = bindings.findByOpenid(openid);
        if (existing == null || existing.bindStatus() != bindStatus) {
            String bindingId = existing != null && existing.bindingId() != null
                    ? existing.bindingId()
                    : (idProvider.getIfAvailable() == null
                        ? "b_" + Math.abs(openid.hashCode()) : idProvider.getIfAvailable().nextBindingId());
            int version = existing == null ? 1
                    : (existing.bindingVersion() == null ? 1 : existing.bindingVersion() + 1);
            bindings.save(new BindingRecord(bindingId, openid, entId, userId, displayName, version, bindStatus,
                    mask(openid), mask(userId), "DEV Mock 企业 " + entId, Instant.now()));
        }

        syncBindRequests(openid, bindStatus, now);

        log.warn("[TSS-WX][DEV-ONLY] 颁发 Mock 令牌: entId={} userId={} bindStatus={} (禁止用于 TEST/PROD)",
                entId, userId, bindStatus);
        return new MockTokenResponse(token, (int) ttl, bindStatus, entId, userId, true,
                "DEV-only mock session token. It is NOT a WeChat session and must never be issued outside dev.");
    }

    /**
     * 让「申请流程」与「绑定关系」互相自洽（INV-B）：颁发令牌即把身份置为某个绑定状态，
     * 那么该身份名下的申请单也必须同时落到与之匹配的位置，否则 {@code /wx/v1/me} 与
     * {@code /wx/v1/bindings/status} 会给出互相矛盾的答案。
     *
     * <ul>
     *   <li>{@code PENDING}（已提交、待 PC 确认）⇒ 必须持有一条未终结的 {@code PENDING_PC_CONFIRM} 申请；
     *       没有就补一条（这是 DEV 夹具的补完，不是伪造用户行为——登录码 {@code MOCK_PENDING} 的语义本就如此，
     *       与 Python 侧 RC3 MOCK 在 {@code api_01_login} 中同义处理一致）；</li>
     *   <li>其余状态（{@code BOUND} / {@code DISABLED} / {@code UNBOUND}）⇒ 不存在未终结的申请流程，
     *       一律归档，使 {@code bindRequestId} 恒为 null（<b>裁决 B</b> 的边界：绑定成功后不得残留申请单）。</li>
     * </ul>
     */
    private void syncBindRequests(String openid, BindStatus bindStatus, Instant now) {
        if (bindStatus == BindStatus.PENDING) {
            BindRequestRecord open = bindRequests.latestRaw(openid);
            boolean usable = open != null
                    && open.status() == TssEnums.BindingStatusValue.PENDING_PC_CONFIRM
                    && !open.isExpired(now);
            if (!usable) {
                String id = bindRequests.nextBindRequestId();
                bindRequests.save(new BindRequestRecord(id, openid, "INV-EXAMPLE", "fixture-" + id,
                        TssEnums.BindingStatusValue.PENDING_PC_CONFIRM, now, now.plusSeconds(600L),
                        null, null, null, null));
            }
            return;
        }
        bindRequests.dropByOpenid(openid);
    }

    /** 脱敏：保留首 2 位与末 3 位，中间以 * 替代。 */
    private static String mask(String raw) {
        if (raw == null || raw.length() <= 5) {
            return raw == null ? null : "****";
        }
        return raw.substring(0, 2) + "****" + raw.substring(raw.length() - 3);
    }

    private static String randomHex(int chars) {
        byte[] buf = new byte[(chars + 1) / 2];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf).substring(0, chars);
    }
}
