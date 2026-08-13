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

    public PriceEstimationService(AnnouncementRepository announcementRepo, RequestsConfig config) {
        this.announcementRepo = announcementRepo;
        this.config = config;
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
        // Filtré par devise : moyenner des annonces en devises différentes (ex. EUR et CAD)
        // produirait un montant sans signification. Le sample ne compare que des trajets
        // publiés dans la même devise que la demande à estimer.
        List<AnnouncementEntity> sample = announcementRepo.findRecentByCorridor(
            departure, arrival, currency,
            PageRequest.of(0, config.estimationCorridorRecentTrips()));

        if (sample.isEmpty()) {
            return new PriceEstimateResponse(null, null, "LOW", 0, currency);
        }

        BigDecimal sum = sample.stream()
            .map(AnnouncementEntity::getPricePerKg)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = sum.divide(BigDecimal.valueOf(sample.size()), 2, RoundingMode.HALF_UP);

        BigDecimal low  = avg.multiply(weightKg).multiply(LOW_FACTOR) .setScale(2, RoundingMode.HALF_UP);
        BigDecimal high = avg.multiply(weightKg).multiply(HIGH_FACTOR).setScale(2, RoundingMode.HALF_UP);

        String confidence;
        if (sample.size() >= 10) {
            confidence = "HIGH";
        } else if (sample.size() >= 5) {
            confidence = "MEDIUM";
        } else {
            confidence = "LOW";
        }

        return new PriceEstimateResponse(low, high, confidence, sample.size(), currency);
    }
}
