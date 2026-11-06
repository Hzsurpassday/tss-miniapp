package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.config.TssWxProperties;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * PC 管理端令牌解析（RC2 {@code adminAuth}，API-12 / API-13）。
 *
 * <p><b>诚实声明</b>：RC2 只声明了 {@code adminAuth: http bearer}，<b>未定义管理端令牌的颁发与校验机制</b>。
 * 本实现为 DEV 联调提供「可配置静态令牌」，且遵循 fail-closed：
 * <ul>
 *   <li>未配置任何令牌 ⇒ 所有 {@code /admin/v1/tss/**} 一律 401（不会默认放行）；</li>
 *   <li>缺凭据 / 令牌无效 ⇒ 401 {@code TSS_UNAUTHORIZED}；</li>
 *   <li>令牌有效但角色不是 ADMIN ⇒ 403 {@code TSS_ADMIN_FORBIDDEN}。</li>
 * </ul>
 * 生产上线前必须替换为真实 PC 会话 + RBAC；在替换并通过评审前，不得宣称管理端鉴权已完备。
 */
@Service
public class AdminTokenService {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenService.class);

    private final Map<String, AdminPrincipal> tokens;

    public AdminTokenService(TssWxProperties props) {
        Map<String, AdminPrincipal> parsed = new java.util.LinkedHashMap<>();
        String raw = props.getAdmin() == null ? null : props.getAdmin().getStaticTokens();
        if (raw != null && !raw.isBlank()) {
            // 格式：<token>=<ROLE>|<entId>，多个以逗号分隔
            for (String entry : raw.split(",")) {
                String e = entry == null ? "" : entry.trim();
                if (e.isBlank()) {
                    continue;
                }
                int eq = e.indexOf('=');
                if (eq <= 0) {
                    log.warn("[TSS-WX] 忽略格式非法的管理端令牌配置片段（应为 <token>=<ROLE>|<entId>）");
                    continue;
                }
                String token = e.substring(0, eq).trim();
                String spec = e.substring(eq + 1).trim();
                String[] parts = spec.split("\\|", 2);
                String role = parts[0].trim();
                String entId = parts.length > 1 ? parts[1].trim() : "";
                if (!token.isEmpty() && !role.isEmpty()) {
                    parsed.put(token, new AdminPrincipal(role, entId.isEmpty() ? null : entId));
                }
            }
        }
        this.tokens = parsed;
        if (parsed.isEmpty()) {
            log.warn("[TSS-WX][fail-closed] 未配置任何管理端令牌：/admin/v1/tss/** 将一律返回 401");
        } else {
            log.warn("[TSS-WX] 管理端使用静态令牌（{} 个），仅可用于 DEV 联调；生产必须接入真实 RBAC",
                    parsed.size());
        }
    }

    /**
     * 解析管理端令牌。
     *
     * @param rawAuthorization {@code Authorization} 头原值
     */
    public AdminPrincipal resolve(String rawAuthorization) {
        String token = WxAuthService.extractBearer(rawAuthorization);
        if (token == null || token.isBlank()) {
            throw new TssApiException(TssErrorCode.TSS_UNAUTHORIZED, "缺少或无效的 Authorization: Bearer 管理端令牌");
        }
        AdminPrincipal p = tokens.get(token);
        if (p == null) {
            throw new TssApiException(TssErrorCode.TSS_UNAUTHORIZED, "管理端令牌无效");
        }
        if (!AdminPrincipal.ROLE_ADMIN.equals(p.role())) {
            throw new TssApiException(TssErrorCode.TSS_ADMIN_FORBIDDEN, "管理端角色权限不足");
        }
        return p;
    }
}
