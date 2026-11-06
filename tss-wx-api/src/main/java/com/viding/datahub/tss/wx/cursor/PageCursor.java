package com.viding.datahub.tss.wx.cursor;

/**
 * 分页游标的内容（服务端内部结构，绝不直接暴露给客户端）。
 *
 * <p>F3：cursor 是<b>不透明签名游标</b>，绑定用户 / 企业 / 筛选条件 / 稳定排序锚点，
 * 不支持前端拆解；改变筛选条件必须丢弃旧 cursor（否则签名校验会失败）。
 */
public record PageCursor(
        int offset,
        String anchor,
        String filterHash,
        String userId,
        String entId,
        long issuedAtEpoch
) {
}
