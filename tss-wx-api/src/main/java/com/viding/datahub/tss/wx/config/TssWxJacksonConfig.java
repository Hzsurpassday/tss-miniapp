package com.viding.datahub.tss.wx.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

/**
 * Jackson 装配：请求侧严格、响应侧完整。
 *
 * <p><b>请求侧</b>：开启 {@code FAIL_ON_UNKNOWN_PROPERTIES}。RC2 的所有请求 schema 都是
 * {@code additionalProperties:false}，按兼容规则该约束「用于防止请求输入多余字段」，
 * 因此客户端多传字段应当被明确拒绝，而不是被静默忽略。
 *
 * <p><b>响应侧</b>：不做全局 {@code non_null} 裁剪——RC2 要求 {@code nextCursor: null}、
 * {@code counterparty: null}、{@code branchGroup: null} 等字段<b>必须出现</b>，
 * 缺省丢弃会破坏「字段不多不少」的契约。每个 DTO 已单独标注 {@code @JsonInclude(ALWAYS)}。
 */
@Configuration
public class TssWxJacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer tssWxJacksonCustomizer() {
        return builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
