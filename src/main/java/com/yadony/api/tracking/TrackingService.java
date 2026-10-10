package com.yadony.api.tracking;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
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
import com.yadony.api.tracking.events.ConfirmationCodeBlockedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.tracking.events.ParcelDepartedEvent;
import com.yadony.api.tracking.events.ParcelInTransitEvent;
import com.yadony.api.matching.reception.BidRecipientLinkRepository;
import com.yadony.api.matching.reception.ReceptionLinkStatus;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
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

    private static final Logger log = LoggerFactory.getLogger(TrackingService.class);

    /** Livraison confirmée avant le départ du trajet (FLUTTER-CB). */
    static final String TRIP_NOT_DEPARTED = "trip-not-departed";

    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final TrackingEventRepository trackingEventRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.yadony.api.common.StorageService storageService;
    private final NotificationDispatcher notificationDispatcher;
    private final MessagesResolver messagesResolver;
    private final BidRecipientLinkRepository recipientLinkRepository;

    private static final int MAX_CODE_ATTEMPTS = 3;
    private static final int MAX_CODE_REFRESHES_PER_DAY = 5;

    @Value("${app.base-url}")
    private String appBaseUrl;

    /**
     * Numéro de suivi obligatoire à la remise du colis (DEPART). Éteint par défaut : les apps
     * déjà installées ne l'envoient pas. À allumer quand la nouvelle version est déployée.
     */
    @Value("${yadony.tracking.require-number-on-depart:false}")
    private boolean requireNumberOnDepart;

    public TrackingService(BidRepository bidRepository,
                           PaymentRepository paymentRepository,
                           UserRepository userRepository,
                           AnnouncementRepository announcementRepository,
                           TrackingEventRepository trackingEventRepository,
                           AuditService auditService,
                           ApplicationEventPublisher eventPublisher,
                           com.yadony.api.common.StorageService storageService,
                           NotificationDispatcher notificationDispatcher,
                           MessagesResolver messagesResolver,
                           BidRecipientLinkRepository recipientLinkRepository) {
        this.bidRepository = bidRepository;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.storageService = storageService;
        this.notificationDispatcher = notificationDispatcher;
        this.messagesResolver = messagesResolver;
        this.recipientLinkRepository = recipientLinkRepository;
    }

    /** Mode recette (FLUTTER-FA) ; fermé tant que Spring ne l'a pas injecté. */
    private com.yadony.api.common.RecetteMode recetteMode = com.yadony.api.common.RecetteMode.disabled();

    @org.springframework.beans.factory.annotation.Autowired
    void setRecetteMode(com.yadony.api.common.RecetteMode recetteMode) {
        this.recetteMode = recetteMode;
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

        // Le destinataire qui a confirmé le colis peut aussi le montrer au voyageur
        // (FLUTTER-7Y). Le QR n'identifie que le colis : la remise exige toujours
        // le code à 6 chiffres.
        boolean isSender = currentUser.getId().equals(bid.getSenderId());
        if (!isSender && !recipientLinkRepository.existsByBidIdAndRecipientUserIdAndStatus(
                bidId, currentUser.getId(), ReceptionLinkStatus.CONFIRMED)) {
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
        Messages m = messagesResolver.forRequest();

        String currentStep;
        String stepLabel;

        if (bid.getStatus() == BidStatus.REJECTED) {
            currentStep = "REJECTED";
            stepLabel = m.get("tracking.step.rejected");
        } else if (bid.getStatus() == BidStatus.CANCELLED) {
            currentStep = "CANCELLED";
            stepLabel = m.get("tracking.step.cancelled");
        } else if (bid.getStatus() == BidStatus.PENDING) {
            currentStep = "PENDING";
            stepLabel = m.get("tracking.step.pending");
        } else if (bid.getStatus() == BidStatus.PAYMENT_ESCROWED) {
            currentStep = "PAYMENT_ESCROWED";
            stepLabel = m.get("tracking.step.payment-escrowed");
        } else {
            // ACCEPTED — calcul de base depuis paiement/confirmation
            if (paymentOpt.isEmpty() || paymentOpt.get().getStatus() == PaymentStatus.PENDING) {
                currentStep = "ACCEPTED";
                stepLabel = m.get("tracking.step.accepted");
            } else if (paymentOpt.get().getStatus() == PaymentStatus.ESCROW && !bid.isVoyageurConfirmed()) {
                currentStep = "PAYMENT_SECURED";
                stepLabel = m.get("tracking.step.payment-secured");
            } else if (paymentOpt.get().getStatus() == PaymentStatus.ESCROW && bid.isVoyageurConfirmed()) {
                currentStep = "IN_TRANSIT";
                stepLabel = m.get("tracking.step.in-transit");
            } else {
                currentStep = "DELIVERED";
                stepLabel = m.get("tracking.step.delivered");
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
                stepLabel = m.get("tracking.step.delivery-confirmed");
            } else if (hasTransit) {
                currentStep = "IN_TRANSIT";
                stepLabel = m.get("tracking.step.in-transit");
            } else if (hasDepart) {
                currentStep = "DEPARTED";
                stepLabel = m.get("tracking.step.departed");
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
        return recordScan(request, firebaseUid).event();
    }

    /**
     * Résultat d'un scan : l'étape enregistrée, et si elle vient d'être créée ({@code created})
     * ou si elle l'était déjà (rejeu idempotent d'un DEPART, aucun effet relancé).
     */
    public record ScanOutcome(TrackingEventResponse event, boolean created) {
    }

    /**
     * Enregistre un scan. Un DEPART déjà enregistré pour ce colis est idempotent : l'étape
     * existante est renvoyée, sans nouvel insert, ni code de confirmation, ni audit, ni
     * notification (FLUTTER-JV, YADONY-BACK-STAGING-8 : l'app envoyait le même scan deux fois
     * à 0,5 s d'écart, envoi direct + file hors ligne, et le second heurtait l'index unique
     * uq_tracking_one_depart_per_bid en 500).
     */
    @Transactional
    public ScanOutcome recordScan(QrScanRequest request, String firebaseUid) {
        // Verrou du colis : deux scans simultanés du même colis se sérialisent. Le second
        // attend le commit du premier, puis voit son DEPART et le renvoie tel quel.
        bidRepository.lockForUpdate(request.bidId());
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

        // Un DEPART ne s'enregistre qu'une fois par colis (index unique V48). Le rejeu du
        // même scan par le même voyageur, même après la suite du parcours, renvoie l'étape
        // existante : le code de confirmation déjà généré reste intact.
        if (request.eventType() == TrackingEventType.DEPART) {
            Optional<TrackingEventEntity> recorded = trackingEventRepository
                    .findFirstByBidIdAndEventTypeOrderByScannedAtAsc(bid.getId(), TrackingEventType.DEPART);
            if (recorded.isPresent()) {
                log.info("Scan DEPART déjà enregistré pour le colis {}, rejeu idempotent", bid.getId());
                return new ScanOutcome(toEventResponse(recorded.get(), null), false);
            }
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

        if (request.eventType() == TrackingEventType.DEPART) {
            assertTrackingNumberOnDepart(bid, request.trackingNumber(), request.scanMethod());
        }

        String photoKey = validatedPhotoKey(bid, request.photoUrl());

        // Le scan TRANSIT est facultatif, le DEPART ne l'est pas : c'est lui qui
        // génère le code de confirmation du destinataire. Un TRANSIT scanné avant
        // tout DEPART sautait cette génération et laissait la livraison
        // inconfirmable (code-not-generated).
        if (request.eventType() == TrackingEventType.TRANSIT && bid.getStatus() == BidStatus.ACCEPTED) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "depart-required",
                    "Depart Required", "Scannez d'abord le départ du colis");
        }

        TrackingEventEntity event = new TrackingEventEntity();
        event.setBidId(bid.getId());
        event.setEventType(request.eventType());
        event.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
        event.setGpsLat(request.gpsLat());
        event.setGpsLon(request.gpsLon());
        event.setGpsLabel(cleanGpsLabel(request.gpsLabel()));
        event.setPhotoUrl(photoKey);
        event.setScanMethod(request.scanMethod());
        if (request.offlineTimestamp() != null) {
            event.setOfflineTimestamp(request.offlineTimestamp());
            event.setSyncedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        trackingEventRepository.save(event);
        // Insert immédiat : une violation d'unicité éclate ici, avant le moindre effet
        // (audit, code, notification), et annule toute la transaction.
        trackingEventRepository.flush();

        auditService.log("TRACKING_EVENT", event.getId(), "SCAN_" + request.eventType(),
                traveler.getId(), Map.of(
                        "bidId", bid.getId().toString(),
                        "eventType", request.eventType().name(),
                        "offline", String.valueOf(request.offlineTimestamp() != null)));

        if (request.eventType() == TrackingEventType.DEPART && bid.getStatus() == BidStatus.ACCEPTED) {
            bid.setStatus(BidStatus.HANDED_OVER);
            if (bid.getConfirmationCode() == null) {
                String code = com.yadony.api.matching.PickupCodes.newCode();
                bid.setConfirmationCode(code);
                bid.setConfirmationCodeAttempts(0);
                bid.setConfirmationCodeExpiry(computeCodeExpiry(announcement));
                var text = com.yadony.api.notifications.NotificationTexts.confirmationCodeReady(
                        notificationDispatcher.messagesFor(bid.getSenderId()));
                notificationDispatcher.notifyUser(
                        bid.getSenderId(),
                        text.title(),
                        text.body(),
                        Map.of("type", "CONFIRMATION_CODE_READY", "bidId", bid.getId().toString()));
                auditService.log("TRACKING_CONFIRMATION_CODE", bid.getId(), "CODE_GENERATED",
                        traveler.getId(), Map.of("bidId", bid.getId().toString()));
                // Le destinataire qui suit le colis dans l'app voit désormais son code.
                eventPublisher.publishEvent(new ParcelDepartedEvent(bid.getId()));
            }
            bidRepository.save(bid);
        }

        if (request.eventType() == TrackingEventType.TRANSIT && bid.getStatus() == BidStatus.HANDED_OVER) {
            bid.setStatus(BidStatus.IN_TRANSIT);
            bidRepository.save(bid);
            // Le trajet est parti : plus aucune nouvelle demande (FLUTTER-AE).
            eventPublisher.publishEvent(new ParcelInTransitEvent(bid.getId(), bid.getAnnouncementId()));
        }

        return new ScanOutcome(toEventResponse(event, null), true);
    }

    /**
     * Relecture, dans une transaction neuve, du DEPART qu'une requête concurrente vient de
     * commiter (filet de {@code TrackingController} quand l'insert heurte l'index unique).
     * Mêmes gardes que le scan : seul le voyageur de l'annonce y accède.
     */
    public TrackingEventResponse findRecordedDepart(UUID bidId, String firebaseUid) {
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
                    "Seul le voyageur de cette annonce peut scanner le QR code");
        }
        return trackingEventRepository
                .findFirstByBidIdAndEventTypeOrderByScannedAtAsc(bidId, TrackingEventType.DEPART)
                .map(e -> toEventResponse(e, null))
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.CONFLICT,
                        "scan-already-recorded", "Scan Already Recorded",
                        "Ce scan est déjà en cours d'enregistrement, réessayez"));
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
        // Le destinataire qui a confirmé le colis dans l'app suit aussi ses étapes (lecture seule).
        boolean isConfirmedRecipient = !isSender && !isTraveler
                && recipientLinkRepository.existsByBidIdAndRecipientUserIdAndStatus(
                        bidId, currentUser.getId(), ReceptionLinkStatus.CONFIRMED);
        if (!isSender && !isTraveler && !isConfirmedRecipient) {
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
        // Destinataire qui a refusé le colis ou s'en est retiré : son nom n'est plus montré
        // au voyageur, comme dans le détail du colis (BidService.toResponse).
        java.util.Set<UUID> recipientHidden = recipientLinkRepository.findByBidIdIn(bidIds).stream()
                .filter(l -> l.getStatus().hidesRecipientFromTraveler())
                .map(com.yadony.api.matching.reception.BidRecipientLinkEntity::getBidId)
                .collect(Collectors.toSet());

        return trackingEventRepository.findByBidIdInOrderByScannedAtDesc(bidIds).stream()
                .map(event -> {
                    BidEntity bid = bidsById.get(event.getBidId());
                    return new TripScanHistoryEntryDto(
                            bid != null ? bid.getTrackingNumber() : null,
                            bid != null && !recipientHidden.contains(bid.getId()) ? bid.getRecipientName() : null,
                            event.getEventType().name(),
                            event.getScannedAt(),
                            scanMethodName(event));
                })
                .toList();
    }

    private TrackingEventResponse toEventResponse(TrackingEventEntity e, String resolvedPhotoUrl) {
        return new TrackingEventResponse(
                e.getId(), e.getBidId(), e.getEventType().name(),
                e.getScannedAt(), e.getGpsLat(), e.getGpsLon(), e.getGpsLabel(),
                resolvedPhotoUrl != null ? resolvedPhotoUrl : e.getPhotoUrl(),
                e.getOfflineTimestamp(), e.getCreatedAt(), scanMethodName(e));
    }

    private static String scanMethodName(TrackingEventEntity e) {
        return e.getScanMethod() != null ? e.getScanMethod().name() : null;
    }

    /**
     * La photo envoyée par le client doit être une clé S3 interne du bid
     * (tracking/{bidId}/...). Les URL absolues sont refusées : elles seraient
     * affichées telles quelles sur la page publique du destinataire.
     */
    private String validatedPhotoKey(BidEntity bid, String photoKey) {
        if (photoKey == null || photoKey.isBlank()) return null;
        String expectedPrefix = "tracking/" + bid.getId() + "/";
        if (photoKey.startsWith("http://") || photoKey.startsWith("https://")
                || photoKey.contains("..")
                || !photoKey.startsWith(expectedPrefix)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid-photo-url", "Invalid Photo URL",
                    "L'URL de la photo doit être une clé S3 valide pour ce bid");
        }
        return photoKey;
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

    /**
     * Efface le code de retrait après trop d'essais faux, trace l'action et prévient
     * l'expéditeur, seul à pouvoir en générer un nouveau (FLUTTER-G1). Sans la
     * notification, le colis restait bloqué sans que personne ne sache quoi faire.
     */
    private void blockCodeAfterTooManyAttempts(BidEntity bid, UUID travelerId) {
        int attempts = bid.getConfirmationCodeAttempts();
        bid.setConfirmationCode(null);
        bid.setConfirmationCodeAttempts(0);
        bid.setConfirmationCodePublicEnabled(false);
        bidRepository.save(bid);
        auditService.log("TRACKING_CONFIRMATION_CODE", bid.getId(), "CODE_ATTEMPTS_EXCEEDED",
                travelerId, Map.of("bidId", bid.getId().toString(),
                        "attempts", String.valueOf(attempts)));
        eventPublisher.publishEvent(new ConfirmationCodeBlockedEvent(bid.getId(), bid.getSenderId()));
    }

    private static YadonyBusinessException tooManyAttempts() {
        // « code-blocked » et non « too-many-attempts » : ce dernier est partagé avec les
        // OTP, dont le texte app (« patientez quelques minutes ») ne s'applique pas ici —
        // attendre ne débloque rien, seul un nouveau code de l'expéditeur le fait.
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-blocked",
                "Code Blocked",
                "Trop d'essais incorrects : ce code est bloqué. Demandez à l'expéditeur d'en générer un nouveau dans l'app");
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

        // Code absent ET colis pas encore remis : le DEPART n'a pas été scanné, il n'y a
        // rien à régénérer. Code absent sur un colis déjà remis : il a été effacé après
        // trois essais faux ou à l'expiration — c'est précisément le cas où l'expéditeur
        // doit pouvoir en obtenir un nouveau, sinon le colis n'est plus jamais confirmable
        // (un second scan DEPART est refusé, et processScan ne génère un code que depuis
        // ACCEPTED).
        if (bid.getConfirmationCode() == null && bid.getStatus() == BidStatus.ACCEPTED) {
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

        String newCode = com.yadony.api.matching.PickupCodes.newCode();
        LocalDateTime newExpiry = com.yadony.api.matching.ArrivalRules.renewedPickupCodeExpiry(announcement);
        bid.setConfirmationCode(newCode);
        bid.setConfirmationCodeAttempts(0);
        bid.setConfirmationCodeExpiry(newExpiry);
        bid.setConfirmationCodePublicEnabled(false);
        bidRepository.save(bid);

        auditService.log("TRACKING_CONFIRMATION_CODE", bidId, "CODE_REFRESHED",
                currentUser.getId(), Map.of("bidId", bidId.toString()));

        return new ConfirmCodeResponse(newCode, newExpiry, bid.isConfirmationCodePublicEnabled());
    }

    // Un refus (code expiré, faux, trop d'essais) enregistre le compteur d'essais ou efface
    // le code avant de lever l'erreur : sans noRollbackFor, ces écritures étaient annulées
    // avec la transaction, la limite de trois essais ne s'appliquait jamais et un code
    // expiré n'était jamais effacé (FLUTTER-BA).
    @Transactional(noRollbackFor = YadonyBusinessException.class)
    public TrackingEventResponse confirmDelivery(UUID bidId, ConfirmDeliveryRequest request,
                                                 String firebaseUid) {
        // Verrou du colis : la livraison et une annulation (expéditeur, voyageur, admin) se
        // sérialisent ; le perdant relit le nouveau statut et refuse. Jamais COMPLETED et remboursé,
        // ni CANCELLED et versé.
        bidRepository.lockForUpdate(bidId);
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

        // Avant toute lecture du code : un essai avant le départ ne consomme aucune
        // des trois tentatives et ne révèle pas si le code est juste.
        assertTripDeparted(bid, announcement, traveler);

        if (bid.getConfirmationCode() == null) {
            // Colis déjà remis sans code : il a été bloqué (trop d'essais) ou a expiré.
            // Renvoyer le voyageur vers le scan DEPART, déjà fait, le laissait sans issue
            // (FLUTTER-G1) : seul l'expéditeur peut en générer un nouveau.
            if (bid.getStatus() != BidStatus.ACCEPTED) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-blocked",
                        "Code Blocked",
                        "Aucun code de retrait valide : demandez à l'expéditeur d'en générer un nouveau dans l'app");
            }
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

        // Ligne héritée : compteur déjà au maximum avec un code encore présent (avant
        // FLUTTER-G1, le code n'était effacé qu'au quatrième essai).
        if (bid.getConfirmationCodeAttempts() >= MAX_CODE_ATTEMPTS) {
            blockCodeAfterTooManyAttempts(bid, traveler.getId());
            throw tooManyAttempts();
        }

        if (!bid.getConfirmationCode().equals(request.confirmationCode())) {
            bid.setConfirmationCodeAttempts(bid.getConfirmationCodeAttempts() + 1);
            int remaining = MAX_CODE_ATTEMPTS - bid.getConfirmationCodeAttempts();
            if (remaining <= 0) {
                // Le troisième essai faux bloque le code tout de suite : l'expéditeur voit
                // aussitôt « Générer un nouveau code » au lieu d'un code mort.
                blockCodeAfterTooManyAttempts(bid, traveler.getId());
                throw tooManyAttempts();
            }
            bidRepository.save(bid);
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-incorrect",
                    "Code Incorrect",
                    "Code incorrect — " + remaining + " tentative(s) restante(s)");
        }

        // Validée avant de consommer le code : une clé rejetée ne doit pas
        // laisser le colis COMPLETED sans sa photo de preuve.
        String photoKey = validatedPhotoKey(bid, request.photoUrl());

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
        event.setPhotoUrl(photoKey);
        event.setScanMethod(request.scanMethod());
        // Lieu de l'arrivée, comme pour les autres étapes (FLUTTER-2A).
        event.setGpsLat(request.gpsLat());
        event.setGpsLon(request.gpsLon());
        event.setGpsLabel(cleanGpsLabel(request.gpsLabel()));
        trackingEventRepository.save(event);

        eventPublisher.publishEvent(new DeliveryConfirmedEvent(bid.getId(), bid.getSenderId(), traveler.getId()));

        auditService.log("TRACKING_DELIVERY_CONFIRMED", event.getId(), "DELIVERY_CONFIRMED",
                traveler.getId(), Map.of("bidId", bid.getId().toString()));

        return toEventResponse(event, null);
    }

    /**
     * Le colis ne peut être livré qu'une fois le trajet parti (FLUTTER-CB). Le code de
     * retrait existe dès la remise (scan DEPART), souvent la veille : le saisir avant le
     * départ terminait le colis et, pour un paiement carte, libérait le séquestre au
     * voyageur avant le transport. Règle de départ unique : {@code DepartureRules}
     * (date + heure dans le fuseau du trajet ; sans heure, le lendemain du jour de départ).
     * Les statuts IN_TRANSIT et ARRIVED ne suffisent pas : le voyageur les pose lui-même,
     * sans contrôle de date.
     */
    private void assertTripDeparted(BidEntity bid, AnnouncementEntity announcement, UserEntity traveler) {
        if (com.yadony.api.matching.DepartureRules.hasDeparted(announcement, Instant.now())) {
            return;
        }
        // Mode recette (FLUTTER-FA, staging seulement) : un voyageur testeur valide la
        // livraison sans attendre le départ, pour dérouler la chaîne complète dans la journée.
        if (recetteMode.appliesTo(traveler)) {
            log.warn("Mode recette : livraison acceptée avant le départ du trajet, bidId={}", bid.getId());
            recetteMode.recordBypass(com.yadony.api.common.RecetteMode.ACTION_DELIVERY_BEFORE_DEPARTURE,
                    bid.getId(), traveler.getId(), Map.of(
                            "bidId", bid.getId().toString(),
                            "announcementId", announcement.getId().toString(),
                            "bidStatus", bid.getStatus().name()));
            return;
        }
        log.warn("Livraison refusée avant le départ du trajet : bidId={}", bid.getId());
        auditService.log("TRACKING_EVENT", bid.getId(), "DELIVERY_REFUSED_TRIP_NOT_DEPARTED",
                traveler.getId(), Map.of(
                        "bidId", bid.getId().toString(),
                        "bidStatus", bid.getStatus().name()));
        throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, TRIP_NOT_DEPARTED,
                "Trip Not Departed",
                messagesResolver.forRequest().get("problem.trip-not-departed"));
    }

    /**
     * À la remise du colis (DEPART), le voyageur prouve qu'il tient le bon colis : il scanne le QR
     * de l'expéditeur OU saisit le numéro de suivi, que seul l'expéditeur possède. Les étapes
     * suivantes ne le demandent plus. Un numéro envoyé est toujours vérifié. Absent, il n'est exigé
     * que si {@code yadony.tracking.require-number-on-depart} est vrai, et seulement hors scan QR :
     * les apps déjà installées ne l'envoient pas (FLUTTER-BC).
     */
    private void assertTrackingNumberOnDepart(BidEntity bid, String provided, ScanMethod scanMethod) {
        if (provided == null || provided.isBlank()) {
            if (requireNumberOnDepart && scanMethod != ScanMethod.QR) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "tracking-number-required",
                        "Tracking Number Required",
                        "Saisissez le numéro de suivi du colis, donné par l'expéditeur");
            }
            return;
        }
        String expected = bid.getTrackingNumber();
        if (expected == null || !expected.equalsIgnoreCase(provided.trim())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "tracking-number-mismatch",
                    "Tracking Number Mismatch",
                    "Ce numéro de suivi ne correspond pas à ce colis");
        }
    }

    private LocalDateTime computeCodeExpiry(AnnouncementEntity announcement) {
        return com.yadony.api.matching.ArrivalRules.pickupCodeExpiry(announcement);
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
