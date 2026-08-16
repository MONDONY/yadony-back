package com.yadony.api.tracking;

import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
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

    @Value("${app.base-url}")
    private String appBaseUrl;

    public RecipientController(BidRepository bidRepository,
                               AnnouncementRepository announcementRepository,
                               TrackingEventRepository trackingEventRepository,
                               StorageService storageService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.storageService = storageService;
    }

    @GetMapping("/{trackingToken}")
    public String trackingPage(@PathVariable String trackingToken, Model model) {
        return buildTrackingPage(trackingToken, model);
    }

    @GetMapping("/{trackingToken}/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> trackingStatus(@PathVariable String trackingToken) {
        Optional<BidEntity> bidOpt = bidRepository.findByTrackingToken(trackingToken);
        if (bidOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        BidEntity bid = bidOpt.get();
        Optional<AnnouncementEntity> announcementOpt = announcementRepository.findById(bid.getAnnouncementId());
        List<TrackingEventEntity> rawEvents =
                trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId());

        List<Map<String, Object>> events = rawEvents.stream()
                .map(e -> Map.<String, Object>of(
                        "label", labelFor(e.getEventType()),
                        "scannedAt", e.getScannedAt().format(FMT),
                        "offline", e.getOfflineTimestamp() != null,
                        "gpsLabel", e.getGpsLabel() != null ? e.getGpsLabel() : "",
                        "photoUrl", e.getPhotoUrl() != null ? resolvePhoto(e.getPhotoUrl()) : ""
                ))
                .toList();

        String currentStep = resolveCurrentStep(bid, rawEvents);
        return ResponseEntity.ok(Map.of(
                "currentStep", currentStep,
                "events", events,
                "publicConfirmationCode", publicConfirmationCode(bid),
                "arrivalInstructions", announcementOpt.map(AnnouncementEntity::getArrivalInstructions).map(s -> s == null ? "" : s).orElse("")
        ));
    }

    private String buildTrackingPage(String trackingToken, Model model) {
        Optional<BidEntity> bidOpt = bidRepository.findByTrackingToken(trackingToken);

        if (bidOpt.isEmpty()) {
            model.addAttribute("invalid", true);
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
                        labelFor(e.getEventType()),
                        e.getScannedAt().format(FMT),
                        e.getGpsLabel(),
                        resolvePhoto(e.getPhotoUrl()),
                        e.getOfflineTimestamp() != null
                ))
                .toList();

        String currentStepLabel = resolveCurrentStep(bid, rawEvents);

        model.addAttribute("trackingNumber", bid.getTrackingNumber());
        model.addAttribute("corridor", corridor);
        model.addAttribute("currentStep", currentStepLabel);
        model.addAttribute("events", events);
        model.addAttribute("qrCodeBase64", generateQrBase64(scanUrl(bid)));
        model.addAttribute("publicConfirmationCode", publicConfirmationCode(bid));
        model.addAttribute("arrivalInstructions",
                announcementOpt.map(AnnouncementEntity::getArrivalInstructions).map(s -> s == null ? "" : s).orElse(""));
        model.addAttribute("invalid", false);
        model.addAttribute("trackingToken", trackingToken);
        model.addAttribute("isDelivered", bid.getStatus() == BidStatus.COMPLETED);

        return "recipient/tracking";
    }

    private String labelFor(TrackingEventType type) {
        return switch (type) {
            case DEPART -> "Colis remis au voyageur";
            case TRANSIT -> "En transit";
            case ARRIVEE -> "Arrivée confirmée";
        };
    }

    private String resolvePhoto(String key) {
        if (key == null || key.startsWith("http")) return key;
        return storageService.generatePresignedUrl(key, Duration.ofHours(1));
    }

    private String resolveCurrentStep(BidEntity bid, List<TrackingEventEntity> events) {
        boolean hasArrivee = events.stream().anyMatch(e -> e.getEventType() == TrackingEventType.ARRIVEE);
        boolean hasTransit = events.stream().anyMatch(e -> e.getEventType() == TrackingEventType.TRANSIT);
        boolean hasDepart = events.stream().anyMatch(e -> e.getEventType() == TrackingEventType.DEPART);

        if (hasArrivee) return "Livraison confirmée";
        if (hasTransit) return "En transit";
        if (hasDepart) return "Colis remis au voyageur — en route";
        return "En attente du scan de départ";
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
