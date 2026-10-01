package com.yadony.api.matching.reception;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationTexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Rattache un colis au compte de son destinataire, par le numéro saisi par l'expéditeur.
 *
 * <p>Deux entrées : {@link #linkIfPossible} à l'acceptation du colis (push au
 * destinataire), et {@link #catchUp} quand le destinataire ouvre ses réceptions après
 * avoir installé l'app (sans push : il est déjà dans l'app). Chacune s'exécute dans sa
 * propre transaction, pour qu'un échec n'emporte jamais l'opération qui l'a déclenchée.
 */
@Service
public class ReceptionLinker {

    private static final Logger log = LoggerFactory.getLogger(ReceptionLinker.class);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final BidRecipientLinkRepository linkRepository;
    private final FirebaseContactService firebaseContact;
    private final BlockVisibility blockVisibility;
    private final NotificationDispatcher notificationDispatcher;
    private final AuditService auditService;

    public ReceptionLinker(BidRepository bidRepository,
                           AnnouncementRepository announcementRepository,
                           UserRepository userRepository,
                           BidRecipientLinkRepository linkRepository,
                           FirebaseContactService firebaseContact,
                           BlockVisibility blockVisibility,
                           NotificationDispatcher notificationDispatcher,
                           AuditService auditService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.linkRepository = linkRepository;
        this.firebaseContact = firebaseContact;
        this.blockVisibility = blockVisibility;
        this.notificationDispatcher = notificationDispatcher;
        this.auditService = auditService;
    }

    /**
     * Crée le lien PENDING et prévient le destinataire, si le colis est accepté, que son
     * numéro est international et qu'il appartient à un compte tiers non bloqué par
     * l'expéditeur. Idempotent : un colis déjà rattaché est laissé tel quel.
     *
     * @return le lien créé, vide si aucun rattachement n'a eu lieu
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<BidRecipientLinkEntity> linkIfPossible(UUID bidId) {
        BidEntity bid = bidRepository.findById(bidId).orElse(null);
        if (bid == null || !BidStatus.IN_FLIGHT.contains(bid.getStatus())
                || bid.getTrackingToken() == null || linkRepository.existsByBidId(bidId)) {
            return Optional.empty();
        }
        Optional<String> e164 = ReceptionPhones.toE164(bid.getRecipientPhone());
        if (e164.isEmpty()) {
            return Optional.empty();
        }
        Optional<UserEntity> recipient = firebaseContact.findUidByPhone(e164.get())
                .flatMap(userRepository::findByFirebaseUid);
        if (recipient.isEmpty()) {
            return Optional.empty();
        }
        UUID recipientId = recipient.get().getId();
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
        UUID travelerId = announcement != null ? announcement.getTravelerId() : null;
        if (recipientId.equals(bid.getSenderId()) || recipientId.equals(travelerId)
                || blockVisibility.isHidden(bid.getSenderId(), recipientId)) {
            return Optional.empty();
        }

        BidRecipientLinkEntity link = save(bid, recipientId, "LINKED_ON_ACCEPT");
        notifyIncoming(bid, announcement, recipientId);
        return Optional.of(link);
    }

    /**
     * Rattrapage paresseux : rattache à {@code user} les colis actifs dont le numéro de
     * destinataire est le sien, sans push. Firebase injoignable ou numéro absent : rien.
     *
     * @return nombre de liens créés
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int catchUp(UserEntity user) {
        String phone = firebaseContact.getContact(user.getFirebaseUid()).phoneNumber();
        String key = ReceptionPhones.digitsKey(phone);
        if (key.isEmpty()) {
            return 0;
        }
        String suffix = "%" + key.charAt(key.length() - 1);
        List<BidEntity> candidates = linkRepository.findCatchUpCandidates(
                user.getId(), BidStatus.IN_FLIGHT, suffix);
        int created = 0;
        for (BidEntity bid : candidates) {
            if (bid.getTrackingToken() == null
                    || !key.equals(ReceptionPhones.digitsKey(bid.getRecipientPhone()))
                    || blockVisibility.isHidden(bid.getSenderId(), user.getId())) {
                continue;
            }
            save(bid, user.getId(), "LINKED_ON_CATCH_UP");
            created++;
        }
        return created;
    }

    private BidRecipientLinkEntity save(BidEntity bid, UUID recipientId, String action) {
        BidRecipientLinkEntity link = linkRepository.save(new BidRecipientLinkEntity(bid.getId(), recipientId));
        auditService.log("BID_RECIPIENT_LINK", link.getId(), action, null,
                Map.of("bidId", bid.getId().toString(), "recipientUserId", recipientId.toString()));
        return link;
    }

    private void notifyIncoming(BidEntity bid, AnnouncementEntity announcement, UUID recipientId) {
        String senderFirstName = userRepository.findById(bid.getSenderId())
                .map(UserEntity::getFirstName)
                .orElse(null);
        var text = NotificationTexts.recipientParcelIncoming(notificationDispatcher.messagesFor(recipientId),
                senderFirstName,
                announcement != null ? announcement.getDepartureCity() : null,
                announcement != null ? announcement.getArrivalCity() : null);
        notificationDispatcher.notifyUser(recipientId, text.title(), text.body(),
                Map.of("type", ReceptionNotifications.INCOMING, "bidId", bid.getId().toString()));
        log.info("Colis {} rattaché au compte de son destinataire {}", bid.getId(), recipientId);
    }
}
