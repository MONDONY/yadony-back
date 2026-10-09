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
        for (String variant : new String[] {"sender-trips", "traveler-packages"}) {
            map.put("firstActionReminder 99 " + variant, NotificationTexts.firstActionReminder(m, variant, 99));
            map.put("firstActionReminder 1 " + variant, NotificationTexts.firstActionReminder(m, variant, 1));
        }
        for (String variant : new String[] {"sender-none", "traveler-none", "unknown"}) {
            map.put("firstActionReminder " + variant, NotificationTexts.firstActionReminder(m, variant, 0));
        }
        map.put("bidAcceptedPayNow", NotificationTexts.bidAcceptedPayNow(m));
        map.put("bidRejected", NotificationTexts.bidRejected(m));
        map.put("handoverDeadlinePassed", NotificationTexts.handoverDeadlinePassed(m));
        map.put("handoverDeadlinePassedRefunded", NotificationTexts.handoverDeadlinePassedRefunded(m));
        map.put("bidRejectedTripWithdrawn", NotificationTexts.bidRejectedTripWithdrawn(m));
        for (com.yadony.api.matching.BidRejectionReason r : com.yadony.api.matching.BidRejectionReason.values()) {
            map.put("bidRejectedWithReason " + r, NotificationTexts.bidRejectedWithReason(m, r.name(), false));
            map.put("bidRejectedWithReason refund " + r, NotificationTexts.bidRejectedWithReason(m, r.name(), true));
        }
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
        // Septembre a l'abréviation la plus longue (« sept. ») et mercredi le jour le plus long.
        java.time.LocalDate longestDate = java.time.LocalDate.of(2026, 9, 30);
        map.put("tripRescheduled", NotificationTexts.tripRescheduled(m, longestDate, true));
        map.put("tripRescheduled info", NotificationTexts.tripRescheduled(m, longestDate, false));
        map.put("tripRescheduleKept", NotificationTexts.tripRescheduleKept(m, NOM));
        map.put("tripRescheduleKept sans prénom", NotificationTexts.tripRescheduleKept(m, null));
        map.put("tripRescheduleWithdrawn", NotificationTexts.tripRescheduleWithdrawn(m));
        map.put("travelerNoShow", NotificationTexts.travelerNoShow(m));
        map.put("tripArrived", NotificationTexts.tripArrived(m));
        map.put("tripInProgress", NotificationTexts.tripInProgress(m));
        map.put("handoverReminder", NotificationTexts.handoverReminder(m,
                "Aéroport Roissy Charles-de-Gaulle, terminal 2E, porte 12, devant le comptoir"));
        map.put("handoverReminder sans lieu", NotificationTexts.handoverReminder(m, " "));
        map.put("confirmationCodeReady", NotificationTexts.confirmationCodeReady(m));
        map.put("confirmationCodeBlocked", NotificationTexts.confirmationCodeBlocked(m));
        map.put("confirmationCodeRequested", NotificationTexts.confirmationCodeRequested(m));
        map.put("deliveryConfirmed", NotificationTexts.deliveryConfirmed(m));
        map.put("deliveryNoShowForSender", NotificationTexts.deliveryNoShowForSender(m));
        map.put("deliveryNoShowForTraveler", NotificationTexts.deliveryNoShowForTraveler(m));
        map.put("deliveryRetryAppointmentForTraveler", NotificationTexts.deliveryRetryAppointmentForTraveler(m));
        map.put("parcelUnclaimedForSender", NotificationTexts.parcelUnclaimedForSender(m));
        map.put("parcelUnclaimedForTraveler", NotificationTexts.parcelUnclaimedForTraveler(m));
        map.put("parcelReturnedForSender", NotificationTexts.parcelReturnedForSender(m));
        map.put("parcelReturnedForTraveler", NotificationTexts.parcelReturnedForTraveler(m));
        map.put("returnDeadlineWarningForSender", NotificationTexts.returnDeadlineWarningForSender(m));
        map.put("returnDeadlineWarningForTraveler", NotificationTexts.returnDeadlineWarningForTraveler(m));
        map.put("parcelReturnRequired", NotificationTexts.parcelReturnRequired(m));
        map.put("parcelReturnToSender", NotificationTexts.parcelReturnToSender(m, java.time.LocalDate.of(2026, 9, 30)));
        map.put("returnDeadlineExpired", NotificationTexts.returnDeadlineExpired(m));
        map.put("disputeOpenedForSender", NotificationTexts.disputeOpenedForSender(m));
        map.put("disputeOpenedForTraveler", NotificationTexts.disputeOpenedForTraveler(m));
        map.put("disputeUpdated", NotificationTexts.disputeUpdated(m));
        map.put("disputeResolved", NotificationTexts.disputeResolved(m));
        // Délai de contestation à trois chiffres : le réglage n'est pas borné.
        map.put("senderNoShowReported", NotificationTexts.senderNoShowReported(m, 168));
        map.put("noShowConfirmedHandover", NotificationTexts.noShowConfirmedHandover(m));
        map.put("noShowConfirmedDelivery", NotificationTexts.noShowConfirmedDelivery(m));
        map.put("noShowRejected", NotificationTexts.noShowRejected(m));
        map.put("bidNegotiationProposal", NotificationTexts.bidNegotiationProposal(m, "1250.50"));
        map.put("bidNegotiationCounter", NotificationTexts.bidNegotiationCounter(m, "1250.50", 3));
        map.put("bidNegotiationAccepted", NotificationTexts.bidNegotiationAccepted(m, "1250.50"));
        map.put("bidNegotiationClosed", NotificationTexts.bidNegotiationClosed(m));
        map.put("bidNegotiationExpired", NotificationTexts.bidNegotiationExpired(m));
        map.put("negotiationStarted", NotificationTexts.negotiationStarted(m, MONTANT, "XOF"));
        map.put("negotiationCounter", NotificationTexts.negotiationCounter(m, MONTANT, "XOF", 3));
        map.put("negotiationAwaitingTrip", NotificationTexts.negotiationAwaitingTrip(m, MONTANT, "XOF"));
        map.put("negotiationAwaitingPayment", NotificationTexts.negotiationAwaitingPayment(m, MONTANT, "XOF"));
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
        map.put("requestAcceptedForTraveler", NotificationTexts.requestAcceptedForTraveler(m, MONTANT, "XOF"));
        map.put("requestAcceptedForSender", NotificationTexts.requestAcceptedForSender(m, MONTANT, "XOF"));
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
            map.put("packageRequestRemoved " + reason, NotificationTexts.packageRequestRemoved(m, reason.name()));
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
        map.put("kycRevoked", NotificationTexts.kycRevoked(m));
        map.put("stripeOnboardingIncomplete", NotificationTexts.stripeOnboardingIncomplete(m));
        map.put("cardExpiring", NotificationTexts.cardExpiring(m, "American Express", "1234"));
        map.put("cardExpiring sans marque", NotificationTexts.cardExpiring(m, null, null));
        map.put("accountSuspended", NotificationTexts.accountSuspended(m));
        map.put("accountDeletionCancelledByAdmin", NotificationTexts.accountDeletionCancelledByAdmin(m));
        map.put("messagingMuted", NotificationTexts.messagingMuted(m));
        map.put("adminWarning", NotificationTexts.adminWarning(m, null));
        map.put("reportResolved", NotificationTexts.reportResolved(m));
        // Plafond d'un ajustement : 500 € d'équivalent, soit au plus 6 chiffres + symbole.
        String pireMontant = com.yadony.api.payments.wallet.WalletAmountText.format(new BigDecimal("99999.99"), "CAD");
        map.put("walletAdjustedByAdmin crédit", NotificationTexts.walletAdjustedByAdmin(m, true, pireMontant));
        map.put("walletAdjustedByAdmin débit", NotificationTexts.walletAdjustedByAdmin(m, false, pireMontant));
        map.put("supportReply", NotificationTexts.supportReply(m));
        map.put("supportStarted", NotificationTexts.supportStarted(m,
                "Votre vérification d'identité doit être refaite avant votre prochain envoi vers Dakar"));
        map.put("supportStarted court", NotificationTexts.supportStarted(m, "Compte"));
        map.put("supportStarted sans sujet", NotificationTexts.supportStarted(m, "  "));
        map.put("recipientParcelIncoming", NotificationTexts.recipientParcelIncoming(m, NOM, DEPART, ARRIVEE));
        map.put("recipientParcelIncoming sans trajet", NotificationTexts.recipientParcelIncoming(m, NOM, null, null));
        map.put("recipientParcelIncoming sans prénom", NotificationTexts.recipientParcelIncoming(m, " ", DEPART, ARRIVEE));
        map.put("recipientParcelDeparted", NotificationTexts.recipientParcelDeparted(m));
        map.put("recipientParcelArrived", NotificationTexts.recipientParcelArrived(m, ARRIVEE));
        map.put("recipientParcelArrived sans ville", NotificationTexts.recipientParcelArrived(m, null));
        map.put("recipientParcelDelivered", NotificationTexts.recipientParcelDelivered(m));
        map.put("recipientConfirmed", NotificationTexts.recipientConfirmed(m, NOM));
        map.put("recipientConfirmed sans prénom", NotificationTexts.recipientConfirmed(m, null));
        map.put("recipientDeclined", NotificationTexts.recipientDeclined(m));
        map.put("recipientDeclinedToTraveler", NotificationTexts.recipientDeclinedToTraveler(m));
        map.put("recipientReplacementRequested", NotificationTexts.recipientReplacementRequested(m));
        map.put("recipientWithdrawnToSender", NotificationTexts.recipientWithdrawnToSender(m, NOM));
        map.put("recipientWithdrawnToSender sans prénom", NotificationTexts.recipientWithdrawnToSender(m, null));
        map.put("recipientWithdrawnToTraveler", NotificationTexts.recipientWithdrawnToTraveler(m, NOM));
        map.put("recipientParcelCancelled", NotificationTexts.recipientParcelCancelled(m));
        map.put("recipientParcelRescheduled", NotificationTexts.recipientParcelRescheduled(m));
        map.put("recipientParcelReassigned", NotificationTexts.recipientParcelReassigned(m));
        map.put("recipientChanged", NotificationTexts.recipientChanged(m));
        map.put("recipientPickupUpdated", NotificationTexts.recipientPickupUpdated(m));
        map.put("recipientParcelAnnounced", NotificationTexts.recipientParcelAnnounced(m, NOM));
        map.put("recipientParcelAnnounced sans prénom", NotificationTexts.recipientParcelAnnounced(m, null));
        map.put("callMissed", NotificationTexts.callMissed(m, NOM));
        map.put("callMissed sans prénom", NotificationTexts.callMissed(m, null));
        map.put("recipientInvitation", NotificationTexts.recipientInvitation(m, NOM));
        map.put("recipientInvitation sans prénom", NotificationTexts.recipientInvitation(m, " "));
        map.put("recipientInvitationAccepted", NotificationTexts.recipientInvitationAccepted(m, NOM));
        map.put("recipientInvitationAccepted sans prénom", NotificationTexts.recipientInvitationAccepted(m, null));
        map.put("recipientInvitationRemoved", NotificationTexts.recipientInvitationRemoved(m, NOM));
        map.put("recipientInvitationRemoved sans prénom", NotificationTexts.recipientInvitationRemoved(m, null));
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

    // Sentry FLUTTER-5S : envoyé dès la récupération du colis, l'ancien texte
    // (« prêt à remettre votre colis ») faisait croire à une arrivée.
    @Test
    void confirmationCodeReady_saysParcelPickedUpNotReadyToDeliver() {
        var fr = NotificationTexts.confirmationCodeReady(TestMessages.fr());
        var en = NotificationTexts.confirmationCodeReady(TestMessages.en());
        assertThat(fr.title()).isEqualTo("Colis récupéré");
        assertThat(fr.body()).contains("en route").doesNotContain("prêt à remettre");
        assertThat(en.title()).isEqualTo("Parcel picked up");
        assertThat(en.body()).contains("on its way").doesNotContain("ready to hand over");
    }

    // FLUTTER-G1 : le code bloqué après trop d'essais ne se débloque qu'avec un nouveau
    // code, que seul l'expéditeur génère. Le texte doit le dire, pas inviter à patienter.
    @Test
    void confirmationCodeBlocked_tellsSenderToGenerateANewCode() {
        var fr = NotificationTexts.confirmationCodeBlocked(TestMessages.fr());
        var en = NotificationTexts.confirmationCodeBlocked(TestMessages.en());
        assertThat(fr.title()).isEqualTo("Code de retrait bloqué");
        assertThat(fr.body()).contains("Trop d'essais").contains("nouveau code");
        assertThat(en.title()).isEqualTo("Pickup code blocked");
        assertThat(en.body()).contains("new code");
    }

    // FLUTTER-G2 : le voyageur demande un nouveau code ; l'expéditeur doit comprendre
    // qu'il lui revient de le générer.
    @Test
    void confirmationCodeRequested_asksSenderToGenerateTheCode() {
        var fr = NotificationTexts.confirmationCodeRequested(TestMessages.fr());
        var en = NotificationTexts.confirmationCodeRequested(TestMessages.en());
        assertThat(fr.title()).isEqualTo("Nouveau code demandé");
        assertThat(fr.body()).isEqualTo("Le voyageur demande un nouveau code de retrait. Générez-le maintenant.");
        assertThat(en.title()).isEqualTo("New code requested");
        assertThat(en.body()).contains("new pickup code").contains("Generate");
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
    void recipientInvitationTexts_bothLanguages() {
        var fr = TestMessages.fr();
        var en = TestMessages.en();
        assertThat(NotificationTexts.recipientInvitation(fr, "Awa")).isEqualTo(new NotificationText(
                "Nouvelle demande", "Awa veut vous ajouter à ses destinataires Yadony."));
        assertThat(NotificationTexts.recipientInvitationAccepted(fr, "Fatou")).isEqualTo(new NotificationText(
                "Destinataire ajouté", "Fatou a accepté : ses colis lui seront rattachés."));
        assertThat(NotificationTexts.recipientParcelAnnounced(fr, "Awa")).isEqualTo(new NotificationText(
                "Un colis arrive", "Awa vous envoie un colis. Suivez-le dans l'app."));
        assertThat(NotificationTexts.recipientInvitation(en, null).body())
                .isEqualTo("A sender wants to add you to their Yadony recipients.");
        assertThat(NotificationTexts.recipientInvitationAccepted(en, null).body()).startsWith("The recipient accepted");
        assertThat(NotificationTexts.recipientParcelAnnounced(en, "Awa").title()).isEqualTo("A parcel is coming");
        assertThat(NotificationTexts.recipientInvitationRemoved(fr, "Awa")).isEqualTo(new NotificationText(
                "Destinataires Yadony", "Awa ne vous compte plus parmi ses destinataires Yadony."));
        assertThat(NotificationTexts.recipientInvitationRemoved(en, null).body())
                .isEqualTo("A sender removed you from their Yadony recipients.");
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
        // Montant dans SA devise, rendu du portefeuille (WalletAmountText) : décimales et
        // symbole du catalogue SupportedCurrency, milliers séparés par une espace insécable.
        // Les francs CFA n'ont pas de décimales. Montants inchangés quelle que soit la langue (D7).
        assertThat(NotificationTexts.amount(new BigDecimal("15000"), "XOF")).isEqualTo("15\u00A0000\u00A0F CFA");
        assertThat(NotificationTexts.amount(new BigDecimal("1250"), "XAF")).isEqualTo("1\u00A0250\u00A0FCFA");
        assertThat(NotificationTexts.amount(new BigDecimal("12.5"), "eur")).isEqualTo("12,50\u00A0€");
        assertThat(NotificationTexts.amount(new BigDecimal("1250.5"), "EUR")).isEqualTo("1\u00A0250,50\u00A0€");
        // Devise absente : euro, jamais le code d'une autre devise.
        assertThat(NotificationTexts.amount(new BigDecimal("45"), null)).isEqualTo("45,00\u00A0€");
        assertThat(NotificationTexts.amount(null, "XOF")).isEmpty();
        assertThat(NotificationTexts.mobileMoneyAmount(new BigDecimal("15000"), "XOF")).isEqualTo("15\u00A0000\u00A0F CFA");
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
    @DisplayName("FLUTTER-EK : report accepté, prénom de l'expéditeur, repli générique")
    void tripRescheduleKept_bothLanguages() {
        var fr = TestMessages.fr();
        var en = TestMessages.en();
        assertThat(NotificationTexts.tripRescheduleKept(fr, "Awa").title()).isEqualTo("Report accepté");
        assertThat(NotificationTexts.tripRescheduleKept(fr, "Awa").body())
                .isEqualTo("Awa garde son colis sur la nouvelle date.");
        assertThat(NotificationTexts.tripRescheduleKept(fr, " ").body())
                .isEqualTo("Un expéditeur garde son colis sur la nouvelle date.");
        assertThat(NotificationTexts.tripRescheduleKept(en, "Awa").title()).isEqualTo("Reschedule accepted");
        assertThat(NotificationTexts.tripRescheduleKept(en, "Awa").body())
                .isEqualTo("Awa is keeping their parcel on the new date.");
        assertThat(NotificationTexts.tripRescheduleKept(en, null).body())
                .isEqualTo("A sender is keeping their parcel on the new date.");
    }

    @Test
    void recipientTexts_bothLanguages() {
        var fr = TestMessages.fr();
        var en = TestMessages.en();
        assertThat(NotificationTexts.recipientParcelIncoming(fr, "Awa", "Paris", "Dakar").body())
                .isEqualTo("Awa : colis Paris vers Dakar.");
        assertThat(NotificationTexts.recipientParcelIncoming(en, "Awa", "Paris", "Dakar").body())
                .isEqualTo("Awa: parcel from Paris to Dakar.");
        assertThat(NotificationTexts.recipientParcelIncoming(fr, null, "Paris", "Dakar").body())
                .startsWith("Un expéditeur");
        assertThat(NotificationTexts.recipientParcelArrived(en, "Dakar").body())
                .isEqualTo("It is in Dakar. Have your pickup code ready.");
        assertThat(NotificationTexts.recipientConfirmed(fr, null).body()).startsWith("Le destinataire suit");
        assertThat(NotificationTexts.recipientConfirmed(en, "Fatou").body()).startsWith("Fatou tracks");
        assertThat(NotificationTexts.recipientDeclined(en).title()).isEqualTo("Check the recipient");
        assertThat(NotificationTexts.recipientParcelCancelled(fr).title()).isEqualTo("Envoi annulé");
        assertThat(NotificationTexts.recipientParcelCancelled(en).body())
                .isEqualTo("The planned transport of your parcel has been cancelled.");
        assertThat(NotificationTexts.recipientParcelRescheduled(fr).title()).isEqualTo("Nouvelles dates");
        assertThat(NotificationTexts.recipientParcelRescheduled(en).body())
                .isEqualTo("Your parcel's trip has new dates. See the details.");
        assertThat(NotificationTexts.recipientParcelReassigned(fr).title()).isEqualTo("Colis réattribué");
        assertThat(NotificationTexts.recipientParcelReassigned(fr).body())
                .isEqualTo("L'expéditeur a changé de destinataire : ce colis n'est plus pour vous.");
        assertThat(NotificationTexts.recipientParcelReassigned(en).title()).isEqualTo("Parcel reassigned");
        assertThat(NotificationTexts.recipientChanged(fr).title()).isEqualTo("Destinataire modifié");
        assertThat(NotificationTexts.recipientChanged(fr).body())
                .isEqualTo("L'expéditeur a changé le destinataire d'un colis. Voyez le détail.");
        assertThat(NotificationTexts.recipientChanged(en).body())
                .isEqualTo("The sender changed the recipient of a parcel. See the details.");
        assertThat(NotificationTexts.recipientPickupUpdated(fr).title()).isEqualTo("Retrait mis à jour");
        assertThat(NotificationTexts.recipientPickupUpdated(fr).body())
                .isEqualTo("Le voyageur a modifié les instructions de retrait de votre colis.");
        assertThat(NotificationTexts.recipientPickupUpdated(en).title()).isEqualTo("Pickup updated");
        assertThat(NotificationTexts.recipientPickupUpdated(en).body())
                .isEqualTo("The traveler has updated the pickup instructions for your parcel.");
    }

    @Test
    void bidRejectedWithReason_unknownReason_returnsNull() {
        assertThat(NotificationTexts.bidRejectedWithReason(TestMessages.fr(), null, false)).isNull();
        assertThat(NotificationTexts.bidRejectedWithReason(TestMessages.fr(), "Trop lourd", true)).isNull();
        assertThat(NotificationTexts.bidRejectedWithReason(TestMessages.en(), "NO_CAPACITY", true).body())
                .isEqualTo("The traveler declined: not enough room. Refund in progress.");
    }

    @Test
    void parcelReturnToSender_bothLanguages() {
        java.time.LocalDate deadline = java.time.LocalDate.of(2026, 10, 11);
        assertThat(NotificationTexts.parcelReturnToSender(TestMessages.fr(), deadline)).isEqualTo(new NotificationText(
                "L'expéditeur a annulé",
                "Rendez-lui le colis avant le 11 oct. et saisissez son code de retour."));
        assertThat(NotificationTexts.parcelReturnToSender(TestMessages.en(), deadline)).isEqualTo(new NotificationText(
                "The sender cancelled",
                "Return the parcel to them by Oct 11 and enter their return code."));
    }

    @Test
    void parcelReturnRequired_bothLanguages() {
        assertThat(NotificationTexts.parcelReturnRequired(TestMessages.fr())).isEqualTo(new NotificationText(
                "Colis à vous restituer",
                "Remboursement en cours. Le code de retour est dans le suivi du colis."));
        assertThat(NotificationTexts.parcelReturnRequired(TestMessages.en())).isEqualTo(new NotificationText(
                "Parcel to be returned",
                "Refund in progress. Your return code is in the parcel tracking."));
    }

    @Test
    void handoverDeadlinePassed_bothLanguages() {
        assertThat(NotificationTexts.handoverDeadlinePassed(TestMessages.fr())).isEqualTo(new NotificationText(
                "Date limite de dépôt passée", "La date limite de dépôt est passée : demande annulée."));
        assertThat(NotificationTexts.handoverDeadlinePassedRefunded(TestMessages.fr()).body())
                .isEqualTo("La date limite de dépôt est passée : demande annulée et remboursée.");
        assertThat(NotificationTexts.handoverDeadlinePassed(TestMessages.en())).isEqualTo(new NotificationText(
                "Drop-off deadline passed", "The drop-off deadline has passed: request cancelled."));
        assertThat(NotificationTexts.handoverDeadlinePassedRefunded(TestMessages.en()).body())
                .isEqualTo("The drop-off deadline has passed: request cancelled and refunded.");
    }

    @Test
    void callMissedTexts_bothLanguages() {
        assertThat(NotificationTexts.callMissed(TestMessages.fr(), "Awa")).isEqualTo(new NotificationText(
                "Appel manqué", "Awa a essayé de vous appeler."));
        assertThat(NotificationTexts.callMissed(TestMessages.en(), "Awa")).isEqualTo(new NotificationText(
                "Missed call", "Awa tried to call you."));
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

    @Test
    void firstActionReminder_pluralizesCount() {
        Messages m = TestMessages.fr();
        assertThat(NotificationTexts.firstActionReminder(m, "sender-trips", 1).body()).startsWith("1 trajet part ");
        assertThat(NotificationTexts.firstActionReminder(m, "sender-trips", 3).body()).startsWith("3 trajets partent ");
        assertThat(NotificationTexts.firstActionReminder(m, "traveler-packages", 2).body()).startsWith("2 colis attendent ");
        assertThat(NotificationTexts.firstActionReminder(m, "sender-none", 0).title()).isEqualTo("Soyez prévenu en premier");
    }
}
