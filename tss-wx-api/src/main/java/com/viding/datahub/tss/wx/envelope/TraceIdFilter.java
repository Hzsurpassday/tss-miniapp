package com.viding.datahub.tss.wx.envelope;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为每个请求生成/传递 traceId，写入请求属性、MDC 与响应头 {@code X-Trace-Id}。
 *
 * <p>统一包络（F1）要求每次响应都带 traceId，便于端到端排查。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String RESPONSE_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(RESPONSE_HEADER);
        String traceId = (incoming == null || incoming.isBlank()) ? TraceContext.generate() : incoming.trim();
        request.setAttribute(TraceContext.ATTRIBUTE, traceId);
        MDC.put(TraceContext.MDC_KEY, traceId);
        response.setHeader(RESPONSE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }
}
