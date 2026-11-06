package com.viding.datahub.tss.wx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * RC2 契约适配层运行参数（前缀 {@code datahub.tss.wx}）。
 *
 * <p>设计原则：所有「真实能力」都必须显式配置才可用，缺省即 fail-closed，
 * 不允许出现「看起来开了、实际不可用却还在跑」的假象。
 */
@ConfigurationProperties(prefix = "datahub.tss.wx")
public class TssWxProperties {

    /** RC2 DataMeta.schemaVersion。 */
    private String schemaVersion = "1.0.0";

    /** RC2 DataMeta.dataMode：本适配层恒为 MOCK（真实 ERP 接入前不得改为 ERP）。 */
    private String dataMode = "MOCK";

    /** 时间输出时区。 */
    private String clockZone = "Asia/Shanghai";

    /** 静态样例源位置（B1）。 */
    private String mockExamples = "classpath:mock/api-examples-rc2.json";

    /**
     * RC2 {@code DataMeta.freshness}（F2）取值：{@code FRESH} / {@code DELAYED} / {@code UNAVAILABLE}。
     *
     * <p>默认 {@code FRESH}：MOCK 数据源就在本进程内，不存在同步延迟，这是<b>唯一诚实</b>的默认值。
     * 之所以要可配置，是为了让前端「新鲜度提示条」的三种形态都能在联调中被真实触发——
     * 否则 {@code DELAYED} / {@code UNAVAILABLE} 两条分支永远走不到，等于没测。
     *
     * <p><b>红线</b>：接真实 ERP 后必须改为按 {@code asOf} 与阈值<b>计算</b>，不得继续用静态配置冒充实时判断。
     */
    private String freshness = "FRESH";

    /** 不透明签名游标参数。 */
    private Cursor cursor = new Cursor();

    /** 真实微信登录参数（唯一真实身份来源）。 */
    private Wechat wechat = new Wechat();

    /** DEV 专用 Mock 令牌颁发器参数（B1 安全红线）。 */
    private MockToken mockToken = new MockToken();

    /** PC 管理端静态令牌（DEV 联调用；生产必须接入真实 RBAC）。 */
    private Admin admin = new Admin();

    /** 订阅消息（API-08）参数。 */
    private Subscription subscription = new Subscription();

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String v) { schemaVersion = v; }
    public String getDataMode() { return dataMode; }
    public void setDataMode(String v) { dataMode = v; }
    public String getClockZone() { return clockZone; }
    public void setClockZone(String v) { clockZone = v; }
    public String getMockExamples() { return mockExamples; }
    public void setMockExamples(String v) { mockExamples = v; }
    public String getFreshness() { return freshness; }
    public void setFreshness(String v) { freshness = v; }
    public Cursor getCursor() { return cursor; }
    public void setCursor(Cursor v) { cursor = v; }
    public Wechat getWechat() { return wechat; }
    public void setWechat(Wechat v) { wechat = v; }
    public MockToken getMockToken() { return mockToken; }
    public void setMockToken(MockToken v) { mockToken = v; }
    public Admin getAdmin() { return admin; }
    public void setAdmin(Admin v) { admin = v; }
    public Subscription getSubscription() { return subscription; }
    public void setSubscription(Subscription v) { subscription = v; }

    /**
     * 订阅消息参数（API-08）。
     *
     * <p>{@code knownTemplateIds} 是服务端<b>已知可用</b>的微信订阅消息模板 ID 白名单。
     * 未配置时任何模板都视为「不可用」⇒ 返回 {@code UNAVAILABLE}，
     * 避免把「用户点了同意」直接谎报为「订阅已生效」。
     */
    public static class Subscription {
        private List<String> knownTemplateIds = new ArrayList<>();

        public List<String> getKnownTemplateIds() { return knownTemplateIds; }
        public void setKnownTemplateIds(List<String> v) { knownTemplateIds = v; }
    }

    /** 不透明签名游标配置。 */
    public static class Cursor {
        /** HMAC 密钥；为空时进程启动随机生成（重启后旧游标失效）。 */
        private String secret = "";
        /** 游标有效期（秒）。 */
        private long ttlSeconds = 1800L;
        /** 默认每页条数。 */
        private int defaultLimit = 20;
        /** 每页上限（RC2：maximum 100）。 */
        private int maxLimit = 100;

        public String getSecret() { return secret; }
        public void setSecret(String v) { secret = v; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long v) { ttlSeconds = v; }
        public int getDefaultLimit() { return defaultLimit; }
        public void setDefaultLimit(int v) { defaultLimit = v; }
        public int getMaxLimit() { return maxLimit; }
        public void setMaxLimit(int v) { maxLimit = v; }
    }

    /** 真实微信登录配置。enabled=false 或 appid/secret 缺失 ⇒ 微信登录不可用（fail-closed）。 */
    public static class Wechat {
        private boolean enabled = false;
        private String appid = "";
        private String secret = "";
        private String code2SessionUrl = "https://api.weixin.qq.com/sns/jscode2session";
        private int timeoutMs = 5000;
        private long sessionTtlSeconds = 7200L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { enabled = v; }
        public String getAppid() { return appid; }
        public void setAppid(String v) { appid = v; }
        public String getSecret() { return secret; }
        public void setSecret(String v) { secret = v; }
        public String getCode2SessionUrl() { return code2SessionUrl; }
        public void setCode2SessionUrl(String v) { code2SessionUrl = v; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int v) { timeoutMs = v; }
        public long getSessionTtlSeconds() { return sessionTtlSeconds; }
        public void setSessionTtlSeconds(long v) { sessionTtlSeconds = v; }

        /** 是否具备真实登录能力（开关 + appid + secret 三者齐备）。 */
        public boolean isUsable() {
            return enabled && appid != null && !appid.isBlank() && secret != null && !secret.isBlank();
        }
    }

    /** DEV 专用 Mock 令牌颁发器配置（B1）。 */
    public static class MockToken {
        /** 显式开关，默认 false。 */
        private boolean enabled = false;
        /** 运行环境自证串，必须为 DEV_LOCAL 才允许颁发。 */
        private String envAttestation = "";
        /** 颁发的 Mock 会话有效期（秒）。 */
        private long ttlSeconds = 7200L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { enabled = v; }
        public String getEnvAttestation() { return envAttestation; }
        public void setEnvAttestation(String v) { envAttestation = v; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long v) { ttlSeconds = v; }
    }

    /**
     * PC 管理端静态令牌（DEV）。
     *
     * <p>格式：{@code <token>=<ROLE>|<entId>}，多个之间以英文逗号分隔，例如
     * {@code <由环境注入的令牌>=ADMIN|E498AU5L35J}。
     *
     * <p>【RC3 红线】本类与所有配置文件的<b>默认值必须为空</b>：任何可用令牌都只能由运行环境注入
     * （环境变量 {@code TSS_WX_ADMIN_STATIC_TOKENS} 或启动参数），源码与配置中不得出现明文令牌。
     *
     * <p>保留为原始字符串而不是 {@code Map}：Boot 4 不再提供 String→Map 的默认转换器，
     * 与其依赖隐式转换，不如在 {@code AdminTokenService} 中显式解析（行为可测、失败可解释）。
     */
    public static class Admin {
        private String staticTokens = "";

        public String getStaticTokens() { return staticTokens; }
        public void setStaticTokens(String v) { staticTokens = v; }
    }
}
