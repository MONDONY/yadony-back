package com.yadony.api.cancellation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Verrou D3 — pas d'annulation en transit ni après le départ réel d'un colis remis. */
class CancellationGuardTest {

    private BidEntity bid(BidStatus status) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "status", status);
        return b;
    }

    private AnnouncementEntity announcement(OffsetDateTime departureAt) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureAt(departureAt);
        return a;
    }

    @Test
    void handed_over_before_departure_is_cancellable() {
        assertThatCode(() -> CancellationGuard.assertCancellable(
                bid(BidStatus.HANDED_OVER), announcement(OffsetDateTime.now().plusHours(5))))
                .doesNotThrowAnyException();
    }

    @Test
    void handed_over_after_departure_is_locked() {
        assertThatThrownBy(() -> CancellationGuard.assertCancellable(
                bid(BidStatus.HANDED_OVER), announcement(OffsetDateTime.now().minusMinutes(1))))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void in_transit_is_always_locked() {
        assertThatThrownBy(() -> CancellationGuard.assertCancellable(
                bid(BidStatus.IN_TRANSIT), announcement(OffsetDateTime.now().plusHours(5))))
                .isInstanceOf(YadonyBusinessException.class);
    }

    /** Régression C1 : ARRIVED est la suite de IN_TRANSIT, il doit être aussi verrouillé.
     *  Sans ARRIVED dans le verrou, la fenêtre d'annulation remboursée se rouvrait
     *  au moment même où le colis est arrivé (trou financier). */
    @Test
    void arrived_is_always_locked() {
        assertThatThrownBy(() -> CancellationGuard.assertCancellable(
                bid(BidStatus.ARRIVED), announcement(OffsetDateTime.now().plusHours(5))))
                .isInstanceOf(YadonyBusinessException.class);
    }

    /** Régression C1 : verrouillé même sans departureAt renseigné sur l'annonce —
     *  le statut ARRIVED se suffit à lui-même, il ne dépend pas du backstop de départ. */
    @Test
    void arrived_is_locked_even_without_departure_at() {
        assertThatThrownBy(() -> CancellationGuard.assertCancellable(
                bid(BidStatus.ARRIVED), announcement(null)))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void handed_over_without_departure_at_is_cancellable() {
        assertThatCode(() -> CancellationGuard.assertCancellable(
                bid(BidStatus.HANDED_OVER), announcement(null)))
                .doesNotThrowAnyException();
    }

    @Test
    void accepted_after_departure_is_still_cancellable() {
        // Le verrou de départ ne s'applique qu'au colis remis (HANDED_OVER).
        assertThatCode(() -> CancellationGuard.assertCancellable(
                bid(BidStatus.ACCEPTED), announcement(OffsetDateTime.now().minusHours(1))))
                .doesNotThrowAnyException();
    }
}
