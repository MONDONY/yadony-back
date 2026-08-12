package com.yadony.api.requests.service;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceEstimationServiceTest {

    @Mock private AnnouncementRepository announcementRepo;
    @Mock private RequestsConfig config;
    @InjectMocks private PriceEstimationService service;

    @BeforeEach
    void setup() {
        when(config.estimationCorridorRecentTrips()).thenReturn(20);
    }

    private AnnouncementEntity announcementWithPrice(BigDecimal pricePerKg) {
        AnnouncementEntity a = Mockito.mock(AnnouncementEntity.class);
        when(a.getPricePerKg()).thenReturn(pricePerKg);
        return a;
    }

    private List<AnnouncementEntity> buildSample(int n, BigDecimal pricePerKg) {
        return IntStream.range(0, n)
            .mapToObj(i -> announcementWithPrice(pricePerKg))
            .toList();
    }

    @Test
    @DisplayName("N=15 → confidence HIGH, range = avg×weight × [0.85, 1.15]")
    void estimate_highConfidence() {
        List<AnnouncementEntity> sample = buildSample(15, new BigDecimal("20"));
        when(announcementRepo.findRecentByCorridor(eq("Paris"), eq("Dakar"), eq("EUR"), any(Pageable.class)))
            .thenReturn(sample);

        var est = service.estimate("Paris", "Dakar", new BigDecimal("5"), "EUR");

        assertThat(est.confidence()).isEqualTo("HIGH");
        assertThat(est.lowEur()).isEqualByComparingTo("85.00");
        assertThat(est.highEur()).isEqualByComparingTo("115.00");
        assertThat(est.sampleSize()).isEqualTo(15);
    }

    @Test
    @DisplayName("N=5 → confidence MEDIUM")
    void estimate_mediumConfidence() {
        List<AnnouncementEntity> sample = buildSample(5, new BigDecimal("20"));
        when(announcementRepo.findRecentByCorridor(eq("Paris"), eq("Dakar"), eq("EUR"), any(Pageable.class)))
            .thenReturn(sample);

        var est = service.estimate("Paris", "Dakar", new BigDecimal("5"), "EUR");

        assertThat(est.confidence()).isEqualTo("MEDIUM");
        assertThat(est.sampleSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("N=2 → confidence LOW")
    void estimate_lowConfidence() {
        List<AnnouncementEntity> sample = buildSample(2, new BigDecimal("20"));
        when(announcementRepo.findRecentByCorridor(eq("Paris"), eq("Dakar"), eq("EUR"), any(Pageable.class)))
            .thenReturn(sample);

        var est = service.estimate("Paris", "Dakar", new BigDecimal("5"), "EUR");

        assertThat(est.confidence()).isEqualTo("LOW");
        assertThat(est.sampleSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("N=0 → fourchette null + LOW")
    void estimate_emptyCorridor() {
        when(announcementRepo.findRecentByCorridor(eq("Paris"), eq("Dakar"), eq("EUR"), any(Pageable.class)))
            .thenReturn(List.of());

        var est = service.estimate("Paris", "Dakar", new BigDecimal("5"), "EUR");

        assertThat(est.confidence()).isEqualTo("LOW");
        assertThat(est.lowEur()).isNull();
        assertThat(est.highEur()).isNull();
        assertThat(est.sampleSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("filtre le sample par devise et l'expose dans la réponse — jamais EUR forcé")
    void estimate_nonEurCurrency_filtersRepositoryQueryAndExposesCurrency() {
        List<AnnouncementEntity> sample = buildSample(15, new BigDecimal("20"));
        when(announcementRepo.findRecentByCorridor(eq("Paris"), eq("Toronto"), eq("CAD"), any(Pageable.class)))
            .thenReturn(sample);

        var est = service.estimate("Paris", "Toronto", new BigDecimal("5"), "CAD");

        assertThat(est.currency()).isEqualTo("CAD");
        Mockito.verify(announcementRepo, Mockito.never())
            .findRecentByCorridor(anyString(), anyString(), eq("EUR"), any(Pageable.class));
    }
}
