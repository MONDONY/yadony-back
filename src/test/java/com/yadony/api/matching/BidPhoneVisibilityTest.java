package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Numéro du destinataire servi au voyageur : seulement après le retrait du colis (FLUTTER-JT). */
class BidPhoneVisibilityTest {

    @Test
    void numeroRevele_apresRetraitDuColis() {
        assertThat(BidService.recipientPhoneForTraveler("0612345678", BidStatus.HANDED_OVER)).isEqualTo("0612345678");
        assertThat(BidService.recipientPhoneForTraveler("0612345678", BidStatus.IN_TRANSIT)).isEqualTo("0612345678");
        assertThat(BidService.recipientPhoneForTraveler("0612345678", BidStatus.ARRIVED)).isEqualTo("0612345678");
        assertThat(BidService.recipientPhoneForTraveler("0612345678", BidStatus.COMPLETED)).isEqualTo("0612345678");
    }

    @Test
    void numeroMasque_avantRetrait_mêmeAccepte() {
        assertThat(BidService.recipientPhoneForTraveler("0612345678", BidStatus.ACCEPTED)).isNull();
        assertThat(BidService.recipientPhoneForTraveler("0612345678", BidStatus.PENDING)).isNull();
        assertThat(BidService.recipientPhoneForTraveler("0612345678", BidStatus.PAYMENT_ESCROWED)).isNull();
        assertThat(BidService.recipientPhoneForTraveler("0612345678", BidStatus.AWAITING_PAYMENT)).isNull();
        assertThat(BidService.recipientPhoneForTraveler("0612345678", BidStatus.REJECTED)).isNull();
        assertThat(BidService.recipientPhoneForTraveler("0612345678", BidStatus.CANCELLED)).isNull();
    }

    @Test
    void numeroNull_resteNull() {
        assertThat(BidService.recipientPhoneForTraveler(null, BidStatus.HANDED_OVER)).isNull();
    }

    @Test
    void contactExpediteurVoyageur_resteOuvertDesAccepted() {
        // La constante dédiée ne touche pas au contact expéditeur ↔ voyageur.
        assertThat(BidStatus.PHONE_VISIBLE_STATUSES).contains(BidStatus.ACCEPTED);
        assertThat(BidStatus.RECIPIENT_PHONE_VISIBLE_TO_TRAVELER_STATUSES)
                .containsExactlyInAnyOrder(BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT,
                        BidStatus.ARRIVED, BidStatus.COMPLETED);
    }
}
