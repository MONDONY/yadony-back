package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.ContactWindow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Qui peut envoyer une photo dans une conversation, et quand (FLUTTER-B4).
 *
 * <p>Même fenêtre que les appels ({@link ContactWindow}) : du bid accepté ET payé
 * (ACCEPTED, HANDED_OVER, IN_TRANSIT, ARRIVED) jusqu'à la livraison + {@code graceDays}
 * jours (COMPLETED), et pendant le retour d'un colis annulé après sa remise (FLUTTER-FM). Un bid AWAITING_PAYMENT est exclu par construction : le mobile money
 * publie {@code BidAcceptedEvent} avant le paiement, seul le statut ACCEPTED atteste que
 * l'argent est pris, quel que soit le mode (carte, espèces avec commission, mobile money).
 *
 * <p>Vaut pour les deux types de conversation d'un bid (expéditeur ↔ voyageur et
 * destinataire ↔ voyageur). Refusée sur une conversation fermée, en lecture seule ou
 * supprimée, entre comptes masqués l'un pour l'autre, ou pour un compte coupé de messagerie.
 */
@Component
public class ConversationMediaPolicy {

    /** Raison d'un refus, journalisée et utile aux tests ; le client ne reçoit que {@code media-not-allowed}. */
    public enum Denial { CONVERSATION_UNAVAILABLE, OUT_OF_WINDOW, BLOCKED, MESSAGING_MUTED }

    public static final String ERROR_CODE = "media-not-allowed";

    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final BlockVisibility blockVisibility;
    private final int graceDays;
    private final Clock clock;

    @Autowired
    public ConversationMediaPolicy(BidRepository bidRepository, UserRepository userRepository,
                                   BlockVisibility blockVisibility,
                                   @Value("${yadony.calls.delivery-grace-days:3}") int graceDays) {
        this(bidRepository, userRepository, blockVisibility, graceDays, Clock.systemUTC());
    }

    ConversationMediaPolicy(BidRepository bidRepository, UserRepository userRepository,
                            BlockVisibility blockVisibility, int graceDays, Clock clock) {
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.blockVisibility = blockVisibility;
        this.graceDays = graceDays;
        this.clock = clock;
    }

    /** Variante qui charge le bid et l'utilisateur. */
    public Optional<Denial> check(ConversationEntity conv, UUID userId) {
        BidEntity bid = conv.getBidId() == null ? null : bidRepository.findById(conv.getBidId()).orElse(null);
        return check(conv, userId, bid, null);
    }

    /**
     * Évaluation sur le bid chargé : fenêtre de contact complète ({@link ContactWindow#isOpen(BidEntity,
     * int, LocalDateTime)}), retour d'un colis annulé après sa remise compris (FLUTTER-FM).
     */
    public Optional<Denial> check(ConversationEntity conv, UUID userId, BidEntity bid, UserEntity user) {
        return check(conv, userId, bid, user, false);
    }

    /**
     * Même évaluation ; {@code visibilityChecked} : la liste des conversations a déjà écarté les
     * contreparties masquées, le contrôle de blocage (symétrique) est sauté.
     */
    public Optional<Denial> check(ConversationEntity conv, UUID userId, BidEntity bid, UserEntity user,
                                  boolean visibilityChecked) {
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        // Le retour ne concerne que l'expéditeur et le voyageur : la conversation destinataire
        // garde la fenêtre du seul statut.
        boolean open = conv != null && conv.isRecipientConversation()
                ? bid != null && ContactWindow.isOpen(bid.getStatus(), bid.getDeliveredAt(), graceDays, now)
                : ContactWindow.isOpen(bid, graceDays, now);
        return evaluate(conv, userId, open, user, visibilityChecked);
    }

    /**
     * Évaluation complète. Les filtres sans requête passent d'abord : la liste des
     * conversations l'appelle pour chaque fil, seuls ceux d'une commande en cours lisent
     * l'utilisateur et les blocages.
     *
     * @param user l'utilisateur s'il est déjà chargé, sinon {@code null} (lu ici au besoin)
     */
    public Optional<Denial> check(ConversationEntity conv, UUID userId, BidStatus bidStatus,
                                  LocalDateTime deliveredAt, UserEntity user) {
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        return evaluate(conv, userId, ContactWindow.isOpen(bidStatus, deliveredAt, graceDays, now), user, false);
    }

    /**
     * @param visibilityChecked l'appelant a déjà écarté les contreparties masquées (liste des
     *                          conversations) : le contrôle de blocage, symétrique, est sauté.
     */
    private Optional<Denial> evaluate(ConversationEntity conv, UUID userId, boolean windowOpen, UserEntity user,
                                      boolean visibilityChecked) {
        if (conv == null || userId == null || conv.getDeletedAt() != null || conv.isClosed()
                || !isParticipant(conv, userId)
                || conv.isDeletedByUser(userId) || conv.isReadOnlyFor(userId)) {
            return Optional.of(Denial.CONVERSATION_UNAVAILABLE);
        }
        if (!windowOpen) {
            return Optional.of(Denial.OUT_OF_WINDOW);
        }
        UUID otherId = userId.equals(conv.participantAId()) ? conv.getTravelerId() : conv.participantAId();
        if (!visibilityChecked
                && (blockVisibility.isHidden(userId, otherId) || blockVisibility.isHidden(otherId, userId))) {
            return Optional.of(Denial.BLOCKED);
        }
        UserEntity actor = user != null ? user : userRepository.findById(userId).orElse(null);
        if (actor == null || actor.isMessagingMuted(clock.instant())) {
            return Optional.of(Denial.MESSAGING_MUTED);
        }
        return Optional.empty();
    }

    /** Lève un 403 {@code media-not-allowed} si la politique refuse. */
    public void assertAllowed(ConversationEntity conv, UserEntity user) {
        BidEntity bid = conv.getBidId() == null ? null : bidRepository.findById(conv.getBidId()).orElse(null);
        Optional<Denial> denial = check(conv, user.getId(), bid, user);
        if (denial.isPresent()) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, ERROR_CODE, "Media Not Allowed",
                    "Les photos sont disponibles une fois la demande acceptée et payée, "
                            + "jusqu'à " + graceDays + " jours après la livraison.");
        }
    }

    private static boolean isParticipant(ConversationEntity conv, UUID userId) {
        return userId.equals(conv.participantAId()) || userId.equals(conv.getTravelerId());
    }
}
