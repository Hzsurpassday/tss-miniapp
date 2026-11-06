package com.viding.datahub.tss.wx.auth;

import com.viding.datahub.tss.wx.dto.TssEnums.BindStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 绑定记录的内存实现。
 *
 * <p><b>诚实声明</b>：本实现仅服务于 DEV Mock 服务与契约测试，不连接 ERP USERDOC。
 * 生产落地时应替换为读取 {@code tss_staff_binding} 的实现，接口不变。
 */
@Service
public class InMemoryBindingStore implements BindingStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryBindingStore.class);

    private final Map<String, BindingRecord> byOpenid = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(1);

    public InMemoryBindingStore() {
        // 预置一条与 RC2 样例一致的已绑定记录，供 DEV 联调与契约测试使用。
        // openid 用 MockIdentity 派生，使「为同一身份颁发 Mock 令牌」时能复用本夹具
        // （从而 API-02 的 userId/entId/displayName/bindingVersion 与 RC2 样例完全一致）。
        // 注意：这只是 Mock 夹具，不代表 ERP 中真实存在该 USERDOC 用户。
        String demoOpenid = MockIdentity.openidFor("E498AU5L35J", "U4FXG07VRDI");
        byOpenid.put(demoOpenid, new BindingRecord(
                "b_1", demoOpenid, "E498AU5L35J", "U4FXG07VRDI", "示例员工", 3, BindStatus.BOUND,
                "o_****", "U4****RDI", "示例企业", Instant.now()));
        log.info("[TSS-WX] binding store: in-memory mock (1 seeded binding, MOCK data only)");
    }

    /** 生成下一个绑定 ID（DEV 内存实现的自增序列）。 */
    public String nextBindingId() {
        return "b_" + (1000 + sequence.getAndIncrement());
    }

    @Override
    public BindingRecord findByOpenid(String openid) {
        return openid == null ? null : byOpenid.get(openid);
    }

    @Override
    public void save(BindingRecord record) {
        byOpenid.put(record.openid(), record);
    }

    @Override
    public void removeByOpenid(String openid) {
        byOpenid.remove(openid);
    }

    @Override
    public List<BindingRecord> byEntId(String entId) {
        List<BindingRecord> out = new ArrayList<>();
        byOpenid.values().forEach(r -> {
            if (entId != null && entId.equals(r.entId())) {
                out.add(r);
            }
        });
        return out;
    }

    @Override
    public List<BindingRecord> all() {
        return new ArrayList<>(byOpenid.values());
    }
}
