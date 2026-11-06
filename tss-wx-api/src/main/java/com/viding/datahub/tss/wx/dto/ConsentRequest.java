package com.viding.datahub.tss.wx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** RC2 {@code ConsentRequest}（API-08 请求体）。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ConsentRequest(
        List<String> templateIds,
        Boolean consented
) {
}
