package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.matching.dto.TripsSummaryDto;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripsSummaryService {

    public static final String CACHE_NAME = "trips-summary";

    private static final List<AnnouncementStatus> ACTIVE_STATUSES = List.of(
            AnnouncementStatus.ACTIVE,
            AnnouncementStatus.FULL,
            AnnouncementStatus.IN_PROGRESS);

    /** Bids qui ne sont jamais devenus un envoi réel — exclus du compte « colis envoyés ». */
    private static final List<BidStatus> NON_SHIPMENT_STATUSES = List.of(
            BidStatus.AWAITING_PAYMENT,
            BidStatus.REJECTED,
            BidStatus.CANCELLED,
            BidStatus.EXPIRED,
            BidStatus.NEGOTIATING);

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;
    private final CacheManager cacheManager;
    private final com.yadony.api.payments.currency.ActiveCurrencyResolver activeCurrencyResolver;
    private final com.yadony.api.payments.currency.ExchangeRateService exchangeRateService;

    public TripsSummaryService(
            AnnouncementRepository announcementRepository,
            BidRepository bidRepository,
            PaymentRepository paymentRepository,
            CacheManager cacheManager,
            com.yadony.api.payments.currency.ActiveCurrencyResolver activeCurrencyResolver,
            com.yadony.api.payments.currency.ExchangeRateService exchangeRateService) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.paymentRepository = paymentRepository;
        this.cacheManager = cacheManager;
        this.activeCurrencyResolver = activeCurrencyResolver;
        this.exchangeRateService = exchangeRateService;
    }

    @Cacheable(
            cacheNames = CACHE_NAME,
            key = "T(com.yadony.api.matching.StatsPeriod).cacheKey(#traveler.id, #period)")
    @Transactional(readOnly = true)
    public TripsSummaryDto computeSummary(UserEntity traveler, StatsPeriod period) {
        UUID userId = traveler.getId();
        LocalDateTime from = period.start();
        LocalDateTime to = LocalDateTime.now();

        // activeTrips est un état courant, pas une mesure de période : il ne
        // dépend pas de l'intervalle demandé.
        long activeTrips = announcementRepository
                .countByTravelerIdAndStatusIn(userId, ACTIVE_STATUSES);

        BigDecimal kgSold = bidRepository.sumDeliveredKgForTraveler(
                userId, BidStatus.COMPLETED, from, to);

        // Revenu = carte (escrow libéré) + espèces (net des bids CASH livrés, qui
        // ne passent par aucun PaymentEntity), agrégé PAR DEVISE puis converti au
        // taux courant vers la devise active du voyageur — sommer EUR et XOF à plat
        // n'a pas de sens. Sans le terme cash, un trajet réglé en espèces restait à
        // 0 alors que « Kg vendus » le comptait déjà.
        String activeCurrency = activeCurrencyResolver.resolveDisplay(userId);
        BigDecimal revenue = TravelerRevenue.cardPlusCashByCurrency(
                        paymentRepository.sumCapturedRevenueForTravelerByCurrency(
                                userId, PaymentStatus.RELEASED, from, to),
                        bidRepository.sumCashNetRevenueForTravelerByCurrency(
                                userId, BidStatus.COMPLETED, PaymentMethod.CASH, from, to))
                .entrySet().stream()
                .map(e -> exchangeRateService.convert(e.getValue(), e.getKey(), activeCurrency))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long tripsPublished = announcementRepository
                .countByTravelerIdAndCreatedAtBetweenAndStatusNot(
                        userId, from, to, AnnouncementStatus.DRAFT);

        long parcelsSent = bidRepository.countParcelsSentBySender(
                userId, from, to, NON_SHIPMENT_STATUSES);

        return TripsSummaryDto.of(
                activeTrips,
                kgSold != null ? kgSold : BigDecimal.ZERO,
                revenue.setScale(
                        com.yadony.api.payments.currency.SupportedCurrency
                                .fromCodeOrDefault(activeCurrency).minorUnit(),
                        RoundingMode.HALF_UP),
                tripsPublished,
                parcelsSent,
                period.apiValue());
    }

    /**
     * Invalide le résumé caché d'un voyageur, toutes périodes confondues. À
     * appeler dès que ses kg livrés ou son escrow libéré changent (livraison
     * confirmée, paiement libéré) pour que les statistiques se rafraîchissent
     * sans attendre le TTL Caffeine (5 min).
     *
     * <p>Éviction programmatique plutôt que {@code @CacheEvict} : les clés
     * dépendent de {@link StatsPeriod#values()}, qu'une annotation ne peut pas
     * parcourir — une période ajoutée resterait cachée indéfiniment.
     */
    public void evictSummary(UUID travelerId) {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache == null) {
            return;
        }
        for (StatsPeriod period : StatsPeriod.values()) {
            cache.evict(StatsPeriod.cacheKey(travelerId, period));
        }
    }
}
