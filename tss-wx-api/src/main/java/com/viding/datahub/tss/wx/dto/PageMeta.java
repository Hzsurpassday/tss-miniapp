package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 {@code PageMeta}：分页元信息。
 *
 * <p>{@code nextCursor} 为服务端签名的不透明游标（F3），客户端不得解析；
 * {@code totalApprox} 可能为 null 且不保证精确。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PageMeta(
        String nextCursor,
        boolean hasMore,
        Integer totalApprox
) {
    public static PageMeta end(int total) {
        return new PageMeta(null, false, total);
    }
}
