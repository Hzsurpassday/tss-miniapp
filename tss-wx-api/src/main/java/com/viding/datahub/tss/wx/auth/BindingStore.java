package com.viding.datahub.tss.wx.auth;

import java.util.List;

/** 绑定记录存储抽象。 */
public interface BindingStore {

    BindingRecord findByOpenid(String openid);

    void save(BindingRecord record);

    void removeByOpenid(String openid);

    /** 按企业列出绑定（管理端 API-12 用；只返回该企业，不越权）。 */
    List<BindingRecord> byEntId(String entId);

    List<BindingRecord> all();
}
