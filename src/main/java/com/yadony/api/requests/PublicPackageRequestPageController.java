package com.yadony.api.requests;

import com.yadony.api.payments.PriceBreakdown;
import com.yadony.api.payments.cash.CommissionProperties;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.requests.service.PackageRequestPhotoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Page publique d'une demande de colis, sans authentification.
 *
 * <p>Même raison d'être que {@link com.yadony.api.matching.PublicAnnouncementPageController},
 * pour l'autre moitié du marché : c'est la destination du lien qu'un expéditeur poste sur
 * WhatsApp ou Facebook pour trouver un voyageur. La quasi-totalité des gens qui cliquent
 * n'ont pas encore l'application ; cette page est la vitrine qui les convertit.
 *
 * <p>Deux chemins, tous deux en {@code permitAll} dans {@code SecurityConfig} :
 * {@code /public/demande/{id}}, forme historique, et {@code /demande/{id}}, alias pensé
 * pour l'URL visible par le public une fois nginx en place (voir la javadoc de classe du
 * contrôleur trajet pour le détail de ce mécanisme, identique ici).
 *
 * <p><b>Personnelles, jamais exposées ici :</b> ni {@code recipientName}/{@code recipientPhone}
 * /{@code recipientCity} (identité du destinataire), ni {@code pickupAddressLabel}/
 * {@code deliveryAddressLabel} (adresses précises, remplies seulement après acceptation d'un
 * bid — une demande n'atteint alors de toute façon plus le statut publiquement listable). La
 * page trajet expose l'adresse d'un professionnel du transport qui l'a choisi comme point de
 * remise ; une demande porte l'adresse d'un particulier qui ne l'a jamais consentie à un
 * public anonyme. Seuls les quartiers ({@code pickupNeighborhood}/{@code deliveryNeighborhood}),
 * déjà affichés à tout voyageur dans le feed de recherche de l'application, sont repris ici.
 */
@Controller
@RequestMapping({"/public/demande", "/demande"})
public class PublicPackageRequestPageController {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH);

    private static final String VIEW = "public/demande";

    /** Statuts d'une demande réellement ouverte à une proposition de trajet. */
    private static final Set<PackageRequestStatus> PUBLICLY_LISTABLE_STATUSES =
            EnumSet.of(PackageRequestStatus.OPEN, PackageRequestStatus.NEGOTIATING);

    private final PackageRequestRepository packageRequestRepository;
    private final PackageRequestPhotoService photoService;
    private final CommissionProperties commissionProperties;

    /** Cf. {@code PublicAnnouncementPageController#publicBaseUrl} pour la raison du repli. */
    @Value("${app.public-base-url}")
    private String publicBaseUrl;

    @Value("${app.base-url:}")
    private String appBaseUrl;

    @Value("${app.store.android:}")
    private String storeUrlAndroid;

    @Value("${app.store.ios:}")
    private String storeUrlIos;

    /** Même garde-fou d'injection que {@code PublicAnnouncementPageController} : voir sa javadoc. */
    @Value("${app.store.os-redirect-enabled:false}")
    private String storeOsRedirectEnabledRaw;

    public PublicPackageRequestPageController(PackageRequestRepository packageRequestRepository,
                                              PackageRequestPhotoService photoService,
                                              CommissionProperties commissionProperties) {
        this.packageRequestRepository = packageRequestRepository;
        this.photoService = photoService;
        this.commissionProperties = commissionProperties;
    }

    private boolean isStoreOsRedirectEnabled() {
        return Boolean.parseBoolean(storeOsRedirectEnabledRaw == null
                ? "false"
                : storeOsRedirectEnabledRaw.trim());
    }

    @GetMapping("/{id}")
    public String packageRequestPage(@PathVariable String id,
                                     HttpServletRequest request,
                                     Model model) {
        model.addAttribute("storeUrl", firstNonBlank(storeUrlAndroid, storeUrlIos));

        Optional<PackageRequestEntity> found = parseUuid(id)
                .flatMap(packageRequestRepository::findById)
                .filter(this::isPubliclyVisible);

        if (found.isEmpty()) {
            return unavailable(model);
        }

        PackageRequestEntity packageRequest = found.get();
        UUID packageRequestId = packageRequest.getId();

        String corridor = packageRequest.getDepartureCity() + " vers " + packageRequest.getArrivalCity();
        String desiredDate = packageRequest.getDesiredDate().format(DATE_FMT);

        model.addAttribute("unavailable", false);
        model.addAttribute("departureCity", packageRequest.getDepartureCity());
        model.addAttribute("arrivalCity", packageRequest.getArrivalCity());
        model.addAttribute("desiredDate", desiredDate);
        model.addAttribute("dateTolerance", toleranceLabel(packageRequest));
        // Libellés Open Graph assemblés ici plutôt que dans le gabarit, même raison que
        // pour la page trajet : une expression conditionnelle multiligne portant une
        // apostrophe française n'est pas analysable par Thymeleaf dans un attribut meta.
        model.addAttribute("pageTitle", "Demande " + corridor + " | Yadony");
        model.addAttribute("ogTitle", corridor);
        model.addAttribute("ogDescription",
                "Colis souhaité le " + desiredDate + ". Proposez votre trajet sur Yadony.");
        model.addAttribute("weight", formatDecimal(packageRequest.getWeightKg()));
        model.addAttribute("sizeLabel", sizeLabel(packageRequest.getParcelSize()));
        model.addAttribute("categories", packageRequest.getContentCategory());
        model.addAttribute("pickupNeighborhood", packageRequest.getPickupNeighborhood());
        model.addAttribute("deliveryNeighborhood", packageRequest.getDeliveryNeighborhood());
        model.addAttribute("grossPrice", formatDecimal(grossPrice(packageRequest)));
        model.addAttribute("currency", SupportedCurrency.symbolOf(packageRequest.getCurrency()));
        model.addAttribute("photoUrl", photoService.firstPhotoUrl(packageRequestId));
        model.addAttribute("deepLink", "yadony://demande/" + packageRequestId);
        model.addAttribute("shareUrl", buildShareUrl(packageRequestId));
        applyPrimaryCta(model, packageRequestId, request);

        return VIEW;
    }

    /**
     * Bouton principal : le store du bon système si {@link #isStoreOsRedirectEnabled()} et
     * que l'appareil est identifié, sinon le lien {@code yadony://} historique. Même logique
     * que {@code PublicAnnouncementPageController#applyPrimaryCta}, avec un libellé propre à
     * l'expéditeur qui cherche un voyageur : « Proposer mon trajet » plutôt que « Ouvrir dans
     * l'application ».
     */
    private void applyPrimaryCta(Model model, UUID packageRequestId, HttpServletRequest request) {
        String deepLink = "yadony://demande/" + packageRequestId;
        String osStoreUrl = null;

        if (isStoreOsRedirectEnabled()) {
            osStoreUrl = switch (detectMobileOs(request)) {
                case ANDROID -> blankToNull(storeUrlAndroid);
                case IOS -> blankToNull(storeUrlIos);
                case OTHER -> null;
            };
        }

        model.addAttribute("primaryCtaUrl", osStoreUrl != null ? osStoreUrl : deepLink);
        model.addAttribute("primaryCtaLabel",
                osStoreUrl != null ? "Télécharger l'application" : "Proposer mon trajet");
        model.addAttribute("showGenericStoreButton", !isStoreOsRedirectEnabled());
    }

    private enum MobileOs { ANDROID, IOS, OTHER }

    private MobileOs detectMobileOs(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent == null || agent.isBlank()) {
            return MobileOs.OTHER;
        }
        String lower = agent.toLowerCase(Locale.ROOT);
        if (lower.contains("android")) {
            return MobileOs.ANDROID;
        }
        if (lower.contains("iphone") || lower.contains("ipad") || lower.contains("ipod")) {
            return MobileOs.IOS;
        }
        return MobileOs.OTHER;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("null")
                || trimmed.equalsIgnoreCase("none")
                || trimmed.equals("-")) {
            return null;
        }
        return trimmed;
    }

    /**
     * URL canonique de la page, forme courte {@code /demande/{id}}, sans {@code /public}.
     * Cf. {@code PublicAnnouncementPageController#buildShareUrl} pour le détail du mécanisme.
     */
    private String buildShareUrl(UUID packageRequestId) {
        return resolvedPublicBaseUrl() + "/demande/" + packageRequestId;
    }

    private String resolvedPublicBaseUrl() {
        String configured = blankToNull(publicBaseUrl);
        if (configured != null) {
            return trimTrailingSlash(configured);
        }
        String fallback = blankToNull(appBaseUrl);
        return fallback == null
                ? "http://localhost:8080/api/v1"
                : trimTrailingSlash(fallback) + "/api/v1";
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * Prix affiché à l'expéditeur, commission Yadony comprise. {@code targetPriceEur} est le
     * net que toucherait le voyageur (même contrat que {@code PackageRequestService.toResponse}
     * et {@code PackageRequestSearchMapper}) : l'afficher tel quel annoncerait un montant que
     * personne ne paiera. {@code null} si la demande n'a pas de budget chiffré.
     */
    private BigDecimal grossPrice(PackageRequestEntity packageRequest) {
        BigDecimal net = packageRequest.getTargetPriceEur();
        return net == null
                ? null
                : PriceBreakdown.fromNet(net, commissionProperties.rate()).gross();
    }

    /** Français court, aligné sur le catalogue Flutter (search_composer_screen.dart). */
    private String sizeLabel(ParcelSize parcelSize) {
        if (parcelSize == null) {
            return null;
        }
        return switch (parcelSize) {
            case SMALL -> "Petit colis";
            case MEDIUM -> "Colis moyen";
            case LARGE -> "Grand colis";
        };
    }

    /** {@code null}/0 jour : aucune tolérance à annoncer, la date est ferme. */
    private String toleranceLabel(PackageRequestEntity packageRequest) {
        Short tolerance = packageRequest.getDateToleranceDays();
        if (tolerance == null || tolerance <= 0) {
            return null;
        }
        return "± " + tolerance + (tolerance == 1 ? " jour" : " jours");
    }

    /**
     * Seules les demandes réellement ouvertes à une proposition de trajet sont publiables :
     * un brouillon n'a jamais été soumis par son auteur, une demande acceptée ou terminée n'a
     * plus besoin de voyageur, une demande annulée ou expirée ne doit plus recevoir de trafic.
     * Le soft delete est déjà filtré en amont par {@code @SQLRestriction} sur l'entité.
     */
    private boolean isPubliclyVisible(PackageRequestEntity packageRequest) {
        return PUBLICLY_LISTABLE_STATUSES.contains(packageRequest.getStatus());
    }

    private String unavailable(Model model) {
        model.addAttribute("unavailable", true);
        model.addAttribute("pageTitle", "Demande indisponible | Yadony");
        model.addAttribute("ogTitle", "Demande indisponible");
        model.addAttribute("ogDescription",
                "Cette demande n'est plus disponible. Trouvez un autre colis à transporter sur Yadony.");
        model.addAttribute("shareUrl", "");
        return VIEW;
    }

    private Optional<UUID> parseUuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private String formatDecimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String firstNonBlank(String first, String second) {
        String firstUsable = blankToNull(first);
        if (firstUsable != null) {
            return firstUsable;
        }
        String secondUsable = blankToNull(second);
        return secondUsable == null ? "" : secondUsable;
    }
}
