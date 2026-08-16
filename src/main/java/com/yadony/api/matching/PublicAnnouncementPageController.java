package com.yadony.api.matching;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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

    private final AnnouncementRepository announcementRepository;

    @Value("${app.base-url}")
    private String appBaseUrl;

    @Value("${app.store.android:}")
    private String storeUrlAndroid;

    @Value("${app.store.ios:}")
    private String storeUrlIos;

    public PublicAnnouncementPageController(AnnouncementRepository announcementRepository) {
        this.announcementRepository = announcementRepository;
    }

    @GetMapping("/{id}")
    @Transactional
    public String announcementPage(@PathVariable String id, Model model) {
        model.addAttribute("appBaseUrl", appBaseUrl);
        model.addAttribute("storeUrlAndroid", storeUrlAndroid);
        model.addAttribute("storeUrlIos", storeUrlIos);

        UUID announcementId = parseUuid(id);
        if (announcementId == null) {
            return unavailable(model);
        }

        // @Where(deleted_at IS NULL) sur l'entité écarte déjà les annonces supprimées.
        Optional<AnnouncementEntity> found = announcementRepository.findById(announcementId);
        if (found.isEmpty()) {
            return unavailable(model);
        }

        AnnouncementEntity announcement = found.get();
        if (!isPubliclyVisible(announcement)) {
            return unavailable(model);
        }

        // Compteur d'attribution : mesure le trafic réellement ramené par l'affiche.
        // Volontairement hors du chemin d'erreur — une annonce introuvable ou
        // retirée ne doit pas gonfler la statistique.
        announcementRepository.incrementShareViewCount(announcementId);

        String corridor = announcement.getDepartureCity() + " vers " + announcement.getArrivalCity();
        String departureDate = announcement.getDepartureDate().format(DATE_FMT);

        model.addAttribute("unavailable", false);
        model.addAttribute("announcementId", announcementId.toString());
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
        model.addAttribute("availableKg", formatKg(announcement.getAvailableKg()));
        model.addAttribute("pricePerKg", formatPrice(announcement.getPricePerKg()));
        model.addAttribute("currency", currencySymbol(announcement.getCurrency()));
        model.addAttribute("deepLink", "dony://annonce/" + announcementId);
        model.addAttribute("shareUrl", appBaseUrl + "/public/annonce/" + announcementId);

        return VIEW;
    }

    /**
     * Seules les annonces réellement réservables sont montrées. Une annonce
     * annulée, terminée, archivée ou encore en brouillon afficherait un trajet
     * sur lequel personne ne peut plus se positionner : on renvoie l'état
     * « indisponible », qui garde un appel à l'action vers le reste de
     * l'application plutôt qu'une page morte.
     */
    private boolean isPubliclyVisible(AnnouncementEntity announcement) {
        AnnouncementStatus status = announcement.getStatus();
        return status == AnnouncementStatus.ACTIVE || status == AnnouncementStatus.FULL;
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

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            // Un identifiant malformé vient d'un lien tronqué au copier-coller,
            // pas d'une erreur serveur : on retombe sur la page « indisponible ».
            return null;
        }
    }

    private String formatDeadline(AnnouncementEntity announcement) {
        return announcement.getHandoverDeadline() == null
                ? null
                : announcement.getHandoverDeadline().format(DEADLINE_FMT);
    }

    private String formatKg(BigDecimal kg) {
        return kg == null ? null : kg.stripTrailingZeros().toPlainString();
    }

    private String formatPrice(BigDecimal price) {
        return price == null ? null : price.stripTrailingZeros().toPlainString();
    }

    private String currencySymbol(String currency) {
        if (currency == null) {
            return "€";
        }
        return switch (currency) {
            case "EUR" -> "€";
            case "USD" -> "$";
            case "GBP" -> "£";
            case "CAD" -> "$ CA";
            default -> currency;
        };
    }
}
