package com.viding.datahub.tss.wx.config;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * RC2 时间输出工具（F1：时间遵循带时区 ISO-8601）。
 *
 * <p>所有对外时间字段统一由本类生成，格式 {@code 2026-10-31T10:30:00+08:00}
 * （{@link DateTimeFormatter#ISO_OFFSET_DATE_TIME}，截断到秒）。
 * 固定单一业务时区，避免「同一响应里时间偏移不一致」的隐性契约破坏。
 */
@Component
public class TssWxClock {

    private final ZoneId zone;

    public TssWxClock(TssWxProperties props) {
        String raw = props.getClockZone();
        this.zone = (raw == null || raw.isBlank()) ? ZoneId.of("Asia/Shanghai") : ZoneId.of(raw.trim());
    }

    /** 业务时区（供 from/to 日期比较等使用）。 */
    public ZoneId zone() {
        return zone;
    }

    /** 当前时刻（带偏移，截断到秒）。 */
    public OffsetDateTime offsetNow() {
        return OffsetDateTime.now(zone).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    /** 当前时刻的 ISO-8601 字符串，如 {@code 2026-10-31T10:30:00+08:00}。 */
    public String isoNow() {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(offsetNow());
    }

    public Instant instantNow() {
        return Instant.now();
    }

    /** 当前业务日期（用于 from/to 默认边界）。 */
    public LocalDate today() {
        return LocalDate.now(zone);
    }
}
