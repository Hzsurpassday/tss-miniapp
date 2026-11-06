package com.viding.datahub.tss.wx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 时空智审 V1.0 RC2 契约适配层启动类。
 *
 * <p>职责边界（契约修订决议）：
 * <ul>
 *   <li>实现 {@code /wx/v1} 的 API-01..API-11 与 {@code /admin/v1/tss} 的 API-12..API-13，字段级以
 *       {@code tss-openapi-v1.0-rc2.yaml} 为唯一标准。</li>
 *   <li>V1.0 只读：{@code workflow.readOnly=true}，{@code availableActions} 只输出 VIEW；
 *       OPEN_ERP 仅在经过验证后才会输出（当前未验证，故不输出）。</li>
 *   <li>DEV Mock 数据服务：样例取自 {@code api-examples-rc2.json}，对外是真实 HTTP 响应，
 *       但不得被描述为「已接入真实 ERP」。</li>
 * </ul>
 *
 * <p>本模块与既有 {@code datahub-backend} 完全解耦：不修改其任何类、不占用其端口（8080）、
 * 不复用其数据源，因此可以在没有 MySQL/Flyway/Quartz 的机器上真实启动并跑契约测试。
 */
@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.viding.datahub.tss.wx")
public class TssWxApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(TssWxApiApplication.class, args);
    }
}
