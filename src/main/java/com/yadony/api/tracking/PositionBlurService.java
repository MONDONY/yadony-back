package com.yadony.api.tracking;

import com.yadony.api.city.CityEntity;
import com.yadony.api.city.CityRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Floute la position GPS d'un voyageur : seule la ville peuplée la plus proche et un
 * pourcentage d'avancement sortent d'ici. Les coordonnées brutes ne sont ni retournées,
 * ni journalisées, ni placées dans un message d'exception.
 *
 * <p>Ville : recherche en base dans un rectangle qui s'agrandit par paliers
 * ({@link #BOX_HALF_SIZES_DEG}) jusqu'à trouver une ville d'au moins
 * {@link #MIN_POPULATION} habitants, puis distance haversine sur les seuls candidats.
 *
 * <p>Avancement : projection équirectangulaire locale centrée sur la ville de départ
 * (x = dLon * cos(latDépart), y = dLat), précise à quelques pourcents sur un trajet
 * France-Afrique de l'Ouest. Le point est projeté sur le segment départ-arrivée, le
 * pourcentage est borné à 0..100. Si l'écart à la route dépasse
 * {@link #MAX_OFF_ROUTE_RATIO} de sa longueur, le pourcentage est masqué (null).
 */
@Service
public class PositionBlurService {

    /** Ville affichée : au moins 20 000 habitants, pour viser 10 à 30 km sans hameaux. */
    static final long MIN_POPULATION = 20_000L;

    /** Écart maximal à la route, en part de sa longueur (escales et trajectoires courbes). */
    static final double MAX_OFF_ROUTE_RATIO = 0.25;

    /** Demi-côtés successifs du rectangle de recherche, en degrés (0,25 deg ~ 28 km). */
    private static final double[] BOX_HALF_SIZES_DEG = {0.25, 0.5, 1.0, 2.0, 4.0};

    private static final int MAX_CANDIDATES = 500;
    private static final double EARTH_RADIUS_KM = 6371.0;

    private final CityRepository cityRepository;

    public PositionBlurService(CityRepository cityRepository) {
        this.cityRepository = cityRepository;
    }

    /**
     * Position floutée. {@code cityName} et {@code countryCode} sont null quand aucune ville
     * d'au moins {@link #MIN_POPULATION} habitants n'est dans le rayon maximal de recherche.
     * {@code progressPct} est null quand le trajet est inconnu (ville de départ ou d'arrivée
     * absente ou introuvable), quand la position est hors route
     * ({@link #MAX_OFF_ROUTE_RATIO}) ou quand départ et arrivée sont identiques. L'appelant
     * doit gérer ces null.
     */
    public record BlurredPosition(String cityName, String countryCode, Integer progressPct) {}

    /**
     * Floute une position brute. Voir {@link BlurredPosition} pour les cas où les champs
     * du résultat sont null ; ne lève pas d'exception pour un trajet nul ou inconnu.
     */
    public BlurredPosition blur(double lat, double lon,
                                String departureCity, String departureCountry,
                                String arrivalCity, String arrivalCountry) {
        Optional<CityEntity> nearest = nearestCity(lat, lon);
        Integer progress = progressPct(lat, lon, departureCity, departureCountry, arrivalCity, arrivalCountry);
        return new BlurredPosition(
                nearest.map(CityEntity::getName).orElse(null),
                nearest.map(CityEntity::getCountryCode).orElse(null),
                progress);
    }

    private Optional<CityEntity> nearestCity(double lat, double lon) {
        for (double half : BOX_HALF_SIZES_DEG) {
            List<CityEntity> candidates = cityRepository.findInBoundingBox(
                    lat - half, lat + half, lon - half, lon + half, MIN_POPULATION, MAX_CANDIDATES);
            if (!candidates.isEmpty()) {
                return candidates.stream().min(Comparator.comparingDouble(c -> haversineKm(
                        lat, lon, c.getLatitude().doubleValue(), c.getLongitude().doubleValue())));
            }
        }
        return Optional.empty();
    }

    private Integer progressPct(double lat, double lon, String depCity, String depCountry,
                                String arrCity, String arrCountry) {
        if (depCity == null || depCountry == null || arrCity == null || arrCountry == null) return null;
        Optional<CityEntity> dep = cityRepository.findTopByNameAndCountryCode(depCity, depCountry);
        Optional<CityEntity> arr = cityRepository.findTopByNameAndCountryCode(arrCity, arrCountry);
        if (dep.isEmpty() || arr.isEmpty()) return null;

        double lat0 = dep.get().getLatitude().doubleValue();
        double lon0 = dep.get().getLongitude().doubleValue();
        double cos = Math.cos(Math.toRadians(lat0));
        double bx = (arr.get().getLongitude().doubleValue() - lon0) * cos;
        double by = arr.get().getLatitude().doubleValue() - lat0;
        double px = (lon - lon0) * cos;
        double py = lat - lat0;

        double len2 = bx * bx + by * by;
        if (len2 == 0) return null;
        double t = Math.max(0.0, Math.min(1.0, (px * bx + py * by) / len2));
        double dist = Math.hypot(px - t * bx, py - t * by);
        if (dist > MAX_OFF_ROUTE_RATIO * Math.sqrt(len2)) return null;
        return (int) Math.round(t * 100);
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }
}
