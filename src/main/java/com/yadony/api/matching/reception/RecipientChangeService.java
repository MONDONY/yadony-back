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
import com.yadony.api.matching.BidRecipientDesignation;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.PickupCodes;
import com.yadony.api.matching.TripDelayRepository;
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

    /** Retards annoncés (fin de fourchette hors bateau, lue au renouvellement du code) ; absent hors Spring. */
    private TripDelayRepository tripDelayRepository;

    @Autowired
    void setTripDelayRepository(TripDelayRepository tripDelayRepository) {
        this.tripDelayRepository = tripDelayRepository;
    }

    /** Désignation par identifiant (compte Yadony) ; absente hors Spring, seul le mode nom + numéro marche alors. */
    private BidRecipientDesignation designation;

    @Autowired
    void setDesignation(BidRecipientDesignation designation) {
        this.designation = designation;
    }

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
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "bid-not-found", "problem.bid-not-found.introuvable"));
        if (!caller.getId().equals(bid.getSenderId())) {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "forbidden", "problem.forbidden.seul-expediteur-modifier-destinataire");
        }
        if (!CHANGEABLE.contains(bid.getStatus())) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "recipient-change-not-allowed", "problem.recipient-change-not-allowed");
        }

        boolean byUsername = !isBlank(request.recipientUsername());
        String newName = isBlank(request.recipientName()) ? null : request.recipientName().trim();
        String newPhone = isBlank(request.recipientPhone()) ? null : request.recipientPhone();
        UUID previousAccountId = bid.getRecipientUserId();
        String previousPhone = bid.getRecipientPhone();

        if (byUsername) {
            UUID travelerId = announcementRepository.findById(bid.getAnnouncementId())
                    .map(AnnouncementEntity::getTravelerId).orElse(null);
            // Pose recipientUserId, "@identifiant" et un numéro nul ; refuse le mélange des modes, le voyageur,
            // un compte inconnu ou bloqué, soi-même (aucune écriture en cas de refus).
            designation.apply(bid, caller.getId(), travelerId, request.recipientName(), request.recipientPhone(),
                    request.recipientUsername());
            if (bid.getRecipientUserId().equals(previousAccountId)) {
                // Même compte : seul le libellé est rafraîchi, rien n'est renouvelé.
                bidRepository.save(bid);
                auditService.log("BID", bidId, "RECIPIENT_NAME_UPDATED", caller.getId(),
                        Map.of("bidId", bidId.toString()));
                return bidService.getBidAfterOwnMutation(bidId, firebaseUid);
            }
        } else {
            if (newName == null || newPhone == null) {
                throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "recipient-required",
                        "problem.recipient-required");
            }
            Optional<String> currentPhone = ReceptionPhones.toE164(previousPhone);
            if (currentPhone.isPresent() && currentPhone.get().equals(newPhone)) {
                bid.setRecipientName(newName);
                bidRepository.save(bid);
                auditService.log("BID", bidId, "RECIPIENT_NAME_UPDATED", caller.getId(),
                        Map.of("bidId", bidId.toString()));
                return bidService.getBidAfterOwnMutation(bidId, firebaseUid);
            }
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
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
                    .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "announcement-not-found", "problem.announcement-not-found"));
            bid.setConfirmationCode(PickupCodes.newCode());
            bid.setConfirmationCodeAttempts(0);
            bid.setConfirmationCodeExpiry(ArrivalRules.renewedPickupCodeExpiry(announcement, tripDelayRepository));
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

        if (!byUsername) {
            // Retour au mode numéro : le compte désigné perd l'accès sur-le-champ.
            bid.setRecipientName(newName);
            bid.setRecipientPhone(newPhone);
            bid.setRecipientUserId(null);
        }
        bidRepository.save(bid);

        // f. Jamais de numéro en clair dans le journal (clés hors liste de masquage
        //    d'AuditService, qui effacerait aussi la forme masquée).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bidId", bidId.toString());
        payload.put("previousNumberMasked", maskedOrUnknown(previousPhone));
        if (byUsername) {
            payload.put("newRecipientUserId", bid.getRecipientUserId().toString());
        } else {
            payload.put("newNumberMasked", maskedOrUnknown(newPhone));
        }
        if (previousAccountId != null) {
            payload.put("previousRecipientUserId", previousAccountId.toString());
        }
        payload.put("codeRegenerated", String.valueOf(codeRegenerated));
        payload.put("trackingTokenRevoked", String.valueOf(previousToken != null));
        if (previousLinkStatus != null) {
            payload.put("previousLinkStatus", previousLinkStatus);
        }
        auditService.log("BID", bidId, "RECIPIENT_CHANGED", caller.getId(), payload);
        if (byUsername) {
            designation.audit(bid, caller.getId());
        }

        // c, d, e. Notifications et rattachement du nouveau numéro, après validation.
        UUID travelerId = announcementRepository.findById(bid.getAnnouncementId())
                .map(AnnouncementEntity::getTravelerId)
                .orElse(null);
        eventPublisher.publishEvent(new BidRecipientChangedEvent(bidId, previousRecipientId, travelerId));

        return bidService.getBidAfterOwnMutation(bidId, firebaseUid);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
