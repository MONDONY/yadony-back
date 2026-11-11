package com.yadony.api.dac7.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Une ligne du registre ({@code GET /admin/dac7/sellers/{userId}/diligence}) ; {@code actorName} : e-mail de l'admin. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Dac7DiligenceEntry(Instant at, String action, String channel, String actorName, String note) {
}
