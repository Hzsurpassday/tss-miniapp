package com.viding.datahub.tss.wx.mock;

import com.viding.datahub.tss.wx.dto.TssEnums.Freshness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code meta.freshness} 可配置（F2）。
 *
 * <p>写死 FRESH 的代价：前端新鲜度提示条的 {@code DELAYED} / {@code UNAVAILABLE}
 * 两条分支<b>永远走不到</b>，等于这两个分支从没被测过，真到了数据延迟那天就是线上才发现。
 * 因此 freshness 必须能从配置注入，本测试锁住「可注入 + 非法值回退 FRESH」这两条行为。
 */
@DisplayName("meta.freshness 可配置（默认 FRESH，非法值回退 FRESH）")
class MockFreshnessTest {

    @Test
    @DisplayName("三种合法取值逐个生效")
    void legal_values() {
        assertEquals(Freshness.FRESH, MockDataStore.parseFreshness("FRESH"));
        assertEquals(Freshness.DELAYED, MockDataStore.parseFreshness("DELAYED"));
        assertEquals(Freshness.UNAVAILABLE, MockDataStore.parseFreshness("UNAVAILABLE"));
        assertEquals(Freshness.DELAYED, MockDataStore.parseFreshness("delayed"), "大小写不敏感");
        assertEquals(Freshness.DELAYED, MockDataStore.parseFreshness(" DELAYED "), "允许两端空白");
    }

    @Test
    @DisplayName("null / 空串 / 非法值一律回退 FRESH（不是抛异常，也不是静默给个错值）")
    void illegal_values_fallback_to_fresh() {
        assertEquals(Freshness.FRESH, MockDataStore.parseFreshness(null));
        assertEquals(Freshness.FRESH, MockDataStore.parseFreshness(""));
        assertEquals(Freshness.FRESH, MockDataStore.parseFreshness("   "));
        assertEquals(Freshness.FRESH, MockDataStore.parseFreshness("STALE"),
                "RC2 只有 FRESH/DELAYED/UNAVAILABLE，不得自造取值");
    }
}
