package com.yadony.api.matching;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.BlockService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.config.ContentCategoryNormalizer;
import com.yadony.api.matching.dto.BidGridItemRequest;
import com.yadony.api.matching.dto.BidQuoteRequest;
import com.yadony.api.matching.dto.BidQuoteResponse;
import com.yadony.api.matching.dto.BidRejectRequest;
import com.yadony.api.matching.dto.BidRequest;
import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.matching.dto.ContactPhoneResponse;
import com.yadony.api.promo.PromoService;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.matching.events.BidCreatedEvent;
import com.yadony.api.matching.events.CashBidCreatedEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.currency.CurrencyMatchGuard;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class BidService {

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final RatingRepository ratingRepository;
    private final CancellationRepository cancellationRepository;
    private final BidGridItemRepository bidGridItemRepository;
    private final AnnouncementPriceGridItemRepository annGridItemRepository;
    private final BlockService blockService;
    private final CommissionRateResolver commissionRateResolver;
    private final PromoService promoService;
    private final StorageService storageService;
    private final BidPhotoService bidPhotoService;
    private final FirebaseContactService firebaseContact;
    private final ActiveCurrencyResolver activeCurrencyResolver;
    private final CurrencyMatchGuard currencyMatchGuard;

    public BidService(BidRepository bidRepository, AnnouncementRepository announcementRepository,
                      UserRepository userRepository, AuditService auditService,
                      ApplicationEventPublisher eventPublisher, RatingRepository ratingRepository,
                      CancellationRepository cancellationRepository,
                      BidGridItemRepository bidGridItemRepository,
                      AnnouncementPriceGridItemRepository annGridItemRepository,
                      BlockService blockService,
                      CommissionRateResolver commissionRateResolver,
                      PromoService promoService,
                      StorageService storageService,
                      BidPhotoService bidPhotoService,
                      FirebaseContactService firebaseContact,
                      ActiveCurrencyResolver activeCurrencyResolver,
                      CurrencyMatchGuard currencyMatchGuard) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.ratingRepository = ratingRepository;
        this.cancellationRepository = cancellationRepository;
        this.bidGridItemRepository = bidGridItemRepository;
        this.annGridItemRepository = annGridItemRepository;
        this.blockService = blockService;
        this.commissionRateResolver = commissionRateResolver;
        this.promoService = promoService;
        this.storageService = storageService;
        this.bidPhotoService = bidPhotoService;
        this.firebaseContact = firebaseContact;
        this.activeCurrencyResolver = activeCurrencyResolver;
        this.currencyMatchGuard = currencyMatchGuard;
    }

    /**
     * Devis : calcule le total exact (net, commission, total) avec promo éventuel.
     * Le promo est validé strictement ici (exceptions propagées au contrôleur).
     */
    @Transactional(readOnly = true)
    public BidQuoteResponse quote(String firebaseUid, BidQuoteRequest request) {
        UserEntity sender = findUserByFirebaseUid(firebaseUid);
        AnnouncementEntity ann = announcementRepository.findById(request.announcementId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        // Même détermination de mode que createBid/checkout : au moins poids OU article.
        List<BidGridItemRequest> gridItems = request.gridItems() != null ? request.gridItems() : List.of();
        boolean hasGrid = !gridItems.isEmpty();
        boolean hasKg   = request.weightKg() != null && request.weightKg().compareTo(BigDecimal.ZERO) > 0;

        if (!hasGrid && !hasKg) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "bid-empty", "Bid Empty",
                    "Au moins un article ou un poids doit être renseigné");
        }

        // Net grille : Σ (prix unitaire net × quantité), articles bornés à CETTE annonce.
        BigDecimal gridNet = BigDecimal.ZERO;
        for (BidGridItemRequest g : gridItems) {
            AnnouncementPriceGridItemEntity annItem = annGridItemRepository.findById(g.announcementGridItemId())
                    .filter(i -> i.getAnnouncementId().equals(ann.getId()))
                    .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "invalid-grid-item", "Invalid Grid Item",
                            "Article hors grille de cette annonce : " + g.announcementGridItemId()));
            gridNet = gridNet.add(annItem.getUnitPriceNet().multiply(BigDecimal.valueOf(g.quantity())));
        }
        gridNet = gridNet.setScale(2, java.math.RoundingMode.HALF_UP);

        // Net poids : prix au kilo × poids (mode KG/MIXED).
        BigDecimal kgNet = BigDecimal.ZERO;
        if (hasKg) {
            if (ann.getPricePerKg() == null) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-bid-params", "Invalid Bid Parameters",
                        "Le prix au kilo est requis pour calculer le devis au poids");
            }
            kgNet = ann.getPricePerKg().multiply(request.weightKg()).setScale(2, java.math.RoundingMode.HALF_UP);
        }

        BigDecimal netEur = gridNet.add(kgNet).setScale(2, java.math.RoundingMode.HALF_UP);

        // Résolution du taux — promo validé strictement (exceptions propagées en RFC 7807).
        // Le promo est résolu EN PREMIER : s'il est invalide, on propage avant même de
        // toucher au taux de base (pas d'appel superflu si le devis échoue de toute façon).
        // La ligne "Commission Yadony" affichée reste TOUJOURS au taux de base (non affecté
        // par le promo) — la remise apparaît séparément, sur le total. Un promo qui écraserait
        // silencieusement le taux (ancien comportement) pouvait égaler le taux courant et ne
        // faire gagner aucune remise réelle (régression WELCOME05, 5 % promo = 5 % global).
        String promoCode = request.promoCode() != null ? request.promoCode().strip() : null;
        boolean promoApplied = false;
        String promoLabel = null;
        BigDecimal rate;
        BigDecimal commissionEur;
        BigDecimal totalEur;

        if (promoCode != null && !promoCode.isBlank()) {
            BigDecimal finalRate = commissionRateResolver.resolve(ann.getTravelerId(), sender.getId(), promoCode);
            promoApplied = true;

            rate = commissionRateResolver.resolve(ann.getTravelerId(), sender.getId());
            commissionEur = netEur.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal finalCommissionEur = netEur.multiply(finalRate).setScale(2, java.math.RoundingMode.HALF_UP);
            totalEur = netEur.add(finalCommissionEur).setScale(2, java.math.RoundingMode.HALF_UP);

            BigDecimal discountPoints = rate.subtract(finalRate).max(BigDecimal.ZERO);
            long pct = discountPoints.multiply(java.math.BigDecimal.valueOf(100)).longValue();
            promoLabel = "Code " + promoCode.toUpperCase() + " : " + pct + " % de réduction";
        } else {
            rate = commissionRateResolver.resolve(ann.getTravelerId(), sender.getId());
            commissionEur = netEur.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
            totalEur = netEur.add(commissionEur).setScale(2, java.math.RoundingMode.HALF_UP);
        }

        return new BidQuoteResponse(netEur, gridNet, kgNet, rate, commissionEur, totalEur, promoApplied, promoLabel);
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public BidResponse createBid(UUID announcementId, String firebaseUid,
                                 BidRequest request, HttpServletRequest httpRequest) {

        UserEntity sender = findUserByFirebaseUid(firebaseUid);

        // Pas de garde KYC global ici : c'est le voyageur qui décide, par son réglage
        // « profils vérifiés uniquement », s'il accepte les offres de profils non
        // vérifiés (contrôle plus bas, une fois le voyageur connu). yadony.kyc.enforce
        // continue de gouverner la publication d'annonces
        // (AnnouncementService#assertCanPublish), où personne d'autre ne consent.

        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                        "Annonce introuvable"));

        if (announcement.getStatus() != AnnouncementStatus.ACTIVE) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, "announcement-not-active", "Announcement Not Active",
                    "Cette annonce n'est plus disponible");
        }

        String senderCurrency = activeCurrencyResolver.resolve(sender.getId());
        currencyMatchGuard.assertMatches(announcement.getCurrency(), senderCurrency);

        if (!sender.getRoles().contains(Role.SENDER)) {
            sender.getRoles().add(Role.SENDER);
            userRepository.save(sender);
        }

        // Dedicated trip (tied to a negotiation): other senders can only bid on the
        // surplus capacity once the traveler has opened it (after the negotiating
        // sender paid). The weight cap is enforced by the weight-exceeds-capacity
        // check below since availableKg == surplus once published.
        if (announcement.isClosedToThirdPartyBids()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "surplus-not-open",
                    "Surplus Not Open",
                    "La capacité supplémentaire de ce trajet n'est pas ouverte aux autres expéditeurs");
        }

        // Le sender réservé (celui pour qui le trajet dédié a été créé) a déjà son
        // colis dessus : il ne peut pas bidder sur le surplus de son propre trajet,
        // même une fois le surplus publié (sinon deux colis du même sender sur un trajet).
        if (announcement.isReservedSender(sender.getId())) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, "reserved-sender-cannot-bid", "Reserved Sender Cannot Bid",
                    "Vous avez déjà un colis réservé sur ce trajet");
        }

        if (announcement.getTravelerId().equals(sender.getId())) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, "cannot-bid-own-announcement", "Cannot Bid Own Announcement",
                    "Vous ne pouvez pas faire une demande sur votre propre annonce");
        }

        // Normalisé AVANT le contrôle de refus (C2) : announcement.refusedTypes est déjà
        // normalisé (V171 + écriture normalisée dans AnnouncementService). Comparer un
        // libellé/code legacy encore non normalisé ("Hi-fi") le ferait passer à travers
        // un refus explicite ("Téléphone & électronique") — inversion silencieuse du refus.
        String normalizedContentCategory = ContentCategoryNormalizer.normalizeJoined(request.contentCategory());
        BidContentRules.assertNotRefused(announcement, normalizedContentCategory);

        UUID travelerId = announcement.getTravelerId();

        // Confidentialité v2 — blocage : 404 masque délibérément le blocage
        if (blockService.isBlockedEitherWay(sender.getId(), travelerId)) {
            throw new YadonyBusinessException(
                    HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                    "Annonce introuvable");
        }

        // Seule garde KYC de la création d'offre : le voyageur décide. Tant qu'il
        // laisse « profils vérifiés uniquement » actif (défaut de tous les comptes),
        // seuls les expéditeurs vérifiés peuvent lui écrire ; s'il le désactive, il
        // accepte sciemment les profils non vérifiés et l'app l'en avertit avant.
        // On ne charge le voyageur que si nécessaire (expéditeur non vérifié).
        if (sender.getKycStatus() != KycStatus.VERIFIED) {
            UserEntity traveler = userRepository.findById(travelerId).orElse(null);
            // traveler null (suppression/race) => on laisse passer : la FK garantit normalement sa présence.
            if (traveler != null && traveler.isContactKycOnly()) {
                throw new YadonyBusinessException(
                        HttpStatus.FORBIDDEN, "contact-kyc-required", "KYC Required",
                        "Cet utilisateur n'accepte que les profils vérifiés");
            }
        }

        boolean alreadyHasBid = bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(
                sender.getId(), announcementId,
                List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED));
        if (alreadyHasBid) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, "already-bid", "Demande existante",
                    "Vous avez déjà une demande en cours ou acceptée pour ce trajet");
        }

        boolean isKgFreeCreate = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
        if (!isKgFreeCreate && request.weightKg() != null
                && request.weightKg().compareTo(announcement.getAvailableKg()) > 0) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "weight-exceeds-capacity", "Weight Exceeds Capacity",
                    "Poids demandé supérieur à la capacité disponible");
        }

        if (Boolean.FALSE.equals(request.disclaimerSigned())) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "disclaimer-not-signed", "Disclaimer Not Signed",
                    "Le disclaimer légal doit être accepté");
        }

        List<BidGridItemRequest> gridItems = request.gridItems() != null ? request.gridItems() : List.of();
        boolean hasGrid = !gridItems.isEmpty();
        boolean hasKg   = request.weightKg() != null && request.weightKg().compareTo(BigDecimal.ZERO) > 0;

        if (!hasGrid && !hasKg) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "bid-empty", "Bid Empty",
                "Au moins un article ou un poids doit être renseigné");
        }

        BidPricingMode bidMode = hasGrid && hasKg ? BidPricingMode.MIXED
                               : hasGrid          ? BidPricingMode.GRID
                               :                    BidPricingMode.KG;

        PaymentMethod pm;
        try {
            pm = request.paymentMethod() != null
                    ? PaymentMethod.valueOf(request.paymentMethod().toUpperCase())
                    : PaymentMethod.STRIPE;
        } catch (IllegalArgumentException e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid-payment-method", "Invalid Payment Method",
                    "Méthode de paiement inconnue : " + request.paymentMethod());
        }

        if (pm == PaymentMethod.CASH
                && !announcement.getAcceptedPaymentMethods().contains(pm)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "cash-not-accepted", "Cash Not Accepted",
                    "Cette annonce n'accepte pas le paiement en espèces");
        }

        if (pm == PaymentMethod.STRIPE
                && !announcement.getAcceptedPaymentMethods().contains(pm)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "card-not-accepted", "Card Not Accepted",
                    "Cette annonce n'accepte pas le paiement par carte");
        }

        if (pm == PaymentMethod.WAVE || pm == PaymentMethod.ORANGE_MONEY) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "mobile-money-bid-payment-retired", "Mobile Money Bid Payment Retired",
                    "Le paiement mobile money direct par l'expéditeur n'est plus disponible "
                    + "pour les nouveaux envois. Choisissez Cash ou Carte bancaire.");
        }

        String clientIp = resolveClientIp(httpRequest);

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(sender.getId());
        bid.setWeightKg(request.weightKg());  // peut être null pour GRID mode
        bid.setPricingMode(bidMode);
        bid.setDescription(request.description());
        bid.setContentCategory(normalizedContentCategory);
        bid.setRecipientName(request.recipientName());
        bid.setRecipientPhone(request.recipientPhone());
        bid.setDisclaimerSignedAt(LocalDateTime.now(ZoneOffset.UTC));
        bid.setDisclaimerSignedIp(clientIp);
        bid.setPaymentMethod(pm);
        bid.setStatus(BidStatus.PENDING);
        bid.setCurrency(announcement.getCurrency());

        // Code promo stocké brut (validation + rachat au moment du paiement).
        if (request.promoCode() != null && !request.promoCode().isBlank()) {
            bid.setPromoCode(request.promoCode().strip());
        }

        BidEntity saved = bidRepository.save(bid);

        auditService.log("BID", saved.getId(), "BID_CREATED", sender.getId(),
                Map.<String, Object>of(
                        "announcementId", announcementId.toString(),
                        "weightKg", saved.getWeightKg() != null ? saved.getWeightKg().toString() : "null",
                        "pricingMode", bidMode.name(),
                        "contentCategory", String.valueOf(saved.getContentCategory()),
                        "disclaimerSignedAt", saved.getDisclaimerSignedAt().toString(),
                        "disclaimerSignedIp", clientIp
                ));

        if (hasGrid) {
            List<BidGridItemEntity> bidGridItems = new java.util.ArrayList<>();
            for (BidGridItemRequest gReq : gridItems) {
                AnnouncementPriceGridItemEntity annItem = annGridItemRepository
                    .findById(gReq.announcementGridItemId())
                    .filter(i -> i.getAnnouncementId().equals(announcement.getId()))
                    .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-grid-item", "Invalid Grid Item",
                        "Article hors grille de cette annonce : " + gReq.announcementGridItemId()));
                BidGridItemEntity bgi = new BidGridItemEntity();
                bgi.setBidId(saved.getId());
                bgi.setAnnouncementGridItemId(gReq.announcementGridItemId());
                bgi.setLabelSnapshot(annItem.getLabel());
                bgi.setUnitPriceNetSnapshot(annItem.getUnitPriceNet());
                bgi.setQuantity(gReq.quantity());
                bidGridItems.add(bgi);
            }
            bidGridItemRepository.saveAll(bidGridItems);
        }

        bidPhotoService.attachPhotos(saved.getId(), sender.getId(), request.photoKeys());

        // Le parcours carte publie le même événement après autorisation Stripe dans
        // PaymentService.promoteBidOnPaymentAuthorized(). En CASH, la création du bid
        // termine directement le parcours : le voyageur peut donc être notifié ici.
        if (pm == PaymentMethod.CASH) {
            String senderName = sender.getFirstName() != null && !sender.getFirstName().isBlank()
                    ? sender.getFirstName() : "Un expéditeur";
            String corridor = announcement.getDepartureCity() + " → " + announcement.getArrivalCity();
            eventPublisher.publishEvent(new CashBidCreatedEvent(
                    saved.getId(), announcement.getId(), announcement.getTravelerId(), sender.getId(),
                    senderName, saved.getWeightKg(), corridor));
        }

        return toResponse(saved, sender);
    }

    @Transactional(readOnly = true)
    public void assertSenderOwnsBid(UUID bidId, String firebaseUid) {
        BidEntity bid = findBid(bidId);
        UserEntity user = findUserByFirebaseUid(firebaseUid);
        if (!bid.getSenderId().equals(user.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul l'expéditeur peut confirmer le paiement");
        }
    }

    @Transactional(readOnly = true)
    public BidResponse getBidById(UUID bidId, String firebaseUid) {
        BidEntity bid = findBid(bidId);
        AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
        UserEntity requester = findUserByFirebaseUid(firebaseUid);

        // Accessible by the traveler who owns the announcement, or the sender
        boolean isTraveler = announcement.getTravelerId().equals(requester.getId());
        boolean isSender = bid.getSenderId().equals(requester.getId());

        if (!isTraveler && !isSender) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Accès non autorisé à ce bid");
        }

        UserEntity sender = userRepository.findById(bid.getSenderId()).orElse(null);
        return toResponse(bid, sender, requester.getId());
    }

    /**
     * Numéro de la contrepartie d'un colis, révélé à la demande.
     *
     * <p>Point unique où un numéro sort du serveur pour un colis : le numéro ne voyage
     * plus dans les réponses de liste, il n'est lu dans Firebase que lorsque quelqu'un
     * veut réellement appeler. Trois conditions, toutes vérifiées ici :
     * l'appelant est partie au colis, le statut autorise la révélation, et l'accès est
     * journalisé — on peut donc dire qui a obtenu le numéro de qui, et quand.
     */
    // Transaction en écriture, et non readOnly : la révélation journalise un accès
    // dans audit_log. AuditService.log rejoint la transaction de l'appelant, donc un
    // readOnly ferait échouer l'INSERT ("cannot execute INSERT in a read-only
    // transaction") alors que la lecture, elle, aurait réussi.
    @Transactional
    public ContactPhoneResponse getCounterpartyPhone(UUID bidId, String firebaseUid) {
        BidEntity bid = findBid(bidId);
        AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
        UserEntity requester = findUserByFirebaseUid(firebaseUid);

        boolean isTraveler = announcement.getTravelerId().equals(requester.getId());
        boolean isSender = bid.getSenderId().equals(requester.getId());
        if (!isTraveler && !isSender) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Accès non autorisé à ce colis");
        }
        if (!PHONE_VISIBLE_STATUSES.contains(bid.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "phone-not-revealable",
                    "Phone Not Revealable",
                    "Le numéro n'est communiqué qu'une fois le colis accepté");
        }

        // La contrepartie : l'expéditeur voit le voyageur, le voyageur voit l'expéditeur.
        UUID counterpartyId = isSender ? announcement.getTravelerId() : bid.getSenderId();
        UserEntity counterparty = userRepository.findById(counterpartyId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found",
                        "Not Found", "Utilisateur introuvable"));

        // Réglage de confidentialité de la contrepartie : elle a choisi de n'être
        // joignable que par la messagerie Yadony. Vérifié ici, et pas seulement via le
        // booléen des DTO de liste, car cet endpoint est la seule autorité — un
        // client qui appellerait l'URL directement ne doit pas obtenir le numéro.
        if (counterparty.isHidePhoneNumber()) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "phone-hidden-by-user",
                    "Phone Hidden",
                    "Ce membre préfère échanger par la messagerie Yadony");
        }

        String phone = firebaseContact.getContact(counterparty.getFirebaseUid()).phoneNumber();

        // Payload sans PII : le numéro lui-même ne doit pas atterrir dans audit_log.
        auditService.log("BID", bidId, "CONTACT_PHONE_REVEALED", requester.getId(),
                Map.of("counterpartyId", counterpartyId.toString(),
                        "status", bid.getStatus().name()));

        return new ContactPhoneResponse(phone);
    }

    @Transactional(readOnly = true)
    public List<BidResponse> getBidsForAnnouncement(UUID announcementId, String firebaseUid) {
        AnnouncementEntity announcement = findAnnouncement(announcementId);
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à voir ces demandes");
        }

        List<BidEntity> visible = bidRepository.findByAnnouncementId(announcementId)
                .stream()
                .filter(b -> !b.isDeletedByTraveler())
                .filter(b -> b.getStatus() != BidStatus.AWAITING_PAYMENT)
                .toList();
        return visible.stream()
                .map(b -> {
                    UserEntity sender = userRepository.findById(b.getSenderId()).orElse(null);
                    return toResponse(b, sender);
                }).toList();
    }

    // TTL courte (8 s, cf. CacheConfig) et volontairement SANS @CacheEvict : un
    // bid change de statut des DEUX côtés (expéditeur ET voyageur), et cette
    // méthode n'est appelée qu'avec l'id de l'un des deux — évincer sur mutation
    // demanderait de retrouver puis invalider la clé de l'AUTRE partie à chaque
    // point d'entrée (accept/reject/cancel/tracking...), ce qui est plus risqué
    // (un oubli = cache jamais invalidé) qu'une expiration courte assumée. Le
    // client tolère déjà ce délai (throttle 3 s sur le retour d'onglet).
    @Transactional(readOnly = true)
    @Cacheable(value = "bids-me", key = "#firebaseUid")
    public List<BidResponse> getMyBids(String firebaseUid) {
        UserEntity user = findUserByFirebaseUid(firebaseUid);
        List<BidEntity> mine = bidRepository.findBySenderId(user.getId())
                .stream()
                .filter(b -> !b.isDeletedBySender())
                .toList();
        return mine.stream()
                .map(b -> toResponse(b, user))
                .toList();
    }

    // Même rationale que getMyBids ci-dessus (données bilatérales, TTL courte
    // sans éviction manuelle).
    @Transactional(readOnly = true)
    @Cacheable(value = "traveler-bids-me")
    public Page<BidResponse> getTravelerBids(String firebaseUid, String status, UUID announcementId, String q, int page, int size) {
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);
        BidStatus bidStatus = (status != null && !status.isBlank()) ? BidStatus.valueOf(status) : null;
        String qParam = (q != null && !q.isBlank()) ? q.trim() : null;
        Page<BidEntity> bids = bidRepository.findByTravelerIdFiltered(
                traveler.getId(), bidStatus, announcementId, qParam, PageRequest.of(page, size));
        // Chaque colis doit afficher son EXPÉDITEUR réel (les champs sender.* du DTO),
        // pas le voyageur connecté. On résout les expéditeurs en une seule requête.
        Map<UUID, UserEntity> sendersById = userRepository.findAllById(
                        bids.getContent().stream().map(BidEntity::getSenderId).distinct().toList())
                .stream().collect(Collectors.toMap(UserEntity::getId, u -> u));
        return bids.map(b -> toResponse(b, sendersById.get(b.getSenderId())));
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public BidResponse acceptBid(UUID bidId, String firebaseUid) {
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "bid-not-found", "Bid Not Found", "Demande introuvable"));
        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        requireTravelerOwnsAnnouncement(traveler, announcement);
        return doAcceptBid(bid, announcement, traveler);
    }

    /**
     * Variante système (déclenchée par une automatisation) : le travelerId est déjà
     * garanti propriétaire par construction (résolu depuis la règle d'automatisation
     * elle-même), donc pas de firebaseUid à résoudre — juste une vérification défensive
     * d'appartenance.
     */
    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public BidResponse acceptBidBySystem(UUID bidId, UUID travelerId) {
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "bid-not-found", "Bid Not Found", "Demande introuvable"));
        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));
        if (!announcement.getTravelerId().equals(travelerId)) {
            throw new IllegalStateException(
                    "Automation travelerId mismatch for announcement " + announcement.getId());
        }
        UserEntity traveler = userRepository.findById(travelerId)
                .orElseThrow(() -> new IllegalStateException("Traveler not found: " + travelerId));
        return doAcceptBid(bid, announcement, traveler);
    }

    private BidResponse doAcceptBid(BidEntity bid, AnnouncementEntity announcement, UserEntity traveler) {
        requireBidStatus(bid, BidStatus.PAYMENT_ESCROWED);

        if (announcement.getStatus() == AnnouncementStatus.IN_PROGRESS
                || announcement.getStatus() == AnnouncementStatus.COMPLETED
                || announcement.getStatus() == AnnouncementStatus.CANCELLED) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "announcement-not-accepting", "Announcement Not Accepting",
                    "Le voyageur est déjà parti, ce trajet n'accepte plus de colis");
        }

        boolean isKgFree = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
        if (!isKgFree && bid.getWeightKg() != null
                && bid.getWeightKg().compareTo(announcement.getAvailableKg()) > 0) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, "capacity-insufficient", "Insufficient Capacity",
                    "Capacité insuffisante pour accepter cette demande");
        }

        bid.setStatus(BidStatus.ACCEPTED);
        if (bid.getQrToken() == null) {
            bid.setQrToken(UUID.randomUUID().toString());
        }
        if (bid.getTrackingNumber() == null) {
            bid.setTrackingNumber(generateTrackingNumber());
        }
        if (bid.getTrackingToken() == null) {
            bid.setTrackingToken(java.util.UUID.randomUUID().toString());
        }
        if (!isKgFree && bid.getWeightKg() != null) {
            announcement.setAvailableKg(announcement.getAvailableKg().subtract(bid.getWeightKg()));
        }
        if (!isKgFree && announcement.getAvailableKg().compareTo(BigDecimal.ZERO) <= 0) {
            announcement.setStatus(AnnouncementStatus.FULL);
        }
        announcementRepository.save(announcement);
        bid.applyHandoverFrom(announcement);
        bidRepository.save(bid);

        auditService.log("BID", bid.getId(), "BID_ACCEPTED", traveler.getId(),
                Map.<String, Object>of("announcementId", announcement.getId().toString(),
                       "weightKg", bid.getWeightKg() != null ? bid.getWeightKg().toString() : "null"));

        eventPublisher.publishEvent(new BidAcceptedEvent(
                bid.getId(), bid.getSenderId(), traveler.getId(), announcement.getId(),
                bid.getPaymentMethod() != null && bid.getPaymentMethod().isMobileMoney()));

        return toResponse(bid, userRepository.findById(bid.getSenderId()).orElse(null));
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public BidResponse rejectBid(UUID bidId, String firebaseUid, BidRejectRequest request) {
        BidEntity bid = findBid(bidId);
        AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        requireTravelerOwnsAnnouncement(traveler, announcement);
        return doRejectBid(bid, announcement, traveler, request, false);
    }

    /**
     * Variante système (déclenchée par une automatisation) : le travelerId est déjà
     * garanti propriétaire par construction (résolu depuis la règle d'automatisation
     * elle-même), donc pas de firebaseUid à résoudre — juste une vérification défensive
     * d'appartenance.
     */
    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public BidResponse rejectBidBySystem(UUID bidId, UUID travelerId, String reason) {
        BidEntity bid = findBid(bidId);
        AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
        if (!announcement.getTravelerId().equals(travelerId)) {
            throw new IllegalStateException(
                    "Automation travelerId mismatch for announcement " + announcement.getId());
        }
        UserEntity traveler = userRepository.findById(travelerId)
                .orElseThrow(() -> new IllegalStateException("Traveler not found: " + travelerId));
        return doRejectBid(bid, announcement, traveler, new BidRejectRequest(reason), true);
    }

    private BidResponse doRejectBid(BidEntity bid, AnnouncementEntity announcement,
                                    UserEntity traveler, BidRejectRequest request,
                                    boolean systemInitiated) {
        boolean isOffPlatformPending =
                (bid.getPaymentMethod() == PaymentMethod.CASH
                 || bid.getPaymentMethod() == PaymentMethod.WAVE
                 || bid.getPaymentMethod() == PaymentMethod.ORANGE_MONEY)
                && bid.getStatus() == BidStatus.PENDING;
        if (!isOffPlatformPending) {
            requireBidStatus(bid, BidStatus.PAYMENT_ESCROWED);
        }

        bid.setStatus(BidStatus.REJECTED);
        if (request != null) {
            bid.setRejectionReason(request.reason());
        }
        bidRepository.save(bid);

        auditService.log("BID", bid.getId(), "BID_REJECTED", traveler.getId(),
                Map.of("reason", String.valueOf(bid.getRejectionReason())));

        // Rematch éligible uniquement si l'action vient d'un humain (pas d'une
        // automatisation système) et que le bid était réellement payé (pas un
        // simple refus d'une demande cash encore PENDING, jamais confirmée).
        boolean rematchEligible = !systemInitiated && !isOffPlatformPending;
        eventPublisher.publishEvent(new BidRejectedEvent(
                bid.getId(), bid.getSenderId(), bid.getRejectionReason(),
                bid.getAnnouncementId(), rematchEligible));

        return toResponse(bid, userRepository.findById(bid.getSenderId()).orElse(null));
    }

    @Transactional
    public BidResponse confirmPresence(UUID bidId, String firebaseUid) {
        BidEntity bid = findBid(bidId);
        AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        requireTravelerOwnsAnnouncement(traveler, announcement);

        if (bid.getStatus() != BidStatus.ACCEPTED) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "bid-not-accepted", "Bid Not Accepted",
                    "Confirmation de présence uniquement pour les bids acceptés");
        }

        bid.setVoyageurConfirmed(true);
        bidRepository.save(bid);

        auditService.log("BID", bidId, "PRESENCE_CONFIRMED", traveler.getId(), Map.of());

        return toResponse(bid, userRepository.findById(bid.getSenderId()).orElse(null));
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public BidResponse cancelBid(UUID bidId, String firebaseUid) {
        BidEntity bid = findBid(bidId);
        UserEntity caller = findUserByFirebaseUid(firebaseUid);

        AnnouncementEntity announcement =
                announcementRepository.findById(bid.getAnnouncementId()).orElse(null);

        // L'annulation d'un bid avant remise est ouverte à l'expéditeur ET au
        // voyageur (qui peut se désister d'un colis déjà accepté, paiement en
        // séquestre). Le verrou D3 reste l'autorité sur les statuts annulables ;
        // l'annulation après remise (HANDED_OVER) passe par cancel-after-handover.
        boolean isSender = bid.getSenderId().equals(caller.getId());
        boolean isTraveler = announcement != null
                && announcement.getTravelerId().equals(caller.getId());
        if (!isSender && !isTraveler) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à annuler ce bid");
        }

        if (bid.getStatus() == BidStatus.CANCELLED || bid.getStatus() == BidStatus.REJECTED
                || bid.getStatus() == BidStatus.COMPLETED) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "invalid-status", "Invalid Status",
                    "Impossible d'annuler un bid déjà terminé");
        }

        // Verrou D3 : pas d'annulation en transit ni après le départ réel (colis remis).
        com.yadony.api.cancellation.CancellationGuard.assertCancellable(bid, announcement);

        BidStatus statusBeforeCancel = bid.getStatus();

        restoreCapacityIfNeeded(bid, announcement);

        bid.setStatus(BidStatus.CANCELLED);
        bidRepository.save(bid);

        String reason = isTraveler ? "CANCELLED_BY_TRAVELER" : "CANCELLED_BY_SENDER";
        auditService.log("BID", bidId, "BID_CANCELLED", caller.getId(),
                Map.of("actor", isTraveler ? "TRAVELER" : "SENDER"));

        // Rembourse l'expéditeur (séquestre libéré via RefundProcessor) et notifie,
        // quel que soit l'acteur de l'annulation. Rematch éligible uniquement si
        // c'est le voyageur qui se désiste d'un transport déjà confirmé (bid payé
        // ou accepté) — pas un simple retrait de demande encore PENDING, et pas
        // une annulation côté expéditeur.
        boolean rematchEligible = isTraveler
                && (statusBeforeCancel == BidStatus.ACCEPTED
                    || statusBeforeCancel == BidStatus.PAYMENT_ESCROWED);
        eventPublisher.publishEvent(new BidRejectedEvent(
                bid.getId(), bid.getSenderId(), reason,
                bid.getAnnouncementId(), rematchEligible));

        UserEntity senderUser = isSender
                ? caller
                : userRepository.findById(bid.getSenderId()).orElse(null);
        return toResponse(bid, senderUser);
    }

    /** Si le bid était déjà accepté ou remis, on rend le kilo au voyageur
     *  (sauf pour KG_FREE où la capacité n'est jamais décrémentée). */
    private void restoreCapacityIfNeeded(BidEntity bid, AnnouncementEntity announcement) {
        if (bid.getStatus() != BidStatus.ACCEPTED && bid.getStatus() != BidStatus.HANDED_OVER) {
            return;
        }
        if (announcement == null) {
            return;
        }
        boolean isKgFreeCancel = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
        if (!isKgFreeCancel && bid.getWeightKg() != null) {
            announcement.setAvailableKg(announcement.getAvailableKg().add(bid.getWeightKg()));
        }
        if (!isKgFreeCancel && announcement.getStatus() == AnnouncementStatus.FULL) {
            announcement.setStatus(AnnouncementStatus.ACTIVE);
        }
        announcementRepository.save(announcement);
    }

    /** Statuts pour lesquels {@link #cancelBidForDeletedSender} agit encore. Exposé pour que
     *  {@link AccountDeletionListener} sélectionne EXACTEMENT les bids que ce service traitera —
     *  deux listes séparées divergeraient en silence (bids chargés puis ignorés, ou l'inverse). */
    static final List<BidStatus> CANCELLABLE_BID_STATUSES = List.of(
            BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED, BidStatus.AWAITING_PAYMENT);

    /**
     * Annulation système d'un bid suite à la suppression du compte de son expéditeur
     * (AccountFinalizationService / AccountDeletionListener) — l'expéditeur n'a plus de
     * session live, donc pas d'ownership/firebaseUid à vérifier ici (contrairement à
     * {@link #cancelBid}). Réutilise le même cœur (restitution kg + BidRejectedEvent, donc
     * refund via {@code payments.BidRejectedEventListener} et notification du sender) sans
     * jamais toucher à l'annonce d'un tiers ni générer de rematch (pas éligible pour un
     * simple retrait de demande, cf. {@link #cancelBid}).
     * Idempotent : no-op si le bid n'existe plus ou n'est déjà plus dans un statut annulable.
     */
    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public void cancelBidForDeletedSender(UUID bidId) {
        BidEntity bid = bidRepository.findById(bidId).orElse(null);
        if (bid == null || !CANCELLABLE_BID_STATUSES.contains(bid.getStatus())) {
            return;
        }

        AnnouncementEntity announcement =
                announcementRepository.findById(bid.getAnnouncementId()).orElse(null);

        restoreCapacityIfNeeded(bid, announcement);

        bid.setStatus(BidStatus.CANCELLED);
        bidRepository.save(bid);

        auditService.log("BID", bidId, "BID_CANCELLED", bid.getSenderId(),
                Map.of("actor", "SENDER_ACCOUNT_DELETED"));

        eventPublisher.publishEvent(new BidRejectedEvent(
                bid.getId(), bid.getSenderId(), "SENDER_ACCOUNT_DELETED",
                bid.getAnnouncementId(), false));
    }

    @Transactional
    public void hideBidForSender(UUID bidId, String firebaseUid) {
        BidEntity bid = findBid(bidId);
        UserEntity sender = findUserByFirebaseUid(firebaseUid);

        if (!bid.getSenderId().equals(sender.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à masquer ce bid");
        }

        bid.setDeletedBySender(true);
        bidRepository.save(bid);

        auditService.log("BID", bidId, "BID_HIDDEN_BY_SENDER", sender.getId(), Map.of());
    }

    @Transactional
    public void hideBidForTraveler(UUID bidId, String firebaseUid) {
        BidEntity bid = findBid(bidId);
        AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à effectuer cette action");
        }

        if (bid.getStatus() != BidStatus.REJECTED && bid.getStatus() != BidStatus.CANCELLED) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "invalid-bid-status", "Invalid Bid Status",
                    "Seules les demandes refusées ou annulées peuvent être supprimées");
        }

        bid.setDeletedByTraveler(true);
        bidRepository.save(bid);

        auditService.log("BID", bidId, "BID_DISMISSED_BY_TRAVELER", traveler.getId(), Map.of());
    }

    // Called by scheduler — no auth check, transaction managed internally
    // Story 9.4 — Refus de colis par le voyageur lors de l'inspection
    @Transactional
    public BidResponse refuseParcel(UUID bidId, String firebaseUid,
                                    String reason, String refusalPhotoUrl) {
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);
        BidEntity bid = findBid(bidId);

        AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
        requireTravelerOwnsAnnouncement(traveler, announcement);

        if (bid.getStatus() != BidStatus.ACCEPTED && bid.getStatus() != BidStatus.HANDED_OVER) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-bid-status",
                    "Unprocessable", "Le refus de colis n'est possible que sur un envoi accepté ou remis");
        }

        bid.setStatus(BidStatus.PARCEL_REFUSED);
        bid.setRefusalReason(reason);
        bid.setRefusalPhotoUrl(refusalPhotoUrl);
        bidRepository.save(bid);

        UserEntity sender = userRepository.findById(bid.getSenderId()).orElse(null);
        if (sender != null) {
            sender.setRefusedCount(sender.getRefusedCount() + 1);
            userRepository.save(sender);
        }

        eventPublisher.publishEvent(new com.yadony.api.matching.events.ParcelRefusedEvent(
                bid.getId(), traveler.getId(), bid.getSenderId(), reason));

        auditService.log("BID", bid.getId(), "PARCEL_REFUSED", traveler.getId(),
                Map.of("reason", reason != null ? reason : "",
                        "senderId", bid.getSenderId().toString()));

        return toResponse(bid, sender);
    }

    /** Upload une photo de colis pour le sender courant ; renvoie la clé S3. */
    public String uploadBidPhoto(String firebaseUid, MultipartFile file) {
        UserEntity sender = findUserByFirebaseUid(firebaseUid);
        return bidPhotoService.uploadPhoto(sender.getId(), file);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Délègue à {@link UserEntity#publicDisplayName()} : « Prénom N. », sinon le username.
     *
     * <p>Cette méthode retournait {@code null} pour un compte sans prénom, et le client
     * affichait alors « Expéditeur » sur l'écran « À traiter » : deux demandes de deux
     * personnes différentes y portaient le même nom. Elle rendait par ailleurs le patronyme
     * entier, là où tous les autres écrans l'abrègent.
     */
    private String buildSenderName(UserEntity user) {
        if (user == null) return null;
        return user.publicDisplayName();
    }

    private UserEntity findUserByFirebaseUid(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }

    private AnnouncementEntity findAnnouncement(UUID id) {
        return announcementRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));
    }

    private BidEntity findBid(UUID id) {
        return bidRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found", "Demande introuvable"));
    }

    private void requireTravelerOwnsAnnouncement(UserEntity traveler, AnnouncementEntity announcement) {
        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à effectuer cette action");
        }
    }

    private void requireBidStatus(BidEntity bid, BidStatus expected) {
        if (bid.getStatus() != expected) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "invalid-bid-status", "Invalid Bid Status",
                    "Cette action n'est pas possible pour un bid en statut " + bid.getStatus());
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            // Use the last value added by the trusted proxy — the client cannot spoof it
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    private static final String TRACKING_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final java.security.SecureRandom SECURE_RNG = new java.security.SecureRandom();

    static String generateTrackingNumber() {
        StringBuilder sb = new StringBuilder("DON-");
        for (int i = 0; i < 8; i++) {
            sb.append(TRACKING_CHARS.charAt(SECURE_RNG.nextInt(TRACKING_CHARS.length())));
        }
        return sb.toString();
    }

    BidResponse toResponse(BidEntity bid, UserEntity sender) {
        return toResponse(bid, sender, null);
    }

    private static final java.util.Set<BidStatus> PHONE_VISIBLE_STATUSES = java.util.EnumSet.of(
            BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.COMPLETED);

    /** Valeurs programmatiques (non-libres) de {@code CancellationEntity.reason} écrites par les
     * flux HANDOVER qui n'annulent PAS le trajet entier (no-show expéditeur, annulation après
     * remise) — cf. {@code CancellationService#reportSenderNoShow} et le flux "cancel after
     * handover". Sert à exclure ces cancellations quand on détecte la cancellation "trajet
     * annulé" pour {@link BidResponse#tripCancellationId()} : `reason` y est du texte libre
     * saisi par le voyageur, donc on ne peut identifier positivement "trajet annulé" que par
     * élimination des seules autres valeurs jamais écrites sur une cancellation HANDOVER. */
    private static final java.util.Set<String> NON_TRIP_HANDOVER_REASONS = java.util.Set.of(
            CancellationReason.SENDER_NO_SHOW.name(),
            CancellationReason.SENDER_CANCEL_AFTER_HANDOVER.name(),
            CancellationReason.TRAVELER_CANCEL_AFTER_HANDOVER.name());

    /** Reasons de {@code CancellationEntity.reason} écrites par le flux "rematch bid-only"
     * (voyageur annule le transport d'un bid payé, ou refuse une demande payée) — le trajet
     * (l'annonce) N'est PAS annulé dans ces cas, seul ce bid l'est. Contrairement à
     * {@link #NON_TRIP_HANDOVER_REASONS}, ces cancellations ouvrent quand même droit au
     * rematch : cf. {@code BidLostRematchListener}. */
    private static final java.util.Set<String> REMATCH_BID_REASONS = java.util.Set.of(
            CancellationReason.BID_CANCELLED_BY_TRAVELER.name(),
            CancellationReason.BID_REJECTED_AFTER_PAYMENT.name());

    /** Numéro révélé en clair seulement si l'offre est acceptée ou au-delà, sinon null. */
    static String phoneForStatus(String phone, BidStatus status) {
        if (phone == null) return null;
        return PHONE_VISIBLE_STATUSES.contains(status) ? phone : null;
    }

    /**
     * Le numéro de cet utilisateur est-il communicable pour ce statut ? Ne lit rien
     * dans Firebase : le client reçoit un booléen pour décider d'afficher son bouton
     * d'appel, et ne demande le numéro qu'au tap (cf. {@link #getCounterpartyPhone}).
     *
     * <p>Faux aussi lorsque l'utilisateur a masqué son numéro dans ses réglages de
     * confidentialité : le bouton d'appel disparaît alors chez la contrepartie, mais
     * la messagerie Yadony reste ouverte. Le booléen et {@link #getCounterpartyPhone}
     * appliquent la même règle — le second est l'autorité, celui-ci n'est qu'un
     * indice d'affichage.
     */
    private static boolean phoneAvailableForStatus(UserEntity user, BidStatus status) {
        return user != null && !user.isHidePhoneNumber() && PHONE_VISIBLE_STATUSES.contains(status);
    }

    BidResponse toResponse(BidEntity bid, UserEntity sender, UUID callerId) {
        String senderName = buildSenderName(sender);
        boolean senderPhoneAvailable = phoneAvailableForStatus(sender, bid.getStatus());
        Integer senderTotalShipments = sender != null ? sender.getTotalShipments() : null;
        boolean senderKycVerified = sender != null
                && sender.getKycStatus() == com.yadony.api.auth.KycStatus.VERIFIED;
        boolean senderIsProAccount = sender != null && sender.isProAccount();
        boolean senderKiloPro = sender != null && sender.isKiloPro();
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
        String departureCity = announcement != null ? announcement.getDepartureCity() : "Inconnu";
        String arrivalCity = announcement != null ? announcement.getArrivalCity() : "Inconnu";
        java.time.LocalDate departureDate = announcement != null ? announcement.getDepartureDate() : null;
        java.time.LocalTime departureTime = announcement != null ? announcement.getDepartureTime() : null;
        java.time.LocalTime arrivalTime = announcement != null ? announcement.getArrivalTime() : null;
        java.time.OffsetDateTime departureAt = announcement != null ? announcement.getDepartureAt() : null;
        java.math.BigDecimal pricePerKg = announcement != null ? announcement.getPricePerKg() : null;
        // Bid issu d'une négociation : prix au kilo (et donc net) figés sur le prix
        // négocié, pour ne pas dériver si l'annonce dédiée ouvre son surplus (réécrit
        // son pricePerKg) ou si le trajet lié a un tarif catalogue différent.
        if (bid.getNegotiatedNetEur() != null && bid.getWeightKg() != null
                && bid.getWeightKg().signum() > 0) {
            pricePerKg = bid.getNegotiatedNetEur()
                    .divide(bid.getWeightKg(), 2, java.math.RoundingMode.HALF_UP);
        }
        com.yadony.api.matching.TransportMode transportMode = announcement != null ? announcement.getTransportMode() : null;
        String confirmationCode = (callerId != null && callerId.equals(bid.getSenderId()))
                ? bid.getConfirmationCode() : null;
        // Le code de retour n'est visible que par l'expéditeur (qui le communique au voyageur).
        String returnCode = (callerId != null && callerId.equals(bid.getSenderId()))
                ? bid.getReturnCode() : null;

        UserEntity traveler = (announcement != null)
                ? userRepository.findById(announcement.getTravelerId()).orElse(null)
                : null;
        UUID travelerId = traveler != null ? traveler.getId() : null;
        String travelerName = buildSenderName(traveler);
        boolean travelerPhoneAvailable = phoneAvailableForStatus(traveler, bid.getStatus());
        boolean travelerKycVerified = traveler != null
                && traveler.getKycStatus() == com.yadony.api.auth.KycStatus.VERIFIED;
        boolean travelerIsProAccount = traveler != null && traveler.isProAccount();
        boolean travelerKiloPro = traveler != null && traveler.isKiloPro();
        Integer travelerTotalTrips = traveler != null ? traveler.getTotalTrips() : null;
        java.math.BigDecimal travelerAverageRating = traveler != null ? traveler.getAverageRating() : null;

        boolean senderHasRated = ratingRepository.existsByBidIdAndRaterId(bid.getId(), bid.getSenderId());
        boolean travelerHasRated = travelerId != null
                && ratingRepository.existsByBidIdAndRaterId(bid.getId(), travelerId);

        // Une seule requête pour les 2 cancellations possibles du bid (HANDOVER +
        // DELIVERY, UNIQUE(bid_id, scope) garantit au plus 2 lignes), partitionnées
        // en mémoire — évite un second aller-retour DB par bid sur les endpoints de liste.
        java.util.List<CancellationEntity> bidCancellations = cancellationRepository.findAllByBidId(bid.getId());
        var cancellation = bidCancellations.stream()
                .filter(c -> c.getScope() == CancellationScope.HANDOVER)
                .findFirst().orElse(null);
        String cancellationNoShowStatus = cancellation != null
                ? cancellation.getNoShowStatus().name()
                : null;
        java.time.OffsetDateTime contestationDeadline = cancellation != null
                ? cancellation.getContestationDeadline()
                : null;

        var deliveryCancellation = bidCancellations.stream()
                .filter(c -> c.getScope() == CancellationScope.DELIVERY)
                .findFirst().orElse(null);
        String deliveryNoShowStatus = deliveryCancellation != null
                ? deliveryCancellation.getNoShowStatus().name()
                : null;
        java.time.OffsetDateTime deliveryContestationDeadline = deliveryCancellation != null
                ? deliveryCancellation.getContestationDeadline()
                : null;
        Boolean deliveryNoShowReportedByTraveler = deliveryCancellation != null
                ? "RECIPIENT_NO_SHOW".equals(deliveryCancellation.getReason())
                : null;

        // La cancellation "trajet annulé" (RematchService/cancelTrip, et depuis le fix
        // account-deletion, CancellationService#cancelAnnouncementForDeletedTraveler avec reason
        // TRAVELER_ACCOUNT_DELETED) est la même ligne HANDOVER que ci-dessus (contrainte
        // UNIQUE(bid_id, scope) : au plus une par bid) — MAIS elle partage son scope/noShowStatus
        // par défaut avec d'autres flux (no-show, annulation après remise), donc `reason` seul
        // (texte libre côté cancelTrip) n'est PAS un discriminant positif fiable. On combine donc
        // announcement CANCELLED ET reason qui n'est PAS l'une des constantes programmatiques des
        // autres flux HANDOVER (SENDER_NO_SHOW, *_CANCEL_AFTER_HANDOVER — les seules valeurs
        // non-libres jamais écrites sur une cancellation HANDOVER, cf. CancellationService).
        // Historique : avant ce fix, la suppression de compte passait par un bulk UPDATE SQL
        // (AnnouncementRepository#cancelOpenAnnouncementsByUserId, supprimée) qui basculait
        // l'annonce à CANCELLED sans jamais créer de CancellationEntity — le double signal
        // ci-dessous reste donc nécessaire pour les lignes déjà annulées par cet ancien mécanisme.
        //
        // Rematch bid-only (Task 4) : en plus du cas "trajet annulé" ci-dessus, une cancellation
        // HANDOVER dont `reason` est BID_CANCELLED_BY_TRAVELER ou BID_REJECTED_AFTER_PAYMENT
        // ouvre AUSSI droit au rematch, même si l'annonce reste ACTIVE — seul ce bid a été
        // annulé/refusé par le voyageur, pas le trajet entier (cf. BidLostRematchListener).
        boolean tripWasCancelled = announcement != null && announcement.getStatus() == AnnouncementStatus.CANCELLED;
        boolean isTripCancellation = cancellation != null
                && cancellation.getReason() != null
                && !NON_TRIP_HANDOVER_REASONS.contains(cancellation.getReason());
        boolean isRematchBidCancellation = cancellation != null
                && cancellation.getReason() != null
                && REMATCH_BID_REASONS.contains(cancellation.getReason());
        boolean isRematchCancellation = (tripWasCancelled && isTripCancellation) || isRematchBidCancellation;
        UUID tripCancellationId = isRematchCancellation ? cancellation.getId() : null;
        String tripCancellationRematchStatus = isRematchCancellation
                ? cancellation.getRematchStatus()
                : null;

        // Compute total net: sum of grid items + KG part (for display in Flutter)
        java.math.BigDecimal gridNet = bidGridItemRepository.findByBidId(bid.getId()).stream()
                .map(i -> i.getUnitPriceNetSnapshot().multiply(java.math.BigDecimal.valueOf(i.getQuantity())))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.math.BigDecimal kgNet = (bid.getWeightKg() != null && pricePerKg != null)
                ? bid.getWeightKg().multiply(pricePerKg)
                : java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalNetAmountEur = gridNet.add(kgNet)
                .setScale(2, java.math.RoundingMode.HALF_UP);

        // Montant total payé par l'EXPÉDITEUR = net + commission, dans les DEUX
        // modes de paiement. En carte, il règle le brut à Yadony. En espèces, il
        // remet ce même brut en main propre au voyageur, et Yadony prélève ensuite
        // la commission sur le solde de ce dernier : c'est donc bien l'expéditeur
        // qui la paie, indirectement, et le voyageur conserve le net.
        //
        // Ne JAMAIS retomber sur le net pour le mode CASH : cela annonçait à
        // l'expéditeur un montant inférieur à celui qu'il doit réellement remettre,
        // et à l'endroit le plus sensible du parcours.
        //
        // Le taux figé (commissionRate) n'existe qu'après création du paiement ;
        // avant (PENDING, pas de rate) on résout en direct — ainsi le brut est
        // toujours calculable côté serveur, sans que l'app n'ait besoin du net
        // pour le dériver.
        java.math.BigDecimal effectiveRate = bid.getCommissionRate();
        if (effectiveRate == null && announcement != null) {
            effectiveRate = commissionRateResolver.resolve(announcement.getTravelerId(), bid.getSenderId());
        }
        java.math.BigDecimal totalSenderAmountEur = (effectiveRate != null)
                ? com.yadony.api.payments.PriceBreakdown.fromNet(totalNetAmountEur, effectiveRate).gross()
                : totalNetAmountEur;
        // Tarif/kg affiché à l'expéditeur (brut), dérivé du total brut.
        java.math.BigDecimal pricePerKgSenderEur =
                (totalSenderAmountEur != null && bid.getWeightKg() != null
                        && bid.getWeightKg().signum() > 0)
                        ? totalSenderAmountEur.divide(bid.getWeightKg(), 2, java.math.RoundingMode.HALF_UP)
                        : null;
        // SÉCURITÉ (règle métier) : l'expéditeur ne reçoit JAMAIS le net du
        // voyageur — ni le tarif/kg net, ni le total net. Seulement le brut.
        if (callerId != null && callerId.equals(bid.getSenderId())) {
            pricePerKg = null;
            totalNetAmountEur = null;
        }

        return new BidResponse(
                bid.getId(),
                bid.getAnnouncementId(),
                bid.getSenderId(),
                senderName,
                senderPhoneAvailable,
                senderTotalShipments,
                senderKycVerified,
                senderIsProAccount,
                senderKiloPro,
                bid.getWeightKg(),
                bid.getDescription(),
                bid.getContentCategory(),
                bid.getRecipientName(),
                // Même masquage que sender/traveler : le téléphone du destinataire
                // (tiers non consentant) n'est révélé qu'à partir de l'acceptation,
                // sinon un voyageur pourrait moissonner des numéros via des offres
                // PENDING qu'il refuse ensuite.
                phoneForStatus(bid.getRecipientPhone(), bid.getStatus()),
                bid.getStatus().name(),
                bid.getRejectionReason(),
                bid.getHandoverLocation(),
                bid.getHandoverDeadline(),
                bid.isVoyageurConfirmed(),
                bid.getDisclaimerSignedAt(),
                bid.getCreatedAt(),
                bid.getUpdatedAt(),
                departureCity,
                arrivalCity,
                departureDate,
                departureTime,
                arrivalTime,
                pricePerKg,
                pricePerKgSenderEur,
                transportMode,
                bid.getTrackingNumber(),
                bid.getTrackingToken(),
                confirmationCode,
                bid.isConfirmationCodePublicEnabled(),
                travelerId,
                travelerName,
                travelerPhoneAvailable,
                travelerKycVerified,
                travelerIsProAccount,
                travelerKiloPro,
                travelerTotalTrips,
                travelerAverageRating,
                senderHasRated,
                travelerHasRated,
                bid.getConfirmationCodeRefreshCount(),
                bid.getConfirmationCodeRefreshWindowStart(),
                cancellationNoShowStatus,
                contestationDeadline,
                deliveryNoShowStatus,
                deliveryContestationDeadline,
                deliveryNoShowReportedByTraveler,
                bid.getPaymentMethod() != null ? bid.getPaymentMethod().name() : "STRIPE",
                bid.getPricingMode(),
                totalNetAmountEur,
                totalSenderAmountEur,
                departureAt,
                returnCode,
                bid.getReturnDeadline(),
                bid.getReturnedAt(),
                storageService.avatarUrl(sender != null ? sender.getAvatarUrl() : null),
                storageService.avatarUrl(traveler != null ? traveler.getAvatarUrl() : null),
                bidPhotoService.activePhotos(bid.getId()),
                tripCancellationId,
                tripCancellationRematchStatus,
                bid.getCurrency()
        );
    }
}
