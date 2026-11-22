package com.yadony.api.cancellation.fee;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TripRescheduleRules;
import com.yadony.api.payments.cash.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Seule décision de « ce colis peut-il porter des frais d'annulation, et à quel taux maintenant » : l'enregistrement de
 * la retenue ({@link SenderCancellationFeeRecorder}, au moment de la faute) et l'aperçu montré à l'expéditeur avant
 * d'annuler ({@link CancellationFeePreviewService}) passent tous deux par ici, et ne peuvent donc jamais diverger.
 *
 * <p>Un colis est couvert quand, à la fois :
 * <ul>
 *   <li>l'interrupteur est allumé ({@code yadony.cancellation-fee.enabled}) ET une date d'effet est posée
 *       ({@code effective-from}) : allumé sans date, il reste éteint, avec un avertissement au démarrage ;</li>
 *   <li>le colis a été CRÉÉ à la date d'effet ou après (« nouveaux colis seulement » : un colis plus ancien a été payé
 *       sous des CGU sans frais, il n'en porte jamais, même annulé plus tard) ;</li>
 *   <li>il est payé en ligne, par carte ou mobile money (jamais espèces, ni les modes legacy) ;</li>
 *   <li>son trajet est connu (le taux dépend du départ) ;</li>
 *   <li>aucun report du trajet n'attend la réponse de l'expéditeur ({@link TripRescheduleRules#decisionOpen}) : le
 *       voyageur a déplacé son départ, l'expéditeur peut se retirer sans frais tant que sa décision est ouverte, par le
 *       bouton du report ({@code RescheduleDecisionService}, qui n'enregistre jamais de retenue) comme par l'annulation
 *       ordinaire du colis.</li>
 * </ul>
 * La faute elle-même (annulation par l'expéditeur d'un colis accepté ou remis, absence confirmée) est l'affaire des
 * appelants.
 *
 * <p>Ne décide que des NOUVELLES retenues : éteindre l'interrupteur n'arrête ni le règlement d'une retenue déjà due
 * (remboursement partiel, {@code payments}) ni le versement au voyageur d'une retenue réglée. Ces chemins ne lisent pas
 * l'interrupteur, exprès : l'argent déjà promis doit arriver.
 */
@Component
public class CancellationFeeEligibility {

    private static final Logger log = LoggerFactory.getLogger(CancellationFeeEligibility.class);

    /**
     * @param trip           trajet du colis
     * @param rate           taux du barème à l'instant évalué (0 dans la fenêtre gratuite)
     * @param nextIncreaseAt prochaine hausse du taux ({@link CancellationFeeTiers#nextIncreaseAt}), nul au palier max
     */
    public record Assessment(AnnouncementEntity trip, BigDecimal rate, Instant nextIncreaseAt) {}

    private final CancellationFeeProperties properties;
    private final AnnouncementRepository announcements;

    public CancellationFeeEligibility(CancellationFeeProperties properties, AnnouncementRepository announcements) {
        this.properties = properties;
        this.announcements = announcements;
        if (properties.enabled() && properties.effectiveFrom() == null) {
            log.warn("Frais d'annulation : yadony.cancellation-fee.enabled=true sans "
                    + "yadony.cancellation-fee.effective-from (YADONY_CANCELLATION_FEE_EFFECTIVE_FROM). Aucun frais ne "
                    + "sera retenu tant que la date d'effet n'est pas posée.");
        } else if (properties.enabled()) {
            // WARN exprès : de l'argent est retenu aux expéditeurs à partir de cet instant, visible dans tout journal.
            log.warn("Frais d'annulation ALLUMÉS : retenue sur les colis créés à partir de {}", properties.effectiveFrom());
        } else {
            log.info("Frais d'annulation ÉTEINTS : aucune nouvelle retenue");
        }
    }

    /** Interrupteur allumé ET date d'effet posée. */
    public boolean active() {
        return properties.enabled() && properties.effectiveFrom() != null;
    }

    /**
     * @param statusAtFault statut du colis au moment de la faute, avant l'annulation : l'enregistrement de la retenue
     *                      lit un colis déjà annulé, l'aperçu un colis encore accepté ou remis
     * @return le trajet, le taux du barème à {@code now} et sa prochaine hausse si ce colis est couvert ; vide sinon
     *         (interrupteur éteint, colis antérieur à la date d'effet ou jamais enregistré, espèces, trajet introuvable,
     *         réponse à un report du trajet encore ouverte)
     */
    public Optional<Assessment> assess(BidEntity bid, BidStatus statusAtFault, Instant now) {
        if (!active() || !createdSinceActivation(bid) || !paidOnline(bid.getPaymentMethod())) {
            return Optional.empty();
        }
        Optional<AnnouncementEntity> trip = Optional.ofNullable(bid.getAnnouncementId()).flatMap(announcements::findById);
        if (trip.isEmpty()) {
            log.warn("Frais d'annulation non calculables : trajet du colis {} introuvable, aucun frais", bid.getId());
            return Optional.empty();
        }
        if (TripRescheduleRules.decisionOpen(bid, statusAtFault, trip.get(), now)) {
            log.info("Aucun frais d'annulation sur le colis {} : report du trajet en attente de la réponse de "
                    + "l'expéditeur", bid.getId());
            return Optional.empty();
        }
        return Optional.of(new Assessment(trip.get(), CancellationFeeTiers.rateFor(trip.get(), now, properties),
                CancellationFeeTiers.nextIncreaseAt(trip.get(), now, properties)));
    }

    /** Créé à la date d'effet ou après. Sans date de création (jamais enregistré) : non couvert, en faveur de l'expéditeur. */
    private boolean createdSinceActivation(BidEntity bid) {
        return bid.getCreatedAt() != null
                && !bid.getCreatedAt().toInstant(ZoneOffset.UTC).isBefore(properties.effectiveFrom());
    }

    /** Argent plateforme à retenir : carte (séquestre Stripe) ou mobile money. Jamais espèces ni legacy. */
    private static boolean paidOnline(PaymentMethod method) {
        return method != null && (method.isCardEscrow() || method.isMobileMoney());
    }
}
