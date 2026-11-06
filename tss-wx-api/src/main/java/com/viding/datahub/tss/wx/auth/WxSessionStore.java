package com.viding.datahub.tss.wx.auth;

import java.util.List;

/**
 * 会话存储抽象（真实微信会话）。
 *
 * <p>本适配层默认提供内存实现，进程重启即失效——这是有意为之：DEV Mock 服务不应持有跨重启的身份凭据。
 * 生产落地时应替换为数据库实现（与既有 {@code tss_wechat_session} 表对齐），接口不变。
 */
public interface WxSessionStore {

    void save(WxSessionRecord record);

    /** 按 token 查找；不存在返回 null。 */
    WxSessionRecord find(String token);

    void revoke(String token, String reason);

    void revokeByOpenid(String openid, String reason);

    /** 换绑：只撤销该 openid 的「其它」会话，保留刚完成绑定的当前会话。 */
    void revokeByOpenidExcept(String openid, String keepToken, String reason);

    /** 快照（仅供管理端/测试核对，不含 token 明文之外的敏感信息）。 */
    List<WxSessionRecord> all();
}
