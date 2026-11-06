package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.config.TssWxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 启动期 fail-fast 守卫：确保 Mock 令牌颁发器<b>无法在 TEST/PROD 启用</b>（B1）。
 *
 * <p>逻辑：只要 {@code datahub.tss.wx.mock-token.enabled=true} 而当前 profile 不是「纯 dev」，
 * 就在应用启动后立即抛 {@link IllegalStateException} 终止进程。
 * 这样即使有人误把开关写进 TEST/PROD 配置，服务也起不来，而不是静默提供一个可伪造身份的端点。
 */
@Component
@Order(0)
public class MockTokenGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MockTokenGuard.class);

    private final Environment environment;
    private final TssWxProperties props;

    public MockTokenGuard(Environment environment, TssWxProperties props) {
        this.environment = environment;
        this.props = props;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        boolean enabled = props.getMockToken().isEnabled();
        boolean dev = environment.acceptsProfiles(Profiles.of("dev"));
        boolean nonDev = environment.acceptsProfiles(Profiles.of("test", "prod"));
        boolean devOnly = dev && !nonDev;

        if (enabled && !devOnly) {
            throw new IllegalStateException(
                    "TSS Mock 令牌颁发器只允许在 dev profile 启用；当前 profile 不满足（dev=" + dev
                            + ", test/prod=" + nonDev + "）。请关闭 datahub.tss.wx.mock-token.enabled 或改用 dev profile。");
        }
        if (enabled) {
            log.warn("[TSS-WX][DEV-ONLY] Mock 令牌颁发器已启用（dev profile）。"
                    + "禁止在 TEST/PROD 使用；它可签发任意身份的会话，仅供本机联调。");
        } else {
            log.info("[TSS-WX] Mock 令牌颁发器未启用（默认关闭）。真实身份来源只有微信 jscode2session。");
        }
    }
}
