package com.yadony.api.payments.currency;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.payments.cash.PaymentMethod;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * Recette du 2026-09-09 : une annonce XOF enregistrait la carte parmi ses moyens de paiement
 * et le séquestre Stripe partait en euros pour un montant en francs CFA.
 */
class AnnouncementPaymentRailsRestrictTest {

    @Test
    void zoneCfa_retireLaCarte_gardeEspecesEtMobileMoney() {
        assertThat(AnnouncementPaymentRails.restrictToCurrency(
                EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY), "XOF"))
                .containsExactlyInAnyOrder(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY);
        assertThat(AnnouncementPaymentRails.restrictToCurrency(
                EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH), "XAF"))
                .containsExactly(PaymentMethod.CASH);
    }

    @Test
    void horsZoneCfa_retireLeMobileMoney_gardeCarteEtEspeces() {
        assertThat(AnnouncementPaymentRails.restrictToCurrency(
                EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY), "EUR"))
                .containsExactlyInAnyOrder(PaymentMethod.STRIPE, PaymentMethod.CASH);
    }

    @Test
    void sansRailRestant_lEspeceToujours() {
        assertThat(AnnouncementPaymentRails.restrictToCurrency(EnumSet.of(PaymentMethod.STRIPE), "XOF"))
                .containsExactly(PaymentMethod.CASH);
        assertThat(AnnouncementPaymentRails.restrictToCurrency(null, "XOF"))
                .containsExactly(PaymentMethod.CASH);
        assertThat(AnnouncementPaymentRails.restrictToCurrency(EnumSet.noneOf(PaymentMethod.class), "EUR"))
                .containsExactly(PaymentMethod.CASH);
    }

    @Test
    void deviseAbsente_repliEuro() {
        assertThat(AnnouncementPaymentRails.restrictToCurrency(
                EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.MOBILE_MONEY), null))
                .containsExactly(PaymentMethod.STRIPE);
    }

    @Test
    void offerable_intersecteLeChoixAvecDeviseEtCapacites() {
        // Zone CFA : la carte n'existe pas, le mobile money exige un compte de versement.
        assertThat(AnnouncementPaymentRails.offerable(
                EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY), "XOF", true, false))
                .containsExactly(PaymentMethod.CASH);
        assertThat(AnnouncementPaymentRails.offerable(
                EnumSet.of(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY), "XOF", false, true))
                .containsExactlyInAnyOrder(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY);
        // Hors zone CFA : la carte exige un compte Connect.
        assertThat(AnnouncementPaymentRails.offerable(EnumSet.of(PaymentMethod.STRIPE), "EUR", false, false))
                .isEmpty();
        assertThat(AnnouncementPaymentRails.offerable(EnumSet.of(PaymentMethod.STRIPE), "EUR", true, false))
                .containsExactly(PaymentMethod.STRIPE);
        // Le choix explicite est respecté : espèces seules restent espèces seules.
        assertThat(AnnouncementPaymentRails.offerable(EnumSet.of(PaymentMethod.CASH), "EUR", true, true))
                .containsExactly(PaymentMethod.CASH);
        assertThat(AnnouncementPaymentRails.offerable(null, "EUR", true, true)).isEmpty();
    }
}
