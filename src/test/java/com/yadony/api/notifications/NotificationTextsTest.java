package com.yadony.api.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chaque libellé du catalogue, au PIRE CAS réaliste : villes les plus longues
 * des corridors, nom déjà réduit à 16 caractères, montants XOF, nombres à deux
 * chiffres. Le titre tient sur une ligne, le corps sur deux, sans tiret cadratin,
 * sans flèche, sans ville dans le titre.
 */
@DisplayName("Catalogue des libellés : caps au pire cas")
class NotificationTextsTest {

    private static final String NOM = "Kouassi-Konan Ahouansou";          // → « Kouassi-Konan A. », 16
    private static final String DEPART = "Charleville-Mézières";           // 20
    private static final String ARRIVEE = "Villeneuve-d'Ascq";             // 17
    private static final BigDecimal MONTANT = new BigDecimal("1250.00");
    private static final BigDecimal POIDS = new BigDecimal("32.5");

    /** Tous les libellés, avec leurs arguments de pire cas. */
    private static Map<String, NotificationText> pireCas() {
        Map<String, NotificationText> m = new LinkedHashMap<>();
        m.put("newBid", NotificationTexts.newBid(NOM, POIDS, DEPART + " → " + ARRIVEE));
        m.put("newBid sans poids", NotificationTexts.newBid(NOM, null, DEPART + " → " + ARRIVEE));
        m.put("bidAccepted", NotificationTexts.bidAccepted(NOM));
        m.put("bidAcceptedPayNow", NotificationTexts.bidAcceptedPayNow());
        m.put("bidRejected", NotificationTexts.bidRejected());
        m.put("bidRejectedTripWithdrawn", NotificationTexts.bidRejectedTripWithdrawn());
        for (NotificationTexts.BidLoss loss : NotificationTexts.BidLoss.values()) {
            m.put("bidLostWithRematch " + loss, NotificationTexts.bidLostWithRematch(loss, 12));
            m.put("bidLostWithRematch 1 " + loss, NotificationTexts.bidLostWithRematch(loss, 1));
            m.put("bidLostRefund " + loss, NotificationTexts.bidLostRefund(loss));
        }
        m.put("bidExpired", NotificationTexts.bidExpired());
        m.put("parcelRefused", NotificationTexts.parcelRefused(
                "Le contenu déclaré ne correspond pas du tout à ce qui a été présenté à la remise"));
        m.put("parcelRefused sans motif", NotificationTexts.parcelRefused(null));
        m.put("tripCancelledRefund", NotificationTexts.tripCancelledRefund());
        m.put("tripCancelledWithRematch", NotificationTexts.tripCancelledWithRematch(12));
        m.put("tripCancelledNoTraveler", NotificationTexts.tripCancelledNoTraveler());
        m.put("travelerNoShow", NotificationTexts.travelerNoShow());
        m.put("tripArrived", NotificationTexts.tripArrived());
        m.put("tripInProgress", NotificationTexts.tripInProgress());
        m.put("handoverReminder", NotificationTexts.handoverReminder(
                "Aéroport Roissy Charles-de-Gaulle, terminal 2E, porte 12, devant le comptoir"));
        m.put("handoverReminder sans lieu", NotificationTexts.handoverReminder(" "));
        m.put("confirmationCodeReady", NotificationTexts.confirmationCodeReady());
        m.put("deliveryConfirmed", NotificationTexts.deliveryConfirmed());
        m.put("deliveryNoShowForSender", NotificationTexts.deliveryNoShowForSender());
        m.put("deliveryNoShowForTraveler", NotificationTexts.deliveryNoShowForTraveler());
        m.put("parcelReturnedForSender", NotificationTexts.parcelReturnedForSender());
        m.put("parcelReturnedForTraveler", NotificationTexts.parcelReturnedForTraveler());
        m.put("returnDeadlineWarningForSender", NotificationTexts.returnDeadlineWarningForSender());
        m.put("returnDeadlineWarningForTraveler", NotificationTexts.returnDeadlineWarningForTraveler());
        m.put("returnDeadlineExpired", NotificationTexts.returnDeadlineExpired());
        m.put("disputeOpenedForSender", NotificationTexts.disputeOpenedForSender());
        m.put("disputeOpenedForTraveler", NotificationTexts.disputeOpenedForTraveler());
        m.put("disputeUpdated", NotificationTexts.disputeUpdated());
        m.put("disputeResolved", NotificationTexts.disputeResolved());
        m.put("bidNegotiationProposal", NotificationTexts.bidNegotiationProposal("1250.50"));
        m.put("bidNegotiationCounter", NotificationTexts.bidNegotiationCounter("1250.50", 3));
        m.put("bidNegotiationAccepted", NotificationTexts.bidNegotiationAccepted("1250.50"));
        m.put("bidNegotiationClosed", NotificationTexts.bidNegotiationClosed());
        m.put("bidNegotiationExpired", NotificationTexts.bidNegotiationExpired());
        m.put("negotiationStarted", NotificationTexts.negotiationStarted(MONTANT));
        m.put("negotiationCounter", NotificationTexts.negotiationCounter(MONTANT, 3));
        m.put("negotiationAwaitingTrip", NotificationTexts.negotiationAwaitingTrip(MONTANT));
        m.put("negotiationAwaitingPayment", NotificationTexts.negotiationAwaitingPayment(MONTANT));
        m.put("negotiationTripChanged", NotificationTexts.negotiationTripChanged());
        m.put("commissionPending", NotificationTexts.commissionPending(MONTANT, "XOF"));
        m.put("commissionDeclined", NotificationTexts.commissionDeclined());
        m.put("commissionExpiredForTraveler", NotificationTexts.commissionExpiredForTraveler());
        m.put("commissionExpiredForSender", NotificationTexts.commissionExpiredForSender());
        m.put("requestAcceptedForTraveler", NotificationTexts.requestAcceptedForTraveler(MONTANT));
        m.put("requestAcceptedForSender", NotificationTexts.requestAcceptedForSender(MONTANT));
        m.put("requestExpired", NotificationTexts.requestExpired());
        m.put("negotiationReminder", NotificationTexts.negotiationReminder(NOM));
        m.put("negotiationEnded", NotificationTexts.negotiationEnded(NOM));
        m.put("negotiationExpired", NotificationTexts.negotiationExpired());
        m.put("packageMatch", NotificationTexts.packageMatch(DEPART, ARRIVEE));
        m.put("travelerInvite", NotificationTexts.travelerInvite(NOM, DEPART, ARRIVEE));
        m.put("travelerNewAnnouncement", NotificationTexts.travelerNewAnnouncement(NOM, DEPART, ARRIVEE));
        m.put("corridorAlertTrip", NotificationTexts.corridorAlertTrip(DEPART, ARRIVEE));
        m.put("corridorAlertDigest trajets 99", NotificationTexts.corridorAlertDigest(true, 99, DEPART, ARRIVEE));
        m.put("corridorAlertDigest trajets 999", NotificationTexts.corridorAlertDigest(true, 999, DEPART, ARRIVEE));
        m.put("corridorAlertDigest colis 99", NotificationTexts.corridorAlertDigest(false, 99, DEPART, ARRIVEE));
        m.put("corridorAlertDigest 1", NotificationTexts.corridorAlertDigest(true, 1, DEPART, ARRIVEE));
        m.put("announcementRemoved", NotificationTexts.announcementRemoved("Non conforme aux conditions d'utilisation"));
        m.put("capacityFree", NotificationTexts.capacityFree(POIDS, 48, DEPART, ARRIVEE));
        m.put("lastMinuteOffer", NotificationTexts.lastMinuteOffer(24, DEPART + " → " + ARRIVEE));
        m.put("loyalSender", NotificationTexts.loyalSender(NOM, DEPART, ARRIVEE));
        m.put("paymentReleased", NotificationTexts.paymentReleased("12500,00 €"));
        m.put("mobileMoneyPaymentPending", NotificationTexts.mobileMoneyPaymentPending("ORANGE_MONEY"));
        m.put("mobileMoneyPaymentConfirmed", NotificationTexts.mobileMoneyPaymentConfirmed());
        m.put("kycVerified", NotificationTexts.kycVerified());
        m.put("kycActionRequired", NotificationTexts.kycActionRequired());
        m.put("kycReset", NotificationTexts.kycReset());
        m.put("stripeOnboardingIncomplete", NotificationTexts.stripeOnboardingIncomplete());
        m.put("cardExpiring", NotificationTexts.cardExpiring("American Express", "1234"));
        m.put("cardExpiring sans marque", NotificationTexts.cardExpiring(null, null));
        m.put("accountSuspended", NotificationTexts.accountSuspended());
        m.put("messagingMuted", NotificationTexts.messagingMuted());
        m.put("adminWarning", NotificationTexts.adminWarning(null));
        return m;
    }

    @Test
    void titlesFitOnOneLineAndBodiesOnTwo() {
        List<String> depassements = new ArrayList<>();
        pireCas().forEach((nom, t) -> {
            if (t.title() == null || t.title().length() > NotificationCaps.TITLE_MAX)
                depassements.add(nom + " : titre " + (t.title() == null ? "nul" : t.title().length() + " « " + t.title() + " »"));
            if (t.body() == null || t.body().length() > NotificationCaps.BODY_MAX)
                depassements.add(nom + " : corps " + (t.body() == null ? "nul" : t.body().length() + " « " + t.body() + " »"));
        });
        assertThat(depassements).isEmpty();
    }

    @Test
    void noDashNoArrowNoTutoiement() {
        pireCas().forEach((nom, t) -> {
            String tout = t.title() + " " + t.body();
            assertThat(tout).as(nom).doesNotContain("—").doesNotContain("→").doesNotContain("...");
            assertThat(tout).as(nom + " (tutoiement)").doesNotContainIgnoringCase("tu as ").doesNotContain("N'oublie");
        });
    }

    @Test
    void noCityInTitles() {
        pireCas().forEach((nom, t) ->
                assertThat(t.title()).as(nom).doesNotContain(DEPART).doesNotContain(ARRIVEE));
    }

    @Test
    void namesAreShortenedAndCitiesNeverAre() {
        var t = NotificationTexts.travelerInvite("Mohammed Abdoulaye Diallo", DEPART, ARRIVEE);
        assertThat(t.body()).startsWith("Mohammed A. propose ").contains(DEPART).contains(ARRIVEE);
        assertThat(NotificationTexts.newBid("Karim Traoré", new BigDecimal("12.0"), "Paris → Dakar").body())
                .isEqualTo("Karim T., 12 kg, Paris vers Dakar.");
    }

    @Test
    void formattingHelpers() {
        assertThat(NotificationTexts.kg(new BigDecimal("12.0"))).isEqualTo("12 kg");
        assertThat(NotificationTexts.kg(new BigDecimal("12.50"))).isEqualTo("12,5 kg");
        assertThat(NotificationTexts.eur(new BigDecimal("45"))).isEqualTo("45,00 €");
        assertThat(NotificationTexts.amount(new BigDecimal("1250"), "XOF")).isEqualTo("1250,00 XOF");
        assertThat(NotificationTexts.amount(new BigDecimal("12.5"), "eur")).isEqualTo("12,50 €");
        assertThat(NotificationTexts.provider("ORANGE_MONEY")).isEqualTo("Orange Money");
        assertThat(NotificationTexts.provider("WAVE")).isEqualTo("Wave");
        assertThat(NotificationTexts.corridorFromLabel("Paris → Dakar")).isEqualTo("Paris vers Dakar");
        assertThat(NotificationTexts.corridor("Paris", "Dakar")).isEqualTo("Paris vers Dakar");
    }

    @Test
    void everyCatalogueEntryIsCoveredByTheWorstCase() {
        Set<String> helpers = Set.of("corridor", "corridorFromLabel", "kg", "eur", "amount", "provider");
        List<String> declared = new ArrayList<>();
        for (Method m : NotificationTexts.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())
                    && m.getReturnType() == NotificationText.class && !helpers.contains(m.getName())) {
                declared.add(m.getName());
            }
        }
        Set<String> couverts = new java.util.HashSet<>();
        for (String k : pireCas().keySet()) couverts.add(k.split(" ")[0]);
        assertThat(couverts).containsAll(declared);
    }
}
