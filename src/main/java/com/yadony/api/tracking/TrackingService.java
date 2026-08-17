package com.yadony.api.tracking;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.tracking.dto.ConfirmCodeResponse;
import com.yadony.api.tracking.dto.ConfirmDeliveryRequest;
import com.yadony.api.tracking.dto.QrCodeResponse;
import com.yadony.api.tracking.dto.QrScanRequest;
import com.yadony.api.tracking.dto.TrackingEventResponse;
import com.yadony.api.tracking.dto.TrackingSearchResponse;
import com.yadony.api.tracking.dto.TripScanHistoryEntryDto;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class TrackingService {

    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final TrackingEventRepository trackingEventRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.yadony.api.common.StorageService storageService;
    private final NotificationDispatcher notificationDispatcher;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_CODE_ATTEMPTS = 3;
    private static final int MAX_CODE_REFRESHES_PER_DAY = 5;

    @Value("${app.base-url}")
    private String appBaseUrl;

    public TrackingService(BidRepository bidRepository,
                           PaymentRepository paymentRepository,
                           UserRepository userRepository,
                           AnnouncementRepository announcementRepository,
                           TrackingEventRepository trackingEventRepository,
                           AuditService auditService,
                           ApplicationEventPublisher eventPublisher,
                           com.yadony.api.common.StorageService storageService,
                           NotificationDispatcher notificationDispatcher) {
        this.bidRepository = bidRepository;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.storageService = storageService;
        this.notificationDispatcher = notificationDispatcher;
    }

    public QrCodeResponse getQrCode(UUID bidId, String firebaseUid) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Transaction introuvable"));

        UserEntity currentUser = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        if (!currentUser.getId().equals(bid.getSenderId())) {
            throw new YadonyBusinessException(
                    HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Accès interdit à ce QR code");
        }

        if (bid.getQrToken() == null) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "qr-not-ready", "QR Not Ready",
                    "Le QR code n'est pas encore disponible pour cette transaction");
        }

        String scanUrl = appBaseUrl + "/api/v1/tracking/" + bidId + "/scan";
        String qrBase64 = generateQrBase64(scanUrl);

        return new QrCodeResponse(bidId, scanUrl, qrBase64);
    }

    public TrackingSearchResponse searchByTrackingNumber(String trackingNumber, String firebaseUid) {
        String normalized = trackingNumber.trim().toUpperCase();
        BidEntity bid = bidRepository.findByTrackingNumber(normalized)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "tracking-not-found", "Tracking Not Found",
                        "Aucun colis trouvé avec le numéro : " + normalized));

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                        "Annonce introuvable"));

        // Le numéro de suivi n'est pas un secret : sans contrôle de propriété, n'importe
        // qui pourrait lire le statut du colis et les instructions de retrait (adresse
        // physique). Seuls l'expéditeur et le voyageur qui transporte y ont droit.
        UserEntity currentUser = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        boolean isSender = bid.getSenderId().equals(currentUser.getId());
        boolean isTraveler = announcement.getTravelerId().equals(currentUser.getId());
        if (!isSender && !isTraveler) {
            throw new YadonyBusinessException(
                    HttpStatus.FORBIDDEN, "tracking/forbidden", "Forbidden",
                    "Ce colis n'est pas le vôtre");
        }

        java.util.Optional<PaymentEntity> paymentOpt = paymentRepository.findByBidId(bid.getId());

        String currentStep;
        String stepLabel;

        if (bid.getStatus() == BidStatus.REJECTED) {
            currentStep = "REJECTED";
            stepLabel = "Refusé";
        } else if (bid.getStatus() == BidStatus.CANCELLED) {
            currentStep = "CANCELLED";
            stepLabel = "Annulé";
        } else if (bid.getStatus() == BidStatus.PENDING) {
            currentStep = "PENDING";
            stepLabel = "En attente de confirmation";
        } else if (bid.getStatus() == BidStatus.PAYMENT_ESCROWED) {
            currentStep = "PAYMENT_ESCROWED";
            stepLabel = "Paiement gelé — confirmation voyageur en attente";
        } else {
            // ACCEPTED — calcul de base depuis paiement/confirmation
            if (paymentOpt.isEmpty() || paymentOpt.get().getStatus() == PaymentStatus.PENDING) {
                currentStep = "ACCEPTED";
                stepLabel = "Voyage confirmé — paiement en attente";
            } else if (paymentOpt.get().getStatus() == PaymentStatus.ESCROW && !bid.isVoyageurConfirmed()) {
                currentStep = "PAYMENT_SECURED";
                stepLabel = "Paiement sécurisé — remise prévue";
            } else if (paymentOpt.get().getStatus() == PaymentStatus.ESCROW && bid.isVoyageurConfirmed()) {
                currentStep = "IN_TRANSIT";
                stepLabel = "En transit";
            } else {
                currentStep = "DELIVERED";
                stepLabel = "Livré";
            }

            // Priorité aux scans réels — ils reflètent l'état physique du colis
            List<TrackingEventEntity> events =
                    trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId());
            boolean hasArrivee = events.stream()
                    .anyMatch(e -> e.getEventType() == TrackingEventType.ARRIVEE);
            boolean hasTransit = events.stream()
                    .anyMatch(e -> e.getEventType() == TrackingEventType.TRANSIT);
            boolean hasDepart = events.stream()
                    .anyMatch(e -> e.getEventType() == TrackingEventType.DEPART);

            if (hasArrivee) {
                currentStep = "DELIVERED";
                stepLabel = "Livraison confirmée ✓";
            } else if (hasTransit) {
                currentStep = "IN_TRANSIT";
                stepLabel = "En transit";
            } else if (hasDepart) {
                currentStep = "DEPARTED";
                stepLabel = "Colis remis au voyageur — en route";
            }
        }

        String paymentStatus = paymentOpt.map(p -> p.getStatus().name()).orElse("NONE");

        return new TrackingSearchResponse(
                bid.getTrackingNumber(),
                bid.getId(),
                announcement.getDepartureCity(),
                announcement.getArrivalCity(),
                currentStep,
                stepLabel,
                paymentStatus,
                announcement.getArrivalInstructions()
        );
    }

    @Transactional
    public TrackingEventResponse processScan(QrScanRequest request, String firebaseUid) {
        BidEntity bid = bidRepository.findById(request.bidId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Transaction introuvable"));

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                        "Annonce introuvable"));

        UserEntity traveler = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul le voyageur de cette annonce peut scanner le QR code");
        }

        if (bid.getStatus() != BidStatus.ACCEPTED
                && bid.getStatus() != BidStatus.HANDED_OVER
                && bid.getStatus() != BidStatus.IN_TRANSIT) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "bid-not-accepted",
                    "Bid Not Accepted", "Ce colis n'est pas dans un état scannable");
        }

        if (bid.getQrToken() == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "qr-not-ready",
                    "QR Not Ready", "Le QR code n'est pas encore disponible");
        }

        if (request.eventType() == TrackingEventType.ARRIVEE) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "use-confirm-delivery",
                    "Use Confirm Delivery",
                    "L'arrivée doit être confirmée avec le code de confirmation fourni par l'expéditeur");
        }

        if (request.offlineTimestamp() != null
                && request.offlineTimestamp().isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5))) {
            // 5-minute tolerance accounts for typical client clock skew while still
            // rejecting clearly fraudulent backdating attempts.
            auditService.log("TRACKING_EVENT", bid.getId(), "FRAUD_FUTURE_TIMESTAMP",
                    traveler.getId(), Map.of("offlineTimestamp", request.offlineTimestamp().toString()));
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-timestamp",
                    "Invalid Timestamp", "Le timestamp du scan ne peut pas être dans le futur");
        }

        // photoUrl from client must be an internal S3 key (e.g. tracking/{bidId}/...).
        // Reject absolute URLs to prevent attackers from injecting external content
        // that would be displayed on the public recipient page.
        String photoKey = request.photoUrl();
        if (photoKey != null && !photoKey.isBlank()) {
            String expectedPrefix = "tracking/" + bid.getId() + "/";
            if (photoKey.startsWith("http://") || photoKey.startsWith("https://")
                    || photoKey.contains("..")
                    || !photoKey.startsWith(expectedPrefix)) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-photo-url", "Invalid Photo URL",
                        "L'URL de la photo doit être une clé S3 valide pour ce bid");
            }
        }

        TrackingEventEntity event = new TrackingEventEntity();
        event.setBidId(bid.getId());
        event.setEventType(request.eventType());
        event.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
        event.setGpsLat(request.gpsLat());
        event.setGpsLon(request.gpsLon());
        event.setGpsLabel(cleanGpsLabel(request.gpsLabel()));
        event.setPhotoUrl(photoKey);
        if (request.offlineTimestamp() != null) {
            event.setOfflineTimestamp(request.offlineTimestamp());
            event.setSyncedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        trackingEventRepository.save(event);

        auditService.log("TRACKING_EVENT", event.getId(), "SCAN_" + request.eventType(),
                traveler.getId(), Map.of(
                        "bidId", bid.getId().toString(),
                        "eventType", request.eventType().name(),
                        "offline", String.valueOf(request.offlineTimestamp() != null)));

        if (request.eventType() == TrackingEventType.DEPART && bid.getStatus() == BidStatus.ACCEPTED) {
            bid.setStatus(BidStatus.HANDED_OVER);
            if (bid.getConfirmationCode() == null) {
                String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
                bid.setConfirmationCode(code);
                bid.setConfirmationCodeAttempts(0);
                bid.setConfirmationCodeExpiry(computeCodeExpiry(announcement));
                notificationDispatcher.notifyUser(
                        bid.getSenderId(),
                        "Code de livraison disponible",
                        "Le voyageur est prêt à remettre votre colis. Partagez le code.",
                        Map.of("type", "CONFIRMATION_CODE_READY", "bidId", bid.getId().toString()));
                auditService.log("TRACKING_CONFIRMATION_CODE", bid.getId(), "CODE_GENERATED",
                        traveler.getId(), Map.of("bidId", bid.getId().toString()));
            }
            bidRepository.save(bid);
        }

        if (request.eventType() == TrackingEventType.TRANSIT
                && (bid.getStatus() == BidStatus.ACCEPTED || bid.getStatus() == BidStatus.HANDED_OVER)) {
            bid.setStatus(BidStatus.IN_TRANSIT);
            bidRepository.save(bid);
        }

        return toEventResponse(event, null);
    }

    @Transactional(readOnly = true)
    public List<TrackingEventResponse> getEvents(UUID bidId, String firebaseUid) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Transaction introuvable"));

        UserEntity currentUser = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                        "Annonce introuvable"));

        boolean isSender = currentUser.getId().equals(bid.getSenderId());
        boolean isTraveler = currentUser.getId().equals(announcement.getTravelerId());
        if (!isSender && !isTraveler) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Accès interdit à ces événements de tracking");
        }

        return trackingEventRepository.findByBidIdOrderByScannedAtAsc(bidId).stream()
                .map(e -> toEventResponse(e, resolvePhotoUrl(e.getPhotoUrl())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TripScanHistoryEntryDto> getTripScanHistory(UUID announcementId, String firebaseUid) {
        AnnouncementEntity announcement = announcementRepository.findById(announcementId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                        "Annonce introuvable"));

        UserEntity currentUser = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        if (!announcement.getTravelerId().equals(currentUser.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Accès interdit à l'historique de ce trajet");
        }

        List<BidEntity> bids = bidRepository.findByAnnouncementId(announcementId);
        if (bids.isEmpty()) {
            return List.of();
        }
        Map<UUID, BidEntity> bidsById = bids.stream()
                .collect(Collectors.toMap(BidEntity::getId, bid -> bid));
        List<UUID> bidIds = new java.util.ArrayList<>(bidsById.keySet());

        return trackingEventRepository.findByBidIdInOrderByScannedAtDesc(bidIds).stream()
                .map(event -> {
                    BidEntity bid = bidsById.get(event.getBidId());
                    return new TripScanHistoryEntryDto(
                            bid != null ? bid.getTrackingNumber() : null,
                            bid != null ? bid.getRecipientName() : null,
                            event.getEventType().name(),
                            event.getScannedAt());
                })
                .toList();
    }

    private TrackingEventResponse toEventResponse(TrackingEventEntity e, String resolvedPhotoUrl) {
        return new TrackingEventResponse(
                e.getId(), e.getBidId(), e.getEventType().name(),
                e.getScannedAt(), e.getGpsLat(), e.getGpsLon(), e.getGpsLabel(),
                resolvedPhotoUrl != null ? resolvedPhotoUrl : e.getPhotoUrl(),
                e.getOfflineTimestamp(), e.getCreatedAt());
    }

    private String cleanGpsLabel(String label) {
        if (label == null || label.isBlank()) return null;
        String trimmed = label.trim();
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }

    private String resolvePhotoUrl(String photoKey) {
        if (photoKey == null || photoKey.startsWith("http")) return photoKey;
        return storageService.generatePresignedUrl(photoKey, Duration.ofHours(1));
    }

    public ConfirmCodeResponse getConfirmationCode(UUID bidId, String firebaseUid) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Transaction introuvable"));

        UserEntity currentUser = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        if (!currentUser.getId().equals(bid.getSenderId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul l'expéditeur peut consulter le code de confirmation");
        }

        return new ConfirmCodeResponse(
                bid.getConfirmationCode(),
                bid.getConfirmationCodeExpiry(),
                bid.isConfirmationCodePublicEnabled());
    }

    @Transactional
    public ConfirmCodeResponse setConfirmationCodePublicVisible(UUID bidId, String firebaseUid, boolean visible) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Transaction introuvable"));

        UserEntity currentUser = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        if (!currentUser.getId().equals(bid.getSenderId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul l'expéditeur peut publier le code de confirmation");
        }

        if (bid.getConfirmationCode() == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-not-generated",
                    "Code Not Generated",
                    "Le code de confirmation n'est pas encore disponible — le voyageur doit d'abord scanner le départ");
        }

        bid.setConfirmationCodePublicEnabled(visible);
        bidRepository.save(bid);

        auditService.log("TRACKING_CONFIRMATION_CODE", bidId,
                visible ? "CODE_PUBLIC_ENABLED" : "CODE_PUBLIC_DISABLED",
                currentUser.getId(), Map.of("bidId", bidId.toString()));

        return new ConfirmCodeResponse(
                bid.getConfirmationCode(),
                bid.getConfirmationCodeExpiry(),
                bid.isConfirmationCodePublicEnabled());
    }

    @Transactional
    public ConfirmCodeResponse refreshConfirmationCode(UUID bidId, String firebaseUid) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Transaction introuvable"));

        UserEntity currentUser = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        if (!currentUser.getId().equals(bid.getSenderId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul l'expéditeur peut régénérer le code de confirmation");
        }

        if (bid.getStatus() != BidStatus.ACCEPTED
                && bid.getStatus() != BidStatus.HANDED_OVER
                && bid.getStatus() != BidStatus.IN_TRANSIT
                && bid.getStatus() != BidStatus.ARRIVED) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "bid-not-accepted",
                    "Bid Not Accepted", "Ce colis ne peut pas recevoir un nouveau code dans son état actuel");
        }

        if (bid.getConfirmationCode() == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-not-generated",
                    "Code Not Generated",
                    "Le code de confirmation n'est pas encore disponible — le voyageur doit d'abord scanner le départ");
        }

        // Rate-limit : 5 régénérations maximum par fenêtre de 24h glissante
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime windowStart = bid.getConfirmationCodeRefreshWindowStart();
        if (windowStart != null && windowStart.isAfter(now.minusHours(24))) {
            if (bid.getConfirmationCodeRefreshCount() >= MAX_CODE_REFRESHES_PER_DAY) {
                throw new YadonyBusinessException(HttpStatus.TOO_MANY_REQUESTS, "too-many-refreshes",
                        "Too Many Refreshes",
                        "Limite atteinte : 5 régénérations par 24h. Patientez avant de réessayer.");
            }
            bid.setConfirmationCodeRefreshCount(bid.getConfirmationCodeRefreshCount() + 1);
        } else {
            bid.setConfirmationCodeRefreshCount(1);
            bid.setConfirmationCodeRefreshWindowStart(now);
        }

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                        "Annonce introuvable"));

        String newCode = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        LocalDateTime newExpiry = computeCodeExpiry(announcement);
        bid.setConfirmationCode(newCode);
        bid.setConfirmationCodeAttempts(0);
        bid.setConfirmationCodeExpiry(newExpiry);
        bid.setConfirmationCodePublicEnabled(false);
        bidRepository.save(bid);

        auditService.log("TRACKING_CONFIRMATION_CODE", bidId, "CODE_REFRESHED",
                currentUser.getId(), Map.of("bidId", bidId.toString()));

        return new ConfirmCodeResponse(newCode, newExpiry, bid.isConfirmationCodePublicEnabled());
    }

    @Transactional
    public TrackingEventResponse confirmDelivery(UUID bidId, ConfirmDeliveryRequest request,
                                                 String firebaseUid) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Transaction introuvable"));

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                        "Annonce introuvable"));

        UserEntity traveler = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul le voyageur de cette annonce peut confirmer la livraison");
        }

        if (bid.getStatus() != BidStatus.ACCEPTED
                && bid.getStatus() != BidStatus.HANDED_OVER
                && bid.getStatus() != BidStatus.IN_TRANSIT
                && bid.getStatus() != BidStatus.ARRIVED) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "bid-not-accepted",
                    "Bid Not Accepted", "Ce colis ne peut pas être confirmé dans son état actuel");
        }

        if (bid.getConfirmationCode() == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-not-generated",
                    "Code Not Generated",
                    "Le code de confirmation n'est pas encore disponible — scannez d'abord le départ du colis");
        }

        if (bid.getConfirmationCodeExpiry() != null
                && LocalDateTime.now(ZoneOffset.UTC).isAfter(bid.getConfirmationCodeExpiry())) {
            bid.setConfirmationCode(null);
            bid.setConfirmationCodeExpiry(null);
            bid.setConfirmationCodeAttempts(0);
            bid.setConfirmationCodePublicEnabled(false);
            bidRepository.save(bid);
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-expired",
                    "Code Expired",
                    "Le code de confirmation a expiré — demandez à l'expéditeur de vous partager un nouveau code");
        }

        if (bid.getConfirmationCodeAttempts() >= MAX_CODE_ATTEMPTS) {
            bid.setConfirmationCode(null);
            bid.setConfirmationCodeAttempts(0);
            bid.setConfirmationCodePublicEnabled(false);
            bidRepository.save(bid);
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "too-many-attempts",
                    "Too Many Attempts",
                    "Trop de tentatives incorrectes — contactez l'expéditeur pour obtenir le code");
        }

        if (!bid.getConfirmationCode().equals(request.confirmationCode())) {
            bid.setConfirmationCodeAttempts(bid.getConfirmationCodeAttempts() + 1);
            bidRepository.save(bid);
            int remaining = MAX_CODE_ATTEMPTS - bid.getConfirmationCodeAttempts();
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-incorrect",
                    "Code Incorrect",
                    remaining > 0
                            ? "Code incorrect — " + remaining + " tentative(s) restante(s)"
                            : "Trop de tentatives — contactez l'expéditeur pour obtenir le code");
        }

        bid.setConfirmationCode(null);
        bid.setConfirmationCodeExpiry(null);
        bid.setConfirmationCodeAttempts(0);
        bid.setConfirmationCodePublicEnabled(false);
        bid.setStatus(BidStatus.COMPLETED);
        bidRepository.save(bid);

        TrackingEventEntity event = new TrackingEventEntity();
        event.setBidId(bid.getId());
        event.setEventType(TrackingEventType.ARRIVEE);
        event.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
        trackingEventRepository.save(event);

        eventPublisher.publishEvent(new DeliveryConfirmedEvent(bid.getId(), bid.getSenderId(), traveler.getId()));

        auditService.log("TRACKING_DELIVERY_CONFIRMED", event.getId(), "DELIVERY_CONFIRMED",
                traveler.getId(), Map.of("bidId", bid.getId().toString()));

        return toEventResponse(event, null);
    }

    private LocalDateTime computeCodeExpiry(AnnouncementEntity announcement) {
        if (announcement.getArrivalTime() != null) {
            // Heure d'arrivée connue → même jour que le départ + 24h de marge
            return announcement.getDepartureDate().atTime(announcement.getArrivalTime()).plusDays(1);
        }
        // Pas d'heure d'arrivée → 72h après le début du jour de départ (couvre J+1 + buffer)
        return announcement.getDepartureDate().atStartOfDay().plusDays(3);
    }

    private String generateQrBase64(String content) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 2,
                    EncodeHintType.CHARACTER_SET, "UTF-8"
            );
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, 400, 400, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new YadonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "qr-generation-error", "QR Generation Error",
                    "Erreur lors de la génération du QR code");
        }
    }
}
