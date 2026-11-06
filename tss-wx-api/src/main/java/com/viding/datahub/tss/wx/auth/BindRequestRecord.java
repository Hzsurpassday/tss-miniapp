package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.dto.TssEnums.BindingStatusValue;

import java.time.Instant;

/**
 * 绑定申请记录（API-09 发起 / API-10 查询）。
 *
 * <p>V1.0 绑定需 PC 端确认，故发起后状态为 {@code PENDING_PC_CONFIRM}；
 * 本适配层不实现 PC 确认端点（属下一阶段 Admin 契约范围），只如实维持待确认状态并给出过期时间。
 *
 * <p><b>生命周期（INV-B 的落地点）</b>：申请单只在 {@code PENDING_PC_CONFIRM} / {@code EXPIRED}
 * 两种「尚未终结」状态下对外可见；一旦流程终结（PC 确认通过 → BOUND、被拒 → REJECTED、
 * 解绑、关系停用）即刻从存储中归档，使 {@code bindRequestId} 恒等于「当前存在尚未终结的申请流程」。
 */
public record BindRequestRecord(
        String bindRequestId,
        String openid,
        String inviteCode,
        String claimNonce,
        BindingStatusValue status,
        Instant createdAt,
        Instant expiresAt,
        String entId,
        String userId,
        String erpUserMasked,
        String entDisplayName
) {

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** 返回状态变更后的副本（其余字段不变）。 */
    public BindRequestRecord withStatus(BindingStatusValue newStatus) {
        return new BindRequestRecord(bindRequestId, openid, inviteCode, claimNonce, newStatus,
                createdAt, expiresAt, entId, userId, erpUserMasked, entDisplayName);
    }

    /** 返回有效期变更后的副本（DEV 夹具用于即时制造「申请已过期」）。 */
    public BindRequestRecord withExpiresAt(Instant newExpiresAt) {
        return new BindRequestRecord(bindRequestId, openid, inviteCode, claimNonce, status,
                createdAt, newExpiresAt, entId, userId, erpUserMasked, entDisplayName);
    }
}
