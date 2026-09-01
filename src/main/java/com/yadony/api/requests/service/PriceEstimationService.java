package com.yadony.api.requests.service;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.dto.PriceEstimateResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

// TODO(cache-eviction): wire @CacheEvict("estimation-corridor") in AnnouncementService.save()
//   to invalidate when announcements change. Out of scope for this task.
//   Cache TTL configured in CacheConfig (Caffeine default expireAfterWrite=5min) applies in the interim.
@Service
public class PriceEstimationService {

    private static final BigDecimal LOW_FACTOR  = new BigDecimal("0.85");
    private static final BigDecimal HIGH_FACTOR = new BigDecimal("1.15");

    private final AnnouncementRepository announcementRepo;
    private final RequestsConfig config;
    private final com.yadony.api.payments.currency.ExchangeRateService exchangeRateService;

    public PriceEstimationService(AnnouncementRepository announcementRepo, RequestsConfig config,
                                  com.yadony.api.payments.currency.ExchangeRateService exchangeRateService) {
        this.announcementRepo = announcementRepo;
        this.config = config;
        this.exchangeRateService = exchangeRateService;
    }

    /**
     * Estimates the price range for shipping a parcel on a given corridor.
     * Uses the average pricePerKg of recent announcements on the same corridor,
     * then applies ±15% to build a confidence range.
     *
     * <p>Confidence levels:
     * <ul>
     *   <li>HIGH   — sample ≥ 10</li>
     *   <li>MEDIUM — sample ≥ 5</li>
     *   <li>LOW    — sample &lt; 5 or empty</li>
     * </ul>
     */
    @Cacheable(value = "estimation-corridor",
               key = "#departure + '|' + #arrival + '|' + #currency + '|' + T(java.lang.Math).ceil(#weightKg.doubleValue())")
    public PriceEstimateResponse estimate(String departure, String arrival, BigDecimal weightKg, String currency) {
        // Marché unifié : l'échantillon prend le corridor TOUTES devises confondues et
        // moyenne sur le pivot EUR (price_per_kg_eur), puis la fourchette est convertie
        // dans la devise de la demande. L'ancien cloisonnement rendait l'estimation
        // muette (« LOW, 0 trajet ») sur un corridor pourtant actif dans une autre
        // devise — Paris→Dakar plein de trajets EUR n'estimait rien pour un
        // expéditeur XOF. La devise reste dans la clé de cache : c'est celle du
        // montant RENDU, pas un filtre.
        List<AnnouncementEntity> sample = announcementRepo.findRecentByCorridor(
            departure, arrival,
            PageRequest.of(0, config.estimationCorridorRecentTrips()));

        List<BigDecimal> pivots = sample.stream()
            .map(a -> a.getPricePerKgEur() != null
                    ? a.getPricePerKgEur()
                    : exchangeRateService.toEurPivot(a.getPricePerKg(), a.getCurrency()))
            .filter(java.util.Objects::nonNull)
            .toList();

        if (pivots.isEmpty()) {
            return new PriceEstimateResponse(null, null, "LOW", 0, currency);
        }

        BigDecimal sum = pivots.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgEur = sum.divide(BigDecimal.valueOf(pivots.size()), 4, RoundingMode.HALF_UP);

        // Bornes en EUR puis conversion : convert() arrondit au nombre de décimales de
        // la devise cible (0 en XOF — un « 5 903,55 F CFA » n'existe pas).
        BigDecimal low = exchangeRateService.convert(
            avgEur.multiply(weightKg).multiply(LOW_FACTOR), "EUR", currency);
        BigDecimal high = exchangeRateService.convert(
            avgEur.multiply(weightKg).multiply(HIGH_FACTOR), "EUR", currency);

        String confidence;
        if (pivots.size() >= 10) {
            confidence = "HIGH";
        } else if (pivots.size() >= 5) {
            confidence = "MEDIUM";
        } else {
            confidence = "LOW";
        }

        return new PriceEstimateResponse(low, high, confidence, pivots.size(), currency);
    }
}
