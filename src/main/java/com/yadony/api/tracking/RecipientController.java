package com.yadony.api.tracking;

import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.RevokedTrackingTokenRepository;
import com.yadony.api.tracking.dto.TravelerTrackingView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Controller
@RequestMapping("/tracking/public")
public class RecipientController {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRENCH);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final TrackingEventRepository trackingEventRepository;
    private final StorageService storageService;
    private final RevokedTrackingTokenRepository revokedTokenRepository;
    private final TravelerTrackingQuery travelerTrackingQuery;

    /** Statuts de colis pour lesquels le suivi approximatif du voyageur a un sens (colis pris en charge). */
    private static final java.util.Set<BidStatus> TRAVELER_TRACKING_STATUSES = java.util.EnumSet.of(
            BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED, BidStatus.COMPLETED);

    @Value("${app.base-url}")
    private String appBaseUrl;

    @Value("${app.store.ios:}")
    private String storeUrlIos;

    @Value("${app.store.android:}")
    private String storeUrlAndroid;

    private static final java.util.regex.Pattern PHONE_SEPARATORS = java.util.regex.Pattern.compile("[\\s\\-.()]");
    private static final java.util.regex.Pattern E164 = java.util.regex.Pattern.compile("\\+\\d{8,15}");

    public RecipientController(BidRepository bidRepository,
                               AnnouncementRepository announcementRepository,
                               TrackingEventRepository trackingEventRepository,
                               StorageService storageService,
                               RevokedTrackingTokenRepository revokedTokenRepository,
                               TravelerTrackingQuery travelerTrackingQuery) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.storageService = storageService;
        this.revokedTokenRepository = revokedTokenRepository;
        this.travelerTrackingQuery = travelerTrackingQuery;
    }

    @GetMapping("/{trackingToken}")
    public String trackingPage(@PathVariable String trackingToken, Model model,
                               jakarta.servlet.http.HttpServletRequest request) {
        return buildTrackingPage(trackingToken, model, request.getHeader("User-Agent"));
    }

    @GetMapping("/{trackingToken}/status")
    @ResponseBody
    public ResponseEntity<?> trackingStatus(@PathVariable String trackingToken) {
        Optional<BidEntity> bidOpt = bidRepository.findByTrackingToken(trackingToken);
        if (bidOpt.isEmpty()) {
            if (revokedTokenRepository.existsByToken(trackingToken)) {
                return linkRevoked();
            }
            return ResponseEntity.notFound().build();
        }
        BidEntity bid = bidOpt.get();
        Optional<AnnouncementEntity> announcementOpt = announcementRepository.findById(bid.getAnnouncementId());
        List<TrackingEventEntity> rawEvents =
                trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId());

        List<Map<String, Object>> events = rawEvents.stream()
                .map(e -> Map.<String, Object>of(
                        "label", ParcelSteps.label(e.getEventType()),
                        "scannedAt", e.getScannedAt().format(FMT),
                        "offline", e.getOfflineTimestamp() != null,
                        "gpsLabel", e.getGpsLabel() != null ? e.getGpsLabel() : "",
                        "photoUrl", e.getPhotoUrl() != null ? resolvePhoto(e.getPhotoUrl()) : ""
                ))
                .toList();

        String currentStep = ParcelSteps.currentStep(rawEvents);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("currentStep", currentStep);
        body.put("events", events);
        body.put("publicConfirmationCode", publicConfirmationCode(bid));
        body.put("arrivalInstructions", arrivalInstructionsOrEmpty(announcementOpt));
        // Champ facultatif : dernière position approximative seulement, jamais d'historique.
        if (TRAVELER_TRACKING_STATUSES.contains(bid.getStatus())) {
            body.put("travelerTracking", travelerTrackingQuery.forPublic(bid, OffsetDateTime.now(ZoneOffset.UTC)));
        }
        return ResponseEntity.ok(body);
    }

    /** Lien remplacé après un changement de destinataire : 410, sans rien dire du colis. */
    private static ResponseEntity<ProblemDetail> linkRevoked() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.GONE,
                "Le destinataire de ce colis a changé. Ce lien de suivi n'est plus valide.");
        problem.setType(URI.create("https://yadony.app/errors/tracking-link-revoked"));
        problem.setTitle("Tracking Link Revoked");
        problem.setProperty("code", "tracking-link-revoked");
        return ResponseEntity.status(HttpStatus.GONE)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static String arrivalInstructionsOrEmpty(Optional<AnnouncementEntity> announcementOpt) {
        return announcementOpt.map(AnnouncementEntity::getArrivalInstructions)
                .filter(s -> s != null)
                .orElse("");
    }

    private String buildTrackingPage(String trackingToken, Model model, String userAgent) {
        Optional<BidEntity> bidOpt = bidRepository.findByTrackingToken(trackingToken);

        if (bidOpt.isEmpty()) {
            model.addAttribute("invalid", true);
            model.addAttribute("recipientChanged", revokedTokenRepository.existsByToken(trackingToken));
            return "recipient/tracking";
        }

        BidEntity bid = bidOpt.get();

        Optional<AnnouncementEntity> announcementOpt =
                announcementRepository.findById(bid.getAnnouncementId());

        String corridor = announcementOpt.map(a ->
                a.getDepartureCity() + " → " + a.getArrivalCity()
        ).orElse("—");

        List<TrackingEventEntity> rawEvents =
                trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId());

        List<RecipientEventDto> events = rawEvents.stream()
                .map(e -> new RecipientEventDto(
                        ParcelSteps.label(e.getEventType()),
                        e.getScannedAt().format(FMT),
                        e.getGpsLabel(),
                        resolvePhoto(e.getPhotoUrl()),
                        e.getOfflineTimestamp() != null
                ))
                .toList();

        String currentStepLabel = ParcelSteps.currentStep(rawEvents);

        model.addAttribute("trackingNumber", bid.getTrackingNumber());
        model.addAttribute("corridor", corridor);
        model.addAttribute("currentStep", currentStepLabel);
        model.addAttribute("events", events);
        model.addAttribute("qrCodeBase64", generateQrBase64(scanUrl(bid)));
        model.addAttribute("publicConfirmationCode", publicConfirmationCode(bid));
        model.addAttribute("arrivalInstructions", arrivalInstructionsOrEmpty(announcementOpt));
        model.addAttribute("invalid", false);
        model.addAttribute("trackingToken", trackingToken);
        model.addAttribute("isDelivered", bid.getStatus() == BidStatus.COMPLETED);
        addAppInvitation(model, bid, userAgent);
        addTravelerTracking(model, bid);

        return "recipient/tracking";
    }

    /**
     * Bloc « Position du voyageur » : statut, avancement, ville proche et ancienneté de la dernière
     * position, sans historique. Absent tant que le colis n'est pas pris en charge.
     */
    private void addTravelerTracking(Model model, BidEntity bid) {
        if (!TRAVELER_TRACKING_STATUSES.contains(bid.getStatus())) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TravelerTrackingView view = travelerTrackingQuery.forPublic(bid, now);
        model.addAttribute("travelerStatus", view.status().name());
        model.addAttribute("travelerCity", view.nearestCity());
        model.addAttribute("travelerProgress", view.progressPct());
        if (view.lastUpdatedAt() != null) {
            long minutes = Math.max(0, Duration.between(view.lastUpdatedAt(), now).toMinutes());
            if (minutes < 60) {
                model.addAttribute("travelerAgeKey", "tracking.traveler.age.minutes");
                model.addAttribute("travelerAgeValue", minutes);
            } else if (minutes < 1440) {
                model.addAttribute("travelerAgeKey", "tracking.traveler.age.hours");
                model.addAttribute("travelerAgeValue", minutes / 60);
            } else {
                model.addAttribute("travelerAgeKey", "tracking.traveler.age.days");
                model.addAttribute("travelerAgeValue", minutes / 1440);
            }
        }
    }

    /**
     * Carte « Suivez ce colis dans l'app Yadony » (YADONY-FLUTTER-PROD-12) : lien
     * {@code yadony://colis/{bidId}}, bouton store du téléphone détecté (absent si l'URL n'est
     * pas configurée ou le système inconnu : jamais de bouton mort), et rappel du numéro sous
     * lequel s'inscrire, MASQUÉ. Le numéro n'apparaît que s'il est international : c'est la
     * seule forme que le rattrapage sait relier à un compte (ReceptionLinker).
     */
    private void addAppInvitation(Model model, BidEntity bid, String userAgent) {
        model.addAttribute("appLink", "yadony://colis/" + bid.getId());
        model.addAttribute("storeUrl",
                com.yadony.api.common.UserAgents.storeUrlFor(userAgent, storeUrlIos, storeUrlAndroid));
        model.addAttribute("maskedRecipientPhone", maskedRecipientPhone(bid.getRecipientPhone()));
    }

    /** {@code +225 07 01 02 03 34} → {@code +225 •••• 34} ; {@code null} si non international. */
    static String maskedRecipientPhone(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = PHONE_SEPARATORS.matcher(raw).replaceAll("");
        if (compact.startsWith("00")) {
            compact = "+" + compact.substring(2);
        }
        if (!E164.matcher(compact).matches()) {
            return null;
        }
        return com.yadony.api.common.Msisdn.mask(compact);
    }

    private String resolvePhoto(String key) {
        if (key == null || key.startsWith("http")) return key;
        return storageService.generatePresignedUrl(key, Duration.ofHours(1));
    }

    private String publicConfirmationCode(BidEntity bid) {
        if (!bid.isConfirmationCodePublicEnabled() || bid.getConfirmationCode() == null) {
            return "";
        }
        LocalDateTime expiry = bid.getConfirmationCodeExpiry();
        if (expiry != null && LocalDateTime.now(ZoneOffset.UTC).isAfter(expiry)) {
            return "";
        }
        return bid.getConfirmationCode();
    }

    private String scanUrl(BidEntity bid) {
        return appBaseUrl + "/api/v1/tracking/" + bid.getId() + "/scan";
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
            return "";
        }
    }

    public record RecipientEventDto(
            String label,
            String scannedAt,
            String gpsLabel,
            String photoUrl,
            boolean offline
    ) {}
}
