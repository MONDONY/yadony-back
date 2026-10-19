package com.yadony.api.payments.overview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yadony.api.payments.bankpayout.BankPayoutStatus;
import com.yadony.api.payments.overview.dto.BankPayoutDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyOverviewBankPayoutTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private static MoneyRow row(MoneyRole role, String rail, String paymentStatus, MoneyRow.BankPayoutRow tracking) {
        return new MoneyRow(role, UUID.randomUUID(), "COMPLETED", "STRIPE", "EUR", "YD-1", BigDecimal.ONE, null,
                UUID.randomUUID(), "Paris", "Dakar", TODAY, TODAY, UUID.randomUUID(), UUID.randomUUID(), paymentStatus,
                rail, new BigDecimal("5.68"), new BigDecimal("0.68"), null, "EUR", false, null, null, null, 0, 0, null,
                0, 0, tracking);
    }

    private static MoneyRow.BankPayoutRow tracking(String status) {
        return new MoneyRow.BankPayoutRow(LocalDate.of(2026, 10, 22), status, LocalDate.of(2026, 10, 24),
                Instant.parse("2026-10-24T09:12:00Z"), "account_closed");
    }

    @Test
    void traveler_cardReleased_tracked_getsDerivedStatus() {
        BankPayoutDto pending = MoneyOverviewService.bankPayout(
                row(MoneyRole.TRAVELER, "STRIPE", "RELEASED", tracking(null)), TODAY);
        assertThat(pending).isEqualTo(new BankPayoutDto(BankPayoutStatus.PENDING_AVAILABILITY,
                LocalDate.of(2026, 10, 22), null, null, null));

        assertThat(MoneyOverviewService.bankPayout(row(MoneyRole.TRAVELER, "STRIPE", "RELEASED", tracking(null)),
                LocalDate.of(2026, 10, 22)).status()).isEqualTo(BankPayoutStatus.AVAILABLE);

        BankPayoutDto inTransit = MoneyOverviewService.bankPayout(
                row(MoneyRole.TRAVELER, "STRIPE", "RELEASED", tracking("in_transit")), TODAY);
        assertThat(inTransit.status()).isEqualTo(BankPayoutStatus.IN_TRANSIT);
        assertThat(inTransit.arrivalDate()).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(inTransit.paidAt()).isNull();

        BankPayoutDto paid = MoneyOverviewService.bankPayout(
                row(MoneyRole.TRAVELER, "STRIPE", "RELEASED", tracking("paid")), TODAY);
        assertThat(paid.paidAt()).isEqualTo(Instant.parse("2026-10-24T09:12:00Z"));
        assertThat(paid.failureCode()).isNull();

        BankPayoutDto failed = MoneyOverviewService.bankPayout(
                row(MoneyRole.TRAVELER, "STRIPE", "RELEASED", tracking("failed")), TODAY);
        assertThat(failed.status()).isEqualTo(BankPayoutStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("account_closed");
        assertThat(failed.arrivalDate()).isNull();
    }

    @Test
    void absent_forSender_mobileMoney_untracked_orNotReleased() {
        assertThat(MoneyOverviewService.bankPayout(row(MoneyRole.SENDER, "STRIPE", "RELEASED", tracking("paid")), TODAY))
                .isNull();
        assertThat(MoneyOverviewService.bankPayout(row(MoneyRole.TRAVELER, "PAWAPAY", "RELEASED", tracking("paid")), TODAY))
                .isNull();
        assertThat(MoneyOverviewService.bankPayout(row(MoneyRole.TRAVELER, "STRIPE", "RELEASED", null), TODAY)).isNull();
        assertThat(MoneyOverviewService.bankPayout(row(MoneyRole.TRAVELER, "STRIPE", "ESCROW", tracking(null)), TODAY))
                .isNull();
    }

    @Test
    void json_servesNullableFieldsAsNull_evenWithGlobalNonNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String json = mapper.writeValueAsString(new BankPayoutDto(BankPayoutStatus.AVAILABLE,
                LocalDate.of(2026, 10, 22), null, null, null));
        assertThat(json).isEqualTo("{\"status\":\"AVAILABLE\",\"availableOn\":\"2026-10-22\",\"arrivalDate\":null,"
                + "\"paidAt\":null,\"failureCode\":null}");
    }
}
