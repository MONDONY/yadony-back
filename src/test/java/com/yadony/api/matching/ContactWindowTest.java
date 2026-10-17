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

    // ── 48 h après l'annulation d'une commande en cours (FLUTTER-P1) ─────────

    private static BidEntity cancelledFrom(BidStatus from, LocalDateTime cancelledAt) {
        BidEntity bid = new BidEntity();
        bid.setStatus(from);
        bid.setStatus(BidStatus.CANCELLED);
        bid.setCancelledAt(cancelledAt);
        return bid;
    }

    @Test
    void aLAnnulation_leStatutQuitteEtLInstantSontRetenus() {
        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.IN_TRANSIT);
        LocalDateTime before = LocalDateTime.now(java.time.ZoneOffset.UTC);

        bid.setStatus(BidStatus.CANCELLED);

        assertThat(bid.getCancelledFromStatus()).isEqualTo(BidStatus.IN_TRANSIT);
        assertThat(bid.getCancelledAt()).isAfterOrEqualTo(before);
    }

    @Test
    void uneSecondeAnnulation_neDeplacePasLHorodatage() {
        BidEntity bid = cancelledFrom(BidStatus.ACCEPTED, NOW.minusHours(10));

        bid.setStatus(BidStatus.CANCELLED);

        assertThat(bid.getCancelledAt()).isEqualTo(NOW.minusHours(10));
        assertThat(bid.getCancelledFromStatus()).isEqualTo(BidStatus.ACCEPTED);
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"ACCEPTED", "HANDED_OVER", "IN_TRANSIT", "ARRIVED"})
    void commandeEnCoursAnnulee_resteJoignable48h(BidStatus from) {
        BidEntity bid = cancelledFrom(from, NOW.minusHours(47).minusMinutes(59).minusSeconds(59));
        assertThat(ContactWindow.isRecentlyCancelled(bid, NOW)).isTrue();
        assertThat(ContactWindow.isOpen(bid, 3, NOW)).isTrue();
        assertThat(ContactWindow.phoneVisible(bid, NOW)).isTrue();
    }

    @Test
    void a48hPile_laFenetreEstFermee() {
        BidEntity bid = cancelledFrom(BidStatus.ACCEPTED, NOW.minus(ContactWindow.AFTER_CANCELLATION));
        assertThat(ContactWindow.isRecentlyCancelled(bid, NOW)).isFalse();
        assertThat(ContactWindow.isOpen(bid, 3, NOW)).isFalse();
        assertThat(ContactWindow.phoneVisible(bid, NOW)).isFalse();
    }

    @Test
    void auDelaDe48h_fermee() {
        BidEntity bid = cancelledFrom(BidStatus.ARRIVED, NOW.minusHours(49));
        assertThat(ContactWindow.isOpen(bid, 3, NOW)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"PENDING", "AWAITING_PAYMENT", "PAYMENT_ESCROWED", "NEGOTIATING"})
    void demandeAnnuleeAvantAcceptation_nOuvreRien(BidStatus from) {
        BidEntity bid = cancelledFrom(from, NOW.minusHours(1));
        assertThat(ContactWindow.isRecentlyCancelled(bid, NOW)).isFalse();
        assertThat(ContactWindow.isOpen(bid, 3, NOW)).isFalse();
        assertThat(ContactWindow.phoneVisible(bid, NOW)).isFalse();
    }

    @Test
    void annulationAnterieureAV316_sansHorodatage_fermee() {
        BidEntity bid = cancelledFrom(BidStatus.ACCEPTED, null);
        bid.setCancelledFromStatus(null);
        assertThat(ContactWindow.isRecentlyCancelled(bid, NOW)).isFalse();
    }

    @Test
    void bidSorti_deLAnnulation_nEstPlusConcerne() {
        BidEntity bid = cancelledFrom(BidStatus.ACCEPTED, NOW.minusHours(1));
        bid.setStatus(BidStatus.EXPIRED);
        assertThat(ContactWindow.isRecentlyCancelled(bid, NOW)).isFalse();
    }

    // ── Absence de l'expéditeur confirmée (FLUTTER-QQ) : pas de fenêtre de 48 h ─────

    @Test
    void absenceExpediteurConfirmee_fermeAppelsEtNumeroToutDeSuite() {
        BidEntity bid = cancelledFrom(BidStatus.ACCEPTED, NOW.minusMinutes(5));
        bid.setRejectionReason(BidEntity.REJECTION_SENDER_NO_SHOW);

        assertThat(ContactWindow.isClosedBySenderNoShow(bid)).isTrue();
        assertThat(ContactWindow.isRecentlyCancelled(bid, NOW)).isFalse();
        assertThat(ContactWindow.isOpen(bid, 3, NOW)).isFalse();
        assertThat(ContactWindow.phoneVisible(bid, NOW)).isFalse();
    }

    @Test
    void autreAnnulationRecente_garde48h() {
        BidEntity bid = cancelledFrom(BidStatus.ACCEPTED, NOW.minusMinutes(5));
        bid.setRejectionReason("CANCELLED_BY_SENDER");

        assertThat(ContactWindow.isClosedBySenderNoShow(bid)).isFalse();
        assertThat(ContactWindow.isRecentlyCancelled(bid, NOW)).isTrue();
    }

    @Test
    void motifAbsenceSurUnBidNonAnnule_neFermeRien() {
        BidEntity accepted = new BidEntity();
        accepted.setStatus(BidStatus.ACCEPTED);
        accepted.setRejectionReason(BidEntity.REJECTION_SENDER_NO_SHOW);

        assertThat(ContactWindow.isClosedBySenderNoShow(accepted)).isFalse();
        assertThat(ContactWindow.isClosedBySenderNoShow(null)).isFalse();
        assertThat(ContactWindow.isOpen(accepted, 3, NOW)).isTrue();
    }
}
