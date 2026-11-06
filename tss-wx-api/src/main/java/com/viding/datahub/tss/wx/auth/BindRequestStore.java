package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.dto.TssEnums.BindingStatusValue;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 绑定申请的内存存储（DEV Mock 服务）。
 *
 * <p>同时承担幂等职责：{@code claimNonce} 与 {@code clientNonce} 一旦被用于某个内容，
 * 再次用于不同内容即视为冲突（{@code TSS_IDEMPOTENCY_CONFLICT}）。
 *
 * <p><b>归档语义（INV-B / 裁决 B）</b>：申请单一旦终结即<b>从存储中移除</b>，而不是留在库里靠展现层遮掩。
 * 这样 {@code bindRequestId} 天然只表达「当前存在尚未终结的申请流程」，
 * 「PC 确认后已绑定」与「从未申请过」的响应形状完全一致。
 */
@Service
public class BindRequestStore {

    private final Map<String, BindRequestRecord> byId = new ConcurrentHashMap<>();
    private final Map<String, String> nonceOwner = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(1);

    public String nextBindRequestId() {
        return "br_" + String.format("%03d", sequence.getAndIncrement());
    }

    public void save(BindRequestRecord record) {
        byId.put(record.bindRequestId(), record);
        nonceOwner.put(nonceKey(record.claimNonce()), record.openid() + ":" + record.inviteCode());
    }

    /**
     * 按 id 取申请，过期则<b>返回</b>一条 {@code EXPIRED} 视图（不改写存储）。
     *
     * <p>存储改写在 {@link BindingViewResolver#resolve} 的状态推进阶段统一完成，
     * 保证 {@code /wx/v1/me} 与 {@code /wx/v1/bindings/status} 看到同一份已推进的状态。
     */
    public BindRequestRecord find(String bindRequestId) {
        BindRequestRecord rec = findRaw(bindRequestId);
        if (rec == null) {
            return null;
        }
        if (rec.isExpired(Instant.now())) {
            return rec.withStatus(BindingStatusValue.EXPIRED);
        }
        return rec;
    }

    /** 原样取申请（不做过期改写）；DEV 夹具与状态推进使用。 */
    public BindRequestRecord findRaw(String bindRequestId) {
        if (bindRequestId == null || bindRequestId.isBlank()) {
            return null;
        }
        return byId.get(bindRequestId);
    }

    /** 归档（移除）单条申请。 */
    public void remove(String bindRequestId) {
        if (bindRequestId != null) {
            byId.remove(bindRequestId);
        }
    }

    /**
     * 移除该 openid 名下的全部绑定申请（解绑 / 关系终局时调用）。
     *
     * <p><b>为什么必须移除而不是标记过期</b>：RC3 不变量 INV-B 要求
     * 「{@code bindRequestId} 非 null，仅当会话当前持有尚未终结的申请单」。
     * 解绑后绑定关系已不存在，若残留一条 {@code PENDING_PC_CONFIRM} 的申请，
     * API-10 就会一边说「申请待确认」、一边与 API-02 的 {@code UNBOUND} 打架——
     * 这正是 RC3 明确禁止的「{@code status} 与 {@code bindRequestId} 语义互相矛盾」。
     * 与 Python 侧 RC3 MOCK（{@code advance_bind_state} 终态归档）的处置一致。
     */
    public void dropByOpenid(String openid) {
        if (openid == null) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (BindRequestRecord r : byId.values()) {
            if (openid.equals(r.openid())) {
                ids.add(r.bindRequestId());
            }
        }
        for (String id : ids) {
            byId.remove(id);
        }
    }

    /** 取该 openid 最近一次绑定申请（带过期改写；API-10 缺省行为）。 */
    public BindRequestRecord latest(String openid) {
        BindRequestRecord best = latestRaw(openid);
        return best == null ? null : find(best.bindRequestId());
    }

    /** 取该 openid 最近一次绑定申请的<b>原始</b>记录（不做过期改写）。 */
    public BindRequestRecord latestRaw(String openid) {
        BindRequestRecord best = null;
        List<BindRequestRecord> all = new ArrayList<>(byId.values());
        for (BindRequestRecord r : all) {
            if (openid == null || !openid.equals(r.openid())) {
                continue;
            }
            if (best == null || r.createdAt().isAfter(best.createdAt())) {
                best = r;
            }
        }
        return best;
    }

    /**
     * DEV 夹具：把申请单立即置为过期（用于演示「申请过期」这一格，无需等待 TTL）。
     *
     * @return 改写后的记录；不存在则返回 null
     */
    public BindRequestRecord markExpired(String bindRequestId, Instant now) {
        BindRequestRecord rec = findRaw(bindRequestId);
        if (rec == null) {
            return null;
        }
        BindRequestRecord expired = rec.withStatus(BindingStatusValue.EXPIRED)
                .withExpiresAt(now.minusSeconds(1));
        byId.put(expired.bindRequestId(), expired);
        return expired;
    }

    /**
     * DEV 夹具：把申请单置为「PC 端已拒绝」。
     *
     * <p>REJECTED 属终态：下一次状态推进即被归档，因此 {@code bindRequestId} 归 null（INV-B）。
     *
     * @return 改写后的记录；不存在则返回 null
     */
    public BindRequestRecord markRejected(String bindRequestId) {
        BindRequestRecord rec = findRaw(bindRequestId);
        if (rec == null) {
            return null;
        }
        BindRequestRecord rejected = rec.withStatus(BindingStatusValue.REJECTED);
        byId.put(rejected.bindRequestId(), rejected);
        return rejected;
    }

    /**
     * 校验幂等 nonce：若该 nonce 曾用于不同内容，返回 false（视为冲突）。
     *
     * @param nonce 客户端一次性随机值
     * @param content 本次请求的内容指纹
     */
    public boolean checkNonce(String nonce, String content) {
        if (nonce == null || nonce.isBlank()) {
            return true;
        }
        String prev = nonceOwner.get(nonceKey(nonce));
        if (prev == null) {
            return true;
        }
        return prev.equals(content);
    }

    public void rememberNonce(String nonce, String content) {
        if (nonce == null || nonce.isBlank()) {
            return;
        }
        nonceOwner.put(nonceKey(nonce), content);
    }

    private static String nonceKey(String nonce) {
        return "nonce:" + nonce;
    }
}
