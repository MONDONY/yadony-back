package com.yadony.api.matching;

import com.yadony.api.common.PageResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.PublicAnnouncementCard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.TreeSet;

/**
 * Recherche publique d'annonces : anonyme, lecture seule, sans jeton.
 *
 * <p>Ouvert par la règle {@code /public/**} de {@code SecurityConfig} (rien à y modifier).
 * Surface volontairement étroite : liste fermée de paramètres, tout autre paramètre est refusé
 * (400 {@code invalid-search}) plutôt qu'ignoré, afin qu'aucun filtre réservé aux comptes
 * (position, tri, GP Pro, KYC...) ne devienne accessible par un nom de paramètre déjà connu de
 * la recherche authentifiée. Les bornes (pages, plage de dates, longueurs) sont dans
 * {@link PublicAnnouncementQuery}.
 */
@RestController
@RequestMapping("/public/announcements")
public class PublicAnnouncementSearchController {

    static final Set<String> ALLOWED_PARAMETERS = Set.of(
            "departureCity", "arrivalCity", "departureDateFrom", "departureDateTo",
            "minAvailableKg", "maxPricePerKg", "minRating", "urgent",
            "transportMode", "contentType", "minItemSize", "viaCity",
            "minVolumeM3", "maxPricePerM3", "arrivalBefore", "page");

    /** Écrit en clair : {@code CacheControl} sérialise « max-age=30, public », ordre que les tests figent autrement. */
    private static final String CACHE_CONTROL = "public, max-age=30";

    private final PublicAnnouncementSearchService searchService;

    public PublicAnnouncementSearchController(PublicAnnouncementSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<PublicAnnouncementCard>> search(
            @RequestParam(required = false) String departureCity,
            @RequestParam(required = false) String arrivalCity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departureDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departureDateTo,
            @RequestParam(required = false) BigDecimal minAvailableKg,
            @RequestParam(required = false) BigDecimal maxPricePerKg,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(required = false) Boolean urgent,
            @RequestParam(required = false) String transportMode,
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) String minItemSize,
            @RequestParam(required = false) String viaCity,
            @RequestParam(required = false) BigDecimal minVolumeM3,
            @RequestParam(required = false) BigDecimal maxPricePerM3,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate arrivalBefore,
            @RequestParam(required = false) Integer page,
            HttpServletRequest request) {
        rejectUnknownParameters(request);
        PublicAnnouncementQuery query = PublicAnnouncementQuery.of(
                departureCity, arrivalCity, departureDateFrom, departureDateTo,
                minAvailableKg, maxPricePerKg, minRating, urgent,
                transportMode, contentType, minItemSize, viaCity, minVolumeM3, maxPricePerM3, arrivalBefore,
                page, LocalDate.now(ZoneOffset.UTC));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(searchService.search(query));
    }

    private static void rejectUnknownParameters(HttpServletRequest request) {
        if (!ALLOWED_PARAMETERS.containsAll(request.getParameterMap().keySet())) {
            // Le détail ne recopie pas les noms reçus : rien du client n'est renvoyé tel quel.
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "invalid-search", "Invalid Search",
                    "Unsupported query parameter. Allowed: " + String.join(", ", new TreeSet<>(ALLOWED_PARAMETERS)));
        }
    }
}
