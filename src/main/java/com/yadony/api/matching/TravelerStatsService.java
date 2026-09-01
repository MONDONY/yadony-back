package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.matching.dto.TravelerStatsDto;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Service
public class TravelerStatsService {

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;
    private final com.yadony.api.payments.currency.ActiveCurrencyResolver activeCurrencyResolver;
    private final com.yadony.api.payments.currency.ExchangeRateService exchangeRateService;

    public TravelerStatsService(
            AnnouncementRepository announcementRepository,
            BidRepository bidRepository,
            PaymentRepository paymentRepository,
            com.yadony.api.payments.currency.ActiveCurrencyResolver activeCurrencyResolver,
            com.yadony.api.payments.currency.ExchangeRateService exchangeRateService
    ) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.paymentRepository = paymentRepository;
        this.activeCurrencyResolver = activeCurrencyResolver;
        this.exchangeRateService = exchangeRateService;
    }

    @Transactional(readOnly = true)
    public TravelerStatsDto computeStats(UserEntity traveler) {
        UUID userId = traveler.getId();
        YearMonth current = YearMonth.now();
        LocalDateTime monthStart = current.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = current.atEndOfMonth().atTime(23, 59, 59);

        // Carte (escrow libéré) + espèces (net des bids CASH livrés, hors PaymentEntity),
        // fusionnés PAR DEVISE puis convertis vers la devise active du voyageur pour le
        // total affiché. La ventilation part telle quelle dans le DTO, sans conversion.
        String activeCurrency = activeCurrencyResolver.resolve(userId);
        java.util.Map<String, BigDecimal> monthlyByCurrency = TravelerRevenue.cardPlusCashByCurrency(
                paymentRepository.sumCapturedRevenueForTravelerByCurrency(
                        userId, PaymentStatus.RELEASED, monthStart, monthEnd),
                bidRepository.sumCashNetRevenueForTravelerByCurrency(
                        userId, BidStatus.COMPLETED, PaymentMethod.CASH, monthStart, monthEnd));
        java.util.Map<String, BigDecimal> totalByCurrency = TravelerRevenue.cardPlusCashByCurrency(
                paymentRepository.sumTotalCapturedRevenueForTravelerByCurrency(userId, PaymentStatus.RELEASED),
                bidRepository.sumTotalCashNetRevenueForTravelerByCurrency(
                        userId, BidStatus.COMPLETED, PaymentMethod.CASH));
        BigDecimal monthlyRevenue = convertedTotal(monthlyByCurrency, activeCurrency);
        BigDecimal totalRevenue = convertedTotal(totalByCurrency, activeCurrency);

        long monthlyTrips = announcementRepository
                .countByTravelerIdAndStatusAndCreatedAtBetween(userId, AnnouncementStatus.COMPLETED, monthStart, monthEnd);

        long deliveredBids = bidRepository
                .countDeliveredBidsForTraveler(userId, BidStatus.COMPLETED, monthStart, monthEnd);

        // Taux d'acceptation : un bid accepté puis livré n'est plus en statut ACCEPTED,
        // on compte donc tout bid ayant dépassé le stade de l'acceptation.
        long accepted = bidRepository.countByAnnouncementTravelerIdAndStatusIn(userId, BidStatus.ACCEPTED_OR_BEYOND);
        // Refus explicites seulement — les bids rejetés par suppression d'annonce ne comptent pas.
        long rejected = bidRepository.countExplicitRejectionsForTraveler(userId);
        double acceptanceRate = (accepted + rejected) == 0 ? 0.0
                : BigDecimal.valueOf((double) accepted / (accepted + rejected))
                        .setScale(2, RoundingMode.HALF_UP).doubleValue();

        // ── Agrégats tout-temps pour la vue d'ensemble du cockpit ──
        long totalTripsCompleted = announcementRepository.countByTravelerIdAndStatus(userId, AnnouncementStatus.COMPLETED);
        // Trajets actifs = mêmes statuts que TripsSummaryService (ACTIVE, FULL, IN_PROGRESS) :
        // un trajet parti avec des colis (IN_PROGRESS) reste « actif » tant qu'il n'est pas COMPLETED.
        long activeTrips = announcementRepository.countByTravelerIdAndStatusIn(
                userId, List.of(AnnouncementStatus.ACTIVE, AnnouncementStatus.FULL, AnnouncementStatus.IN_PROGRESS));
        long totalParcelsDelivered = bidRepository.countByAnnouncementTravelerIdAndStatus(userId, BidStatus.COMPLETED);
        // Colis « en cours » = remis en main OU en transit (pas encore livrés).
        long parcelsInTransit = bidRepository.countByAnnouncementTravelerIdAndStatusIn(userId, BidStatus.EN_ROUTE);

        List<TravelerStatsDto.DestinationStat> topDestinations = announcementRepository
                .findTopDestinationsForTraveler(userId, PageRequest.of(0, 3));

        return new TravelerStatsDto(
                monthlyRevenue,
                totalRevenue,
                monthlyTrips,
                deliveredBids,
                acceptanceRate,
                traveler.getAverageRating() != null ? traveler.getAverageRating() : BigDecimal.ZERO,
                topDestinations,
                totalTripsCompleted,
                activeTrips,
                totalParcelsDelivered,
                parcelsInTransit,
                traveler.getRatingCount(),
                activeCurrency,
                toBreakdown(monthlyByCurrency),
                toBreakdown(totalByCurrency)
        );
    }

    /**
     * Total « environ » dans la devise active : chaque devise encaissée est convertie
     * au taux courant puis sommée. Arrondi au nombre de décimales de la devise active
     * (0 en XOF). Estimation d'affichage — la vérité par devise est la ventilation.
     */
    private BigDecimal convertedTotal(java.util.Map<String, BigDecimal> byCurrency, String activeCurrency) {
        BigDecimal total = byCurrency.entrySet().stream()
                .map(e -> exchangeRateService.convert(e.getValue(), e.getKey(), activeCurrency))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int decimals = com.yadony.api.payments.currency.SupportedCurrency
                .fromCodeOrDefault(activeCurrency).minorUnit();
        return total.setScale(decimals, RoundingMode.HALF_UP);
    }

    private List<TravelerStatsDto.CurrencyRevenue> toBreakdown(java.util.Map<String, BigDecimal> byCurrency) {
        return byCurrency.entrySet().stream()
                .map(e -> new TravelerStatsDto.CurrencyRevenue(e.getKey(), e.getValue()))
                .toList();
    }
}
