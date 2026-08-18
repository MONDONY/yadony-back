package com.yadony.api.matching;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.ContentCategoryNormalizer;
import com.yadony.api.matching.dto.BidCheckoutRequest;
import com.yadony.api.matching.dto.BidCheckoutResponse;
import com.yadony.api.matching.dto.BidGridItemRequest;
import com.yadony.api.payments.PaymentService;
import com.yadony.api.payments.dto.CreatePaymentRequest;
import com.yadony.api.payments.dto.PaymentResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class BidCheckoutService {

    static final int AWAITING_PAYMENT_GRACE_MINUTES = 15;

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final PaymentService paymentService;
    private final String stripePublishableKey;
    private final BidGridItemRepository bidGridItemRepository;
    private final AnnouncementPriceGridItemRepository annGridItemRepository;
    private final BidPhotoService bidPhotoService;

    public BidCheckoutService(BidRepository bidRepository,
                              AnnouncementRepository announcementRepository,
                              UserRepository userRepository,
                              AuditService auditService,
                              PaymentService paymentService,
                              @Value("${stripe.publishable-key:}") String stripePublishableKey,
                              BidGridItemRepository bidGridItemRepository,
                              AnnouncementPriceGridItemRepository annGridItemRepository,
                              BidPhotoService bidPhotoService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.paymentService = paymentService;
        this.stripePublishableKey = stripePublishableKey;
        this.bidGridItemRepository = bidGridItemRepository;
        this.annGridItemRepository = annGridItemRepository;
        this.bidPhotoService = bidPhotoService;
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public BidCheckoutResponse checkout(String firebaseUid,
                                       BidCheckoutRequest req,
                                       HttpServletRequest httpRequest) {

        UserEntity sender = userRepository.findByFirebaseUid(firebaseUid)
            .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "user-not-found", "User Not Found", "Utilisateur introuvable"));

        if (!sender.getRoles().contains(Role.SENDER)) {
            sender.getRoles().add(Role.SENDER);
            userRepository.save(sender);
        }

        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(req.announcementId())
            .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        if (announcement.getStatus() != AnnouncementStatus.ACTIVE) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                "announcement-not-active", "Announcement Not Active",
                "Cette annonce n'est plus disponible");
        }

        // Dedicated trip (tied to a negotiation): a fresh dedicated trip is ACTIVE with
        // availableKg == the negotiating sender's reserved weight. Without this guard a
        // third-party sender could drive a Stripe escrow against that reserved capacity.
        // Other senders may only checkout against the surplus once the traveler opens it.
        if (announcement.isClosedToThirdPartyBids()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "surplus-not-open",
                "Surplus Not Open",
                "La capacité supplémentaire de ce trajet n'est pas ouverte aux autres expéditeurs");
        }

        // Le sender réservé a déjà son colis sur ce trajet dédié : il ne peut pas
        // ouvrir un escrow sur le surplus de son propre trajet (deux colis sinon).
        if (announcement.isReservedSender(sender.getId())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                "reserved-sender-cannot-bid", "Reserved Sender Cannot Bid",
                "Vous avez déjà un colis réservé sur ce trajet");
        }

        if (announcement.getTravelerId().equals(sender.getId())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                "cannot-bid-own-announcement", "Cannot Bid Own Announcement",
                "Vous ne pouvez pas faire une demande sur votre propre annonce");
        }

        // Normalisé AVANT le contrôle de refus (C2) — cf. BidService.createBid pour le
        // raisonnement complet (announcement.refusedTypes est déjà normalisé).
        String normalizedContentCategory = ContentCategoryNormalizer.normalizeJoined(req.contentCategory());
        BidContentRules.assertNotRefused(announcement, normalizedContentCategory);

        // Idempotency: if an AWAITING_PAYMENT bid exists (e.g. payment sheet crashed),
        // resume it instead of creating a new one.
        Optional<BidEntity> awaitingBid = bidRepository.findBySenderIdAndAnnouncementIdAndStatus(
            sender.getId(), announcement.getId(), BidStatus.AWAITING_PAYMENT);
        if (awaitingBid.isPresent()) {
            BidEntity existing = awaitingBid.get();
            CreatePaymentRequest resumeReq = new CreatePaymentRequest();
            resumeReq.setBidId(existing.getId());
            resumeReq.setSavePaymentMethod(req.savePaymentMethod());
            PaymentResponse resumed = paymentService.createEscrow(resumeReq, firebaseUid);
            return new BidCheckoutResponse(
                existing.getId(),
                resumed.getClientSecret(),
                stripePublishableKey,
                existing.getAwaitingPaymentExpiresAt(),
                resumed.getCurrency(),
                resumed.getPaymentMethodTypes()
            );
        }

        boolean alreadyHasBid = bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(
            sender.getId(), announcement.getId(),
            List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
                    BidStatus.NEGOTIATING));
        if (alreadyHasBid) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                "already-bid", "Demande existante",
                "Vous avez déjà une demande en cours pour ce trajet");
        }

        // KG_FREE (« kilo libre ») : capacité non bornée — availableKg est une
        // valeur stockée factice (>= 1) sans signification. On ne plafonne donc
        // pas le poids dans ce cas, sinon le poids saisi librement (>= 5) serait
        // rejeté à tort par le « weight-exceeds-capacity » 422.
        boolean isKgFree = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
        if (!isKgFree && req.weightKg() != null
                && req.weightKg().compareTo(announcement.getAvailableKg()) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "weight-exceeds-capacity", "Weight Exceeds Capacity",
                "Poids demandé supérieur à la capacité disponible");
        }

        List<BidGridItemRequest> gridItems = req.gridItems() != null ? req.gridItems() : List.of();
        boolean hasGrid = !gridItems.isEmpty();
        boolean hasKg   = req.weightKg() != null && req.weightKg().compareTo(BigDecimal.ZERO) > 0;

        if (!hasGrid && !hasKg) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "bid-empty", "Bid Empty",
                "Au moins un article ou un poids doit être renseigné");
        }

        BidPricingMode bidMode = hasGrid && hasKg ? BidPricingMode.MIXED
                               : hasGrid          ? BidPricingMode.GRID
                               :                    BidPricingMode.KG;

        String ip = resolveClientIp(httpRequest);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(hasKg ? req.weightKg() : null);
        bid.setPricingMode(bidMode);
        bid.setDescription(req.description());
        bid.setContentCategory(normalizedContentCategory);
        bid.setRecipientName(req.recipientName());
        bid.setRecipientPhone(req.recipientPhone());
        bid.setDisclaimerSignedAt(now);
        bid.setDisclaimerSignedIp(ip);
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(now.plusMinutes(AWAITING_PAYMENT_GRACE_MINUTES));

        BidEntity saved = bidRepository.save(bid);

        // Traitement grid items et calcul gridNet
        BigDecimal gridNet = BigDecimal.ZERO;
        if (hasGrid) {
            List<BidGridItemEntity> bidGridItems = new ArrayList<>();
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
                gridNet = gridNet.add(annItem.getUnitPriceNet().multiply(BigDecimal.valueOf(gReq.quantity())));
            }
            bidGridItemRepository.saveAll(bidGridItems);
        }

        bidPhotoService.attachPhotos(saved.getId(), sender.getId(), req.photoKeys());

        BigDecimal kgNet = hasKg && announcement.getPricePerKg() != null
            ? saved.getWeightKg().multiply(announcement.getPricePerKg())
            : BigDecimal.ZERO;
        BigDecimal totalNet = gridNet.add(kgNet);

        // Délégation à PaymentService — crée le PaymentIntent Stripe
        CreatePaymentRequest paymentReq = new CreatePaymentRequest();
        paymentReq.setBidId(saved.getId());
        paymentReq.setTotalNetEur(totalNet);
        paymentReq.setSavePaymentMethod(req.savePaymentMethod());
        PaymentResponse paymentResp = paymentService.createEscrow(paymentReq, firebaseUid);

        // Backfill paymentIntentId on the bid so schedulers can find it
        saved.setPaymentIntentId(paymentResp.getStripePaymentIntentId());
        bidRepository.save(saved);

        return new BidCheckoutResponse(
            saved.getId(),
            paymentResp.getClientSecret(),
            stripePublishableKey,
            saved.getAwaitingPaymentExpiresAt(),
            paymentResp.getCurrency(),
            paymentResp.getPaymentMethodTypes()
        );
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
}
