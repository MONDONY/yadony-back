package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.matching.dto.TravelerStatsDto;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.dto.CurrencyAmountRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TravelerStatsServiceTest {

    @Mock AnnouncementRepository announcementRepository;
    @Mock BidRepository bidRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock ActiveCurrencyResolver activeCurrencyResolver;
    @Mock ExchangeRateService exchangeRateService;

    private TravelerStatsService service() {
        return new TravelerStatsService(announcementRepository, bidRepository, paymentRepository,
                activeCurrencyResolver, exchangeRateService);
    }

    private UserEntity traveler(UUID id) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        ReflectionTestUtils.setField(u, "averageRating", new BigDecimal("5.00"));
        ReflectionTestUtils.setField(u, "ratingCount", 7);
        return u;
    }

    private void stubCommonCounts(UUID id) {
        when(announcementRepository.countByTravelerIdAndStatusAndCreatedAtBetween(
                eq(id), eq(AnnouncementStatus.COMPLETED), any(), any())).thenReturn(1L);
        when(bidRepository.countDeliveredBidsForTraveler(eq(id), eq(BidStatus.COMPLETED), any(), any()))
                .thenReturn(1L);
        when(bidRepository.countByAnnouncementTravelerIdAndStatusIn(id, BidStatus.ACCEPTED_OR_BEYOND))
                .thenReturn(4L);
        when(bidRepository.countExplicitRejectionsForTraveler(id)).thenReturn(2L);
        when(announcementRepository.countByTravelerIdAndStatus(id, AnnouncementStatus.COMPLETED)).thenReturn(7L);
        when(announcementRepository.countByTravelerIdAndStatusIn(
                eq(id), eq(List.of(AnnouncementStatus.ACTIVE, AnnouncementStatus.FULL, AnnouncementStatus.IN_PROGRESS))))
                .thenReturn(1L);
        when(bidRepository.countByAnnouncementTravelerIdAndStatus(id, BidStatus.COMPLETED)).thenReturn(3L);
        when(bidRepository.countByAnnouncementTravelerIdAndStatusIn(id, BidStatus.EN_ROUTE)).thenReturn(1L);
        when(announcementRepository.findTopDestinationsForTraveler(eq(id), any())).thenReturn(List.of());
    }

    /** Même devise partout : convert est l'identité, comme en production. */
    private void stubIdentityConversion() {
        lenient().when(exchangeRateService.convert(any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void computeStats_acceptanceRate_countsAcceptedThenDeliveredBids() {
        UUID id = UUID.randomUUID();
        UserEntity traveler = traveler(id);

        when(activeCurrencyResolver.resolveDisplay(id)).thenReturn("EUR");
        stubIdentityConversion();
        when(paymentRepository.sumCapturedRevenueForTravelerByCurrency(eq(id), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(List.of());
        when(paymentRepository.sumTotalCapturedRevenueForTravelerByCurrency(eq(id), eq(PaymentStatus.RELEASED)))
                .thenReturn(List.of(new CurrencyAmountRow("EUR", new BigDecimal("402.00"))));
        when(bidRepository.sumCashNetRevenueForTravelerByCurrency(eq(id), eq(BidStatus.COMPLETED), any(), any(), any()))
                .thenReturn(List.of());
        when(bidRepository.sumTotalCashNetRevenueForTravelerByCurrency(eq(id), eq(BidStatus.COMPLETED), any()))
                .thenReturn(List.of());
        stubCommonCounts(id);

        TravelerStatsDto dto = service().computeStats(traveler);

        assertThat(dto.acceptanceRate()).isEqualTo(0.67, within(0.001));
        assertThat(dto.totalTripsCompleted()).isEqualTo(7);
        assertThat(dto.activeTrips()).isEqualTo(1);
        assertThat(dto.totalParcelsDelivered()).isEqualTo(3);
        assertThat(dto.parcelsInTransit()).isEqualTo(1);
        assertThat(dto.ratingCount()).isEqualTo(7);
        assertThat(dto.totalRevenue()).isEqualByComparingTo("402.00");
        assertThat(dto.currency()).isEqualTo("EUR");
        assertThat(dto.totalRevenueByCurrency())
                .containsExactly(new TravelerStatsDto.CurrencyRevenue("EUR", new BigDecimal("402.00")));
    }

    @Test
    void computeStats_multiCurrency_neverSumsRawAmounts() {
        UUID id = UUID.randomUUID();
        UserEntity traveler = traveler(id);

        when(activeCurrencyResolver.resolveDisplay(id)).thenReturn("EUR");
        // Carte : 100 EUR. Cash : 65 595,70 XOF (= 100 EUR à la parité fixe).
        when(paymentRepository.sumCapturedRevenueForTravelerByCurrency(eq(id), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(List.of(new CurrencyAmountRow("EUR", new BigDecimal("100.00"))));
        when(bidRepository.sumCashNetRevenueForTravelerByCurrency(eq(id), eq(BidStatus.COMPLETED), any(), any(), any()))
                .thenReturn(List.of(new CurrencyAmountRow("XOF", new BigDecimal("65595.70"))));
        when(paymentRepository.sumTotalCapturedRevenueForTravelerByCurrency(eq(id), eq(PaymentStatus.RELEASED)))
                .thenReturn(List.of(new CurrencyAmountRow("EUR", new BigDecimal("100.00"))));
        when(bidRepository.sumTotalCashNetRevenueForTravelerByCurrency(eq(id), eq(BidStatus.COMPLETED), any()))
                .thenReturn(List.of(new CurrencyAmountRow("XOF", new BigDecimal("65595.70"))));
        when(exchangeRateService.convert(any(), eq("EUR"), eq("EUR")))
                .thenAnswer(inv -> inv.getArgument(0));
        when(exchangeRateService.convert(eq(new BigDecimal("65595.70")), eq("XOF"), eq("EUR")))
                .thenReturn(new BigDecimal("100.00"));
        stubCommonCounts(id);

        TravelerStatsDto dto = service().computeStats(traveler);

        // 100 EUR + 65 595,70 XOF = 200 EUR convertis — jamais 65 695,70.
        assertThat(dto.monthlyRevenue()).isEqualByComparingTo("200.00");
        assertThat(dto.totalRevenue()).isEqualByComparingTo("200.00");
        // La ventilation garde chaque devise telle quelle, triée alphabétiquement.
        assertThat(dto.monthlyRevenueByCurrency()).containsExactly(
                new TravelerStatsDto.CurrencyRevenue("EUR", new BigDecimal("100.00")),
                new TravelerStatsDto.CurrencyRevenue("XOF", new BigDecimal("65595.70")));
    }
}
