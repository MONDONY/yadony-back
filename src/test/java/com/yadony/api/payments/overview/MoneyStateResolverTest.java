package com.yadony.api.payments.overview;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyStateResolverTest {

    private static final OffsetDateTime RELEASED_AT = OffsetDateTime.of(2026, 10, 5, 14, 0, 0, 0, ZoneOffset.ofHours(2));

    /** Ligne de base : voyageur, carte, 100 EUR dont 12 de commission, en séquestre, colis accepté. */
    private static Row row() {
        return new Row();
    }

    private static final class Row {
        MoneyRole role = MoneyRole.TRAVELER;
        String bidStatus = "ACCEPTED";
        UUID paymentId = UUID.randomUUID();
        String paymentStatus = "ESCROW";
        String rail = "STRIPE";
        BigDecimal amount = new BigDecimal("100.00");
        BigDecimal commission = new BigDecimal("12.00");
        BigDecimal refunded;
        String currency = "eur";
        boolean chargeback;
        LocalDateTime payoutHeldAt;
        OffsetDateTime releasedAt;
        OffsetDateTime updatedAt = RELEASED_AT;
        long openDisputes;
        long allDisputes;
        OffsetDateTime holdUntil;
        long openPayouts;
        long openRefunds;

        MoneyRow build() {
            return new MoneyRow(role, UUID.randomUUID(), bidStatus, "STRIPE", "EUR", "DON-ABCDEFGH", new BigDecimal("10"), null,
                    UUID.randomUUID(), "Paris", "Dakar", LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), UUID.randomUUID(),
                    paymentId, paymentStatus, rail, amount, commission, refunded, currency, chargeback,
                    payoutHeldAt, releasedAt, updatedAt, openDisputes, allDisputes, holdUntil, openPayouts, openRefunds);
        }

        MoneyStateResolver.Resolution resolve() {
            return MoneyStateResolver.resolve(build()).orElseThrow();
        }
    }

    @Test
    void escrowBeforeHandover_isEscrowed_releasedOnDelivery_netOfCommission() {
        MoneyStateResolver.Resolution r = row().resolve();
        assertThat(r.state()).isEqualTo(MoneyState.ESCROWED);
        assertThat(r.condition()).isEqualTo(ReleaseCondition.ON_DELIVERY_CONFIRMATION);
        assertThat(r.amount()).isEqualByComparingTo("88.00");
        assertThat(r.currency()).isEqualTo("EUR");
        assertThat(r.releaseAt()).isNull();
    }

    @Test
    void escrowWithParcelEnRoute_awaitsDeliveryConfirmation() {
        for (String status : new String[]{"HANDED_OVER", "IN_TRANSIT", "ARRIVED"}) {
            Row r = row();
            r.bidStatus = status;
            assertThat(r.resolve().state()).isEqualTo(MoneyState.AWAITING_DELIVERY_CONFIRMATION);
        }
    }

    @Test
    void recipientNoShowHold_isScheduled_atHoldEnd() {
        Row r = row();
        r.bidStatus = "ARRIVED";
        r.holdUntil = OffsetDateTime.of(2026, 10, 17, 10, 0, 0, 0, ZoneOffset.ofHours(2));
        MoneyStateResolver.Resolution res = r.resolve();
        assertThat(res.state()).isEqualTo(MoneyState.RELEASE_SCHEDULED);
        assertThat(res.condition()).isEqualTo(ReleaseCondition.AUTO_RELEASE_AFTER_HOLD_IF_NO_DISPUTE);
        assertThat(res.releaseAt()).isEqualTo(OffsetDateTime.of(2026, 10, 17, 8, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void openDispute_winsOverHold() {
        Row r = row();
        r.holdUntil = OffsetDateTime.now();
        r.openDisputes = 1;
        r.allDisputes = 1;
        MoneyStateResolver.Resolution res = r.resolve();
        assertThat(res.state()).isEqualTo(MoneyState.IN_DISPUTE);
        assertThat(res.condition()).isEqualTo(ReleaseCondition.ADMIN_DECISION);
        assertThat(res.releaseAt()).isNull();
    }

    @Test
    void chargeback_isInDispute() {
        Row r = row();
        r.chargeback = true;
        assertThat(r.resolve().state()).isEqualTo(MoneyState.IN_DISPUTE);
    }

    @Test
    void heldPayout_partialRefund_orResolvedDispute_areOnHold() {
        Row held = row();
        held.payoutHeldAt = RELEASED_AT.toLocalDateTime();
        Row partial = row();
        partial.refunded = new BigDecimal("30.00");
        Row resolved = row();
        resolved.allDisputes = 1;
        for (Row r : new Row[]{held, partial, resolved}) {
            MoneyStateResolver.Resolution res = r.resolve();
            assertThat(res.state()).isEqualTo(MoneyState.ON_HOLD);
            assertThat(res.condition()).isEqualTo(ReleaseCondition.ADMIN_REVIEW);
        }
    }

    @Test
    void zeroRefund_isNotAHold() {
        Row r = row();
        r.refunded = BigDecimal.ZERO;
        assertThat(r.resolve().state()).isEqualTo(MoneyState.ESCROWED);
    }

    @Test
    void deliveredButStillEscrow_isPayoutInProgress() {
        Row r = row();
        r.bidStatus = "COMPLETED";
        assertThat(r.resolve().state()).isEqualTo(MoneyState.PAYOUT_IN_PROGRESS);
        assertThat(r.resolve().condition()).isEqualTo(ReleaseCondition.PAYOUT_PROCESSING);
    }

    @Test
    void nullBidStatus_fallsBackToEscrowed() {
        Row r = row();
        r.bidStatus = null;
        assertThat(r.resolve().state()).isEqualTo(MoneyState.ESCROWED);
    }

    @Test
    void openMobileMoneyRefund_isRefundPending() {
        Row r = row();
        r.openRefunds = 1;
        r.openDisputes = 1;
        MoneyStateResolver.Resolution res = r.resolve();
        assertThat(res.state()).isEqualTo(MoneyState.REFUND_PENDING);
        assertThat(res.condition()).isEqualTo(ReleaseCondition.REFUND_PROCESSING);
    }

    @Test
    void released_isReleasedRecently_withSettlementDate() {
        Row r = row();
        r.paymentStatus = "RELEASED";
        r.releasedAt = RELEASED_AT;
        MoneyStateResolver.Resolution res = r.resolve();
        assertThat(res.state()).isEqualTo(MoneyState.RELEASED_RECENTLY);
        assertThat(res.condition()).isEqualTo(ReleaseCondition.RELEASED);
        assertThat(res.settledAt()).isEqualTo(RELEASED_AT.withOffsetSameInstant(ZoneOffset.UTC));
    }

    @Test
    void releasedWithOpenMobileMoneyPayout_isPayoutInProgress_forBothRoles() {
        Row traveler = row();
        traveler.paymentStatus = "RELEASED";
        traveler.rail = "PAWAPAY";
        traveler.openPayouts = 1;
        assertThat(traveler.resolve().state()).isEqualTo(MoneyState.PAYOUT_IN_PROGRESS);

        Row sender = row();
        sender.role = MoneyRole.SENDER;
        sender.paymentStatus = "RELEASED";
        sender.openPayouts = 1;
        assertThat(sender.resolve().state()).isEqualTo(MoneyState.PAYOUT_IN_PROGRESS);
    }

    @Test
    void releasedWithoutPayout_isHiddenFromSender() {
        Row r = row();
        r.role = MoneyRole.SENDER;
        r.paymentStatus = "RELEASED";
        assertThat(MoneyStateResolver.resolve(r.build())).isEmpty();
    }

    @Test
    void refunded_isHiddenFromTraveler_andShownToSender() {
        Row traveler = row();
        traveler.paymentStatus = "REFUNDED";
        assertThat(MoneyStateResolver.resolve(traveler.build())).isEmpty();

        Row sender = row();
        sender.role = MoneyRole.SENDER;
        sender.paymentStatus = "REFUNDED";
        MoneyStateResolver.Resolution res = sender.resolve();
        assertThat(res.state()).isEqualTo(MoneyState.REFUNDED_RECENTLY);
        assertThat(res.condition()).isEqualTo(ReleaseCondition.REFUNDED);
        assertThat(res.amount()).isEqualByComparingTo("100.00");
        assertThat(res.settledAt()).isEqualTo(RELEASED_AT.withOffsetSameInstant(ZoneOffset.UTC));
    }

    @Test
    void senderAmount_isPaidMinusAlreadyRefunded() {
        Row r = row();
        r.role = MoneyRole.SENDER;
        r.refunded = new BigDecimal("40.00");
        assertThat(MoneyStateResolver.amountFor(r.build())).isEqualByComparingTo("60.00");
        r.refunded = null;
        assertThat(MoneyStateResolver.amountFor(r.build())).isEqualByComparingTo("100.00");
    }

    @Test
    void travelerAmount_toleratesMissingValues_andNeverGoesNegative() {
        Row r = row();
        r.amount = null;
        r.commission = null;
        assertThat(MoneyStateResolver.amountFor(r.build())).isEqualByComparingTo("0");
        r.amount = new BigDecimal("5");
        r.commission = new BigDecimal("9");
        assertThat(MoneyStateResolver.amountFor(r.build())).isEqualByComparingTo("0");
    }

    @Test
    void cashParcel_hasNoAmount_andNothingToReceiveThroughYadony() {
        MoneyRow cash = new MoneyRow(MoneyRole.TRAVELER, UUID.randomUUID(), "IN_TRANSIT", "CASH", "xof",
                "DON-CASH0001", new BigDecimal("10"), null, UUID.randomUUID(), "Paris", "Bamako", LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 10, 20), UUID.randomUUID(), null, null, null, null, null, null, null, false,
                null, null, null, 0, 0, null, 0, 0);
        MoneyStateResolver.Resolution res = MoneyStateResolver.resolve(cash).orElseThrow();
        assertThat(res.state()).isEqualTo(MoneyState.CASH);
        assertThat(res.condition()).isEqualTo(ReleaseCondition.CASH_IN_PERSON);
        assertThat(res.amount()).isNull();
        assertThat(res.currency()).isEqualTo("XOF");
    }

    @Test
    void nullCurrency_staysNull() {
        Row r = row();
        r.currency = null;
        assertThat(r.resolve().currency()).isNull();
    }
}
