package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.config.TssWxProperties;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEV Mock 令牌颁发器的门禁验证（B1 安全红线）。
 *
 * <p>目标：证明它<b>无法被单一开关绕过</b>——dev profile、显式开关、运行环境自证、回环来源
 * 四项缺一不可；且开关在非 dev profile 下会让进程启动失败（fail-fast）。
 */
@DisplayName("DEV Mock 令牌四重门禁")
class MockTokenGateTest {

    private static TssWxProperties props(boolean enabled, String attestation) {
        TssWxProperties p = new TssWxProperties();
        TssWxProperties.MockToken m = new TssWxProperties.MockToken();
        m.setEnabled(enabled);
        m.setEnvAttestation(attestation);
        p.setMockToken(m);
        return p;
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        return e;
    }

    @Test
    @DisplayName("四项全满足才允许颁发")
    void allFourConditionsRequired() {
        MockTokenGate gate = new MockTokenGate(env("dev"), props(true, "DEV_LOCAL"));
        assertTrue(gate.isEnabled());
        gate.assertIssuable("127.0.0.1");
    }

    @Test
    @DisplayName("缺少显式开关 => 拒绝（默认关闭）")
    void deniedWhenSwitchOff() {
        MockTokenGate gate = new MockTokenGate(env("dev"), props(false, "DEV_LOCAL"));
        assertFalse(gate.isEnabled());
        TssApiException ex = assertThrows(TssApiException.class, () -> gate.assertIssuable("127.0.0.1"));
        assertEquals(TssErrorCode.TSS_NOT_CONFIGURED, ex.errorCode());
        assertEquals(503, ex.httpStatus(), "服务能力未配置 ⇒ 503（收口到权威码 TSS_NOT_CONFIGURED）");
    }

    @Test
    @DisplayName("缺少运行环境自证 => 拒绝")
    void deniedWhenAttestationMissing() {
        MockTokenGate gate = new MockTokenGate(env("dev"), props(true, ""));
        assertFalse(gate.isEnabled());
        assertThrows(TssApiException.class, () -> gate.assertIssuable("127.0.0.1"));
    }

    @Test
    @DisplayName("profile 为 prod => 拒绝（即使开关打开且有自证）")
    void deniedInProdProfile() {
        MockTokenGate gate = new MockTokenGate(env("prod"), props(true, "DEV_LOCAL"));
        assertFalse(gate.profileAllowsDev());
        assertFalse(gate.isEnabled(), "非 dev profile 下 isEnabled 必须为 false");
        assertThrows(TssApiException.class, () -> gate.assertIssuable("127.0.0.1"));
    }

    @Test
    @DisplayName("profile 同时含 dev 与 prod => 拒绝")
    void deniedWhenDevAndProdTogether() {
        MockTokenGate gate = new MockTokenGate(env("dev", "prod"), props(true, "DEV_LOCAL"));
        assertFalse(gate.profileAllowsDev());
        assertThrows(TssApiException.class, () -> gate.assertIssuable("127.0.0.1"));
    }

    @Test
    @DisplayName("来源非回环地址 => 拒绝")
    void deniedWhenRemoteNotLoopback() {
        MockTokenGate gate = new MockTokenGate(env("dev"), props(true, "DEV_LOCAL"));
        assertTrue(gate.isEnabled(), "前三项满足，但来源项应在每次请求时拦截");
        assertThrows(TssApiException.class, () -> gate.assertIssuable("203.0.113.9"));
        assertThrows(TssApiException.class, () -> gate.assertIssuable(null));
    }

    @Test
    @DisplayName("回环地址识别：127.0.0.1 / ::1 / localhost")
    void loopbackDetection() {
        assertTrue(MockTokenGate.isLoopback("127.0.0.1"));
        assertTrue(MockTokenGate.isLoopback("::1"));
        assertTrue(MockTokenGate.isLoopback("[::1]"));
        assertTrue(MockTokenGate.isLoopback("localhost"));
        assertFalse(MockTokenGate.isLoopback("10.0.0.5"));
        assertFalse(MockTokenGate.isLoopback(""));
    }

    @Test
    @DisplayName("启动守卫：开关打开但 profile 为 prod => 进程启动失败（fail-fast）")
    void guardFailsFastInProd() {
        MockTokenGuard guard = new MockTokenGuard(env("prod"), props(true, "DEV_LOCAL"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard.run(null));
        assertTrue(ex.getMessage().contains("dev"), "异常信息应说明必须使用 dev profile");
    }

    @Test
    @DisplayName("启动守卫：dev profile + 开关打开 => 放行并留告警")
    void guardAllowsInDev() {
        MockTokenGuard guard = new MockTokenGuard(env("dev"), props(true, "DEV_LOCAL"));
        guard.run(null);
    }

    @Test
    @DisplayName("启动守卫：prod + 开关关闭 => 放行（默认安全）")
    void guardAllowsWhenDisabledInProd() {
        MockTokenGuard guard = new MockTokenGuard(env("prod"), props(false, ""));
        guard.run(null);
    }

    @Test
    @DisplayName("装配条件：prod 下即使开关打开也不注册 DEV Bean")
    void devOnlyConditionFalseInProd() {
        ConditionContext ctx = Mockito.mock(ConditionContext.class);
        MockEnvironment e = env("prod");
        e.setProperty(DevOnlyCondition.ENABLED_PROPERTY, "true");
        e.setProperty(DevOnlyCondition.ATTESTATION_PROPERTY, "DEV_LOCAL");
        Mockito.when(ctx.getEnvironment()).thenReturn(e);
        assertFalse(new DevOnlyCondition().matches(ctx, null),
                "TEST/PROD 下 DEV Mock Bean 必须不被注册");
    }

    @Test
    @DisplayName("装配条件：dev + 自证 + 开关 => 注册")
    void devOnlyConditionTrueInDev() {
        ConditionContext ctx = Mockito.mock(ConditionContext.class);
        MockEnvironment e = env("dev");
        e.setProperty(DevOnlyCondition.ENABLED_PROPERTY, "true");
        e.setProperty(DevOnlyCondition.ATTESTATION_PROPERTY, "DEV_LOCAL");
        Mockito.when(ctx.getEnvironment()).thenReturn(e);
        assertTrue(new DevOnlyCondition().matches(ctx, null));
    }

    @Test
    @DisplayName("Mock 令牌与真实令牌形态互斥")
    void tokenFormsAreExclusive() {
        String mockToken = WxAuthService.MOCK_TOKEN_PREFIX + "abc";
        assertTrue(mockToken.startsWith("mock."));
        String realToken = WxLoginService.newToken();
        assertFalse(realToken.startsWith(WxAuthService.MOCK_TOKEN_PREFIX),
                "真实登录签发的令牌不得带 mock. 前缀");
        assertEquals(64, realToken.length());
    }
}
