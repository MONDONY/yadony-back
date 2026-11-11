package com.yadony.api.dac7.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yadony.api.dac7.Dac7Report.Anomaly;
import com.yadony.api.dac7.Dac7Report.AnomalyType;
import com.yadony.api.dac7.Dac7Source;

import java.time.LocalDate;
import java.util.UUID;

/** {@code GET /admin/dac7/anomalies} : les champs de {@link Anomaly} tels quels, plus {@code displayName}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Dac7AnomalyRow(AnomalyType type, UUID travelerId, String displayName, Dac7Source source, UUID sourceId,
                             String currency, LocalDate day) {

    public static Dac7AnomalyRow of(Anomaly a, String displayName) {
        return new Dac7AnomalyRow(a.type(), a.travelerId(), displayName, a.source(), a.sourceId(), a.currency(),
                a.day());
    }
}
