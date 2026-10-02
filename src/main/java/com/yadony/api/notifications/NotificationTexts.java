package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.payments.wallet.WalletAmountText;

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

    /**
     * Montant dans SA devise, tel qu'on le montre : « 15 000 F CFA », « 1 250,00 € »,
     * « 45,00 $ ». Décimales et symbole du catalogue {@code SupportedCurrency} (aucune
     * décimale pour XOF/XAF), milliers séparés par une espace insécable : même rendu que le
     * portefeuille ({@link WalletAmountText}). Devise absente : euro. Montant inchangé
     * quelle que soit la langue (D7).
     *
     * <p>Les champs {@code …Eur} des négociations portent un montant dans la devise du
     * trajet ou du fil, pas en euros : on ne leur colle jamais « € » d'office.
     */
    public static String amount(BigDecimal amount, String currency) {
        return amount == null ? "" : WalletAmountText.format(amount, currency);
    }

    /** Rail mobile money : même rendu que {@link #amount(BigDecimal, String)}. */
    public static String mobileMoneyAmount(BigDecimal amount, String currencyCode) {
        return amount(amount, currencyCode);
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

    // ── Colis : déclaration d'absence (no-show) et décision de l'administration ──

    /** L'expéditeur déclaré absent à la remise par le voyageur : il peut contester. */
    public static NotificationText senderNoShowReported(Messages m, int contestationHours) {
        return new NotificationText(m.get("notification.sender-no-show-reported.title"),
                m.get("notification.sender-no-show-reported.body", contestationHours));
    }

    public static NotificationText noShowConfirmedHandover(Messages m) {
        return new NotificationText(m.get("notification.no-show-confirmed.title"),
                m.get("notification.no-show-confirmed.handover.body"));
    }

    public static NotificationText noShowConfirmedDelivery(Messages m) {
        return new NotificationText(m.get("notification.no-show-confirmed.title"),
                m.get("notification.no-show-confirmed.delivery.body"));
    }

    public static NotificationText noShowRejected(Messages m) {
        return new NotificationText(m.get("notification.no-show-rejected.title"),
                m.get("notification.no-show-rejected.body"));
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

    public static NotificationText negotiationStarted(Messages m, BigDecimal proposed, String currency) {
        return new NotificationText(m.get("notification.negotiation-started.title"),
                m.get("notification.negotiation-started.body", amount(proposed, currency)));
    }

    public static NotificationText negotiationCounter(Messages m, BigDecimal newPrice, String currency, int round) {
        return new NotificationText(m.get("notification.counter-offer.title"),
                m.get("notification.negotiation-counter.body", amount(newPrice, currency), round));
    }

    public static NotificationText negotiationAwaitingTrip(Messages m, BigDecimal agreed, String currency) {
        return new NotificationText(m.get("notification.negotiation-awaiting-trip.title"),
                m.get("notification.negotiation-awaiting-trip.body", amount(agreed, currency)));
    }

    /** {@code grossToPay} : ce que l'expéditeur va payer, commission comprise. */
    public static NotificationText negotiationAwaitingPayment(Messages m, BigDecimal grossToPay, String currency) {
        return new NotificationText(m.get("notification.negotiation-awaiting-payment.title"),
                m.get("notification.negotiation-awaiting-payment.body", amount(grossToPay, currency)));
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

    /** {@code net} : ce que touche le voyageur. */
    public static NotificationText requestAcceptedForTraveler(Messages m, BigDecimal net, String currency) {
        return new NotificationText(m.get("notification.request-accepted.traveler.title"),
                m.get("notification.request-accepted.traveler.body", amount(net, currency)));
    }

    /** {@code grossPaid} : ce que l'expéditeur a payé, commission comprise. */
    public static NotificationText requestAcceptedForSender(Messages m, BigDecimal grossPaid, String currency) {
        return new NotificationText(m.get("notification.request-accepted.sender.title"),
                m.get("notification.request-accepted.sender.body", amount(grossPaid, currency)));
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

    /**
     * Demande d'envoi retirée par la modération. {@code reasonCode} est le motif PUBLIC
     * catalogué ({@code AnnouncementRemovalReason}) ; la note interne du modérateur n'entre
     * jamais ici.
     */
    public static NotificationText packageRequestRemoved(Messages m, String reasonCode) {
        String reason = m.get("notification.request-removal-reason." + reasonCode);
        return new NotificationText(m.get("notification.package-request-removed.title"),
                m.get("notification.package-request-removed.body", reason));
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

    /** Revocation par un administrateur : jamais son motif interne, seulement l'action attendue. */
    public static NotificationText kycRevoked(Messages m) {
        return new NotificationText(m.get("notification.kyc-revoked.title"), m.get("notification.kyc-revoked.body"));
    }

    public static NotificationText kycReset(Messages m) {
        return new NotificationText(m.get("notification.kyc-reset.title"), m.get("notification.kyc-reset.body"));
    }

    /**
     * Relance « première action » après KYC. variant : sender-trips, sender-none, traveler-packages,
     * traveler-none, unknown. Les variantes avec offres comptent les trajets ou colis (pluriel .one/.other).
     */
    public static NotificationText firstActionReminder(Messages m, String variant, long count) {
        String prefix = "notification.first-action." + variant;
        boolean counted = "sender-trips".equals(variant) || "traveler-packages".equals(variant);
        String body = counted
                ? m.plural(prefix + ".body", count, String.valueOf(count))
                : m.get(prefix + ".body");
        return new NotificationText(m.get(prefix + ".title"), body);
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

    /** Demande de suppression de compte annulée par l'équipe (compte encore en délai de grâce). */
    public static NotificationText accountDeletionCancelledByAdmin(Messages m) {
        return new NotificationText(m.get("notification.account-deletion-cancelled.title"),
                m.get("notification.account-deletion-cancelled.body"));
    }

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

    /** Signalement traité : un remerciement, jamais la décision ni la sanction. */
    public static NotificationText reportResolved(Messages m) {
        return new NotificationText(m.get("notification.report-resolved.title"), m.get("notification.report-resolved.body"));
    }

    /**
     * Solde corrigé à la main par l'équipe. {@code amountText} est déjà formaté
     * ({@code WalletAmountText#format}) ; le motif interne de la correction n'apparaît
     * jamais ici.
     */
    public static NotificationText walletAdjustedByAdmin(Messages m, boolean credit, String amountText) {
        String key = credit ? "notification.wallet-admin-credit" : "notification.wallet-admin-debit";
        return new NotificationText(m.get(key + ".title"), m.get(key + ".body", amountText));
    }

    // ── Support ──────────────────────────────────────────────────────────────

    /** Réponse du support dans un fil que l'utilisateur connaît déjà. */
    public static NotificationText supportReply(Messages m) {
        return new NotificationText(m.get("notification.support-reply.title"),
                m.get("notification.support-reply.body"));
    }

    /**
     * Premier message d'une conversation ouverte par le support : l'utilisateur
     * n'a rien demandé, le corps cite donc le sujet. On raccourcit le sujet (la
     * variable) au mot pour que la phrase assemblée tienne dans le cap, jamais
     * la phrase elle-même. Sujet vide : phrase sans sujet.
     */
    public static NotificationText supportStarted(Messages m, String subject) {
        String title = m.get("notification.support-started.title");
        if (subject == null || subject.isBlank()) {
            return new NotificationText(title, m.get("notification.support-started.default-body"));
        }
        int room = NotificationCaps.BODY_MAX - m.get("notification.support-started.body", "").length();
        String flat = subject.trim().replaceAll("\\s+", " ");
        return new NotificationText(title, m.get("notification.support-started.body", truncateAtWord(flat, room)));
    }

    /** Report du trajet, vu par l'expéditeur : nouvelle date et motif, puis ce qu'il peut faire. */
    // Le motif n'entre pas dans les 72 caractères du corps : il est dans la conversation et l'app.
    public static NotificationText tripRescheduled(Messages m, java.time.LocalDate newDate,
                                                   boolean decisionRequired) {
        String date = newDate.format(java.time.format.DateTimeFormatter.ofPattern(
                m.get("notification.trip-rescheduled.date-pattern"), m.locale()));
        return new NotificationText(m.get("notification.trip-rescheduled.title"),
                m.get(decisionRequired
                        ? "notification.trip-rescheduled-decision.body"
                        : "notification.trip-rescheduled-info.body", date));
    }

    public static NotificationText tripRescheduleKept(Messages m) {
        return new NotificationText(m.get("notification.trip-reschedule-kept.title"),
                m.get("notification.trip-reschedule-kept.body"));
    }

    public static NotificationText tripRescheduleWithdrawn(Messages m) {
        return new NotificationText(m.get("notification.trip-reschedule-withdrawn.title"),
                m.get("notification.trip-reschedule-withdrawn.body"));
    }

    // ── Destinataire qui suit son colis dans l'app (lot 2) ───────────────────

    /** Colis rattaché au compte du destinataire. Prénom de l'expéditeur seulement. */
    public static NotificationText recipientParcelIncoming(Messages m, String senderFirstName,
                                                           String departureCity, String arrivalCity) {
        String who = senderFirstName == null || senderFirstName.isBlank()
                ? m.get("notification.fallback.sender")
                : shortDisplayName(senderFirstName);
        String body = departureCity == null || arrivalCity == null
                ? m.get("notification.recipient-incoming.no-route.body", who)
                : m.get("notification.recipient-incoming.body", who, departureCity, arrivalCity);
        return new NotificationText(m.get("notification.recipient-incoming.title"), body);
    }

    /** Au destinataire de confiance (invitation acceptée) : colis rattaché d'emblée, simple annonce. */
    public static NotificationText recipientParcelAnnounced(Messages m, String senderFirstName) {
        return new NotificationText(m.get("notification.recipient-announced.title"),
                m.get("notification.recipient-announced.body", who(m, senderFirstName, "notification.fallback.sender")));
    }

    // ── Invitations au carnet de destinataires (lot 4) ───────────────────────

    /** À l'invité : un expéditeur veut l'ajouter à ses destinataires. */
    public static NotificationText callMissed(Messages m, String callerFirstName) {
        return new NotificationText(m.get("notification.call-missed.title"),
                m.get("notification.call-missed.body", who(m, callerFirstName, "notification.fallback.sender")));
    }

    public static NotificationText recipientInvitation(Messages m, String inviterFirstName) {
        return new NotificationText(m.get("notification.recipient-invitation.title"),
                m.get("notification.recipient-invitation.body", who(m, inviterFirstName, "notification.fallback.sender")));
    }

    /** À l'inviteur : l'invité a accepté, ses colis lui seront rattachés directement. */
    public static NotificationText recipientInvitationAccepted(Messages m, String inviteeFirstName) {
        return new NotificationText(m.get("notification.recipient-invitation-accepted.title"),
                m.get("notification.recipient-invitation-accepted.body",
                        who(m, inviteeFirstName, "notification.fallback.recipient")));
    }

    private static String who(Messages m, String firstName, String fallbackKey) {
        return firstName == null || firstName.isBlank() ? m.get(fallbackKey) : shortDisplayName(firstName);
    }

    public static NotificationText recipientParcelDeparted(Messages m) {
        return new NotificationText(m.get("notification.recipient-departed.title"),
                m.get("notification.recipient-departed.body"));
    }

    /** La ville descend dans le corps : jamais de ville dans un titre. */
    public static NotificationText recipientParcelArrived(Messages m, String arrivalCity) {
        String body = arrivalCity == null || arrivalCity.isBlank()
                ? m.get("notification.recipient-arrived.no-city.body")
                : m.get("notification.recipient-arrived.body", arrivalCity);
        return new NotificationText(m.get("notification.recipient-arrived.title"), body);
    }

    public static NotificationText recipientParcelDelivered(Messages m) {
        return new NotificationText(m.get("notification.recipient-delivered.title"),
                m.get("notification.recipient-delivered.body"));
    }

    /** Au destinataire : le transport de son colis est annulé (trajet, expéditeur, absence…). */
    public static NotificationText recipientParcelCancelled(Messages m) {
        return new NotificationText(m.get("notification.recipient-cancelled.title"),
                m.get("notification.recipient-cancelled.body"));
    }

    /** À l'ancien destinataire : l'expéditeur a changé de destinataire, le colis n'est plus pour lui. */
    public static NotificationText recipientParcelReassigned(Messages m) {
        return new NotificationText(m.get("notification.recipient-reassigned.title"),
                m.get("notification.recipient-reassigned.body"));
    }

    /** Au voyageur : l'expéditeur a changé le destinataire d'un colis. */
    public static NotificationText recipientChanged(Messages m) {
        return new NotificationText(m.get("notification.recipient-changed.title"),
                m.get("notification.recipient-changed.body"));
    }

    /** Au destinataire : le voyageur a reporté le trajet de son colis. */
    public static NotificationText recipientParcelRescheduled(Messages m) {
        return new NotificationText(m.get("notification.recipient-rescheduled.title"),
                m.get("notification.recipient-rescheduled.body"));
    }

    /** Au destinataire : le voyageur a modifié les instructions de retrait après l'arrivée. */
    public static NotificationText recipientPickupUpdated(Messages m) {
        return new NotificationText(m.get("notification.recipient-pickup-updated.title"),
                m.get("notification.recipient-pickup-updated.body"));
    }

    /** À l'expéditeur : le destinataire a confirmé, il suit le colis dans l'app. */
    public static NotificationText recipientConfirmed(Messages m, String recipientFirstName) {
        String who = recipientFirstName == null || recipientFirstName.isBlank()
                ? m.get("notification.fallback.recipient")
                : shortDisplayName(recipientFirstName);
        return new NotificationText(m.get("notification.recipient-confirmed.title"),
                m.get("notification.recipient-confirmed.body", who));
    }

    /** À l'expéditeur : le titulaire du numéro dit que le colis n'est pas pour lui. */
    public static NotificationText recipientDeclined(Messages m) {
        return new NotificationText(m.get("notification.recipient-declined.title"),
                m.get("notification.recipient-declined.body"));
    }
}
