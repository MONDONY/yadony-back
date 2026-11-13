package com.yadony.api.payments.supplement;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.PayoutTrigger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Supplément de prix d'un colis payé au dépôt : fermé dès le dépôt, inchangé avant et pour un colis payé à la livraison. */
class BidSupplementWindowDepositTest {

    private final BidSupplementWindow window = new BidSupplementWindow(new BidSupplementProperties(),
            Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC));
    private final AnnouncementEntity trip = trip();

    @Test
    void supplement_depositBidHandedOver_422() {
        for (BidStatus status : new BidStatus[]{BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED,
                BidStatus.COMPLETED}) {
            BidEntity bid = bid(status, PayoutTrigger.DEPOSIT);

            assertThatThrownBy(() -> window.requireOpen(bid, trip))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.getErrorCode()).isEqualTo("supplement-after-deposit");
                    });
            assertThat(window.isOpen(bid, trip)).isFalse();
        }
    }

    @Test
    void supplement_depositBidBeforeDeposit_open() {
        BidEntity bid = bid(BidStatus.ACCEPTED, PayoutTrigger.DEPOSIT);

        assertThat(window.requireOpen(bid, trip)).isNotNull();
        assertThat(window.isOpen(bid, trip)).isTrue();
    }

    @Test
    void supplement_planeHandedOver_unchanged() {
        BidEntity bid = bid(BidStatus.HANDED_OVER, PayoutTrigger.DELIVERY);

        assertThat(window.requireOpen(bid, trip)).isEqualTo(LocalDateTime.of(2026, 11, 13, 12, 0));
        assertThat(window.isOpen(bid, trip)).isTrue();
    }

    @Test
    void supplement_deliveryBidInTransit_stillInvalidStatus() {
        BidEntity bid = bid(BidStatus.IN_TRANSIT, PayoutTrigger.DELIVERY);

        assertThatThrownBy(() -> window.requireOpen(bid, trip))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("invalid-bid-status"));
    }

    private static AnnouncementEntity trip() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureDate(LocalDate.of(2026, 11, 20));
        a.setTimezone("Europe/Paris");
        return a;
    }

    private static BidEntity bid(BidStatus status, PayoutTrigger trigger) {
        BidEntity bid = new BidEntity();
        bid.setStatus(status);
        try {
            Field f = BidEntity.class.getDeclaredField("payoutTrigger");
            f.setAccessible(true);
            f.set(bid, trigger);
            Field at = BidEntity.class.getDeclaredField("payoutTriggerFrozenAt");
            at.setAccessible(true);
            at.set(bid, LocalDateTime.of(2026, 11, 1, 0, 0));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return bid;
    }
}
