package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminReportContextResponse.Fact;
import com.yadony.api.admin.dto.AdminReportContextResponse.TargetPreview;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.signalements.ReportEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ce que la cible d'un signalement est, en quelques lignes : annonce, demande d'envoi ou offre.
 * Un modérateur qui retire une annonce doit pouvoir lire le trajet, le prix et le texte sans
 * ouvrir une autre page. Aucune donnée de contact ni d'adresse précise : seulement ce qui décrit
 * l'annonce publiée. Lecture seule ; une cible disparue ou d'un autre type rend {@code null}.
 */
@Component
public class ReportTargetPreviewBuilder {

    static final int DESCRIPTION_MAX = 600;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final AnnouncementRepository announcementRepo;
    private final PackageRequestRepository packageRequestRepo;
    private final BidRepository bidRepo;

    public ReportTargetPreviewBuilder(AnnouncementRepository announcementRepo,
                                      PackageRequestRepository packageRequestRepo,
                                      BidRepository bidRepo) {
        this.announcementRepo = announcementRepo;
        this.packageRequestRepo = packageRequestRepo;
        this.bidRepo = bidRepo;
    }

    public TargetPreview build(ReportEntity report) {
        if (report.getTargetType() == null || report.getTargetId() == null) {
            return null;
        }
        return switch (report.getTargetType()) {
            case ANNOUNCEMENT -> announcementRepo.findById(report.getTargetId()).map(this::announcement).orElse(null);
            case PACKAGE_REQUEST -> packageRequestRepo.findById(report.getTargetId()).map(this::packageRequest).orElse(null);
            case BID -> bidRepo.findById(report.getTargetId()).map(this::bid).orElse(null);
            case USER, MESSAGE, RATING, APP -> null;
        };
    }

    private TargetPreview announcement(AnnouncementEntity a) {
        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact("Trajet", MatchingTextUtil.corridorLabel(a.getDepartureCity(), a.getArrivalCity())));
        add(facts, "Départ", date(a.getDepartureDate()));
        add(facts, "Transport", transport(a.getTransportMode()));
        add(facts, "Prix", a.getPricePerKg() != null ? money(a.getPricePerKg(), a.getCurrency()) + " / kg" : null);
        add(facts, "Poids", weights(a.getAvailableKg(), a.getTotalKg()));
        return new TargetPreview("ANNOUNCEMENT", name(a.getStatus()), facts, shorten(a.getDescription()));
    }

    private TargetPreview packageRequest(PackageRequestEntity p) {
        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact("Trajet", MatchingTextUtil.corridorLabel(p.getDepartureCity(), p.getArrivalCity())));
        String when = date(p.getDesiredDate());
        if (when != null && p.getDateToleranceDays() != null && p.getDateToleranceDays() > 0) {
            when += " (± " + p.getDateToleranceDays() + " j)";
        }
        add(facts, "Date souhaitée", when);
        add(facts, "Poids", p.getWeightKg() != null ? kg(p.getWeightKg()) : null);
        add(facts, "Contenu", p.getContentCategory());
        if (p.getTargetPriceEur() != null) {
            add(facts, "Budget", money(p.getTargetPriceEur(), "EUR") + (p.isNegotiable() ? " (négociable)" : ""));
        }
        return new TargetPreview("PACKAGE_REQUEST", name(p.getStatus()), facts, shorten(p.getDescription()));
    }

    private TargetPreview bid(BidEntity b) {
        List<Fact> facts = new ArrayList<>();
        Optional<AnnouncementEntity> announcement = b.getAnnouncementId() != null
                ? announcementRepo.findById(b.getAnnouncementId()) : Optional.empty();
        announcement.ifPresent(a -> facts.add(new Fact("Annonce",
                MatchingTextUtil.corridorLabel(a.getDepartureCity(), a.getArrivalCity())
                        + (a.getDepartureDate() != null ? " · " + DATE.format(a.getDepartureDate()) : ""))));
        add(facts, "Poids", b.getWeightKg() != null ? kg(b.getWeightKg()) : null);
        add(facts, "Contenu", b.getContentCategory());
        if (b.getNegotiatedNetEur() != null) {
            add(facts, "Montant négocié", money(b.getNegotiatedNetEur(), "EUR"));
        }
        return new TargetPreview("BID", name(b.getStatus()), facts, shorten(b.getDescription()));
    }

    private static void add(List<Fact> facts, String label, String value) {
        if (value != null && !value.isBlank()) {
            facts.add(new Fact(label, value));
        }
    }

    private static String name(Enum<?> e) {
        return e != null ? e.name() : null;
    }

    private static String date(LocalDate d) {
        return d != null ? DATE.format(d) : null;
    }

    private static String transport(TransportMode mode) {
        if (mode == null) return null;
        return switch (mode) {
            case PLANE -> "Avion";
            case CAR -> "Voiture";
            case TRAIN -> "Train";
            case BUS -> "Bus";
            case BOAT -> "Bateau";
            case OTHER -> "Autre";
        };
    }

    /** Nombre sans zéros inutiles, virgule française : « 8,5 ». */
    static String number(BigDecimal n) {
        String plain = n.stripTrailingZeros().toPlainString();
        return plain.replace('.', ',');
    }

    static String kg(BigDecimal n) {
        return number(n) + " kg";
    }

    static String money(BigDecimal amount, String currency) {
        return number(amount) + " " + (currency != null && !currency.isBlank() ? currency : "EUR");
    }

    private static String weights(BigDecimal available, BigDecimal total) {
        if (available != null && total != null) {
            return number(available) + " kg disponibles sur " + number(total) + " kg";
        }
        if (available != null) return number(available) + " kg disponibles";
        return total != null ? kg(total) : null;
    }

    /** Texte libre : coupé proprement, jamais plus de {@value #DESCRIPTION_MAX} caractères. */
    static String shorten(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.strip();
        return trimmed.length() <= DESCRIPTION_MAX ? trimmed : trimmed.substring(0, DESCRIPTION_MAX - 1).stripTrailing() + "…";
    }
}
