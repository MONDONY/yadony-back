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

    // ── Retour d'un colis annulé après remise (FLUTTER-FM) ──────────────────

    private static BidEntity cancelledWithReturn(LocalDateTime deadline, LocalDateTime returnedAt) {
        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.CANCELLED);
        bid.setReturnDeadline(deadline);
        bid.setReturnedAt(returnedAt);
        return bid;
    }

    @Test
    void retourEnCours_ouvreLaFenetreEtLeNumero() {
        BidEntity bid = cancelledWithReturn(NOW.plusDays(2), null);
        assertThat(ContactWindow.isReturnInProgress(bid, NOW)).isTrue();
        assertThat(ContactWindow.isOpen(bid, 3, NOW)).isTrue();
        assertThat(ContactWindow.phoneVisible(bid, NOW)).isTrue();
    }

    @Test
    void colisRestitue_fermeLaFenetre() {
        BidEntity bid = cancelledWithReturn(NOW.plusDays(2), NOW.minusHours(1));
        assertThat(ContactWindow.isReturnInProgress(bid, NOW)).isFalse();
        assertThat(ContactWindow.isOpen(bid, 3, NOW)).isFalse();
        assertThat(ContactWindow.phoneVisible(bid, NOW)).isFalse();
    }

    @Test
    void delaiDeRetourEcoule_fermeLaFenetre() {
        assertThat(ContactWindow.isOpen(cancelledWithReturn(NOW, null), 3, NOW)).isFalse();
        assertThat(ContactWindow.isOpen(cancelledWithReturn(NOW.minusMinutes(1), null), 3, NOW)).isFalse();
    }

    @Test
    void annuleSansRetour_resteFerme() {
        assertThat(ContactWindow.isOpen(cancelledWithReturn(null, null), 3, NOW)).isFalse();
        assertThat(ContactWindow.phoneVisible(cancelledWithReturn(null, null), NOW)).isFalse();
    }

    @Test
    void delaiPoseSurUnAutreStatut_neCompteQueParLeStatut() {
        BidEntity refused = cancelledWithReturn(NOW.plusDays(2), null);
        refused.setStatus(BidStatus.PARCEL_REFUSED);
        assertThat(ContactWindow.isReturnInProgress(refused, NOW)).isFalse();
        assertThat(ContactWindow.isOpen(refused, 3, NOW)).isFalse();
    }

    @Test
    void bidNul_ferme() {
        assertThat(ContactWindow.isOpen((BidEntity) null, 3, NOW)).isFalse();
        assertThat(ContactWindow.isReturnInProgress(null, NOW)).isFalse();
        assertThat(ContactWindow.phoneVisible(null, NOW)).isFalse();
    }

    @Test
    void bidActif_suitLeStatut() {
        BidEntity accepted = new BidEntity();
        accepted.setStatus(BidStatus.ACCEPTED);
        assertThat(ContactWindow.isOpen(accepted, 3, NOW)).isTrue();
        assertThat(ContactWindow.phoneVisible(accepted, NOW)).isTrue();
    }
}
