package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.config.TssWxProperties;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * DEV 专用 Mock 令牌颁发器的<b>运行时门禁</b>（B1 安全红线）。
 *
 * <p>本门禁的设计目标是「无法被单一开关绕过」：颁发一次 Mock 令牌必须<b>同时</b>满足四个互相独立的条件，
 * 任一不满足即拒绝（fail-closed）：
 * <ol>
 *   <li><b>Profile 条件</b>：当前激活 profile 必须包含 {@code dev}，且<b>不得</b>包含 {@code test} / {@code prod}；</li>
 *   <li><b>显式开关</b>：{@code datahub.tss.wx.mock-token.enabled=true}（默认 false）；</li>
 *   <li><b>运行环境自证</b>：{@code datahub.tss.wx.mock-token.env-attestation=DEV_LOCAL}（默认空 ⇒ 拒绝）；</li>
 *   <li><b>来源地址</b>：请求必须来自本机回环地址（127.0.0.1 / ::1 / localhost）。</li>
 * </ol>
 * 此外，{@link MockTokenGuard} 在启动时做 fail-fast 校验：若开关为 true 而 profile 不是纯 dev，
 * 进程直接启动失败，杜绝「TEST/PROD 上被悄悄打开」。
 *
 * <p>与真实微信登录的隔离：Mock 令牌写入 {@link DevMockSessionStore}，真实登录写入 {@link WxSessionStore}；
 * 两者存储、令牌形态（{@code mock.} 前缀 vs 64 位 hex）、颁发条件完全分离。
 */
@Component
public class MockTokenGate {

    private static final Logger log = LoggerFactory.getLogger(MockTokenGate.class);

    /** 允许颁发的环境自证串：必须显式等于该值。 */
    public static final String REQUIRED_ATTESTATION = "DEV_LOCAL";

    private final Environment environment;
    private final TssWxProperties props;

    public MockTokenGate(Environment environment, TssWxProperties props) {
        this.environment = environment;
        this.props = props;
    }

    /** 是否满足「profile + 显式开关 + 运行环境自证」三项（不含来源地址，地址在每次请求时校验）。 */
    public boolean isEnabled() {
        return profileAllowsDev() && props.getMockToken().isEnabled() && attestationOk();
    }

    /**
     * 校验是否满足全部四项条件；不满足则抛 {@code TSS_NOT_CONFIGURED}(503)。
     *
     * @param remoteAddr 请求来源 IP；为 null 视为不满足
     */
    public void assertIssuable(String remoteAddr) {
        if (!profileAllowsDev()) {
            deny("当前 profile 不是 dev（或同时含 test/prod），Mock 令牌颁发器不可用");
        }
        if (!props.getMockToken().isEnabled()) {
            deny("Mock 令牌颁发器默认关闭，需显式设置 datahub.tss.wx.mock-token.enabled=true");
        }
        if (!attestationOk()) {
            deny("运行环境自证不匹配，需设置 datahub.tss.wx.mock-token.env-attestation=" + REQUIRED_ATTESTATION);
        }
        if (!isLoopback(remoteAddr)) {
            deny("Mock 令牌只允许来自本机回环地址，当前来源: " + remoteAddr);
        }
        log.warn("[TSS-WX][DEV-ONLY] Mock 令牌颁发：已通过四重门禁（profile/开关/环境自证/回环来源）");
    }

    /** profile 必须是 dev，且不得同时为 test/prod。 */
    public boolean profileAllowsDev() {
        boolean dev = environment.acceptsProfiles(Profiles.of("dev"));
        boolean nonDev = environment.acceptsProfiles(Profiles.of("test", "prod"));
        return dev && !nonDev;
    }

    public boolean attestationOk() {
        String v = props.getMockToken().getEnvAttestation();
        return REQUIRED_ATTESTATION.equals(v == null ? "" : v.trim());
    }

    /** 逐条列出未满足的条件（供运维/测试取证，不泄露密钥）。 */
    public String describeBlockers() {
        StringBuilder sb = new StringBuilder();
        if (!profileAllowsDev()) {
            sb.append("[profile 非 dev 或含 test/prod]");
        }
        if (!props.getMockToken().isEnabled()) {
            sb.append("[显式开关未打开]");
        }
        if (!attestationOk()) {
            sb.append("[运行环境自证缺失或不匹配]");
        }
        return sb.length() == 0 ? "[]" : sb.toString();
    }

    /** 是否本机回环地址。 */
    public static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        String v = remoteAddr.trim();
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            v = end > 0 ? v.substring(1, end) : v;
        }
        return "127.0.0.1".equals(v) || "::1".equals(v) || "0:0:0:0:0:0:0:1".equals(v)
                || "localhost".equalsIgnoreCase(v);
    }

    private void deny(String reason) {
        log.warn("[TSS-WX][fail-closed] Mock 令牌颁发被拒绝: {} ; blockers={}", reason, describeBlockers());
        throw new TssApiException(TssErrorCode.TSS_NOT_CONFIGURED, reason);
    }
}
