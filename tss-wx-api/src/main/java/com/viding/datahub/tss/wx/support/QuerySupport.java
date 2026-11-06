package com.viding.datahub.tss.wx.support;

import com.viding.datahub.tss.wx.dto.TssEnums.DocType;
import com.viding.datahub.tss.wx.dto.TssEnums.HistoryAction;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 查询参数解析（F3）：任何非法取值都以 {@code TSS_INVALID_ARGUMENT}(400) 明确拒绝，
 * 不做「解析失败就当没传」的静默降级——静默降级会让筛选结果与调用方预期不符却无任何提示。
 */
@Component
public class QuerySupport {

    /** docType 解析：空 ⇒ null（不过滤）；非法 ⇒ 400。 */
    public DocType docType(String raw) {
        return enumOrNull(raw, DocType.class, "docType");
    }

    /** action 解析：空 ⇒ null（不过滤）；非法 ⇒ 400。 */
    public HistoryAction action(String raw) {
        return enumOrNull(raw, HistoryAction.class, "action");
    }

    /** keyword 校验：RC2 约束 1..100 字符；空 ⇒ null。 */
    public String keyword(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if (v.length() < 1 || v.length() > 100) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT,
                    "keyword 长度必须在 1..100 之间，当前为 " + v.length());
        }
        return v;
    }

    /** 日期解析（{@code yyyy-MM-dd}）：空 ⇒ null；非法 ⇒ 400。 */
    public LocalDate date(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT,
                    name + " 必须是 yyyy-MM-dd 格式的日期，当前为 " + raw);
        }
    }

    /** 校验 from 不得晚于 to（RC2：from 不得晚于 to）。 */
    public void assertRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT,
                    "from 不得晚于 to（from=" + from + ", to=" + to + "）");
        }
    }

    private static <E extends Enum<E>> E enumOrNull(String raw, Class<E> type, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT,
                    name + " 取值非法: " + raw);
        }
    }
}
