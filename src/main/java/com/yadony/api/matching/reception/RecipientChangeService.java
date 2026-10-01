package com.yadony.api.matching.reception;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.Msisdn;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.ArrivalRules;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.PickupCodes;
import com.yadony.api.matching.RevokedTrackingTokenEntity;
import com.yadony.api.matching.RevokedTrackingTokenRepository;
import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.matching.events.BidRecipientChangedEvent;
import com.yadony.api.matching.reception.dto.ChangeRecipientRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * L'expéditeur change le destinataire de son colis, de l'acceptation jusqu'à la remise.
 *
 * <p>Même numéro (après normalisation E.164) : seul le nom change. Numéro différent :
 * l'ancien destinataire perd tout accès. Le lien de suivi public est révoqué et
 * remplacé, le code de retrait régénéré (hors quota de régénérations de l'expéditeur)
 * et masqué de la page publique, le rattachement à son compte soft-deleted. Les
 * notifications et le rattachement du nouveau numéro suivent la validation
 * ({@link BidRecipientChangedEvent}).
 */
@Service
public class RecipientChangeService {

    /** Statuts où le colis n'est pas encore remis au destinataire. */
    static final Set<BidStatus> CHANGEABLE = EnumSet.of(
            BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED);

    /** Liens dont le titulaire a été prévenu du colis : il doit savoir qu'il n'est plus pour lui. */
    private static final Set<ReceptionLinkStatus> NOTIFIED_LINKS =
            EnumSet.of(ReceptionLinkStatus.PENDING, ReceptionLinkStatus.CONFIRMED);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final BidRecipientLinkRepository linkRepository;
    private final RevokedTrackingTokenRepository revokedTokenRepository;
    private final BidService bidService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public RecipientChangeService(BidRepository bidRepository,
                                  AnnouncementRepository announcementRepository,
                                  UserRepository userRepository,
                                  BidRecipientLinkRepository linkRepository,
                                  RevokedTrackingTokenRepository revokedTokenRepository,
                                  BidService bidService,
                                  AuditService auditService,
                                  ApplicationEventPublisher eventPublisher) {
        this(bidRepository, announcementRepository, userRepository, linkRepository, revokedTokenRepository,
                bidService, auditService, eventPublisher, Clock.systemUTC());
    }

    RecipientChangeService(BidRepository bidRepository,
                           AnnouncementRepository announcementRepository,
                           UserRepository userRepository,
                           BidRecipientLinkRepository linkRepository,
                           RevokedTrackingTokenRepository revokedTokenRepository,
                           BidService bidService,
                           AuditService auditService,
                           ApplicationEventPublisher eventPublisher,
                           Clock clock) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.linkRepository = linkRepository;
        this.revokedTokenRepository = revokedTokenRepository;
        this.bidService = bidService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Le verrou pessimiste sur le colis sérialise deux changements simultanés : le second
     * lit l'état laissé par le premier (nouveau numéro, nouveau jeton).
     */
    @Transactional
    public BidResponse changeRecipient(UUID bidId, String firebaseUid, ChangeRecipientRequest request) {
        UserEntity caller = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found", "Bid introuvable"));
        if (!caller.getId().equals(bid.getSenderId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul l'expéditeur peut modifier le destinataire");
        }
        if (!CHANGEABLE.contains(bid.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "recipient-change-not-allowed",
                    "Recipient Change Not Allowed",
                    "Le colis a déjà été remis ou n'est plus en cours : le destinataire ne peut plus changer");
        }

        String newName = request.recipientName().trim();
        String newPhone = request.recipientPhone();
        Optional<String> currentPhone = ReceptionPhones.toE164(bid.getRecipientPhone());

        if (currentPhone.isPresent() && currentPhone.get().equals(newPhone)) {
            bid.setRecipientName(newName);
            bidRepository.save(bid);
            auditService.log("BID", bidId, "RECIPIENT_NAME_UPDATED", caller.getId(),
                    Map.of("bidId", bidId.toString()));
            return bidService.getBidAfterOwnMutation(bidId, firebaseUid);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        String previousPhone = bid.getRecipientPhone();

        // a. Le lien public de l'ancien destinataire cesse de valoir.
        String previousToken = bid.getTrackingToken();
        if (previousToken != null) {
            revokedTokenRepository.save(new RevokedTrackingTokenEntity(previousToken, bidId,
                    RevokedTrackingTokenEntity.Reason.RECIPIENT_CHANGED, now));
        }
        bid.setTrackingToken(UUID.randomUUID().toString());

        // b. L'ancien destinataire connaît le code : il change, sans entamer le quota de
        //    régénérations de l'expéditeur, et quitte la page publique.
        boolean codeRegenerated = bid.getConfirmationCode() != null;
        if (codeRegenerated) {
            AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                    .orElseThrow(() -> new YadonyBusinessException(
                            HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                            "Annonce introuvable"));
            bid.setConfirmationCode(PickupCodes.newCode());
            bid.setConfirmationCodeAttempts(0);
            bid.setConfirmationCodeExpiry(ArrivalRules.pickupCodeExpiry(announcement));
        }
        bid.setConfirmationCodePublicEnabled(false);

        // c. L'ancien rattachement disparaît ; un titulaire déjà prévenu le sera du retrait.
        UUID previousRecipientId = null;
        String previousLinkStatus = null;
        Optional<BidRecipientLinkEntity> link = linkRepository.findByBidId(bidId);
        if (link.isPresent()) {
            BidRecipientLinkEntity l = link.get();
            previousLinkStatus = l.getStatus().name();
            if (NOTIFIED_LINKS.contains(l.getStatus())) {
                previousRecipientId = l.getRecipientUserId();
            }
            l.softDelete();
            linkRepository.save(l);
        }

        bid.setRecipientName(newName);
        bid.setRecipientPhone(newPhone);
        bidRepository.save(bid);

        // f. Jamais de numéro en clair dans le journal (clés hors liste de masquage
        //    d'AuditService, qui effacerait aussi la forme masquée).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bidId", bidId.toString());
        payload.put("previousNumberMasked", maskedOrUnknown(previousPhone));
        payload.put("newNumberMasked", maskedOrUnknown(newPhone));
        payload.put("codeRegenerated", String.valueOf(codeRegenerated));
        payload.put("trackingTokenRevoked", String.valueOf(previousToken != null));
        if (previousLinkStatus != null) {
            payload.put("previousLinkStatus", previousLinkStatus);
        }
        auditService.log("BID", bidId, "RECIPIENT_CHANGED", caller.getId(), payload);

        // c, d, e. Notifications et rattachement du nouveau numéro, après validation.
        UUID travelerId = announcementRepository.findById(bid.getAnnouncementId())
                .map(AnnouncementEntity::getTravelerId)
                .orElse(null);
        eventPublisher.publishEvent(new BidRecipientChangedEvent(bidId, previousRecipientId, travelerId));

        return bidService.getBidAfterOwnMutation(bidId, firebaseUid);
    }

    /** Numéro masqué ({@code +221 •••• 67}), ou « inconnu » s'il n'est pas lisible. */
    static String maskedOrUnknown(String phone) {
        if (phone == null) {
            return "inconnu";
        }
        try {
            return Msisdn.mask(phone);
        } catch (IllegalArgumentException e) {
            return "inconnu";
        }
    }
}
