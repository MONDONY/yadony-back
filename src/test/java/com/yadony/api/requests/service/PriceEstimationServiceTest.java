package com.yadony.api.requests.service;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.requests.RequestsConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceEstimationServiceTest {

    @Mock private AnnouncementRepository announcementRepo;
    @Mock private RequestsConfig config;
    @Mock private ExchangeRateService exchangeRateService;
    @InjectMocks private PriceEstimationService service;

    @BeforeEach
    void setup() {
        when(config.estimationCorridorRecentTrips()).thenReturn(20);
        // Cible EUR -> identité, comme ExchangeRateService.convert en production.
        lenient().when(exchangeRateService.convert(any(), eq("EUR"), eq("EUR")))
            .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0))
                    .setScale(2, java.math.RoundingMode.HALF_UP));
    }

    /** Annonce dont le pivot EUR est déjà posé (cas nominal depuis V235). */
    private AnnouncementEntity announcementWithPivot(BigDecimal pricePerKgEur) {
        AnnouncementEntity a = Mockito.mock(AnnouncementEntity.class);
        when(a.getPricePerKgEur()).thenReturn(pricePerKgEur);
        return a;
    }

    private List<AnnouncementEntity> buildSample(int n, BigDecimal pivotEur) {
        return IntStream.range(0, n)
            .mapToObj(i -> announcementWithPivot(pivotEur))
            .toList();
    }

    @Test
    @DisplayName("N=15 -> confidence HIGH, range = avg x weight x [0.85, 1.15]")
    void estimate_highConfidence() {
        // buildSample stubbe ses mocks : le sortir de thenReturn, un stubbing pendant
        // un stubbing est un « unfinished stubbing » pour Mockito.
        List<AnnouncementEntity> sample = buildSample(15, new BigDecimal("20"));
        when(announcementRepo.findRecentByCorridor(eq("Paris"), eq("Dakar"), any(Pageable.class)))
            .thenReturn(sample);

        var est = service.estimate("Paris", "Dakar", new BigDecimal("5"), "EUR");

        assertThat(est.confidence()).isEqualTo("HIGH");
        assertThat(est.lowEur()).isEqualByComparingTo("85.00");
        assertThat(est.highEur()).isEqualByComparingTo("115.00");
        assertThat(est.sampleSize()).isEqualTo(15);
    }

    @Test
    @DisplayName("N=5 -> confidence MEDIUM")
    void estimate_mediumConfidence() {
        // buildSample stubbe ses mocks : le sortir de thenReturn, un stubbing pendant
        // un stubbing est un « unfinished stubbing » pour Mockito.
        List<AnnouncementEntity> sample = buildSample(5, new BigDecimal("20"));
        when(announcementRepo.findRecentByCorridor(eq("Paris"), eq("Dakar"), any(Pageable.class)))
            .thenReturn(sample);

        var est = service.estimate("Paris", "Dakar", new BigDecimal("5"), "EUR");

        assertThat(est.confidence()).isEqualTo("MEDIUM");
        assertThat(est.sampleSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("N=2 -> confidence LOW")
    void estimate_lowConfidence() {
        // buildSample stubbe ses mocks : le sortir de thenReturn, un stubbing pendant
        // un stubbing est un « unfinished stubbing » pour Mockito.
        List<AnnouncementEntity> sample = buildSample(2, new BigDecimal("20"));
        when(announcementRepo.findRecentByCorridor(eq("Paris"), eq("Dakar"), any(Pageable.class)))
            .thenReturn(sample);

        var est = service.estimate("Paris", "Dakar", new BigDecimal("5"), "EUR");

        assertThat(est.confidence()).isEqualTo("LOW");
        assertThat(est.sampleSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("N=0 -> fourchette null + LOW")
    void estimate_emptyCorridor() {
        when(announcementRepo.findRecentByCorridor(eq("Paris"), eq("Dakar"), any(Pageable.class)))
            .thenReturn(List.of());

        var est = service.estimate("Paris", "Dakar", new BigDecimal("5"), "EUR");

        assertThat(est.confidence()).isEqualTo("LOW");
        assertThat(est.lowEur()).isNull();
        assertThat(est.highEur()).isNull();
        assertThat(est.sampleSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("corridor actif dans une AUTRE devise : estimé quand même, rendu converti")
    void estimate_crossCurrency_usesWholeCorridorAndConvertsResult() {
        // 15 trajets EUR (pivot 20 EUR/kg) sur Paris->Dakar ; la demande est en XOF.
        // L'ancien cloisonnement rendait « LOW, 0 trajet » ici.
        // buildSample stubbe ses mocks : le sortir de thenReturn, un stubbing pendant
        // un stubbing est un « unfinished stubbing » pour Mockito.
        List<AnnouncementEntity> sample = buildSample(15, new BigDecimal("20"));
        when(announcementRepo.findRecentByCorridor(eq("Paris"), eq("Dakar"), any(Pageable.class)))
            .thenReturn(sample);
        // argThat + compareTo : BigDecimal.equals est sensible au scale, et le
        // service produit 85.000000 (scale 6 après multiply), pas 85.0000.
        when(exchangeRateService.convert(
                argThat(v -> v != null && v.compareTo(new BigDecimal("85")) == 0),
                eq("EUR"), eq("XOF")))
            .thenReturn(new BigDecimal("55756"));
        when(exchangeRateService.convert(
                argThat(v -> v != null && v.compareTo(new BigDecimal("115")) == 0),
                eq("EUR"), eq("XOF")))
            .thenReturn(new BigDecimal("75435"));

        var est = service.estimate("Paris", "Dakar", new BigDecimal("5"), "XOF");

        assertThat(est.confidence()).isEqualTo("HIGH");
        assertThat(est.sampleSize()).isEqualTo(15);
        assertThat(est.currency()).isEqualTo("XOF");
        assertThat(est.lowEur()).isEqualByComparingTo("55756");
        assertThat(est.highEur()).isEqualByComparingTo("75435");
    }

    @Test
    @DisplayName("pivot absent (ligne pré-V235) : repli sur toEurPivot, jamais le brut")
    void estimate_missingPivot_fallsBackToConversion() {
        AnnouncementEntity legacy = Mockito.mock(AnnouncementEntity.class);
        when(legacy.getPricePerKgEur()).thenReturn(null);
        when(legacy.getPricePerKg()).thenReturn(new BigDecimal("13120"));
        when(legacy.getCurrency()).thenReturn("XOF");
        when(announcementRepo.findRecentByCorridor(eq("Dakar"), eq("Paris"), any(Pageable.class)))
            .thenReturn(List.of(legacy));
        when(exchangeRateService.toEurPivot(eq(new BigDecimal("13120")), eq("XOF")))
            .thenReturn(new BigDecimal("20.0000"));

        var est = service.estimate("Dakar", "Paris", new BigDecimal("5"), "EUR");

        assertThat(est.sampleSize()).isEqualTo(1);
        assertThat(est.lowEur()).isEqualByComparingTo("85.00");
        assertThat(est.highEur()).isEqualByComparingTo("115.00");
    }
}
