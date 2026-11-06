package com.viding.datahub.tss.wx.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RC3 红线守护：管理端令牌不得硬编码。
 *
 * <p>对方交付包里把管理端令牌写死在源码/配置中，RC3 明令禁止。我方必须干净：
 * 令牌只能来自配置占位符（环境变量 / 启动参数 / 外部化配置），源码、配置、文档、脚本里
 * 都不允许出现可用的明文令牌。本测试把这条约束变成可执行断言，防止后续"随手加个默认值"回潮。
 */
@DisplayName("RC3 红线：管理端令牌不得硬编码")
class AdminTokenConfigTest {

    /** 形如 {@code xxxxxxxx=ADMIN|ENT} 的明文令牌配置；占位符 {@code ${...}} 不算。 */
    private static final Pattern PLAIN_TOKEN = Pattern.compile("[A-Za-z0-9_-]{8,}=ADMIN\\|");

    @Test
    @DisplayName("生产源码 / 配置 / 文档 / 脚本中不得出现明文管理端令牌")
    void no_plain_token_in_main_sources_docs_and_scripts() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src", "main"))) {
            s.filter(Files::isRegularFile).forEach(files::add);
        }
        for (String extra : List.of("README-启动与验证.md", "smoke-13.sh", "smoke-pagination.sh")) {
            Path p = Path.of(extra);
            if (Files.exists(p)) {
                files.add(p);
            }
        }

        List<String> offenders = new ArrayList<>();
        for (Path p : files) {
            String name = p.toString();
            if (!(name.endsWith(".java") || name.endsWith(".yml") || name.endsWith(".json")
                    || name.endsWith(".md") || name.endsWith(".sh"))) {
                continue;
            }
            String text = Files.readString(p, StandardCharsets.UTF_8);
            int lineNo = 0;
            for (String line : text.split("\\R", -1)) {
                lineNo++;
                if (PLAIN_TOKEN.matcher(line).find()) {
                    offenders.add(p + ":" + lineNo + "  " + line.trim());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "发现硬编码的管理端令牌（必须改为占位符/环境变量注入）：\n" + String.join("\n", offenders));
    }

    @Test
    @DisplayName("application*.yml 中 static-tokens 的默认值必须是空或 ${...} 占位符")
    void config_default_must_be_placeholder_or_empty() throws IOException {
        for (String name : List.of("application.yml", "application-dev.yml")) {
            Path p = Path.of("src", "main", "resources", name);
            assertTrue(Files.exists(p), "缺少配置文件: " + name);
            for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String line = raw;
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash);
                }
                if (!line.contains("static-tokens")) {
                    continue;
                }
                int colon = line.indexOf(':');
                String value = colon < 0 ? "" : line.substring(colon + 1).trim();
                assertTrue(value.isEmpty() || value.startsWith("${"),
                        name + " 中 static-tokens 的默认值必须是空或 ${...} 占位符，实际=" + value);
            }
        }
    }
}
