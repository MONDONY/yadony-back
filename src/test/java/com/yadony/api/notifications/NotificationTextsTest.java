package com.yadony.api.notifications;

import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementRemovalReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chaque libellé du catalogue, au PIRE CAS réaliste : villes les plus longues
 * des corridors, nom déjà réduit à 16 caractères, montants XOF, nombres à deux
 * chiffres. Le titre tient sur une ligne, le corps sur deux, sans tiret cadratin,
 * sans flèche, sans ville dans le titre — dans les deux langues.
 */
@DisplayName("Catalogue des libellés : caps au pire cas")
class NotificationTextsTest {

    private static final String NOM = "Kouassi-Konan Ahouansou";          // → « Kouassi-Konan A. », 16
    private static final String DEPART = "Charleville-Mézières";           // 20
    private static final String ARRIVEE = "Villeneuve-d'Ascq";             // 17
    private static final BigDecimal MONTANT = new BigDecimal("1250.00");
    private static final BigDecimal POIDS = new BigDecimal("32.5");

    private static Stream<Messages> deuxLangues() {
        return Stream.of(TestMessages.fr(), TestMessages.en());
    }

    /** Tous les libellés, avec leurs arguments de pire cas, dans la langue {@code m}. */
    private static Map<String, NotificationText> pireCas(Messages m) {
        Map<String, NotificationText> map = new LinkedHashMap<>();
        map.put("newBid", NotificationTexts.newBid(m, NOM, POIDS, DEPART + " → " + ARRIVEE));
        map.put("newBid sans poids", NotificationTexts.newBid(m, NOM, null, DEPART + " → " + ARRIVEE));
        map.put("bidAccepted", NotificationTexts.bidAccepted(m, NOM));
        map.put("bidAcceptedPayNow", NotificationTexts.bidAcceptedPayNow(m));
        map.put("bidRejected", NotificationTexts.bidRejected(m));
        map.put("bidRejectedTripWithdrawn", NotificationTexts.bidRejectedTripWithdrawn(m));
        for (NotificationTexts.BidLoss loss : NotificationTexts.BidLoss.values()) {
            map.put("bidLostWithRematch " + loss, NotificationTexts.bidLostWithRematch(m, loss, 12));
            map.put("bidLostWithRematch 1 " + loss, NotificationTexts.bidLostWithRematch(m, loss, 1));
            map.put("bidLostRefund " + loss, NotificationTexts.bidLostRefund(m, loss));
        }
        map.put("bidExpired", NotificationTexts.bidExpired(m));
        map.put("parcelRefused", NotificationTexts.parcelRefused(m,
                "Le contenu déclaré ne correspond pas du tout à ce qui a été présenté à la remise"));
        map.put("parcelRefused sans motif", NotificationTexts.parcelRefused(m, null));
        map.put("tripCancelledRefund", NotificationTexts.tripCancelledRefund(m));
        map.put("tripCancelledWithRematch", NotificationTexts.tripCancelledWithRematch(m, 12));
        map.put("tripCancelledNoTraveler", NotificationTexts.tripCancelledNoTraveler(m));
        map.put("travelerNoShow", NotificationTexts.travelerNoShow(m));
        map.put("tripArrived", NotificationTexts.tripArrived(m));
        map.put("tripInProgress", NotificationTexts.tripInProgress(m));
        map.put("handoverReminder", NotificationTexts.handoverReminder(m,
                "Aéroport Roissy Charles-de-Gaulle, terminal 2E, porte 12, devant le comptoir"));
        map.put("handoverReminder sans lieu", NotificationTexts.handoverReminder(m, " "));
        map.put("confirmationCodeReady", NotificationTexts.confirmationCodeReady(m));
        map.put("deliveryConfirmed", NotificationTexts.deliveryConfirmed(m));
        map.put("deliveryNoShowForSender", NotificationTexts.deliveryNoShowForSender(m));
        map.put("deliveryNoShowForTraveler", NotificationTexts.deliveryNoShowForTraveler(m));
        map.put("parcelReturnedForSender", NotificationTexts.parcelReturnedForSender(m));
        map.put("parcelReturnedForTraveler", NotificationTexts.parcelReturnedForTraveler(m));
        map.put("returnDeadlineWarningForSender", NotificationTexts.returnDeadlineWarningForSender(m));
        map.put("returnDeadlineWarningForTraveler", NotificationTexts.returnDeadlineWarningForTraveler(m));
        map.put("returnDeadlineExpired", NotificationTexts.returnDeadlineExpired(m));
        map.put("disputeOpenedForSender", NotificationTexts.disputeOpenedForSender(m));
        map.put("disputeOpenedForTraveler", NotificationTexts.disputeOpenedForTraveler(m));
        map.put("disputeUpdated", NotificationTexts.disputeUpdated(m));
        map.put("disputeResolved", NotificationTexts.disputeResolved(m));
        map.put("bidNegotiationProposal", NotificationTexts.bidNegotiationProposal(m, "1250.50"));
        map.put("bidNegotiationCounter", NotificationTexts.bidNegotiationCounter(m, "1250.50", 3));
        map.put("bidNegotiationAccepted", NotificationTexts.bidNegotiationAccepted(m, "1250.50"));
        map.put("bidNegotiationClosed", NotificationTexts.bidNegotiationClosed(m));
        map.put("bidNegotiationExpired", NotificationTexts.bidNegotiationExpired(m));
        map.put("negotiationStarted", NotificationTexts.negotiationStarted(m, MONTANT));
        map.put("negotiationCounter", NotificationTexts.negotiationCounter(m, MONTANT, 3));
        map.put("negotiationAwaitingTrip", NotificationTexts.negotiationAwaitingTrip(m, MONTANT));
        map.put("negotiationAwaitingPayment", NotificationTexts.negotiationAwaitingPayment(m, MONTANT));
        map.put("negotiationTripChanged", NotificationTexts.negotiationTripChanged(m));
        map.put("commissionPending", NotificationTexts.commissionPending(m, MONTANT, "XOF"));
        map.put("commissionDeclined", NotificationTexts.commissionDeclined(m));
        map.put("depositPendingSender", NotificationTexts.depositPendingSender(m, MONTANT, "XOF"));
        map.put("depositPendingTraveler", NotificationTexts.depositPendingTraveler(m));
        map.put("depositReverted deposit-failed", NotificationTexts.depositReverted(m, "deposit-failed"));
        map.put("depositReverted deposit-expired", NotificationTexts.depositReverted(m, "deposit-expired"));
        map.put("depositReverted sender-cancelled", NotificationTexts.depositReverted(m, "sender-cancelled"));
        map.put("commissionExpiredForTraveler", NotificationTexts.commissionExpiredForTraveler(m));
        map.put("commissionExpiredForSender", NotificationTexts.commissionExpiredForSender(m));
        map.put("requestAcceptedForTraveler", NotificationTexts.requestAcceptedForTraveler(m, MONTANT));
        map.put("requestAcceptedForSender", NotificationTexts.requestAcceptedForSender(m, MONTANT));
        map.put("requestExpired", NotificationTexts.requestExpired(m));
        map.put("negotiationReminder", NotificationTexts.negotiationReminder(m, NOM));
        map.put("negotiationEnded", NotificationTexts.negotiationEnded(m, NOM));
        map.put("negotiationExpired", NotificationTexts.negotiationExpired(m));
        map.put("packageMatch", NotificationTexts.packageMatch(m, DEPART, ARRIVEE));
        map.put("travelerInvite", NotificationTexts.travelerInvite(m, NOM, DEPART, ARRIVEE));
        map.put("senderInvite", NotificationTexts.senderInvite(m, NOM, DEPART, ARRIVEE));
        map.put("travelerNewAnnouncement", NotificationTexts.travelerNewAnnouncement(m, NOM, DEPART, ARRIVEE));
        map.put("corridorAlertTrip", NotificationTexts.corridorAlertTrip(m, DEPART, ARRIVEE));
        map.put("corridorAlertDigest trajets 99", NotificationTexts.corridorAlertDigest(m, true, 99, DEPART, ARRIVEE));
        map.put("corridorAlertDigest trajets 999", NotificationTexts.corridorAlertDigest(m, true, 999, DEPART, ARRIVEE));
        map.put("corridorAlertDigest colis 99", NotificationTexts.corridorAlertDigest(m, false, 99, DEPART, ARRIVEE));
        map.put("corridorAlertDigest 1", NotificationTexts.corridorAlertDigest(m, true, 1, DEPART, ARRIVEE));
        for (AnnouncementRemovalReason reason : AnnouncementRemovalReason.values()) {
            map.put("announcementRemoved " + reason, NotificationTexts.announcementRemoved(m, reason.name()));
        }
        map.put("capacityFree", NotificationTexts.capacityFree(m, POIDS, 48, DEPART, ARRIVEE));
        map.put("lastMinuteOffer", NotificationTexts.lastMinuteOffer(m, 24, DEPART + " → " + ARRIVEE));
        map.put("loyalSender", NotificationTexts.loyalSender(m, NOM, DEPART, ARRIVEE));
        map.put("paymentReleased", NotificationTexts.paymentReleased(m, "12500,00 €"));
        map.put("mobileMoneyPayoutSent", NotificationTexts.mobileMoneyPayoutSent(m, NotificationTexts.amount(MONTANT, "XOF")));
        map.put("mobileMoneyPaymentConfirmed", NotificationTexts.mobileMoneyPaymentConfirmed(m));
        map.put("mobileMoneyPaymentReceived", NotificationTexts.mobileMoneyPaymentReceived(m));
        map.put("mobileMoneyPaymentPending", NotificationTexts.mobileMoneyPaymentPending(m, 99));
        map.put("mobileMoneyPaymentFailed", NotificationTexts.mobileMoneyPaymentFailed(m));
        map.put("mobileMoneyPaymentExpired", NotificationTexts.mobileMoneyPaymentExpired(m));
        map.put("mobileMoneyPaymentExpiredForTraveler", NotificationTexts.mobileMoneyPaymentExpiredForTraveler(m));
        map.put("kycVerified", NotificationTexts.kycVerified(m));
        map.put("kycActionRequired", NotificationTexts.kycActionRequired(m));
        map.put("kycReset", NotificationTexts.kycReset(m));
        map.put("stripeOnboardingIncomplete", NotificationTexts.stripeOnboardingIncomplete(m));
        map.put("cardExpiring", NotificationTexts.cardExpiring(m, "American Express", "1234"));
        map.put("cardExpiring sans marque", NotificationTexts.cardExpiring(m, null, null));
        map.put("accountSuspended", NotificationTexts.accountSuspended(m));
        map.put("messagingMuted", NotificationTexts.messagingMuted(m));
        map.put("adminWarning", NotificationTexts.adminWarning(m, null));
        return map;
    }

    @ParameterizedTest
    @MethodSource("deuxLangues")
    void titlesFitOnOneLineAndBodiesOnTwo(Messages m) {
        List<String> depassements = new ArrayList<>();
        pireCas(m).forEach((nom, t) -> {
            if (t.title() == null || t.title().length() > NotificationCaps.TITLE_MAX)
                depassements.add(nom + " : titre " + (t.title() == null ? "nul" : t.title().length() + " « " + t.title() + " »"));
            if (t.body() == null || t.body().length() > NotificationCaps.BODY_MAX)
                depassements.add(nom + " : corps " + (t.body() == null ? "nul" : t.body().length() + " « " + t.body() + " »"));
        });
        assertThat(depassements).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("deuxLangues")
    void noDashNoArrowNoTutoiement(Messages m) {
        pireCas(m).forEach((nom, t) -> {
            String tout = t.title() + " " + t.body();
            assertThat(tout).as(nom).doesNotContain("—").doesNotContain("→").doesNotContain("...");
            assertThat(tout).as(nom + " (tutoiement)").doesNotContainIgnoringCase("tu as ").doesNotContain("N'oublie");
        });
    }

    @ParameterizedTest
    @MethodSource("deuxLangues")
    void noCityInTitles(Messages m) {
        pireCas(m).forEach((nom, t) ->
                assertThat(t.title()).as(nom).doesNotContain(DEPART).doesNotContain(ARRIVEE));
    }

    @Test
    void namesAreShortenedAndCitiesNeverAre() {
        var fr = TestMessages.fr();
        var en = TestMessages.en();
        var t = NotificationTexts.travelerInvite(fr, "Mohammed Abdoulaye Diallo", DEPART, ARRIVEE);
        assertThat(t.body()).startsWith("Mohammed A. propose ").contains(DEPART).contains(ARRIVEE);
        var tEn = NotificationTexts.travelerInvite(en, "Mohammed Abdoulaye Diallo", DEPART, ARRIVEE);
        assertThat(tEn.body()).startsWith("Mohammed A. offers ").contains(DEPART).contains(ARRIVEE);

        assertThat(NotificationTexts.newBid(fr, "Karim Traoré", new BigDecimal("12.0"), "Paris → Dakar").body())
                .isEqualTo("Karim T., 12 kg, Paris vers Dakar.");
        assertThat(NotificationTexts.newBid(en, "Karim Traoré", new BigDecimal("12.0"), "Paris → Dakar").body())
                .isEqualTo("Karim T., 12 kg, Paris to Dakar.");
    }

    @Test
    void senderInvite_namesSenderAndCorridor() {
        var fr = TestMessages.fr();
        var text = NotificationTexts.senderInvite(fr, "Awa Koné", "Divo", "Annemasse");
        assertThat(text.title()).isEqualTo("Un expéditeur vous invite");
        assertThat(text.body()).startsWith("Awa K. : colis Divo vers Annemasse");
        assertThat(text.body()).doesNotContain("—");
        assertThat(text.body()).doesNotContain("→");

        var en = TestMessages.en();
        var textEn = NotificationTexts.senderInvite(en, "Awa Koné", "Divo", "Annemasse");
        assertThat(textEn.title()).isEqualTo("A sender invites you");
        assertThat(textEn.body()).isEqualTo("Awa K.: parcel from Divo to Annemasse.");
    }

    @Test
    void newBid_nullSenderName_fallsBackToGenericSender() {
        var fr = TestMessages.fr();
        var en = TestMessages.en();
        assertThat(NotificationTexts.newBid(fr, null, POIDS, "Paris → Dakar").body())
                .startsWith("Un expéditeur, ");
        assertThat(NotificationTexts.newBid(en, null, POIDS, "Paris → Dakar").body())
                .startsWith("A sender, ");
        assertThat(NotificationTexts.newBid(fr, "  ", POIDS, "Paris → Dakar").body())
                .startsWith("Un expéditeur, ");
    }

    @Test
    void formattingHelpers() {
        var fr = TestMessages.fr();
        var en = TestMessages.en();
        assertThat(NotificationTexts.kg(fr, new BigDecimal("12.0"))).isEqualTo("12 kg");
        assertThat(NotificationTexts.kg(fr, new BigDecimal("12.50"))).isEqualTo("12,5 kg");
        assertThat(NotificationTexts.kg(en, new BigDecimal("12.50"))).isEqualTo("12.5 kg");
        assertThat(NotificationTexts.kg(en, new BigDecimal("12.0"))).isEqualTo("12 kg");
        assertThat(NotificationTexts.eur(new BigDecimal("45"))).isEqualTo("45,00 €");
        assertThat(NotificationTexts.amount(new BigDecimal("1250"), "XOF")).isEqualTo("1250,00 XOF");
        assertThat(NotificationTexts.amount(new BigDecimal("12.5"), "eur")).isEqualTo("12,50 €");
        // mobileMoneyAmount (tâche 16) : distinct de amount() ci-dessus (jamais modifié, son
        // contrat "code ISO, deux décimales" est utilisé ailleurs, ex. commissionPending) —
        // symbole du catalogue SupportedCurrency (« F CFA »), sans décimale pour les francs CFA.
        // Montants inchangés quelle que soit la langue (D7).
        assertThat(NotificationTexts.mobileMoneyAmount(new BigDecimal("15000"), "XOF")).isEqualTo("15000 F CFA");
        assertThat(NotificationTexts.mobileMoneyAmount(new BigDecimal("45"), "EUR")).isEqualTo("45,00 €");
        assertThat(NotificationTexts.corridorFromLabel(fr, "Paris → Dakar")).isEqualTo("Paris vers Dakar");
        assertThat(NotificationTexts.corridor(fr, "Paris", "Dakar")).isEqualTo("Paris vers Dakar");
        assertThat(NotificationTexts.corridorFromLabel(en, "Paris → Dakar")).isEqualTo("Paris to Dakar");
        assertThat(NotificationTexts.corridor(en, "Paris", "Dakar")).isEqualTo("Paris to Dakar");
    }

    @Test
    void bidLostWithRematch_pluralsInBothLanguages() {
        var fr = TestMessages.fr();
        var en = TestMessages.en();
        assertThat(NotificationTexts.bidLostWithRematch(fr, NotificationTexts.BidLoss.REFUSED, 0).body())
                .endsWith("0 voyageur alternatif proposé.");
        assertThat(NotificationTexts.bidLostWithRematch(fr, NotificationTexts.BidLoss.REFUSED, 1).body())
                .endsWith("1 voyageur alternatif proposé.");
        assertThat(NotificationTexts.bidLostWithRematch(fr, NotificationTexts.BidLoss.REFUSED, 2).body())
                .endsWith("2 voyageurs alternatifs proposés.");
        assertThat(NotificationTexts.bidLostWithRematch(en, NotificationTexts.BidLoss.REFUSED, 0).body())
                .endsWith("0 alternative travelers suggested.");
        assertThat(NotificationTexts.bidLostWithRematch(en, NotificationTexts.BidLoss.REFUSED, 1).body())
                .endsWith("1 alternative traveler suggested.");
        assertThat(NotificationTexts.bidLostWithRematch(en, NotificationTexts.BidLoss.REFUSED, 2).body())
                .endsWith("2 alternative travelers suggested.");
    }

    @Test
    void corridorAlertDigest_englishPlurals() {
        var en = TestMessages.en();
        var one = NotificationTexts.corridorAlertDigest(en, true, 1, "Paris", "Dakar");
        assertThat(one.title()).isEqualTo("1 trip for your alert");
        assertThat(one.body()).isEqualTo("Paris to Dakar: 1 trip matches.");

        var two = NotificationTexts.corridorAlertDigest(en, true, 2, "Paris", "Dakar");
        assertThat(two.title()).isEqualTo("2 trips for your alert");
        assertThat(two.body()).isEqualTo("Paris to Dakar: 2 trips match.");
    }

    @Test
    void everyCatalogueEntryIsCoveredByTheWorstCase() {
        Set<String> helpers = Set.of("corridor", "corridorFromLabel", "kg", "eur", "amount", "mobileMoneyAmount");
        List<String> declared = new ArrayList<>();
        for (Method m : NotificationTexts.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())
                    && m.getReturnType() == NotificationText.class && !helpers.contains(m.getName())) {
                declared.add(m.getName());
            }
        }
        Set<String> couverts = new java.util.HashSet<>();
        for (String k : pireCas(TestMessages.fr()).keySet()) couverts.add(k.split(" ")[0]);
        assertThat(couverts).containsAll(declared);
    }
}
