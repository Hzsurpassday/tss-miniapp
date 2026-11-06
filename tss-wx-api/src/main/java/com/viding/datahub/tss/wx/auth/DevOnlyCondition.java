package com.viding.datahub.tss.wx.auth;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 装配期条件：只有「dev profile + 显式开关 + 运行环境自证」三项同时成立，
 * DEV Mock 相关 Bean（Mock 会话存储 / 颁发器 / 颁发端点）才会被注册。
 *
 * <p>这是 {@link MockTokenGate}（运行时四重校验）之外的<b>第二道独立防线</b>：
 * 只要条件不满足，Bean 根本不存在，端点表现为 404，不存在「端点存在但校验可绕过」的情况。
 */
public class DevOnlyCondition implements Condition {

    public static final String ENABLED_PROPERTY = "datahub.tss.wx.mock-token.enabled";
    public static final String ATTESTATION_PROPERTY = "datahub.tss.wx.mock-token.env-attestation";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        boolean enabled = env.getProperty(ENABLED_PROPERTY, Boolean.class, false);
        boolean attested = MockTokenGate.REQUIRED_ATTESTATION.equals(
                env.getProperty(ATTESTATION_PROPERTY, String.class, ""));
        boolean devOnly = env.acceptsProfiles(Profiles.of("dev"))
                && !env.acceptsProfiles(Profiles.of("test", "prod"));
        return enabled && attested && devOnly;
    }
}
