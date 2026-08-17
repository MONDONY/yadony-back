package com.yadony.api.matching;

import com.yadony.api.matching.dto.AnnouncementPriceGridItemResponse;
import com.yadony.api.payments.currency.SupportedCurrency;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Page publique d'une annonce, sans authentification.
 *
 * <p>Elle est la destination du lien imprimé sur l'affiche que le voyageur
 * génère dans l'application et poste sur ses propres canaux (Facebook,
 * WhatsApp, TikTok). Les gens qui cliquent n'ont, dans leur immense majorité,
 * pas encore l'application : cette page est donc la vitrine qui les convertit,
 * pas une simple redirection.
 *
 * <p>Elle est aussi le prérequis technique des App Links / Universal Links :
 * ceux-ci ne sont pas un type de lien à part, ce sont des URL {@code https://}
 * ordinaires que le système détourne vers l'application <em>lorsqu'elle est
 * installée</em>. Sans page qui répond, le repli est un 404. L'URL servie ici
 * est donc déjà la forme définitive : activer les App Links plus tard
 * n'invalidera aucune affiche déjà publiée.
 *
 * <p>Deux chemins mènent ici, tous deux en {@code permitAll} dans
 * {@code SecurityConfig} : {@code /public/annonce/{id}}, la forme historique,
 * directement joignable sans nginx (utilisée en dev et tant qu'aucun domaine
 * court n'est configuré) ; et {@code /annonce/{id}}, l'alias pensé pour l'URL
 * visible par le public. Le second n'existe que derrière le passage de nginx
 * qui réécrit {@code yadony.com/annonce/{id}} vers {@code .../api/v1/annonce/{id}}
 * (voir {@code nginx/nginx.conf}) — sans lui, seule la forme longue résout.
 * {@link #buildShareUrl} choisit systématiquement la forme courte : tant que
 * {@code app.public-base-url} pointe encore sur l'origine API (repli par
 * défaut), la forme courte y résout quand même grâce à cet alias ; le jour où
 * elle pointera sur le domaine nu, elle devient la véritable URL publique.
 */
@Controller
@RequestMapping({"/public/annonce", "/annonce"})
public class PublicAnnouncementPageController {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter DEADLINE_FMT =
            DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH'h'mm", Locale.FRENCH);

    private static final String VIEW = "public/annonce";

    /**
     * Robots d'aperçu de lien. Ils frappent cette page à chaque fois que
     * quelqu'un colle l'URL dans un post, précisément parce que les balises
     * Open Graph existent pour être moissonnées. Les compter reviendrait à
     * mesurer les collages plutôt que les visiteurs : la statistique
     * d'attribution serait structurellement fausse.
     */
    private static final String[] PREVIEW_CRAWLERS = {
        "facebookexternalhit", "facebookcatalog", "meta-externalagent",
        "whatsapp", "twitterbot", "telegrambot", "slackbot",
        "linkedinbot", "discordbot", "bot", "crawler", "spider"
    };

    private final AnnouncementRepository announcementRepository;
    private final PriceGridService priceGridService;

    /**
     * Base des URL publiques servies par cette application.
     *
     * <p>Distincte de {@code app.base-url}, qui ne porte pas le
     * {@code context-path} {@code /api/v1}. Les appelants existants le
     * recollent à la main ({@code TrackingService}, {@code RecipientController})
     * ; ici la valeur est complète et configurable d'un bloc, pour qu'un
     * domaine court se substitue plus tard par une seule variable
     * d'environnement, sans invalider les affiches déjà publiées.
     */
    @Value("${app.public-base-url}")
    private String publicBaseUrl;

    /**
     * Repli si {@link #publicBaseUrl} arrive vide.
     *
     * <p>Nécessaire car le défaut YAML {@code ${PUBLIC_BASE_URL:...}} ne
     * s'applique que si la variable est <em>absente</em>, pas si elle est
     * <em>définie mais vide</em> — et c'est exactement ce que produit le
     * workflow de déploiement, qui écrit {@code PUBLIC_BASE_URL=} dans le
     * {@code .env} tant que la variable GitHub correspondante n'existe pas.
     * Sans ce repli, un déploiement fait avant sa création publierait un
     * {@code og:url} vide sur toutes les affiches.
     */
    @Value("${app.base-url:}")
    private String appBaseUrl;

    @Value("${app.store.android:}")
    private String storeUrlAndroid;

    @Value("${app.store.ios:}")
    private String storeUrlIos;

    /**
     * Bouton principal orienté store selon l'appareil, plutôt que le lien
     * {@code dony://}. Faux par défaut : l'application n'est pas encore
     * publiée, et un bouton orienté store pointerait vers rien tant que
     * {@code app.store.android}/{@code .ios} restent vides. Le comportement
     * est écrit et testé maintenant ; l'activer plus tard n'est qu'un
     * changement de configuration, pas de code.
     *
     * <p><strong>Injecté en {@code String}, converti par {@link #isStoreOsRedirectEnabled}.</strong>
     * Injecté en {@code boolean}, une valeur <em>définie mais vide</em> fait
     * échouer la conversion et l'application <em>refuse de démarrer</em>
     * ({@code Invalid boolean value []}). Or c'est exactement ce que le
     * workflow de déploiement écrit dans le {@code .env}
     * ({@code STORE_OS_REDIRECT_ENABLED=}) tant que la variable GitHub
     * correspondante n'existe pas. Le défaut YAML ne protège pas : il ne
     * couvre que la variable absente.
     */
    @Value("${app.store.os-redirect-enabled:false}")
    private String storeOsRedirectEnabledRaw;

    /** Vrai uniquement sur une valeur explicitement vraie ; vide ⇒ désactivé. */
    private boolean isStoreOsRedirectEnabled() {
        return Boolean.parseBoolean(storeOsRedirectEnabledRaw == null
                ? "false"
                : storeOsRedirectEnabledRaw.trim());
    }

    public PublicAnnouncementPageController(AnnouncementRepository announcementRepository,
                                            PriceGridService priceGridService) {
        this.announcementRepository = announcementRepository;
        this.priceGridService = priceGridService;
    }

    @GetMapping("/{id}")
    public String announcementPage(@PathVariable String id,
                                   HttpServletRequest request,
                                   Model model) {
        // Un seul bouton store, resolu ici : le gabarit n'a plus a arbitrer
        // entre deux liens avec une condition composee.
        model.addAttribute("storeUrl", firstNonBlank(storeUrlAndroid, storeUrlIos));

        Optional<AnnouncementEntity> found = parseUuid(id)
                .flatMap(announcementRepository::findById)
                .filter(this::isPubliclyVisible);

        if (found.isEmpty()) {
            return unavailable(model);
        }

        AnnouncementEntity announcement = found.get();
        UUID announcementId = announcement.getId();

        // Compteur d'attribution : mesure le trafic réellement ramené par
        // l'affiche. Hors du chemin d'erreur — une annonce retirée ne doit pas
        // gonfler la statistique — et hors des robots d'aperçu.
        if (!isPreviewCrawler(request)) {
            announcementRepository.incrementShareViewCount(announcementId);
        }

        String corridor = announcement.getDepartureCity() + " vers " + announcement.getArrivalCity();
        String departureDate = announcement.getDepartureDate().format(DATE_FMT);

        model.addAttribute("unavailable", false);
        model.addAttribute("departureCity", announcement.getDepartureCity());
        model.addAttribute("arrivalCity", announcement.getArrivalCity());
        model.addAttribute("departureDate", departureDate);
        // Les libellés Open Graph sont assemblés ici plutôt que dans le gabarit :
        // une expression conditionnelle multiligne portant une apostrophe française
        // n'est pas analysable par Thymeleaf dans un attribut de balise meta.
        model.addAttribute("pageTitle", "Trajet " + corridor + " | Yadony");
        model.addAttribute("ogTitle", corridor);
        model.addAttribute("ogDescription",
                "Départ le " + departureDate + ". Réservez vos kilos sur Yadony.");
        model.addAttribute("handoverDeadline", formatDeadline(announcement));
        model.addAttribute("capacity", capacityLabel(announcement));
        model.addAttribute("pricePerKg", formatDecimal(kgPrice(announcement)));
        model.addAttribute("cheapestGridPrice",
                formatDecimal(cheapestGridPrice(announcement)));
        model.addAttribute("currency", SupportedCurrency.symbolOf(announcement.getCurrency()));
        // Lieux de remise et de récupération, tels que le voyageur les a saisis.
        model.addAttribute("pickupAddress", announcement.getPickupAddressLabel());
        model.addAttribute("deliveryAddress", announcement.getDeliveryAddressLabel());
        model.addAttribute("deepLink", "dony://annonce/" + announcementId);
        model.addAttribute("shareUrl", buildShareUrl(announcementId));
        applyPrimaryCta(model, announcementId, request);

        return VIEW;
    }

    /**
     * Bouton principal : le store du bon système si {@link #isStoreOsRedirectEnabled()}
     * et que l'appareil est identifié, sinon le lien {@code dony://} historique.
     *
     * <p>Une majorité écrasante des visiteurs de cette page n'a pas encore
     * l'application — c'est toute la raison d'être de la page. Pour eux, le
     * lien {@code dony://} ne fait rien d'utile ; les envoyer directement vers
     * le store correspondant à leur téléphone les convertit au lieu de les
     * laisser cliquer dans le vide. Repli sur {@code dony://} si le système
     * n'est pas reconnu (ordinateur) ou si son store n'est pas configuré — un
     * bouton qui ne mène nulle part coûte plus cher qu'un bouton absent.
     *
     * <p>Le bouton secondaire générique (attribut {@code storeUrl}, résolu par
     * {@link #firstNonBlank} sans égard au système du visiteur) disparaît dès
     * que le flag est actif : une fois le bouton principal fiable, exposer en
     * plus un lien vers <em>l'autre</em> store à un visiteur iPhone serait pire
     * que ne rien montrer.
     */
    private void applyPrimaryCta(Model model, UUID announcementId, HttpServletRequest request) {
        String deepLink = "dony://annonce/" + announcementId;
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
                osStoreUrl != null ? "Télécharger l'application" : "Ouvrir dans l'application");
        model.addAttribute("showGenericStoreButton", !isStoreOsRedirectEnabled());
    }

    private enum MobileOs { ANDROID, IOS, OTHER }

    /**
     * Détection best-effort par User-Agent, comme {@link #isPreviewCrawler}.
     * Elle n'a pas besoin d'être infaillible : au pire un visiteur mal
     * identifié retombe sur le lien {@code dony://}, sans jamais casser rien.
     */
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
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * URL canonique de la page, publiée dans {@code og:url} et affichable telle
     * quelle. Forme courte systématique : {@code /annonce/{id}}, sans
     * {@code /public}. Elle reste valide dans tous les environnements car
     * {@code /annonce} est mappée sur le même contrôleur que {@code /public/annonce}
     * (voir la classe) — seule sa <em>lisibilité</em> dépend de la configuration :
     * derrière nginx et un domaine nu, c'est la véritable URL publique ; sans eux,
     * elle reste une URL techniquement correcte mais qui expose encore l'origine
     * API.
     */
    private String buildShareUrl(UUID announcementId) {
        return resolvedPublicBaseUrl() + "/annonce/" + announcementId;
    }

    /**
     * Base publique effective, avec repli en cascade sur {@code app.base-url}
     * puis sur l'origine locale. Cf. {@link #appBaseUrl} pour la raison : une
     * variable d'environnement vide n'active pas le défaut YAML.
     */
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

    /** Une base terminée par « / » produirait « //annonce/… ». */
    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * Prix affiché à l'expéditeur, commission Yadony comprise.
     *
     * <p>{@code pricePerKg} est le net voyageur : l'afficher annoncerait un
     * tarif que personne ne paiera, et surtout un tarif <em>différent</em> de
     * celui imprimé sur l'affiche qui pointe vers cette page. Le multiplicateur
     * vient de {@link PriceGridService#displayPrice}, source unique du taux.
     */
    private BigDecimal senderPricePerKg(AnnouncementEntity announcement) {
        BigDecimal net = announcement.getPricePerKg();
        return net == null
                ? null
                : priceGridService.displayPrice(net, announcement.getTravelerId());
    }

    /**
     * Prix au kilo, ou {@code null} lorsque le trajet n'en a pas.
     *
     * <p>En mode {@link PricingMode#MIXED} le tarif au kilo est facultatif, mais
     * la colonne est {@code NOT NULL} et le formulaire de création y écrit
     * {@code 0}. Ni {@code pricePerKg} ni le prix affiché ne valent donc jamais
     * {@code null} : tester la nullité laissait passer un « 0 € par kilo » sur
     * la page qu'un voyageur diffuse publiquement. C'est la valeur qui tranche,
     * pas la présence.
     */
    private BigDecimal kgPrice(AnnouncementEntity announcement) {
        BigDecimal net = announcement.getPricePerKg();
        if (net == null || net.signum() <= 0) {
            return null;
        }
        return senderPricePerKg(announcement);
    }

    /**
     * Prix expéditeur de l'article le moins cher, pour une accroche « dès X ».
     *
     * <p>{@code null} hors mode {@link PricingMode#MIXED}. Le service garantit
     * une grille non vide dans ce mode (422 {@code price-grid-empty} sinon),
     * mais une annonce plus ancienne peut en être dépourvue, d'où le repli.
     */
    private BigDecimal cheapestGridPrice(AnnouncementEntity announcement) {
        if (announcement.getPricingMode() != PricingMode.MIXED) {
            return null;
        }
        return priceGridService
                .getAnnouncementGridItems(announcement.getId(), announcement.getTravelerId())
                .stream()
                .map(AnnouncementPriceGridItemResponse::unitPriceDisplay)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }

    /**
     * Capacité telle qu'elle doit être annoncée.
     *
     * <p>{@link CapacityUnit#KG_FREE} signifie « pas de plafond déclaré » :
     * {@code availableKg} n'est alors qu'une valeur de forme, et l'imprimer
     * comme une limite tromperait l'expéditeur. Le reste de l'application dit
     * « Kg libre » dans ce cas.
     */
    private String capacityLabel(AnnouncementEntity announcement) {
        if (announcement.getCapacityUnit() == CapacityUnit.KG_FREE) {
            return "Kg libre";
        }
        String kg = formatDecimal(announcement.getAvailableKg());
        return kg == null ? null : kg + " kg";
    }

    /**
     * Visibilité publique de l'annonce.
     *
     * <p>Déléguée à {@link AnnouncementEntity#isPubliclyListable()} plutôt que
     * redécidée ici : la règle vit déjà sur l'entité, dont le javadoc prévient
     * qu'un futur point d'entrée ne doit pas en dériver. Cette page en était
     * justement un, et elle avait dérivé de deux façons — elle acceptait
     * {@code FULL}, donc un trajet sans place restante présenté comme
     * réservable, et elle ignorait le verrou des trajets dédiés, exposant
     * publiquement le corridor, la date et le prix d'une négociation privée.
     */
    private boolean isPubliclyVisible(AnnouncementEntity announcement) {
        return announcement.isPubliclyListable();
    }

    /**
     * Les robots d'aperçu s'annoncent dans leur User-Agent. Le filtre est
     * volontairement large : un humain compté en moins vaut mieux qu'une
     * statistique gonflée par un collage de lien.
     */
    private boolean isPreviewCrawler(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent == null || agent.isBlank()) {
            // Un client sans User-Agent n'est pas un navigateur.
            return true;
        }
        String lower = agent.toLowerCase(Locale.ROOT);
        for (String marker : PREVIEW_CRAWLERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String unavailable(Model model) {
        model.addAttribute("unavailable", true);
        model.addAttribute("pageTitle", "Trajet indisponible | Yadony");
        model.addAttribute("ogTitle", "Trajet indisponible");
        model.addAttribute("ogDescription",
                "Ce trajet n'est plus disponible. Trouvez un autre voyageur sur Yadony.");
        model.addAttribute("shareUrl", "");
        return VIEW;
    }

    /**
     * Un identifiant malformé vient d'un lien tronqué au copier-coller depuis un
     * post, pas d'une erreur serveur : il retombe sur la page « indisponible ».
     */
    private Optional<UUID> parseUuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private String formatDeadline(AnnouncementEntity announcement) {
        return announcement.getHandoverDeadline() == null
                ? null
                : announcement.getHandoverDeadline().format(DEADLINE_FMT);
    }

    private String formatDecimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }
}
