package com.yadony.api.matching.reception;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.matching.events.RecipientReplacementRequestedEvent;
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
 * Le destinataire a refusé le colis (ou s'en est retiré) : le voyageur demande à
 * l'expéditeur d'en désigner un autre. Une demande toutes les 12 h par colis, comptée
 * dans {@code audit_log}. Le colis reste remettable avec le code de retrait si
 * l'expéditeur ne fait rien.
 */
@Service
public class RecipientReplacementService {

    /** Action journalisée, relue par {@link BidRecipientLinkRepository#findLastReplacementRequestAt}. */
    public static final String AUDIT_ACTION = "RECIPIENT_REPLACEMENT_REQUESTED";

    /** Délai minimal entre deux demandes pour un même refus. */
    static final Duration COOLDOWN = Duration.ofHours(12);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final BidRecipientLinkRepository linkRepository;
    private final BidService bidService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public RecipientReplacementService(BidRepository bidRepository,
                                       AnnouncementRepository announcementRepository,
                                       UserRepository userRepository,
                                       BidRecipientLinkRepository linkRepository,
                                       BidService bidService,
                                       AuditService auditService,
                                       ApplicationEventPublisher eventPublisher) {
        this(bidRepository, announcementRepository, userRepository, linkRepository, bidService,
                auditService, eventPublisher, Clock.systemUTC());
    }

    RecipientReplacementService(BidRepository bidRepository,
                                AnnouncementRepository announcementRepository,
                                UserRepository userRepository,
                                BidRecipientLinkRepository linkRepository,
                                BidService bidService,
                                AuditService auditService,
                                ApplicationEventPublisher eventPublisher,
                                Clock clock) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.linkRepository = linkRepository;
        this.bidService = bidService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Le verrou pessimiste sur le colis sérialise deux demandes simultanées : la seconde
     * lit la trace d'audit laissée par la première et tombe sous le délai.
     */
    @Transactional
    public BidResponse requestReplacement(UUID bidId, String firebaseUid) {
        UserEntity caller = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "bid-not-found", "problem.bid-not-found.introuvable"));
        UUID travelerId = announcementRepository.findById(bid.getAnnouncementId())
                .map(AnnouncementEntity::getTravelerId)
                .orElse(null);
        if (travelerId == null || !travelerId.equals(caller.getId())) {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "forbidden", "problem.forbidden.seul-voyageur-colis-demander");
        }
        if (!RecipientChangeService.CHANGEABLE.contains(bid.getStatus())) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "recipient-replacement-not-allowed", "problem.recipient-replacement-not-allowed");
        }
        BidRecipientLinkEntity link = linkRepository.findByBidId(bidId)
                .filter(l -> l.getStatus() == ReceptionLinkStatus.DECLINED)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.CONFLICT, "recipient-not-declined", "problem.recipient-not-declined"));

        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        Optional<LocalDateTime> last = linkRepository.lastReplacementRequestAt(link);
        if (last.isPresent() && last.get().plus(COOLDOWN).isAfter(now)) {
            OffsetDateTime nextAllowedAt = last.get().plus(COOLDOWN).atOffset(ZoneOffset.UTC);
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "recipient-replacement-too-soon", "problem.recipient-replacement-too-soon", Map.of("nextRequestAllowedAt", nextAllowedAt.toString()));
        }

        auditService.log("BID", bidId, AUDIT_ACTION, caller.getId(),
                Map.of("bidId", bidId.toString(), "bidStatus", bid.getStatus().name()));
        eventPublisher.publishEvent(new RecipientReplacementRequestedEvent(bidId, bid.getSenderId()));
        return bidService.getBidAfterOwnMutation(bidId, firebaseUid);
    }
}
