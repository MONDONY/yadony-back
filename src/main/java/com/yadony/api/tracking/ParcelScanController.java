package com.yadony.api.tracking;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.PublicNameFormatter;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Page publique ouverte quand on scanne le QR collé sur un colis avec l'appareil photo
 * d'un téléphone. L'URL est celle déjà encodée dans les QR (TrackingService#scanUrl) :
 * les QR imprimés avant cette page marchent sans réimpression.
 */
@Controller
@RequestMapping("/tracking")
public class ParcelScanController {

    static final Set<BidStatus> VISIBLE = EnumSet.of(
            BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT,
            BidStatus.ARRIVED, BidStatus.COMPLETED);

    private static final DateTimeFormatter EVENT_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRENCH);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH);
    private static final String VIEW = "parcel/scan";

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final TrackingEventRepository trackingEventRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final MeterRegistry meterRegistry;
    private final String whatsappNumber;
    private final String supportEmail;
    private final String storeIos;
    private final String storeAndroid;

    public ParcelScanController(BidRepository bidRepository,
                                AnnouncementRepository announcementRepository,
                                TrackingEventRepository trackingEventRepository,
                                UserRepository userRepository,
                                StorageService storageService,
                                MeterRegistry meterRegistry,
                                @Value("${yadony.support.whatsapp-number:}") String whatsappNumber,
                                @Value("${yadony.support.email:support@yadony.com}") String supportEmail,
                                @Value("${app.store.ios:}") String storeIos,
                                @Value("${app.store.android:}") String storeAndroid) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.meterRegistry = meterRegistry;
        this.whatsappNumber = whatsappNumber == null ? "" : whatsappNumber.replaceAll("\\D", "");
        this.supportEmail = supportEmail;
        this.storeIos = storeIos;
        this.storeAndroid = storeAndroid;
    }

    @GetMapping("/{bidId}/scan")
    public String scan(@PathVariable String bidId, Model model, HttpServletResponse response) {
        Optional<BidEntity> bidOpt = parseUuid(bidId).flatMap(bidRepository::findById);
        if (bidOpt.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return inactive(model, null, "not_found");
        }
        BidEntity bid = bidOpt.get();
        Optional<AnnouncementEntity> announcementOpt = announcementRepository.findById(bid.getAnnouncementId());
        if (!VISIBLE.contains(bid.getStatus()) || announcementOpt.isEmpty()) {
            return inactive(model, bid.getTrackingNumber(), "inactive");
        }
        AnnouncementEntity announcement = announcementOpt.get();
        List<TrackingEventEntity> rawEvents = trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId());
        boolean delivered = bid.getStatus() == BidStatus.COMPLETED;

        ParcelScanView parcel = new ParcelScanView(
                bid.getTrackingNumber(),
                delivered ? "Livré" : ParcelSteps.currentStep(rawEvents),
                delivered,
                announcement.getDepartureCity(),
                announcement.getArrivalCity(),
                formatDate(announcement.getDepartureDate()),
                formatDate(announcement.getArrivalDate()),
                splitCategories(bid.getContentCategory()),
                bid.getDescription() == null ? "" : bid.getDescription(),
                formatWeight(bid.getWeightKg()),
                publicUserName(bid.getSenderId()),
                PublicNameFormatter.shortName(bid.getRecipientName()),
                publicUserName(announcement.getTravelerId()),
                rawEvents.stream().map(e -> new RecipientController.RecipientEventDto(
                        ParcelSteps.label(e.getEventType()),
                        e.getScannedAt().format(EVENT_FMT),
                        e.getGpsLabel(),
                        resolvePhoto(e.getPhotoUrl()),
                        e.getOfflineTimestamp() != null)).toList());

        model.addAttribute("inactive", false);
        model.addAttribute("parcel", parcel);
        model.addAttribute("appLink", "yadony://colis/" + bid.getId());
        addContact(model, bid.getTrackingNumber());
        count("shown");
        return VIEW;
    }

    private String inactive(Model model, String trackingNumber, String outcome) {
        model.addAttribute("inactive", true);
        addContact(model, trackingNumber);
        count(outcome);
        return VIEW;
    }

    private void addContact(Model model, String trackingNumber) {
        String subject = trackingNumber == null ? "Colis Yadony" : "Colis " + trackingNumber;
        String text = trackingNumber == null
                ? "Bonjour, j'ai une question sur un colis Yadony."
                : "Bonjour, j'ai une question sur le colis " + trackingNumber + ".";
        model.addAttribute("whatsappUrl", whatsappNumber.isEmpty()
                ? null
                : "https://wa.me/" + whatsappNumber + "?text=" + encode(text));
        model.addAttribute("supportEmail", supportEmail);
        model.addAttribute("mailtoUrl", "mailto:" + supportEmail + "?subject=" + encode(subject));
        model.addAttribute("storeIos", blankToNull(storeIos));
        model.addAttribute("storeAndroid", blankToNull(storeAndroid));
    }

    private String publicUserName(UUID userId) {
        if (userId == null) return PublicNameFormatter.UNKNOWN;
        return userRepository.findById(userId)
                .map(UserEntity::publicDisplayName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(PublicNameFormatter.UNKNOWN);
    }

    private String resolvePhoto(String key) {
        if (key == null || key.startsWith("http")) return key;
        return storageService.generatePresignedUrl(key, Duration.ofHours(1));
    }

    private void count(String outcome) {
        meterRegistry.counter("yadony.parcel.public_scan", "outcome", outcome).increment();
    }

    private static Optional<UUID> parseUuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static List<String> splitCategories(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return Arrays.stream(joined.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String formatWeight(BigDecimal kg) {
        if (kg == null) return "";
        return kg.stripTrailingZeros().toPlainString().replace('.', ',') + " kg";
    }

    private static String formatDate(LocalDate date) {
        return date == null ? "" : date.format(DATE_FMT);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
