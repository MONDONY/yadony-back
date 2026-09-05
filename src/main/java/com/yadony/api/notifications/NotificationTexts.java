package com.yadony.api.notifications;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import static com.yadony.api.notifications.NotificationCaps.shortDisplayName;
import static com.yadony.api.notifications.NotificationCaps.truncateAtWord;

/**
 * Catalogue des libellés de notification : une méthode par événement, la seule
 * source des titres et des corps servis dans le sheet et poussés par FCM.
 *
 * <p>Tout ce qui est écrit ici respecte {@link NotificationCaps} au pire cas
 * réaliste, vérifié par {@code NotificationTextsTest} : titre de 28 caractères
 * sans nom de ville (les villes ne sont pas bornées), corps de 72 caractères.
 * Trois règles, trouvées en mesurant :
 * <ol>
 *   <li>aucune ville dans le titre, elles descendent dans le corps ;</li>
 *   <li>on raccourcit la <em>variable</em> ({@link NotificationCaps#shortDisplayName},
 *       un motif de refus, un lieu de remise), jamais la phrase assemblée ;</li>
 *   <li>les villes ne sont jamais raccourcies : c'est de l'identité, et ce sont
 *       elles qui consomment la marge.</li>
 * </ol>
 * Jamais de tiret cadratin ni de flèche dans un texte affiché : « vers » entre
 * deux villes, une virgule ou un point entre deux propositions. Vouvoiement
 * partout.
 */
public final class NotificationTexts {

    private NotificationTexts() {}

    // ── Aides de formatage ───────────────────────────────────────────────────

    /** « Paris vers Dakar ». */
    public static String corridor(String departure, String arrival) {
        return departure + " vers " + arrival;
    }

    /** Convertit un libellé « Paris → Dakar » (MatchingTextUtil.corridorLabel) en « Paris vers Dakar ». */
    public static String corridorFromLabel(String label) {
        return label == null ? "" : label.replace(" → ", " vers ").replace("→", "vers");
    }

    /** « 12 kg » ou « 12,5 kg », jamais « 12.0 ». */
    public static String kg(BigDecimal weight) {
        if (weight == null) return "";
        BigDecimal w = weight.stripTrailingZeros();
        return (w.scale() <= 0 ? w.toPlainString() : String.format(Locale.FRENCH, "%.1f", w)) + " kg";
    }

    /** « 1250,00 € ». */
    public static String eur(BigDecimal amount) {
        return amount == null ? "" : String.format(Locale.FRENCH, "%.2f €", amount.setScale(2, RoundingMode.HALF_UP));
    }

    /** « 1250,00 XOF » ; l'euro prend son symbole. */
    public static String amount(BigDecimal amount, String currency) {
        if (amount == null) return "";
        if (currency == null || currency.isBlank() || "EUR".equalsIgnoreCase(currency)) return eur(amount);
        return String.format(Locale.FRENCH, "%.2f %s", amount.setScale(2, RoundingMode.HALF_UP), currency.toUpperCase(Locale.ROOT));
    }

    /**
     * Montant tel qu'affiché dans un push mobile money (tâche 16) : « 15000 F CFA », sans
     * décimale pour les deux francs CFA (XOF, XAF), symbole du catalogue
     * {@link com.yadony.api.payments.currency.SupportedCurrency} plutôt que le code ISO —
     * plus lisible dans un push qu'un code à trois lettres, et cohérent avec le rendu déjà
     * utilisé côté admin ({@code ProAnalyticsService#formatAmount}). Distinct de
     * {@link #amount(BigDecimal, String)}, dont le contrat (code ISO, toujours deux
     * décimales) est déjà figé par {@code formattingHelpers()} et utilisé ailleurs (ex.
     * {@code commissionPending}) — jamais modifié ici.
     */
    public static String mobileMoneyAmount(BigDecimal amount, String currencyCode) {
        if (amount == null) return "";
        com.yadony.api.payments.currency.SupportedCurrency currency =
                com.yadony.api.payments.currency.SupportedCurrency.fromCodeOrDefault(currencyCode);
        String number = String.format(Locale.FRENCH, "%." + currency.minorUnit() + "f",
                amount.setScale(currency.minorUnit(), RoundingMode.HALF_UP));
        return number + " " + currency.symbol();
    }

    /** « ORANGE_MONEY » → « Orange Money ». */
    public static String provider(String enumName) {
        if (enumName == null || enumName.isBlank()) return "Mobile Money";
        StringBuilder out = new StringBuilder();
        for (String part : enumName.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static String plural(int n, String singular) {
        return n + " " + singular + (n > 1 ? "s" : "");
    }

    // ── Colis : offres et demandes ───────────────────────────────────────────

    /**
     * Une demande d'envoi arrive sur un trajet. {@code weightKg} peut être nul ;
     * {@code corridorLabel} est celui de l'événement (« Paris → Dakar »).
     */
    public static NotificationText newBid(String senderName, BigDecimal weightKg, String corridorLabel) {
        String corridor = corridorFromLabel(corridorLabel);
        String body = weightKg != null
                ? shortDisplayName(senderName) + ", " + kg(weightKg) + ", " + corridor + "."
                : shortDisplayName(senderName) + ", " + corridor + ".";
        return new NotificationText("Nouvelle demande d'envoi", body);
    }

    /** {@code travelerName} nul ou vide : le voyageur n'a plus de nom public, on reste générique. */
    public static NotificationText bidAccepted(String travelerName) {
        String who = travelerName == null || travelerName.isBlank() ? "Le voyageur" : shortDisplayName(travelerName);
        return new NotificationText("Demande acceptée !", who + " accepte votre colis.");
    }

    public static NotificationText bidAcceptedPayNow() {
        return new NotificationText("Demande acceptée !",
                "Votre colis est accepté. Ouvrez l'app pour régler le paiement.");
    }

    public static NotificationText bidRejected() {
        return new NotificationText("Demande refusée", "Le voyageur a refusé votre demande.");
    }

    public static NotificationText bidRejectedTripWithdrawn() {
        return new NotificationText("Trajet retiré", "Ce trajet n'est plus disponible. Remboursement en cours.");
    }

    /** Pourquoi une offre est perdue, quand le colis peut être reproposé. */
    public enum BidLoss { TRIP_DELETED, TRANSPORT_CANCELLED, REFUSED }

    private static String bidLossTitle(BidLoss loss) {
        return switch (loss) {
            case TRIP_DELETED -> "Trajet supprimé";
            case TRANSPORT_CANCELLED -> "Transport annulé";
            case REFUSED -> "Demande refusée";
        };
    }

    private static String bidLossPrefix(BidLoss loss) {
        return switch (loss) {
            case TRIP_DELETED -> "Le voyageur a supprimé son trajet";
            case TRANSPORT_CANCELLED -> "Le voyageur a annulé le transport";
            case REFUSED -> "Le voyageur a refusé votre demande";
        };
    }

    /** Offre perdue, avec {@code alternatives} voyageurs proposés en remplacement. */
    public static NotificationText bidLostWithRematch(BidLoss loss, int alternatives) {
        return new NotificationText(bidLossTitle(loss),
                bidLossPrefix(loss) + ". " + plural(alternatives, "voyageur") + " alternatif"
                        + (alternatives > 1 ? "s proposés." : " proposé."));
    }

    public static NotificationText bidLostRefund(BidLoss loss) {
        return new NotificationText(bidLossTitle(loss), bidLossPrefix(loss) + ". Remboursement en cours.");
    }

    public static NotificationText bidExpired() {
        return new NotificationText("Demande expirée",
                "Le voyageur est parti avant d'accepter. Remboursement en cours.");
    }

    public static NotificationText parcelRefused(String reason) {
        String motif = reason == null || reason.isBlank() ? "contenu non conforme" : truncateAtWord(reason.trim(), 39);
        return new NotificationText("Colis refusé", "Refusé par le voyageur. Motif : " + motif + ".");
    }

    // ── Colis : trajet du voyageur ───────────────────────────────────────────

    public static NotificationText tripCancelledRefund() {
        return new NotificationText("Trajet annulé", "Le voyageur a annulé son trajet. Remboursement en cours.");
    }

    public static NotificationText tripCancelledWithRematch(int alternatives) {
        return new NotificationText("Trajet annulé",
                "Remboursement en cours. " + plural(alternatives, "voyageur") + " alternatif"
                        + (alternatives > 1 ? "s proposés." : " proposé."));
    }

    public static NotificationText tripCancelledNoTraveler() {
        return new NotificationText("Trajet annulé", "Aucun voyageur disponible sous 72 h. Remboursement traité.");
    }

    public static NotificationText travelerNoShow() {
        return new NotificationText("Voyageur absent",
                "Le voyageur n'est pas venu à la remise. Remboursement en cours.");
    }

    public static NotificationText tripArrived() {
        return new NotificationText("Votre voyageur est arrivé",
                "Les instructions de retrait sont dans le suivi du colis.");
    }

    public static NotificationText tripInProgress() {
        return new NotificationText("Bon voyage !", "Scannez les QR codes à la remise et à la livraison.");
    }

    /** Rappel critique H-2 ; le lieu de remise est libre, donc raccourci au mot. */
    public static NotificationText handoverReminder(String location) {
        String lieu = location == null || location.isBlank() ? "le point de remise" : truncateAtWord(location.trim(), 33);
        return new NotificationText("Plus que 2 h pour déposer",
                "Dernier créneau : " + lieu + ". Le voyageur attend.");
    }

    public static NotificationText confirmationCodeReady() {
        return new NotificationText("Code de livraison disponible",
                "Le voyageur est prêt à remettre votre colis. Partagez le code.");
    }

    public static NotificationText deliveryConfirmed() {
        return new NotificationText("Livraison confirmée", "Votre colis est arrivé à destination.");
    }

    public static NotificationText deliveryNoShowForSender() {
        return new NotificationText("Absence à la livraison",
                "Le voyageur signale que votre destinataire était absent.");
    }

    public static NotificationText deliveryNoShowForTraveler() {
        return new NotificationText("Absence à la livraison",
                "L'expéditeur signale que le colis n'a pas été livré.");
    }

    // ── Colis : retours ──────────────────────────────────────────────────────

    public static NotificationText parcelReturnedForSender() {
        return new NotificationText("Colis rendu", "Le retour de votre colis a été confirmé.");
    }

    public static NotificationText parcelReturnedForTraveler() {
        return new NotificationText("Retour confirmé", "La restitution du colis est enregistrée.");
    }

    public static NotificationText returnDeadlineWarningForSender() {
        return new NotificationText("Code de retour à partager",
                "Le délai de retour expire dans moins de 24 heures.");
    }

    public static NotificationText returnDeadlineWarningForTraveler() {
        return new NotificationText("Retour du colis à effectuer",
                "Confirmez la restitution avant l'expiration du délai.");
    }

    public static NotificationText returnDeadlineExpired() {
        return new NotificationText("Délai de retour dépassé",
                "Le retour du colis n'a pas été confirmé. Notre équipe est alertée.");
    }

    // ── Colis : litiges ──────────────────────────────────────────────────────

    public static NotificationText disputeOpenedForSender() {
        return new NotificationText("Litige ouvert", "Un incident a été signalé sur votre envoi.");
    }

    public static NotificationText disputeOpenedForTraveler() {
        return new NotificationText("Litige ouvert", "Un incident a été signalé sur votre colis.");
    }

    public static NotificationText disputeUpdated() {
        return new NotificationText("Litige mis à jour",
                "Une nouvelle décision financière est enregistrée sur le litige.");
    }

    public static NotificationText disputeResolved() {
        return new NotificationText("Litige résolu",
                "Une décision finale a été prise. Consultez le détail du litige.");
    }

    // ── Colis : négociation de prix sur une offre ────────────────────────────

    public static NotificationText bidNegotiationProposal(String grossEur) {
        return new NotificationText("Nouvelle proposition de prix",
                "Un expéditeur propose " + grossEur + " € pour votre trajet.");
    }

    public static NotificationText bidNegotiationCounter(String grossEur, int round) {
        return new NotificationText("Nouvelle contre-proposition",
                "Nouvelle offre : " + grossEur + " €, tour " + round + ".");
    }

    public static NotificationText bidNegotiationAccepted(String grossEur) {
        return new NotificationText("Prix accepté", "Accord trouvé à " + grossEur + " €.");
    }

    public static NotificationText bidNegotiationClosed() {
        return new NotificationText("Discussion de prix close",
                "La discussion de prix sur ce trajet est terminée.");
    }

    public static NotificationText bidNegotiationExpired() {
        return new NotificationText("Discussion de prix expirée",
                "Faute de réponse, la discussion de prix s'est refermée.");
    }

    // ── Trajets : demandes de colis et négociation ───────────────────────────

    public static NotificationText negotiationStarted(BigDecimal proposedEur) {
        return new NotificationText("Nouvelle proposition reçue",
                "Un voyageur propose " + eur(proposedEur) + " pour votre demande.");
    }

    public static NotificationText negotiationCounter(BigDecimal newPriceEur, int round) {
        return new NotificationText("Nouvelle contre-proposition",
                "Nouvelle offre : " + eur(newPriceEur) + ", tour " + round + ".");
    }

    public static NotificationText negotiationAwaitingTrip(BigDecimal agreedEur) {
        return new NotificationText("Offre acceptée",
                "L'expéditeur accepte " + eur(agreedEur) + ". Choisissez le trajet à lier.");
    }

    public static NotificationText negotiationAwaitingPayment(BigDecimal agreedEur) {
        return new NotificationText("Paiement requis",
                "Le voyageur a confirmé son trajet. Payez " + eur(agreedEur) + " pour finaliser.");
    }

    public static NotificationText negotiationTripChanged() {
        return new NotificationText("Trajet mis à jour",
                "Le voyageur a changé le trajet associé à votre demande.");
    }

    public static NotificationText commissionPending(BigDecimal commission, String currency) {
        return new NotificationText("Confirmez la prise en charge",
                "Offre retenue. Réglez " + amount(commission, currency) + " de commission pour confirmer.");
    }

    public static NotificationText commissionDeclined() {
        return new NotificationText("Le voyageur a renoncé",
                "Accord en espèces abandonné. Votre demande reste ouverte.");
    }

    public static NotificationText commissionExpiredForTraveler() {
        return new NotificationText("Délai de commission dépassé",
                "Commission non réglée à temps : la demande n'est plus disponible.");
    }

    public static NotificationText commissionExpiredForSender() {
        return new NotificationText("Demande de nouveau ouverte",
                "Le voyageur n'a pas réglé la commission. Votre demande reste ouverte.");
    }

    public static NotificationText requestAcceptedForTraveler(BigDecimal agreedEur) {
        return new NotificationText("Paiement reçu, c'est parti",
                eur(agreedEur) + " sous séquestre. Préparez le retrait du colis.");
    }

    public static NotificationText requestAcceptedForSender(BigDecimal agreedEur) {
        return new NotificationText("Demande finalisée",
                "Paiement de " + eur(agreedEur) + " confirmé. Le voyageur va vous contacter.");
    }

    public static NotificationText requestExpired() {
        return new NotificationText("Votre demande a expiré",
                "Aucun voyageur n'a accepté à temps. Vous pouvez en créer une autre.");
    }

    public static NotificationText negotiationReminder(String fromName) {
        return new NotificationText("Relance",
                shortDisplayName(fromName) + " attend de vos nouvelles sur votre négociation.");
    }

    public static NotificationText negotiationEnded(String byName) {
        return new NotificationText("Négociation terminée", shortDisplayName(byName) + " a mis fin à la négociation.");
    }

    public static NotificationText negotiationExpired() {
        return new NotificationText("Négociation expirée", "Cette négociation a expiré faute d'activité.");
    }

    // ── Trajets : alertes, matches, abonnements ──────────────────────────────

    public static NotificationText packageMatch(String departure, String arrival) {
        return new NotificationText("Un colis pour votre trajet",
                corridor(departure, arrival) + " : un colis correspond.");
    }

    public static NotificationText travelerInvite(String travelerName, String departure, String arrival) {
        return new NotificationText("Invitation d'un voyageur",
                shortDisplayName(travelerName) + " propose " + corridor(departure, arrival) + ".");
    }

    public static NotificationText travelerNewAnnouncement(String travelerName, String departure, String arrival) {
        return new NotificationText("Nouveau trajet publié",
                shortDisplayName(travelerName) + " publie " + corridor(departure, arrival) + ".");
    }

    public static NotificationText corridorAlertTrip(String departure, String arrival) {
        return new NotificationText("Un trajet pour votre alerte",
                corridor(departure, arrival) + " : un trajet correspond.");
    }

    /** Récapitulatif d'alerte ; au-delà de 99 le titre renonce au nombre pour rester sur une ligne. */
    public static NotificationText corridorAlertDigest(boolean trips, int count, String departure, String arrival) {
        String what = trips ? "trajet" : "colis";
        String title = count < 100
                ? count + " " + what + (trips && count > 1 ? "s" : "") + " pour votre alerte"
                : (trips ? "Trajets" : "Colis") + " pour votre alerte";
        String body = corridor(departure, arrival) + " : " + count + " " + what
                + (trips && count > 1 ? "s" : "") + (count > 1 ? " correspondent." : " correspond.");
        return new NotificationText(title, body);
    }

    public static NotificationText announcementRemoved(String publicReason) {
        return new NotificationText("Annonce retirée", "Retirée par la modération : " + publicReason + ".");
    }

    // ── Trajets : automatisations voyageur ───────────────────────────────────

    public static NotificationText capacityFree(BigDecimal availableKg, int hours, String departure, String arrival) {
        return new NotificationText("Capacité libérée",
                kg(availableKg) + " libres depuis " + hours + " h, " + corridor(departure, arrival) + ".");
    }

    public static NotificationText lastMinuteOffer(int hoursBeforeDeparture, String corridorLabel) {
        return new NotificationText("Offre de dernière minute",
                "Départ dans moins de " + hoursBeforeDeparture + " h : " + corridorFromLabel(corridorLabel) + ".");
    }

    public static NotificationText loyalSender(String travelerName, String departure, String arrival) {
        return new NotificationText("Trajet sur votre corridor",
                shortDisplayName(travelerName) + " publie " + corridor(departure, arrival) + ".");
    }

    // ── Paiements et identité ────────────────────────────────────────────────

    /** {@code amount} déjà formaté par l'appelant (« 45,00 € »). */
    public static NotificationText paymentReleased(String formattedAmount) {
        return new NotificationText("Paiement reçu !", formattedAmount + ", virement en cours sous 24 h.");
    }

    /**
     * Rail pawaPay (tâche 16) : jumeau mobile money de {@link #paymentReleased(String)},
     * poussé à la confirmation {@code COMPLETED} du payout (pas à la simple soumission).
     * {@code formattedAmount} déjà formaté par l'appelant (« 13200 F CFA », voir
     * {@link #mobileMoneyAmount(BigDecimal, String)}). Toujours envoyé en {@code notifyUser},
     * jamais {@code notifyCritical} (voir {@code NotificationDispatcher#onPaymentReleased}) :
     * un versement déjà confirmé par pawaPay n'a rien d'urgent à faire dans la minute qui
     * suit, contrairement au virement carte (délai J+1, d'où le suivi ACK historique).
     */
    public static NotificationText mobileMoneyPayoutSent(String formattedAmount) {
        return new NotificationText("Versement envoyé", formattedAmount + " envoyés sur votre mobile money.");
    }

    public static NotificationText mobileMoneyPaymentConfirmed() {
        return new NotificationText("Paiement confirmé", "Le paiement Mobile Money de cet envoi est confirmé.");
    }

    /** Push voyageur au deposit COMPLETED (séquestre acquis) : la préparation de la remise peut commencer. */
    public static NotificationText mobileMoneyPaymentReceived() {
        return new NotificationText("Colis payé", "L'expéditeur a payé en mobile money. Préparez la remise.");
    }

    /**
     * Push à l'acceptation d'un bid mobile money, à la place de « Demande acceptée ! ».
     * {@code depositDeadlineMinutes} vient de la configuration ({@code yadony.pawapay.deposit-deadline-minutes})
     * — jamais en dur, sous peine de mentir si le délai est reconfiguré.
     */
    public static NotificationText mobileMoneyPaymentPending(int depositDeadlineMinutes) {
        return new NotificationText("Payez votre envoi",
                "Le voyageur a accepté. Réglez en mobile money sous " + depositDeadlineMinutes + " min.");
    }

    /**
     * Deposit FAILED (PIN refusé, solde insuffisant, opérateur indisponible…) : le motif
     * technique pawaPay n'est jamais exposé, l'expéditeur est seulement invité à réessayer.
     */
    public static NotificationText mobileMoneyPaymentFailed() {
        return new NotificationText("Paiement refusé", "Le paiement mobile money a échoué. Réessayez depuis l'app.");
    }

    /**
     * Tâche 15 — deadline de paiement (30 min après acceptation) dépassée côté expéditeur :
     * le bid est annulé, la capacité rendue au voyageur.
     */
    public static NotificationText mobileMoneyPaymentExpired() {
        return new NotificationText("Délai de paiement dépassé",
                "Votre envoi est annulé, le paiement n'a pas été reçu à temps.");
    }

    /** Même événement, côté voyageur : la capacité qu'il avait cédée lui est rendue. */
    public static NotificationText mobileMoneyPaymentExpiredForTraveler() {
        return new NotificationText("Colis annulé",
                "L'expéditeur n'a pas payé dans le délai. Le colis est annulé.");
    }

    public static NotificationText kycVerified() {
        return new NotificationText("Identité vérifiée",
                "Vous pouvez maintenant publier et effectuer vos transactions.");
    }

    public static NotificationText kycActionRequired() {
        return new NotificationText("Vérification à compléter",
                "Une action est nécessaire pour terminer la vérification.");
    }

    public static NotificationText kycReset() {
        return new NotificationText("Vérification réinitialisée",
                "Un administrateur a réinitialisé votre vérification. Relancez-la.");
    }

    public static NotificationText stripeOnboardingIncomplete() {
        return new NotificationText("Finalisez vos paiements",
                "Il manque quelques informations pour être payé par carte. Deux minutes.");
    }

    public static NotificationText cardExpiring(String brand, String last4) {
        String b = brand == null || brand.isBlank() ? "Carte" : truncateAtWord(brand.trim(), 16);
        String l = last4 == null || last4.isBlank() ? "****" : last4;
        return new NotificationText("Votre carte expire bientôt",
                b + " ***" + l + " expire ce mois-ci. Mettez-la à jour.");
    }

    // ── Annonces et compte ───────────────────────────────────────────────────

    public static NotificationText accountSuspended() {
        return new NotificationText("Compte suspendu", "Votre compte est suspendu après des incidents répétés.");
    }

    public static NotificationText messagingMuted() {
        return new NotificationText("Messagerie suspendue",
                "Votre accès à la messagerie est suspendu par un administrateur.");
    }

    /**
     * Avertissement de modération. La note est libre : c'est une annonce, donc
     * le service garde le texte complet dans {@code fullBody} et résume le corps.
     */
    public static NotificationText adminWarning(String note) {
        String body = note == null || note.isBlank()
                ? "Un comportement signalé sur votre compte a été examiné."
                : note.trim();
        return new NotificationText("Avertissement Yadony", body);
    }
}
