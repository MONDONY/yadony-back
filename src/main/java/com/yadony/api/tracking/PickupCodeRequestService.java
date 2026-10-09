package com.yadony.api.tracking;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.PickupCodes;
import com.yadony.api.tracking.dto.PickupCodeRequestResponse;
import com.yadony.api.tracking.events.ConfirmationCodeRequestedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Le voyageur demande à l'expéditeur un nouveau code de retrait (FLUTTER-G2) : le code a
 * été bloqué après trois essais faux, ou a expiré, et seul l'expéditeur peut en générer un
 * ({@code POST /tracking/{bidId}/refresh-code}). Sans ce bouton, le voyageur devant le
 * destinataire n'avait que « Demandez à l'expéditeur » et aucun moyen de le prévenir.
 *
 * <p>Une demande au plus toutes les 15 minutes par colis, comptée dans {@code audit_log}
 * (aucune colonne dédiée) : l'expéditeur n'est pas relancé en boucle.
 */
@Service
public class PickupCodeRequestService {

    /** Entité et action journalisées, relues pour la limite de fréquence. */
    static final String AUDIT_ENTITY = "TRACKING_CONFIRMATION_CODE";
    static final String AUDIT_ACTION = "CODE_REQUESTED_BY_TRAVELER";

    /** Délai minimal entre deux demandes pour un même colis. */
    static final Duration COOLDOWN = Duration.ofMinutes(15);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditLogRepository auditLogRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MessagesResolver messagesResolver;
    private final Clock clock;

    @Autowired
    public PickupCodeRequestService(BidRepository bidRepository,
                                    AnnouncementRepository announcementRepository,
                                    UserRepository userRepository,
                                    AuditService auditService,
                                    AuditLogRepository auditLogRepository,
                                    ApplicationEventPublisher eventPublisher,
                                    MessagesResolver messagesResolver) {
        this(bidRepository, announcementRepository, userRepository, auditService, auditLogRepository,
                eventPublisher, messagesResolver, Clock.systemUTC());
    }

    PickupCodeRequestService(BidRepository bidRepository,
                             AnnouncementRepository announcementRepository,
                             UserRepository userRepository,
                             AuditService auditService,
                             AuditLogRepository auditLogRepository,
                             ApplicationEventPublisher eventPublisher,
                             MessagesResolver messagesResolver,
                             Clock clock) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.auditLogRepository = auditLogRepository;
        this.eventPublisher = eventPublisher;
        this.messagesResolver = messagesResolver;
        this.clock = clock;
    }

    /**
     * Le verrou pessimiste sur le colis sérialise deux demandes simultanées : la seconde
     * lit la trace d'audit laissée par la première et tombe sous le délai.
     */
    @Transactional
    public PickupCodeRequestResponse requestNewCode(UUID bidId, String firebaseUid) {
        UserEntity caller = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Transaction introuvable"));
        UUID travelerId = announcementRepository.findById(bid.getAnnouncementId())
                .map(AnnouncementEntity::getTravelerId)
                .orElse(null);
        if (travelerId == null || !travelerId.equals(caller.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul le voyageur du colis peut demander un nouveau code de retrait");
        }
        var m = messagesResolver.forRequest();
        if (!PickupCodes.IN_TRAVELER_HANDS.contains(bid.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "code-request-not-allowed",
                    "Code Request Not Allowed", m.get("problem.code-request-not-allowed"));
        }

        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        if (!PickupCodes.renewalNeeded(bid, now)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "code-still-valid",
                    "Code Still Valid", m.get("problem.code-still-valid"));
        }

        Optional<LocalDateTime> last = auditLogRepository
                .findFirstByEntityTypeAndEntityIdAndActionOrderByCreatedAtDescIdDesc(
                        AUDIT_ENTITY, bidId, AUDIT_ACTION)
                .map(AuditLogEntity::getCreatedAt);
        if (last.isPresent() && last.get().plus(COOLDOWN).isAfter(now)) {
            LocalDateTime nextAllowed = last.get().plus(COOLDOWN);
            long seconds = Math.max(1, Duration.between(now, nextAllowed).toSeconds());
            long minutes = (seconds + 59) / 60;
            throw new YadonyBusinessException(HttpStatus.TOO_MANY_REQUESTS, "code-request-too-soon",
                    "Code Request Too Soon", m.get("problem.code-request-too-soon", minutes),
                    Map.of("nextRequestAllowedAt", nextAllowed.atOffset(ZoneOffset.UTC).toString(),
                            "retryAfterSeconds", seconds));
        }

        auditService.log(AUDIT_ENTITY, bidId, AUDIT_ACTION, caller.getId(),
                Map.of("bidId", bidId.toString(),
                        "bidStatus", bid.getStatus().name(),
                        "reason", bid.getConfirmationCode() == null ? "CODE_MISSING" : "CODE_EXPIRED"));
        eventPublisher.publishEvent(new ConfirmationCodeRequestedEvent(bidId, bid.getSenderId()));

        OffsetDateTime requestedAt = now.atOffset(ZoneOffset.UTC);
        return new PickupCodeRequestResponse(requestedAt, requestedAt.plus(COOLDOWN));
    }
}
