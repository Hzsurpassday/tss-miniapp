package com.viding.datahub.tss.wx.auth;

/** PC 管理端身份（RC2 adminAuth）。 */
public record AdminPrincipal(
        String role,
        String entId
) {
    public static final String ROLE_ADMIN = "ADMIN";
}
