package com.yadony.api.dac7.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yadony.api.dac7.Dac7Report.RefundToReconcile;
import com.yadony.api.dac7.Dac7Source;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code GET /admin/dac7/refunds} : les champs de {@link RefundToReconcile} tels quels, plus {@code displayName}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Dac7RefundRow(UUID travelerId, String displayName, Dac7Source source, UUID sourceId, String currency,
                            BigDecimal amount, Instant operationPaidAt, int operationYear, Instant refundedAt,
                            int refundYear, boolean beforeRelease, boolean refundDateEstimated, BigDecimal amountEur) {

    public static Dac7RefundRow of(RefundToReconcile r, String displayName) {
        return new Dac7RefundRow(r.travelerId(), displayName, r.source(), r.sourceId(), r.currency(), r.amount(),
                r.operationPaidAt(), r.operationYear(), r.refundedAt(), r.refundYear(), r.beforeRelease(),
                r.refundDateEstimated(), r.amountEur());
    }
}
