package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.matching.dto.AnnouncementRevenueRow;
import com.yadony.api.matching.dto.ProAnalyticsResponse;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.ExchangeRateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProAnalyticsServiceTest {

    @Mock AnnouncementRepository announcementRepository;
    @Mock BidRepository bidRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock ActiveCurrencyResolver activeCurrencyResolver;
    @Mock ExchangeRateService exchangeRateService;

    private ProAnalyticsService service() {
        lenient().when(activeCurrencyResolver.resolveDisplay(any())).thenReturn("EUR");
        lenient().when(exchangeRateService.convert(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        return new ProAnalyticsService(announcementRepository, bidRepository, paymentRepository,
                activeCurrencyResolver, exchangeRateService);
    }

    /** Stubs communs des KPI non liés aux transactions (revenus/trajets/colis/acceptation). */
    private void stubKpisToZero() {
        lenient().when(paymentRepository.sumCapturedRevenueForTravelerByCurrency(any(), any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(announcementRepository.countByTravelerIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(0L);
        lenient().when(bidRepository.countDeliveredBidsForTraveler(any(), any(), any(), any()))
                .thenReturn(0L);
        lenient().when(bidRepository.countByAnnouncementTravelerIdAndStatus(any(), any()))
                .thenReturn(0L);
    }

    /**
     * La commission affichée doit refléter la commission RÉELLEMENT prélevée
     * (somme des {@code commissionAmount}, overrides inclus), pas un recalcul
     * {@code gross × taux global}.
     */
    @Test
    void transactions_useActualChargedCommission_notGlobalRate() {
        UUID travelerId = UUID.randomUUID();
        UUID annId = UUID.randomUUID();

        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);

        stubKpisToZero();

        when(paymentRepository.findReleasedRevenueByAnnouncement(
                eq(travelerId), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(List.of(new AnnouncementRevenueRow(
                        annId, "Paris", "Dakar", LocalDate.of(2026, 6, 10), "EUR",
                        2L, new BigDecimal("100.00"), new BigDecimal("8.00"))));

        ProAnalyticsResponse resp = service().computeAnalytics(traveler, "month");

        ProAnalyticsResponse.TransactionRowDto row = resp.transactions().get(0);
        assertThat(row.grossRevenue()).isEqualTo(10000L);   // 100,00 €
        assertThat(row.commission()).isEqualTo(800L);       // 8,00 € (≠ 1200 au taux global)
        assertThat(row.netRevenue()).isEqualTo(9200L);      // 100 − 8 = 92,00 €
        assertThat(row.parcelCount()).isEqualTo(2);
        assertThat(row.corridor()).isEqualTo("Paris → Dakar");
    }

    /**
     * Le détail est piloté par les paiements RELEASED : la somme des lignes Net
     * doit se réconcilier avec le total encaissé (KPI « Revenus nets »).
     */
    @Test
    void transactions_netSumReconcilesWithReleasedPayments() {
        UUID travelerId = UUID.randomUUID();
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);

        stubKpisToZero();

        when(paymentRepository.findReleasedRevenueByAnnouncement(
                eq(travelerId), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(List.of(
                        new AnnouncementRevenueRow(UUID.randomUUID(), "Moscow", "Abidjan",
                                LocalDate.of(2026, 6, 29), "EUR", 1L,
                                new BigDecimal("150.08"), new BigDecimal("16.08")),   // net 134,00
                        new AnnouncementRevenueRow(UUID.randomUUID(), "Paris", "Abidjan",
                                LocalDate.of(2026, 6, 24), "EUR", 1L,
                                new BigDecimal("300.16"), new BigDecimal("32.16"))    // net 268,00
                ));

        ProAnalyticsResponse resp = service().computeAnalytics(traveler, "year");

        long netSum = resp.transactions().stream()
                .mapToLong(ProAnalyticsResponse.TransactionRowDto::netRevenue)
                .sum();
        assertThat(netSum).isEqualTo(40200L); // 134,00 + 268,00 = 402,00 €
        assertThat(resp.transactions()).hasSize(2);
    }

    /**
     * Un trajet réglé en espèces n'a pas de PaymentEntity : sa ligne doit venir
     * du terme cash et fusionner avec la ligne carte de la même annonce, pour que
     * la ventilation reste réconciliée avec le KPI « Revenus ».
     */
    @Test
    void transactions_mergeCardAndCashPerAnnouncement() {
        UUID travelerId = UUID.randomUUID();
        UUID annA = UUID.randomUUID();
        UUID annB = UUID.randomUUID();
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);

        stubKpisToZero();

        // annA : une ligne carte (net 92) + une ligne cash (net 50) → fusion.
        when(paymentRepository.findReleasedRevenueByAnnouncement(
                eq(travelerId), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(List.of(new AnnouncementRevenueRow(
                        annA, "Paris", "Dakar", LocalDate.of(2026, 6, 10), "EUR",
                        2L, new BigDecimal("100.00"), new BigDecimal("8.00"))));
        when(bidRepository.findCashRevenueByAnnouncement(
                eq(travelerId), eq(BidStatus.COMPLETED),
                eq(com.yadony.api.payments.cash.PaymentMethod.CASH), any(), any()))
                .thenReturn(List.of(
                        new AnnouncementRevenueRow(annA, "Paris", "Dakar",
                                LocalDate.of(2026, 6, 10), "EUR", 1L,
                                new BigDecimal("50.00"), BigDecimal.ZERO),
                        new AnnouncementRevenueRow(annB, "Lyon", "Abidjan",
                                LocalDate.of(2026, 6, 5), "EUR", 1L,
                                new BigDecimal("30.00"), BigDecimal.ZERO)));

        ProAnalyticsResponse resp = service().computeAnalytics(traveler, "year");

        assertThat(resp.transactions()).hasSize(2);
        ProAnalyticsResponse.TransactionRowDto merged = resp.transactions().stream()
                .filter(r -> r.tripId().equals(annA.toString())).findFirst().orElseThrow();
        assertThat(merged.parcelCount()).isEqualTo(3);          // 2 carte + 1 cash
        assertThat(merged.grossRevenue()).isEqualTo(15000L);    // 100 + 50
        assertThat(merged.commission()).isEqualTo(800L);        // 8 + 0
        assertThat(merged.netRevenue()).isEqualTo(14200L);      // 92 + 50

        long netSum = resp.transactions().stream()
                .mapToLong(ProAnalyticsResponse.TransactionRowDto::netRevenue).sum();
        assertThat(netSum).isEqualTo(17200L);                    // 142 + 30
    }

    @Test
    void transactions_xofRow_usesWholeUnits_andCarriesCurrency() {
        UUID travelerId = UUID.randomUUID();
        UUID annId = UUID.randomUUID();
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);

        stubKpisToZero();
        // Annonce XOF : minorUnit = 0 → les « unités mineures » sont l'unité pleine.
        // L'ancien toCents multipliait par 100 : 5000 F devenaient 500000.
        when(bidRepository.findCashRevenueByAnnouncement(
                eq(travelerId), eq(BidStatus.COMPLETED),
                eq(com.yadony.api.payments.cash.PaymentMethod.CASH), any(), any()))
                .thenReturn(List.of(new AnnouncementRevenueRow(
                        annId, "Dakar", "Paris", LocalDate.of(2026, 7, 2), "XOF",
                        1L, new BigDecimal("5000"), BigDecimal.ZERO)));

        ProAnalyticsResponse resp = service().computeAnalytics(traveler, "month");

        ProAnalyticsResponse.TransactionRowDto row = resp.transactions().get(0);
        assertThat(row.currency()).isEqualTo("XOF");
        assertThat(row.grossRevenue()).isEqualTo(5000L);
        assertThat(row.netRevenue()).isEqualTo(5000L);
    }

    @Test
    void revenueKpi_convertsEachCurrencyIntoTheActiveOne() {
        UUID travelerId = UUID.randomUUID();
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);

        // service() pose un stub identité sur convert : le construire AVANT le stub
        // spécifique, sinon l'identité (posée après) reprendrait la main.
        ProAnalyticsService svc = service();
        stubKpisToZero();
        when(paymentRepository.sumCapturedRevenueForTravelerByCurrency(any(), any(), any(), any()))
                .thenReturn(List.of(new com.yadony.api.payments.dto.CurrencyAmountRow(
                        "XOF", new BigDecimal("65596"))));
        when(exchangeRateService.convert(eq(new BigDecimal("65596")), eq("XOF"), eq("EUR")))
                .thenReturn(new BigDecimal("100.00"));

        ProAnalyticsResponse resp = svc.computeAnalytics(traveler, "month");

        ProAnalyticsResponse.KpiDto revenue = resp.kpis().stream()
                .filter(k -> k.id().equals("revenue")).findFirst().orElseThrow();
        // 65 596 XOF affichés « 100,00 € » (devise active EUR), pas « 65 596,00 € ».
        assertThat(revenue.value()).contains("100,00");
        assertThat(revenue.value()).doesNotContain("65");
    }

    @Test
    void transactions_emptyWhenNoReleasedPayments() {
        UUID travelerId = UUID.randomUUID();
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);

        stubKpisToZero();
        when(paymentRepository.findReleasedRevenueByAnnouncement(
                eq(travelerId), eq(PaymentStatus.RELEASED), any(), any()))
                .thenReturn(List.of());

        ProAnalyticsResponse resp = service().computeAnalytics(traveler, "month");

        assertThat(resp.transactions()).isEmpty();
    }
}
