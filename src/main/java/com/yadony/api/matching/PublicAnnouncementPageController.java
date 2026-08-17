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
 * <p>Exposée sous {@code /public/**}, déjà couvert par la liste
 * {@code permitAll} de {@code SecurityConfig} — aucune ouverture nouvelle dans
 * la configuration de sécurité n'est nécessaire.
 */
@Controller
@RequestMapping("/public/annonce")
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

    @Value("${app.store.android:}")
    private String storeUrlAndroid;

    @Value("${app.store.ios:}")
    private String storeUrlIos;

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
        model.addAttribute("shareUrl", publicBaseUrl + "/public/annonce/" + announcementId);

        return VIEW;
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
