package com.viding.datahub.tss.wx.envelope;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * traceId 上下文：由 {@link TraceIdFilter} 在请求入口写入，统一包络与日志均从此读取。
 *
 * <p>线程外/非 Web 场景（如单元测试直接调用服务）退化为本地生成，保证 {@code traceId} 永不为空。
 */
public final class TraceContext {

    public static final String ATTRIBUTE = TraceContext.class.getName() + ".traceId";
    public static final String MDC_KEY = "traceId";

    private TraceContext() {
    }

    /** 取当前请求 traceId；无请求上下文时生成一次性值。 */
    public static String current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            Object v = sra.getRequest().getAttribute(ATTRIBUTE);
            if (v != null && !v.toString().isBlank()) {
                return v.toString();
            }
        }
        return generate();
    }

    /** 生成一个新 traceId（格式 {@code tr_yyyyMMdd_<10位随机hex>}）。 */
    public static String generate() {
        StringBuilder hex = new StringBuilder();
        long seed = System.nanoTime() ^ System.identityHashCode(new Object());
        for (int i = 0; i < 10; i++) {
            hex.append(Integer.toHexString((int) (seed >>> (i * 3)) & 0xF));
            hex.append(Integer.toHexString((int) (seed >>> (i * 5 + 1)) & 0xF));
        }
        return "tr_" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                + "_" + hex.substring(0, 10);
    }

    /** 读取请求上的 traceId（供 Filter/Controller 显式传递，避免二次生成）。 */
    public static String from(HttpServletRequest request) {
        Object v = request == null ? null : request.getAttribute(ATTRIBUTE);
        return v == null ? null : v.toString();
    }
}
