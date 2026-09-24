package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import static com.yadony.api.notifications.NotificationCaps.shortDisplayName;
import static com.yadony.api.notifications.NotificationCaps.truncateAtWord;

/**
 * Catalogue des libellés de notification : une méthode par événement, la seule
 * source des titres et des corps servis dans le sheet et poussés par FCM.
 *
 * <p>Chaque méthode qui rend un {@link NotificationText} prend {@link Messages}
 * en premier paramètre : c'est la langue du <b>destinataire</b> de la notification
 * (jamais celle de l'émetteur, ni celle de la requête HTTP courante), résolue par
 * {@code NotificationDispatcher#messagesFor(UUID)}.
 *
 * <p>Tout ce qui est écrit ici respecte {@link NotificationCaps} au pire cas
 * réaliste, vérifié par {@code NotificationTextsTest} dans les deux langues :
 * titre de 28 caractères sans nom de ville (les villes ne sont pas bornées),
 * corps de 72 caractères. Trois règles, trouvées en mesurant :
 * <ol>
 *   <li>aucune ville dans le titre, elles descendent dans le corps ;</li>
 *   <li>on raccourcit la <em>variable</em> ({@link NotificationCaps#shortDisplayName},
 *       un motif de refus, un lieu de remise), jamais la phrase assemblée ;</li>
 *   <li>les villes ne sont jamais raccourcies : c'est de l'identité, et ce sont
 *       elles qui consomment la marge.</li>
 * </ol>
 * Jamais de tiret cadratin ni de flèche dans un texte affiché : « vers »/« to »
 * entre deux villes, une virgule ou un point entre deux propositions. Vouvoiement
 * partout côté français ; « you » neutre côté anglais.
 */
public final class NotificationTexts {

    private NotificationTexts() {}

    // ── Aides de formatage ───────────────────────────────────────────────────

    /** « Paris vers Dakar » / « Paris to Dakar ». */
    public static String corridor(Messages m, String departure, String arrival) {
        return departure + " " + m.get("notification.corridor.word") + " " + arrival;
    }

    /** Convertit un libellé « Paris → Dakar » (MatchingTextUtil.corridorLabel) selon la langue. */
    public static String corridorFromLabel(Messages m, String label) {
        if (label == null) return "";
        String word = m.get("notification.corridor.word");
        return label.replace(" → ", " " + word + " ").replace("→", word);
    }

    /** « 12 kg » ou « 12,5 kg »/« 12.5 kg » selon la langue, jamais « 12.0 ». */
    public static String kg(Messages m, BigDecimal weight) {
        if (weight == null) return "";
        BigDecimal w = weight.stripTrailingZeros();
        String number = w.scale() <= 0 ? w.toPlainString() : String.format(m.locale(), "%.1f", w);
        return number + " kg";
    }

    /** « 1250,00 € ». Montant inchangé quelle que soit la langue (D7). */
    public static String eur(BigDecimal amount) {
        return amount == null ? "" : String.format(Locale.FRENCH, "%.2f €", amount.setScale(2, RoundingMode.HALF_UP));
    }

    /** « 1250,00 XOF » ; l'euro prend son symbole. Montant inchangé quelle que soit la langue (D7). */
    public static String amount(BigDecimal amount, String currency) {
        if (amount == null) return "";
        if (currency == null || currency.isBlank() || "EUR".equalsIgnoreCase(currency)) return eur(amount);
        return String.format(Locale.FRENCH, "%.2f %s", amount.setScale(2, RoundingMode.HALF_UP), currency.toUpperCase(Locale.ROOT));
    }

    /**
     * Montant tel qu'affiché dans un push mobile money : « 15000 F CFA », sans
     * décimale pour les deux francs CFA (XOF, XAF), symbole du catalogue
     * {@link com.yadony.api.payments.currency.SupportedCurrency} plutôt que le code ISO —
     * plus lisible dans un push qu'un code à trois lettres, et cohérent avec le rendu déjà
     * utilisé côté admin ({@code ProAnalyticsService#formatAmount}). Distinct de
     * {@link #amount(BigDecimal, String)}, dont le contrat (code ISO, toujours deux
     * décimales) est déjà figé par {@code formattingHelpers()} et utilisé ailleurs (ex.
     * {@code commissionPending}) — jamais modifié ici. Montant inchangé quelle que soit
     * la langue (D7).
     */
    public static String mobileMoneyAmount(BigDecimal amount, String currencyCode) {
        if (amount == null) return "";
        com.yadony.api.payments.currency.SupportedCurrency currency =
                com.yadony.api.payments.currency.SupportedCurrency.fromCodeOrDefault(currencyCode);
        String number = String.format(Locale.FRENCH, "%." + currency.minorUnit() + "f",
                amount.setScale(currency.minorUnit(), RoundingMode.HALF_UP));
        return number + " " + currency.symbol();
    }

    // ── Colis : offres et demandes ───────────────────────────────────────────

    /**
     * Une demande d'envoi arrive sur un trajet. {@code weightKg} peut être nul ;
     * {@code corridorLabel} est celui de l'événement (« Paris → Dakar »).
     * {@code senderName} nul ou vide : l'expéditeur n'a pas de nom public, on
     * reste générique.
     */
    public static NotificationText newBid(Messages m, String senderName, BigDecimal weightKg, String corridorLabel) {
        String corridor = corridorFromLabel(m, corridorLabel);
        String who = senderName == null || senderName.isBlank()
                ? m.get("notification.fallback.sender")
                : shortDisplayName(senderName);
        String body = weightKg != null
                ? who + ", " + kg(m, weightKg) + ", " + corridor + "."
                : who + ", " + corridor + ".";
        return new NotificationText(m.get("notification.new-bid.title"), body);
    }

    /** {@code travelerName} nul ou vide : le voyageur n'a plus de nom public, on reste générique. */
    public static NotificationText bidAccepted(Messages m, String travelerName) {
        String who = travelerName == null || travelerName.isBlank()
                ? m.get("notification.fallback.traveler")
                : shortDisplayName(travelerName);
        return new NotificationText(m.get("notification.bid-accepted.title"),
                m.get("notification.bid-accepted.body", who));
    }

    public static NotificationText bidAcceptedPayNow(Messages m) {
        return new NotificationText(m.get("notification.bid-accepted.title"),
                m.get("notification.bid-accepted-pay-now.body"));
    }

    public static NotificationText bidRejected(Messages m) {
        return new NotificationText(m.get("notification.bid-rejected.title"), m.get("notification.bid-rejected.body"));
    }

    public static NotificationText bidRejectedTripWithdrawn(Messages m) {
        return new NotificationText(m.get("notification.bid-rejected-trip-withdrawn.title"),
                m.get("notification.bid-rejected-trip-withdrawn.body"));
    }

    /** Pourquoi une offre est perdue, quand le colis peut être reproposé. */
    public enum BidLoss { TRIP_DELETED, TRANSPORT_CANCELLED, REFUSED }

    private static String bidLossTitle(Messages m, BidLoss loss) {
        return switch (loss) {
            case TRIP_DELETED -> m.get("notification.bid-loss.trip-deleted.title");
            case TRANSPORT_CANCELLED -> m.get("notification.bid-loss.transport-cancelled.title");
            case REFUSED -> m.get("notification.bid-loss.refused.title");
        };
    }

    private static String bidLossPrefix(Messages m, BidLoss loss) {
        return switch (loss) {
            case TRIP_DELETED -> m.get("notification.bid-loss.trip-deleted.prefix");
            case TRANSPORT_CANCELLED -> m.get("notification.bid-loss.transport-cancelled.prefix");
            case REFUSED -> m.get("notification.bid-loss.refused.prefix");
        };
    }

    /** Offre perdue, avec {@code alternatives} voyageurs proposés en remplacement. */
    public static NotificationText bidLostWithRematch(Messages m, BidLoss loss, int alternatives) {
        return new NotificationText(bidLossTitle(m, loss),
                bidLossPrefix(m, loss) + ". "
                        + m.plural("notification.rematch.alternatives", alternatives, String.valueOf(alternatives)));
    }

    public static NotificationText bidLostRefund(Messages m, BidLoss loss) {
        return new NotificationText(bidLossTitle(m, loss),
                bidLossPrefix(m, loss) + ". " + m.get("notification.refund-in-progress"));
    }

    public static NotificationText bidExpired(Messages m) {
        return new NotificationText(m.get("notification.bid-expired.title"), m.get("notification.bid-expired.body"));
    }

    public static NotificationText parcelRefused(Messages m, String reason) {
        String motif = reason == null || reason.isBlank()
                ? m.get("notification.parcel-refused.default-reason")
                : truncateAtWord(reason.trim(), 39);
        return new NotificationText(m.get("notification.parcel-refused.title"),
                m.get("notification.parcel-refused.body", motif));
    }

    // ── Colis : trajet du voyageur ───────────────────────────────────────────

    public static NotificationText tripCancelledRefund(Messages m) {
        return new NotificationText(m.get("notification.trip-cancelled.title"),
                m.get("notification.trip-cancelled-refund.body"));
    }

    public static NotificationText tripCancelledWithRematch(Messages m, int alternatives) {
        return new NotificationText(m.get("notification.trip-cancelled.title"),
                m.get("notification.refund-in-progress") + " "
                        + m.plural("notification.rematch.alternatives", alternatives, String.valueOf(alternatives)));
    }

    public static NotificationText tripCancelledNoTraveler(Messages m) {
        return new NotificationText(m.get("notification.trip-cancelled.title"),
                m.get("notification.trip-cancelled-no-traveler.body"));
    }

    public static NotificationText travelerNoShow(Messages m) {
        return new NotificationText(m.get("notification.traveler-no-show.title"),
                m.get("notification.traveler-no-show.body"));
    }

    public static NotificationText tripArrived(Messages m) {
        return new NotificationText(m.get("notification.trip-arrived.title"), m.get("notification.trip-arrived.body"));
    }

    public static NotificationText tripInProgress(Messages m) {
        return new NotificationText(m.get("notification.trip-in-progress.title"),
                m.get("notification.trip-in-progress.body"));
    }

    /** Rappel critique H-2 ; le lieu de remise est libre, donc raccourci au mot. */
    public static NotificationText handoverReminder(Messages m, String location) {
        String lieu = location == null || location.isBlank()
                ? m.get("notification.handover-reminder.default-location")
                : truncateAtWord(location.trim(), 33);
        return new NotificationText(m.get("notification.handover-reminder.title"),
                m.get("notification.handover-reminder.body", lieu));
    }

    public static NotificationText confirmationCodeReady(Messages m) {
        return new NotificationText(m.get("notification.confirmation-code-ready.title"),
                m.get("notification.confirmation-code-ready.body"));
    }

    public static NotificationText deliveryConfirmed(Messages m) {
        return new NotificationText(m.get("notification.delivery-confirmed.title"),
                m.get("notification.delivery-confirmed.body"));
    }

    public static NotificationText deliveryNoShowForSender(Messages m) {
        return new NotificationText(m.get("notification.delivery-no-show.title"),
                m.get("notification.delivery-no-show.sender.body"));
    }

    public static NotificationText deliveryNoShowForTraveler(Messages m) {
        return new NotificationText(m.get("notification.delivery-no-show.title"),
                m.get("notification.delivery-no-show.traveler.body"));
    }

    // ── Colis : retours ──────────────────────────────────────────────────────

    public static NotificationText parcelReturnedForSender(Messages m) {
        return new NotificationText(m.get("notification.parcel-returned.sender.title"),
                m.get("notification.parcel-returned.sender.body"));
    }

    public static NotificationText parcelReturnedForTraveler(Messages m) {
        return new NotificationText(m.get("notification.parcel-returned.traveler.title"),
                m.get("notification.parcel-returned.traveler.body"));
    }

    public static NotificationText returnDeadlineWarningForSender(Messages m) {
        return new NotificationText(m.get("notification.return-deadline-warning.sender.title"),
                m.get("notification.return-deadline-warning.sender.body"));
    }

    public static NotificationText returnDeadlineWarningForTraveler(Messages m) {
        return new NotificationText(m.get("notification.return-deadline-warning.traveler.title"),
                m.get("notification.return-deadline-warning.traveler.body"));
    }

    public static NotificationText returnDeadlineExpired(Messages m) {
        return new NotificationText(m.get("notification.return-deadline-expired.title"),
                m.get("notification.return-deadline-expired.body"));
    }

    // ── Colis : litiges ──────────────────────────────────────────────────────

    public static NotificationText disputeOpenedForSender(Messages m) {
        return new NotificationText(m.get("notification.dispute-opened.title"),
                m.get("notification.dispute-opened.sender.body"));
    }

    public static NotificationText disputeOpenedForTraveler(Messages m) {
        return new NotificationText(m.get("notification.dispute-opened.title"),
                m.get("notification.dispute-opened.traveler.body"));
    }

    public static NotificationText disputeUpdated(Messages m) {
        return new NotificationText(m.get("notification.dispute-updated.title"),
                m.get("notification.dispute-updated.body"));
    }

    public static NotificationText disputeResolved(Messages m) {
        return new NotificationText(m.get("notification.dispute-resolved.title"),
                m.get("notification.dispute-resolved.body"));
    }

    // ── Colis : négociation de prix sur une offre ────────────────────────────

    public static NotificationText bidNegotiationProposal(Messages m, String grossEur) {
        return new NotificationText(m.get("notification.bid-negotiation-proposal.title"),
                m.get("notification.bid-negotiation-proposal.body", grossEur));
    }

    public static NotificationText bidNegotiationCounter(Messages m, String grossEur, int round) {
        return new NotificationText(m.get("notification.counter-offer.title"),
                m.get("notification.bid-negotiation-counter.body", grossEur, round));
    }

    public static NotificationText bidNegotiationAccepted(Messages m, String grossEur) {
        return new NotificationText(m.get("notification.bid-negotiation-accepted.title"),
                m.get("notification.bid-negotiation-accepted.body", grossEur));
    }

    public static NotificationText bidNegotiationClosed(Messages m) {
        return new NotificationText(m.get("notification.bid-negotiation-closed.title"),
                m.get("notification.bid-negotiation-closed.body"));
    }

    public static NotificationText bidNegotiationExpired(Messages m) {
        return new NotificationText(m.get("notification.bid-negotiation-expired.title"),
                m.get("notification.bid-negotiation-expired.body"));
    }

    // ── Trajets : demandes de colis et négociation ───────────────────────────

    public static NotificationText negotiationStarted(Messages m, BigDecimal proposedEur) {
        return new NotificationText(m.get("notification.negotiation-started.title"),
                m.get("notification.negotiation-started.body", eur(proposedEur)));
    }

    public static NotificationText negotiationCounter(Messages m, BigDecimal newPriceEur, int round) {
        return new NotificationText(m.get("notification.counter-offer.title"),
                m.get("notification.negotiation-counter.body", eur(newPriceEur), round));
    }

    public static NotificationText negotiationAwaitingTrip(Messages m, BigDecimal agreedEur) {
        return new NotificationText(m.get("notification.negotiation-awaiting-trip.title"),
                m.get("notification.negotiation-awaiting-trip.body", eur(agreedEur)));
    }

    public static NotificationText negotiationAwaitingPayment(Messages m, BigDecimal agreedEur) {
        return new NotificationText(m.get("notification.negotiation-awaiting-payment.title"),
                m.get("notification.negotiation-awaiting-payment.body", eur(agreedEur)));
    }

    public static NotificationText negotiationTripChanged(Messages m) {
        return new NotificationText(m.get("notification.negotiation-trip-changed.title"),
                m.get("notification.negotiation-trip-changed.body"));
    }

    public static NotificationText commissionPending(Messages m, BigDecimal commission, String currency) {
        return new NotificationText(m.get("notification.commission-pending.title"),
                m.get("notification.commission-pending.body", amount(commission, currency)));
    }

    public static NotificationText commissionDeclined(Messages m) {
        return new NotificationText(m.get("notification.commission-declined.title"),
                m.get("notification.commission-declined.body"));
    }

    /** Dépôt mobile money lancé sur un fil : à l'expéditeur de régler. */
    public static NotificationText depositPendingSender(Messages m, BigDecimal gross, String currency) {
        return new NotificationText(m.get("notification.pay-your-shipment.title"),
                m.get("notification.deposit-pending.sender.body", amount(gross, currency)));
    }

    /** Même dépôt, côté voyageur : simple information, rien à faire de son côté. */
    public static NotificationText depositPendingTraveler(Messages m) {
        return new NotificationText(m.get("notification.deposit-pending.traveler.title"),
                m.get("notification.deposit-pending.traveler.body"));
    }

    /**
     * Le fil revient à « à payer » : dépôt échoué, échéance passée ou renoncement de
     * l'expéditeur. {@code reason} vient de {@code NegotiationDepositRevertedEvent}
     * (deposit-failed, deposit-expired, sender-cancelled) ; un motif technique pawaPay
     * n'y apparaît jamais.
     */
    public static NotificationText depositReverted(Messages m, String reason) {
        String body = switch (reason) {
            case "deposit-expired" -> m.get("notification.deposit-reverted.expired.body");
            case "sender-cancelled" -> m.get("notification.deposit-reverted.sender-cancelled.body");
            default -> m.get("notification.deposit-reverted.failed.body");
        };
        return new NotificationText(m.get("notification.deposit-reverted.title"), body);
    }

    public static NotificationText commissionExpiredForTraveler(Messages m) {
        return new NotificationText(m.get("notification.commission-expired.traveler.title"),
                m.get("notification.commission-expired.traveler.body"));
    }

    public static NotificationText commissionExpiredForSender(Messages m) {
        return new NotificationText(m.get("notification.commission-expired.sender.title"),
                m.get("notification.commission-expired.sender.body"));
    }

    public static NotificationText requestAcceptedForTraveler(Messages m, BigDecimal agreedEur) {
        return new NotificationText(m.get("notification.request-accepted.traveler.title"),
                m.get("notification.request-accepted.traveler.body", eur(agreedEur)));
    }

    public static NotificationText requestAcceptedForSender(Messages m, BigDecimal agreedEur) {
        return new NotificationText(m.get("notification.request-accepted.sender.title"),
                m.get("notification.request-accepted.sender.body", eur(agreedEur)));
    }

    public static NotificationText requestExpired(Messages m) {
        return new NotificationText(m.get("notification.request-expired.title"),
                m.get("notification.request-expired.body"));
    }

    /** Un expéditeur invite un voyageur à répondre à sa demande (sens inverse de travelerInvite). */
    public static NotificationText senderInvite(Messages m, String senderName, String departureCity, String arrivalCity) {
        return new NotificationText(m.get("notification.sender-invite.title"),
                m.get("notification.sender-invite.body", shortDisplayName(senderName), departureCity, arrivalCity));
    }

    public static NotificationText negotiationReminder(Messages m, String fromName) {
        return new NotificationText(m.get("notification.negotiation-reminder.title"),
                m.get("notification.negotiation-reminder.body", shortDisplayName(fromName)));
    }

    public static NotificationText negotiationEnded(Messages m, String byName) {
        return new NotificationText(m.get("notification.negotiation-ended.title"),
                m.get("notification.negotiation-ended.body", shortDisplayName(byName)));
    }

    public static NotificationText negotiationExpired(Messages m) {
        return new NotificationText(m.get("notification.negotiation-expired.title"),
                m.get("notification.negotiation-expired.body"));
    }

    // ── Trajets : alertes, matches, abonnements ──────────────────────────────

    public static NotificationText packageMatch(Messages m, String departure, String arrival) {
        return new NotificationText(m.get("notification.package-match.title"),
                m.get("notification.package-match.body", corridor(m, departure, arrival)));
    }

    public static NotificationText travelerInvite(Messages m, String travelerName, String departure, String arrival) {
        return new NotificationText(m.get("notification.traveler-invite.title"),
                m.get("notification.traveler-invite.body", shortDisplayName(travelerName), corridor(m, departure, arrival)));
    }

    public static NotificationText travelerNewAnnouncement(Messages m, String travelerName, String departure, String arrival) {
        return new NotificationText(m.get("notification.traveler-new-announcement.title"),
                m.get("notification.trip-posted.body", shortDisplayName(travelerName), corridor(m, departure, arrival)));
    }

    public static NotificationText corridorAlertTrip(Messages m, String departure, String arrival) {
        return new NotificationText(m.get("notification.corridor-alert-trip.title"),
                m.get("notification.corridor-alert-trip.body", corridor(m, departure, arrival)));
    }

    /** Récapitulatif d'alerte ; au-delà de 99 le titre renonce au nombre pour rester sur une ligne. */
    public static NotificationText corridorAlertDigest(Messages m, boolean trips, int count, String departure, String arrival) {
        String kind = trips ? "trips" : "parcels";
        String title = count < 100
                ? m.plural("notification.alert-digest." + kind + ".title", count, String.valueOf(count))
                : m.get("notification.alert-digest." + kind + ".title.many");
        String body = m.plural("notification.alert-digest." + kind + ".body", count,
                corridor(m, departure, arrival), String.valueOf(count));
        return new NotificationText(title, body);
    }

    public static NotificationText announcementRemoved(Messages m, String reasonCode) {
        String reason = m.get("notification.removal-reason." + reasonCode);
        return new NotificationText(m.get("notification.announcement-removed.title"),
                m.get("notification.announcement-removed.body", reason));
    }

    // ── Trajets : automatisations voyageur ───────────────────────────────────

    public static NotificationText capacityFree(Messages m, BigDecimal availableKg, int hours, String departure, String arrival) {
        return new NotificationText(m.get("notification.capacity-free.title"),
                m.get("notification.capacity-free.body", kg(m, availableKg), hours, corridor(m, departure, arrival)));
    }

    public static NotificationText lastMinuteOffer(Messages m, int hoursBeforeDeparture, String corridorLabel) {
        return new NotificationText(m.get("notification.last-minute-offer.title"),
                m.get("notification.last-minute-offer.body", hoursBeforeDeparture, corridorFromLabel(m, corridorLabel)));
    }

    public static NotificationText loyalSender(Messages m, String travelerName, String departure, String arrival) {
        return new NotificationText(m.get("notification.loyal-sender.title"),
                m.get("notification.trip-posted.body", shortDisplayName(travelerName), corridor(m, departure, arrival)));
    }

    // ── Paiements et identité ────────────────────────────────────────────────

    /** {@code amount} déjà formaté par l'appelant (« 45,00 € »). */
    public static NotificationText paymentReleased(Messages m, String formattedAmount) {
        return new NotificationText(m.get("notification.payment-released.title"),
                m.get("notification.payment-released.body", formattedAmount));
    }

    /**
     * Rail pawaPay : jumeau mobile money de {@link #paymentReleased(Messages, String)},
     * poussé à la confirmation {@code COMPLETED} du payout (pas à la simple soumission).
     * {@code formattedAmount} déjà formaté par l'appelant (« 13200 F CFA », voir
     * {@link #mobileMoneyAmount(BigDecimal, String)}). Toujours envoyé en {@code notifyUser},
     * jamais {@code notifyCritical} (voir {@code NotificationDispatcher#onPaymentReleased}) :
     * un versement déjà confirmé par pawaPay n'a rien d'urgent à faire dans la minute qui
     * suit, contrairement au virement carte (délai J+1, d'où le suivi ACK historique).
     */
    public static NotificationText mobileMoneyPayoutSent(Messages m, String formattedAmount) {
        return new NotificationText(m.get("notification.mobile-money-payout-sent.title"),
                m.get("notification.mobile-money-payout-sent.body", formattedAmount));
    }

    public static NotificationText mobileMoneyPaymentConfirmed(Messages m) {
        return new NotificationText(m.get("notification.mobile-money-payment-confirmed.title"),
                m.get("notification.mobile-money-payment-confirmed.body"));
    }

    /** Push voyageur au deposit COMPLETED (séquestre acquis) : la préparation de la remise peut commencer. */
    public static NotificationText mobileMoneyPaymentReceived(Messages m) {
        return new NotificationText(m.get("notification.mobile-money-payment-received.title"),
                m.get("notification.mobile-money-payment-received.body"));
    }

    /**
     * Push à l'acceptation d'un bid mobile money, à la place de « Demande acceptée ! ».
     * {@code depositDeadlineMinutes} vient de la configuration ({@code yadony.pawapay.deposit-deadline-minutes})
     * — jamais en dur, sous peine de mentir si le délai est reconfiguré.
     */
    public static NotificationText mobileMoneyPaymentPending(Messages m, int depositDeadlineMinutes) {
        return new NotificationText(m.get("notification.pay-your-shipment.title"),
                m.get("notification.mobile-money-payment-pending.body", depositDeadlineMinutes));
    }

    /**
     * Deposit FAILED (PIN refusé, solde insuffisant, opérateur indisponible…) : le motif
     * technique pawaPay n'est jamais exposé, l'expéditeur est seulement invité à réessayer.
     */
    public static NotificationText mobileMoneyPaymentFailed(Messages m) {
        return new NotificationText(m.get("notification.mobile-money-payment-failed.title"),
                m.get("notification.mobile-money-payment-failed.body"));
    }

    /**
     * Deadline de paiement (30 min après acceptation) dépassée côté expéditeur :
     * le bid est annulé, la capacité rendue au voyageur.
     */
    public static NotificationText mobileMoneyPaymentExpired(Messages m) {
        return new NotificationText(m.get("notification.mobile-money-payment-expired.title"),
                m.get("notification.mobile-money-payment-expired.body"));
    }

    /** Même événement, côté voyageur : la capacité qu'il avait cédée lui est rendue. */
    public static NotificationText mobileMoneyPaymentExpiredForTraveler(Messages m) {
        return new NotificationText(m.get("notification.mobile-money-payment-expired.traveler.title"),
                m.get("notification.mobile-money-payment-expired.traveler.body"));
    }

    public static NotificationText kycVerified(Messages m) {
        return new NotificationText(m.get("notification.kyc-verified.title"), m.get("notification.kyc-verified.body"));
    }

    public static NotificationText kycActionRequired(Messages m) {
        return new NotificationText(m.get("notification.kyc-action-required.title"),
                m.get("notification.kyc-action-required.body"));
    }

    public static NotificationText kycReset(Messages m) {
        return new NotificationText(m.get("notification.kyc-reset.title"), m.get("notification.kyc-reset.body"));
    }

    public static NotificationText stripeOnboardingIncomplete(Messages m) {
        return new NotificationText(m.get("notification.stripe-onboarding-incomplete.title"),
                m.get("notification.stripe-onboarding-incomplete.body"));
    }

    public static NotificationText cardExpiring(Messages m, String brand, String last4) {
        String b = brand == null || brand.isBlank() ? m.get("notification.card-expiring.default-brand") : truncateAtWord(brand.trim(), 16);
        String l = last4 == null || last4.isBlank() ? "****" : last4;
        return new NotificationText(m.get("notification.card-expiring.title"),
                m.get("notification.card-expiring.body", b, l));
    }

    // ── Annonces et compte ───────────────────────────────────────────────────

    public static NotificationText accountSuspended(Messages m) {
        return new NotificationText(m.get("notification.account-suspended.title"),
                m.get("notification.account-suspended.body"));
    }

    public static NotificationText messagingMuted(Messages m) {
        return new NotificationText(m.get("notification.messaging-muted.title"),
                m.get("notification.messaging-muted.body"));
    }

    /**
     * Avertissement de modération. La note est libre : c'est une annonce, donc
     * le service garde le texte complet dans {@code fullBody} et résume le corps.
     */
    public static NotificationText adminWarning(Messages m, String note) {
        String body = note == null || note.isBlank() ? m.get("notification.admin-warning.default-body") : note.trim();
        return new NotificationText(m.get("notification.admin-warning.title"), body);
    }
}
