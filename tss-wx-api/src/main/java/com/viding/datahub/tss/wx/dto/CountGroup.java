package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RC2 {@code CountGroup}：本人待办分类计数（API-03）。
 *
 * <p>V1.0 只统计当前企业下「本人」候选的待办，不含任何跨员工或跨企业数据。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CountGroup(
        int total,
        int purchaseInbound,
        int salesBusiness,
        int payment,
        int salesPricing
) {
    public static CountGroup of(int purchaseInbound, int salesBusiness, int payment, int salesPricing) {
        return new CountGroup(purchaseInbound + salesBusiness + payment + salesPricing,
                purchaseInbound, salesBusiness, payment, salesPricing);
    }

    public static CountGroup empty() {
        return new CountGroup(0, 0, 0, 0, 0);
    }
}
