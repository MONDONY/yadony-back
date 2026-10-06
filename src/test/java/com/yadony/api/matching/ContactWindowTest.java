package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Fenêtre de contact expéditeur ↔ voyageur, partagée par l'appel in-app et le bouton téléphone. */
class ContactWindowTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"ACCEPTED", "HANDED_OVER", "IN_TRANSIT", "ARRIVED"})
    void ouverteDeLAcceptationALArrivee(BidStatus status) {
        assertThat(ContactWindow.isOpen(status, null, 3, NOW)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"CANCELLED", "NO_SHOW", "PARCEL_REFUSED", "REJECTED", "PENDING",
            "PAYMENT_ESCROWED", "AWAITING_PAYMENT", "EXPIRED", "NEGOTIATING", "NEGOTIATION_CLOSED"})
    void fermeeHorsCommandeActive(BidStatus status) {
        assertThat(ContactWindow.isOpen(status, NOW.minusHours(1), 3, NOW)).isFalse();
    }

    @Test
    void livreAJPlus2Ouverte() {
        assertThat(ContactWindow.isOpen(BidStatus.COMPLETED, NOW.minusDays(2), 3, NOW)).isTrue();
        assertThat(ContactWindow.isOpen(BidStatus.COMPLETED, NOW.minusDays(3).plusMinutes(1), 3, NOW)).isTrue();
    }

    @Test
    void livreAJPlus3EtAuDelaFermee() {
        assertThat(ContactWindow.isOpen(BidStatus.COMPLETED, NOW.minusDays(3), 3, NOW)).isFalse();
        assertThat(ContactWindow.isOpen(BidStatus.COMPLETED, NOW.minusDays(4), 3, NOW)).isFalse();
    }

    @Test
    void livreSansDateFermee() {
        assertThat(ContactWindow.isOpen(BidStatus.COMPLETED, null, 3, NOW)).isFalse();
    }

    @Test
    void statutInconnuFermee() {
        assertThat(ContactWindow.isOpen(null, null, 3, NOW)).isFalse();
    }
}
