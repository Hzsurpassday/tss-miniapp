package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.config.TssWxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 微信客户端装配：有配置才可用，缺省即 fail-closed。
 *
 * <p>DEV Mock 令牌不经过本客户端，因此 Mock 链路的可用性<b>不可能</b>影响真实登录判断，
 * 反之亦然——两者在装配层面就已经分离。
 */
@Configuration
public class TssWxWechatClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TssWxWechatClientConfiguration.class);

    @Bean
    public TssWxWechatClient tssWxWechatClient(TssWxProperties props) {
        if (props.getWechat().isUsable()) {
            log.info("[TSS-WX] wechat client: HTTP (appid={})", props.getWechat().getAppid());
            return new TssWxWechatClient.HttpWechatClient(props);
        }
        log.warn("[TSS-WX][evidence=not-configured] wechat client: 未配置 appid/secret -> 真实微信登录不可用（fail-closed）");
        return new TssWxWechatClient.NotConfiguredWechatClient();
    }
}
