package com.yadony.api.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.matching.dto.TripsSummaryDto;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.dto.CurrencyAmountRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TripsSummaryServiceTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;
    @Mock private ActiveCurrencyResolver activeCurrencyResolver;
    @Mock private ExchangeRateService exchangeRateService;

    private TripsSummaryService service;
    private UserEntity traveler;

    @BeforeEach
    void setUp() {
        service = new TripsSummaryService(
                announcementRepository, bidRepository, paymentRepository, cacheManager,
                activeCurrencyResolver, exchangeRateService);
        traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", UUID.randomUUID());
        org.mockito.Mockito.lenient().when(activeCurrencyResolver.resolveDisplay(any())).thenReturn("EUR");
        // Même devise → identité, comme ExchangeRateService.convert en production.
        org.mockito.Mockito.lenient().when(exchangeRateService.convert(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static List<CurrencyAmountRow> eur(String amount) {
        return List.of(new CurrencyAmountRow("EUR", new BigDecimal(amount)));
    }

    @Test
    void computeSummary_aggregates_active_trips_kg_and_revenue() {
        when(announcementRepository.countByTravelerIdAndStatusIn(
                eq(traveler.getId()),
                eq(List.of(AnnouncementStatus.ACTIVE, AnnouncementStatus.FULL,
                        AnnouncementStatus.IN_PROGRESS)))).thenReturn(3L);
        when(bidRepository.sumDeliveredKgForTraveler(
                eq(traveler.getId()), eq(BidStatus.COMPLETED), any(), any()))
                .thenReturn(new BigDecimal("19.0"));
        when(paymentRepository.sumCapturedRevenueForTravelerByCurrency(
                eq(traveler.getId()), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(eur("152.4567"));

        TripsSummaryDto dto = service.computeSummary(traveler, StatsPeriod.DEFAULT);

        assertThat(dto.activeTrips()).isEqualTo(3);
        assertThat(dto.kgSold()).isEqualByComparingTo("19.0");
        assertThat(dto.revenue()).isEqualByComparingTo("152.46");
    }

    @Test
    void computeSummary_adds_cash_revenue_to_card_revenue() {
        when(paymentRepository.sumCapturedRevenueForTravelerByCurrency(
                eq(traveler.getId()), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(eur("150.00"));
        // Les deals réglés en espèces ne créent pas de PaymentEntity : leur net
        // (bids CASH livrés) doit s'ajouter au revenu carte, pas rester à 0.
        when(bidRepository.sumCashNetRevenueForTravelerByCurrency(
                eq(traveler.getId()), eq(BidStatus.COMPLETED),
                eq(com.yadony.api.payments.cash.PaymentMethod.CASH), any(), any()))
                .thenReturn(eur("50.00"));

        TripsSummaryDto dto = service.computeSummary(traveler, StatsPeriod.DEFAULT);

        assertThat(dto.revenue()).isEqualByComparingTo("200.00");
    }

    @Test
    void computeSummary_returns_zeros_when_repositories_return_nothing() {
        when(announcementRepository.countByTravelerIdAndStatusIn(
                eq(traveler.getId()), any())).thenReturn(0L);
        when(bidRepository.sumDeliveredKgForTraveler(any(), any(), any(), any()))
                .thenReturn(null);
        when(paymentRepository.sumCapturedRevenueForTravelerByCurrency(any(), any(), any(), any()))
                .thenReturn(List.of());

        TripsSummaryDto dto = service.computeSummary(traveler, StatsPeriod.DEFAULT);

        assertThat(dto.activeTrips()).isZero();
        assertThat(dto.kgSold()).isEqualByComparingTo("0");
        assertThat(dto.revenue()).isEqualByComparingTo("0");
    }

    @Test
    void computeSummary_exposes_the_legacy_aliases_with_the_same_values() {
        when(bidRepository.sumDeliveredKgForTraveler(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("4.0"));
        when(paymentRepository.sumCapturedRevenueForTravelerByCurrency(any(), any(), any(), any()))
                .thenReturn(eur("40.00"));

        TripsSummaryDto dto = service.computeSummary(traveler, StatsPeriod.LAST_7_DAYS);

        // Les clients déployés lisent encore les noms « ThisMonth » : ce sont
        // des alias de sérialisation, ils ne peuvent pas diverger.
        assertThat(dto.kgSoldThisMonth()).isEqualByComparingTo(dto.kgSold());
        assertThat(dto.revenueThisMonth()).isEqualByComparingTo(dto.revenue());
        assertThat(dto.period()).isEqualTo("7d");
    }

    @Test
    void computeSummary_narrows_the_window_for_shorter_periods() {
        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        when(bidRepository.sumDeliveredKgForTraveler(
                any(), any(), from.capture(), any())).thenReturn(BigDecimal.ZERO);

        service.computeSummary(traveler, StatsPeriod.LAST_7_DAYS);
        LocalDateTime sevenDays = from.getValue();

        service.computeSummary(traveler, StatsPeriod.LAST_12_MONTHS);
        LocalDateTime twelveMonths = from.getValue();

        assertThat(twelveMonths).isBefore(sevenDays);
        assertThat(sevenDays.toLocalDate()).isEqualTo(LocalDate.now().minusDays(7));
        assertThat(twelveMonths.toLocalDate()).isEqualTo(LocalDate.now().minusMonths(12));
    }

    @Test
    void computeSummary_counts_trips_published_and_parcels_sent() {
        when(announcementRepository.countByTravelerIdAndCreatedAtBetweenAndStatusNot(
                eq(traveler.getId()), any(), any(), eq(AnnouncementStatus.DRAFT)))
                .thenReturn(2L);
        when(bidRepository.countParcelsSentBySender(
                eq(traveler.getId()), any(), any(), any())).thenReturn(5L);

        TripsSummaryDto dto = service.computeSummary(traveler, StatsPeriod.DEFAULT);

        assertThat(dto.tripsPublished()).isEqualTo(2);
        assertThat(dto.parcelsSent()).isEqualTo(5);
    }

    @Test
    void computeSummary_converts_each_currency_before_summing() {
        // 100 EUR carte + 65 595,70 XOF cash → 200 EUR, jamais 65 695,70.
        when(paymentRepository.sumCapturedRevenueForTravelerByCurrency(
                eq(traveler.getId()), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(eur("100.00"));
        when(bidRepository.sumCashNetRevenueForTravelerByCurrency(
                eq(traveler.getId()), eq(BidStatus.COMPLETED),
                eq(com.yadony.api.payments.cash.PaymentMethod.CASH), any(), any()))
                .thenReturn(List.of(new CurrencyAmountRow("XOF", new BigDecimal("65595.70"))));
        when(exchangeRateService.convert(eq(new BigDecimal("65595.70")), eq("XOF"), eq("EUR")))
                .thenReturn(new BigDecimal("100.00"));

        TripsSummaryDto dto = service.computeSummary(traveler, StatsPeriod.DEFAULT);

        assertThat(dto.revenue()).isEqualByComparingTo("200.00");
    }

    @Test
    void evictSummary_clears_every_period_of_the_traveler() {
        UUID travelerId = UUID.randomUUID();
        when(cacheManager.getCache(TripsSummaryService.CACHE_NAME)).thenReturn(cache);

        service.evictSummary(travelerId);

        // Une période oubliée resterait cachée jusqu'au TTL : l'éviction
        // parcourt l'enum plutôt qu'une liste de clés écrite à la main.
        for (StatsPeriod period : StatsPeriod.values()) {
            verify(cache).evict(StatsPeriod.cacheKey(travelerId, period));
        }
    }

    @Test
    void evictSummary_is_a_noop_when_the_cache_is_absent() {
        when(cacheManager.getCache(TripsSummaryService.CACHE_NAME)).thenReturn(null);

        service.evictSummary(UUID.randomUUID());
    }
}
