package com.yadony.api.matching;

import com.yadony.api.payments.cash.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filtres de recherche de trajets ajoutés après la signature historique de
 * {@link AnnouncementService#searchAnnouncements} : regroupés ici pour ne pas allonger
 * une liste de paramètres positionnels déjà longue.
 *
 * @param maxStops       FLUTTER-GD, borne d'escales brute (normalisée par {@link TripStops#searchBound})
 * @param paymentMethods FLUTTER-G0, moyens recherchés (jamais null, vide = pas de filtre)
 * @param includeFull    FLUTTER-JH, ajoute les trajets complets à venir APRÈS les trajets
 *                       réservables. Absent (ancienne app, compteur) : réservables seuls.
 * @param publicView     recherche publique anonyme ({@link PublicAnnouncementSearchService}) :
 *                       ni bloc 2 ({@code similarAnnouncements}), ni conversions de devise, que
 *                       la carte publique jetterait. Toujours {@code false} pour l'app.
 * @param minItemSize    taille minimale de colis que le trajet doit accepter : filtre strict, un trajet
 *                       sans taille max n'y apparaît pas. {@code null} = pas de filtre.
 * @param viaCity        ville de passage : trajets des voyages dont une étape part de cette ville ou y
 *                       arrive. Normalisée (sans espaces superflus, en minuscules) ; vide = {@code null}.
 * @param minVolumeM3    bateau : volume restant minimal en m³. Strict : un trajet au kilo (sans volume)
 *                       n'y apparaît pas. {@code null} = pas de filtre.
 * @param maxPricePerM3  bateau : prix au m³ maximal, saisi dans la devise du lecteur (EUR en anonyme) ;
 *                       comparé dans la devise de chaque trajet. Strict : un trajet au kilo n'a pas de
 *                       prix au m³ et n'y apparaît pas. {@code null} = pas de filtre.
 *                       {@code minVolumeM3} et {@code maxPricePerM3} sont bornés comme en recherche publique
 *                       (positifs, plafond, {@link PublicAnnouncementQuery#MAX_DECIMALS} décimales) : sinon
 *                       400 {@code invalid-search}.
 * @param arrivalBefore  arrivée estimée au plus tard à cette date : fin de fourchette
 *                       ({@code arrival_date_max}) si elle existe, sinon {@code arrival_date}. Strict :
 *                       un trajet sans date d'arrivée n'y apparaît pas. {@code null} = pas de filtre.
 */
public record AnnouncementSearchExtras(Integer maxStops, Set<PaymentMethod> paymentMethods, boolean includeFull,
                                       boolean publicView, ItemSize minItemSize, String viaCity,
                                       BigDecimal minVolumeM3, BigDecimal maxPricePerM3, LocalDate arrivalBefore) {

    public static final AnnouncementSearchExtras NONE = new AnnouncementSearchExtras(null, Set.of(), false);

    /** Recherche publique anonyme : aucun filtre ajouté, bloc 2 et conversions sautés. */
    public static final AnnouncementSearchExtras PUBLIC_VIEW = new AnnouncementSearchExtras(null, Set.of(), false, true);

    public AnnouncementSearchExtras {
        paymentMethods = paymentMethods == null ? Set.of() : Set.copyOf(paymentMethods);
        String city = viaCity == null ? "" : viaCity.strip().replaceAll("\\s+", " ");
        viaCity = city.isEmpty() ? null : city.toLowerCase(Locale.ROOT);
        // Bornés AVANT tout calcul (exposant géant, négatif, décimales en trop : 400 invalid-search,
        // comme en recherche publique) puis sans zéros de queue : 1.0 et 1 donnent la même clé de cache.
        minVolumeM3 = PublicAnnouncementQuery.boundedDecimal(minVolumeM3, "minVolumeM3",
                PublicAnnouncementQuery.MAX_MIN_VOLUME_M3);
        maxPricePerM3 = PublicAnnouncementQuery.boundedDecimal(maxPricePerM3, "maxPricePerM3",
                PublicAnnouncementQuery.MAX_PRICE_PER_M3);
    }

    /** Sans filtre bateau (volume, prix au m³, arrivée) : les appels existants. */
    public AnnouncementSearchExtras(Integer maxStops, Set<PaymentMethod> paymentMethods, boolean includeFull,
                                    boolean publicView, ItemSize minItemSize, String viaCity) {
        this(maxStops, paymentMethods, includeFull, publicView, minItemSize, viaCity, null, null, null);
    }

    /** Sans taille minimale ni ville de passage (appels existants, vue publique sans ces filtres). */
    public AnnouncementSearchExtras(Integer maxStops, Set<PaymentMethod> paymentMethods, boolean includeFull,
                                    boolean publicView) {
        this(maxStops, paymentMethods, includeFull, publicView, null, null);
    }

    /** Appels de l'app : jamais la vue publique. */
    public AnnouncementSearchExtras(Integer maxStops, Set<PaymentMethod> paymentMethods, boolean includeFull) {
        this(maxStops, paymentMethods, includeFull, false);
    }

    public AnnouncementSearchExtras(Integer maxStops, Set<PaymentMethod> paymentMethods) {
        this(maxStops, paymentMethods, false);
    }

    /** Fragment de clé de cache, stable quel que soit l'ordre des moyens reçus. */
    public String cacheKey() {
        String methods = paymentMethods.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        return "stops=" + TripStops.searchBound(maxStops) + "_pm=" + methods + "_full=" + includeFull
                + "_public=" + publicView + "_size=" + minItemSize + "_via=" + viaCity
                + "_vol=" + plain(minVolumeM3) + "_m3=" + plain(maxPricePerM3) + "_arr=" + arrivalBefore;
    }

    /** {@code toString} (notation scientifique) : jamais de chaîne géante, même pour un exposant élevé. */
    private static String plain(BigDecimal value) {
        return value == null ? "null" : value.toString();
    }
}
