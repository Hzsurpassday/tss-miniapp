package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 客户端身份注入门禁（企业上下文 + 本人数据隔离）。
 *
 * <p>RC2 与契约决议明确：{@code /wx/v1} 的 USERID 与 entId 一律由服务端可信会话推导（单当前企业），
 * <b>绝不接受客户端提交的 userId / entId 作为授权依据</b>。
 *
 * <p>本门禁对出现在查询串中的身份类参数直接拒绝（400 TSS_INVALID_ARGUMENT），而不是「静默忽略」——
 * 静默忽略会让调用方误以为自己提交的身份生效了，这比报错更危险。
 */
@Component
public class ClientIdentityGuard {

    /** 禁止由客户端提交的身份类参数名（大小写不敏感）。 */
    private static final Set<String> FORBIDDEN = Set.of(
            "userid", "entid", "openid", "staffid", "bindingversion", "unionid", "username");

    public void rejectIfClientIdentitySupplied(HttpServletRequest request) {
        if (request == null) {
            return;
        }
        Enumeration<String> names = request.getParameterNames();
        if (names == null) {
            return;
        }
        Set<String> hit = new LinkedHashSet<>();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name != null && FORBIDDEN.contains(name.toLowerCase())) {
                hit.add(name);
            }
        }
        if (!hit.isEmpty()) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT,
                    "身份与企业上下文由服务端会话推导，不接受客户端提交的参数: " + String.join(",", hit));
        }
    }
}
