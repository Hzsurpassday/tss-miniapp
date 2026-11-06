package com.viding.datahub.tss.wx.cursor;

import com.viding.datahub.tss.wx.config.TssWxProperties;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不透明签名游标（F3）：绑定用户 / 企业 / 筛选条件 / 稳定排序锚点。
 */
@DisplayName("不透明签名游标")
class CursorCodecTest {

    private CursorCodec codec;
    private static final String USER = "U1";
    private static final String ENT = "E1";
    private static final String ANCHOR = "anchor-v1";

    @BeforeEach
    void setUp() {
        TssWxProperties p = new TssWxProperties();
        p.getCursor().setSecret("unit-test-secret");
        p.getCursor().setTtlSeconds(1800);
        codec = new CursorCodec(p);
    }

    @Test
    @DisplayName("编解码往返一致")
    void roundTrip() {
        String hash = codec.filterHash("todos", "SALES_BUSINESS", "");
        String raw = codec.encode(new PageCursor(20, ANCHOR, hash, USER, ENT, now()));
        assertEquals(20, codec.decode(raw, USER, ENT, hash, ANCHOR));
    }

    @Test
    @DisplayName("空游标表示第一页（offset=0）")
    void emptyCursorIsFirstPage() {
        assertEquals(0, codec.decode(null, USER, ENT, "h", ANCHOR));
        assertEquals(0, codec.decode("", USER, ENT, "h", ANCHOR));
    }

    @Test
    @DisplayName("篡改签名 => TSS_INVALID_CURSOR")
    void tamperedSignatureRejected() {
        String hash = codec.filterHash("todos", "", "");
        String raw = codec.encode(new PageCursor(10, ANCHOR, hash, USER, ENT, now()));
        String tampered = raw.substring(0, raw.length() - 2) + "zz";
        TssApiException ex = assertThrows(TssApiException.class,
                () -> codec.decode(tampered, USER, ENT, hash, ANCHOR));
        assertEquals(TssErrorCode.TSS_INVALID_CURSOR, ex.errorCode());
        assertEquals(400, ex.httpStatus());
    }

    @Test
    @DisplayName("换用户使用 => 拒绝（禁止跨用户续页）")
    void otherUserRejected() {
        String hash = codec.filterHash("todos", "", "");
        String raw = codec.encode(new PageCursor(10, ANCHOR, hash, USER, ENT, now()));
        assertThrows(TssApiException.class, () -> codec.decode(raw, "U2", ENT, hash, ANCHOR));
    }

    @Test
    @DisplayName("换企业使用 => 拒绝（禁止跨企业续页）")
    void otherEntRejected() {
        String hash = codec.filterHash("todos", "", "");
        String raw = codec.encode(new PageCursor(10, ANCHOR, hash, USER, ENT, now()));
        assertThrows(TssApiException.class, () -> codec.decode(raw, USER, "E2", hash, ANCHOR));
    }

    @Test
    @DisplayName("筛选条件变化 => 拒绝（改变筛选必须丢弃旧 cursor）")
    void filterChangedRejected() {
        String oldHash = codec.filterHash("todos", "", "");
        String newHash = codec.filterHash("todos", "PAYMENT", "");
        String raw = codec.encode(new PageCursor(10, ANCHOR, oldHash, USER, ENT, now()));
        assertThrows(TssApiException.class, () -> codec.decode(raw, USER, ENT, newHash, ANCHOR));
    }

    @Test
    @DisplayName("排序锚点变化 => 拒绝")
    void anchorChangedRejected() {
        String hash = codec.filterHash("todos", "", "");
        String raw = codec.encode(new PageCursor(10, ANCHOR, hash, USER, ENT, now()));
        assertThrows(TssApiException.class, () -> codec.decode(raw, USER, ENT, hash, "anchor-v2"));
    }

    @Test
    @DisplayName("过期游标 => 拒绝")
    void expiredRejected() {
        String hash = codec.filterHash("todos", "", "");
        long longAgo = (System.currentTimeMillis() / 1000) - 3600;
        String raw = codec.encode(new PageCursor(10, ANCHOR, hash, USER, ENT, longAgo));
        assertThrows(TssApiException.class, () -> codec.decode(raw, USER, ENT, hash, ANCHOR));
    }

    @Test
    @DisplayName("畸形输入 => 拒绝（不因解析异常泄漏细节）")
    void malformedRejected() {
        assertThrows(TssApiException.class, () -> codec.decode("abc", USER, ENT, "h", ANCHOR));
        assertThrows(TssApiException.class, () -> codec.decode("a.b.c", USER, ENT, "h", ANCHOR));
        assertThrows(TssApiException.class, () -> codec.decode("!!!.@@@", USER, ENT, "h", ANCHOR));
    }

    @Test
    @DisplayName("游标不可被前端拆解：不含可读的用户/企业明文")
    void opaquePayload() {
        String hash = codec.filterHash("todos", "", "");
        String raw = codec.encode(new PageCursor(20, ANCHOR, hash, "U_SECRET", "E_SECRET", now()));
        assertTrue(raw.contains("."), "应为 payload.signature 形态");
        // payload 段是 base64url，不应直接包含明文 userId/entId
        String payload = raw.substring(0, raw.indexOf('.'));
        assertTrue(!payload.contains("U_SECRET") && !payload.contains("E_SECRET"),
                "明文身份不得出现在游标中（应编码后再签名）");
        assertNotEquals("20", payload, "offset 也不应以明文暴露");
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }
}
