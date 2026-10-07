package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.dto.PriceGridItemRequest;
import com.yadony.api.matching.dto.PriceGridItemResponse;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.ExchangeRateLookup;
import com.yadony.api.payments.currency.ExchangeRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PriceGridServiceTest {

    @Mock PriceGridItemRepository gridRepo;
    @Mock AnnouncementPriceGridItemRepository annGridRepo;
    @Mock AuditService auditService;
    @Mock CommissionRateResolver commissionRateResolver;
    @Mock ActiveCurrencyResolver activeCurrencyResolver;
    @Mock ExchangeRateLookup exchangeRateLookup;

    PriceGridService service;

    @BeforeEach
    void setUp() {
        // Vrai ExchangeRateService sur un lookup simulé : les tests de conversion vérifient
        // l'arrondi réel (décimales de la devise cible, HALF_UP), pas un mock.
        service = new PriceGridService(gridRepo, annGridRepo, auditService, commissionRateResolver,
                activeCurrencyResolver, new ExchangeRateService(exchangeRateLookup));
        lenient().when(activeCurrencyResolver.resolve(any())).thenReturn("EUR");
        lenient().when(exchangeRateLookup.unitsPerEur("XOF")).thenReturn(new BigDecimal("655.957"));
        lenient().when(exchangeRateLookup.unitsPerEur("XAF")).thenReturn(new BigDecimal("655.957"));
    }

    private static PriceGridItemEntity gridItem(String label, String net, int position) {
        PriceGridItemEntity item = new PriceGridItemEntity();
        item.setLabel(label);
        item.setUnitPriceNet(new BigDecimal(net));
        item.setPosition(position);
        return item;
    }

    @SuppressWarnings("unchecked")
    private List<AnnouncementPriceGridItemEntity> capturedSnapshots() {
        org.mockito.ArgumentCaptor<List<AnnouncementPriceGridItemEntity>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(annGridRepo).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void addItem_saves_and_returns_response_with_display_price() {
        UUID travelerId = UUID.randomUUID();
        when(commissionRateResolver.resolve(any())).thenReturn(new BigDecimal("0.12"));
        PriceGridItemRequest req = new PriceGridItemRequest("Valise cabine", new BigDecimal("10.00"));

        PriceGridItemEntity saved = new PriceGridItemEntity();
        saved.setLabel("Valise cabine");
        saved.setUnitPriceNet(new BigDecimal("10.00"));
        saved.setPosition(0);

        when(gridRepo.countByTravelerId(travelerId)).thenReturn(0L);
        when(gridRepo.save(any())).thenReturn(saved);

        PriceGridItemResponse response = service.addItem(travelerId, req, travelerId);

        assertThat(response.label()).isEqualTo("Valise cabine");
        assertThat(response.unitPriceNet()).isEqualByComparingTo("10.00");
        assertThat(response.unitPriceDisplay()).isEqualByComparingTo("11.20");
        verify(gridRepo).save(any(PriceGridItemEntity.class));
    }

    @Test
    void displayPrice_derivesFromResolvedCommissionRate() {
        // SOURCE UNIQUE : le taux vient du resolver (override utilisateur / yadony.commission.rate).
        // À 20 % : 10,00 € net → 12,00 € affiché.
        when(commissionRateResolver.resolve(any())).thenReturn(new BigDecimal("0.20"));
        assertThat(service.displayPrice(new BigDecimal("10.00"), null)).isEqualByComparingTo("12.00");
    }

    @Test
    void addItem_rejects_when_limit_reached() {
        UUID travelerId = UUID.randomUUID();
        when(gridRepo.countByTravelerId(travelerId)).thenReturn(20L);

        assertThatThrownBy(() ->
            service.addItem(travelerId, new PriceGridItemRequest("X", BigDecimal.ONE), travelerId)
        ).isInstanceOf(ResponseStatusException.class)
         .hasMessageContaining("price-grid-limit");
    }

    @Test
    void snapshotToAnnouncement_copies_items_immutably() {
        UUID travelerId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();

        PriceGridItemEntity item = new PriceGridItemEntity();
        item.setLabel("Carton");
        item.setUnitPriceNet(new BigDecimal("15.00"));
        item.setPosition(0);

        when(gridRepo.findByTravelerIdOrderByPositionAsc(travelerId)).thenReturn(List.of(item));

        service.snapshotToAnnouncement(travelerId, announcementId, "EUR");

        verify(annGridRepo).saveAll(argThat(items -> {
            var list = (java.util.List<AnnouncementPriceGridItemEntity>) items;
            AnnouncementPriceGridItemEntity snap = list.get(0);
            return snap.getLabel().equals("Carton")
                && snap.getUnitPriceNet().compareTo(new BigDecimal("15.00")) == 0
                && snap.getAnnouncementId().equals(announcementId);
        }));
    }

    @Test
    void snapshotToAnnouncement_throws_422_when_grid_empty() {
        UUID travelerId = UUID.randomUUID();
        when(gridRepo.findByTravelerIdOrderByPositionAsc(travelerId)).thenReturn(List.of());

        assertThatThrownBy(() ->
            service.snapshotToAnnouncement(travelerId, UUID.randomUUID(), "EUR")
        ).isInstanceOf(ResponseStatusException.class)
         .satisfies(ex -> assertThat(((ResponseStatusException)ex).getStatusCode())
             .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void displayPrice_multiplies_by_resolved_rate_with_half_up_rounding() {
        when(commissionRateResolver.resolve(any())).thenReturn(new BigDecimal("0.12"));
        assertThat(service.displayPrice(new BigDecimal("10.00"), null)).isEqualByComparingTo("11.20");
        assertThat(service.displayPrice(new BigDecimal("7.00"), null)).isEqualByComparingTo("7.84");
        // 41.00 × 1.12 = 45.92 (used in spec acceptance criteria)
        assertThat(service.displayPrice(new BigDecimal("41.00"), null)).isEqualByComparingTo("45.92");
    }

    @Test
    void snapshotToAnnouncement_convertsEurGridToXofAnnouncement_FLUTTER_ER() {
        // Grille staging (EUR) publiée sur une annonce XOF : 10 € ne doit plus devenir 10 F CFA.
        UUID travelerId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        when(gridRepo.findByTravelerIdOrderByPositionAsc(travelerId)).thenReturn(List.of(
                gridItem("Valise", "10.00", 0),
                gridItem("Carton", "12.00", 1),
                gridItem("Sac", "8.00", 2),
                gridItem("Colis", "13.00", 3)));

        service.snapshotToAnnouncement(travelerId, announcementId, "XOF");

        List<AnnouncementPriceGridItemEntity> snaps = capturedSnapshots();
        // 10 × 655,957 = 6 559,57 → 6 560 ; 12 → 7 871,484 → 7 871 ; 8 → 5 247,656 → 5 248 ;
        // 13 → 8 527,441 → 8 527 (XOF sans sous-unité, HALF_UP).
        assertThat(snaps).extracting(AnnouncementPriceGridItemEntity::getUnitPriceNet)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("6560"), new BigDecimal("7871"),
                        new BigDecimal("5248"), new BigDecimal("8527"));
        assertThat(snaps.get(0).getUnitPriceNet().scale()).isZero();
        assertThat(snaps).extracting(AnnouncementPriceGridItemEntity::getLabel)
                .containsExactly("Valise", "Carton", "Sac", "Colis");
        assertThat(snaps).allMatch(s -> s.getAnnouncementId().equals(announcementId));
        verify(auditService).log(eq("ANNOUNCEMENT"), eq(announcementId),
                eq("ANNOUNCEMENT_PRICE_GRID_SNAPSHOTTED"), eq(travelerId),
                eq(java.util.Map.<String, Object>of("itemCount", "4",
                        "gridCurrency", "EUR", "announcementCurrency", "XOF")));
    }

    @Test
    void snapshotToAnnouncement_convertsEurGridToXafAnnouncement() {
        UUID travelerId = UUID.randomUUID();
        when(gridRepo.findByTravelerIdOrderByPositionAsc(travelerId))
                .thenReturn(List.of(gridItem("Valise", "10.00", 0)));

        service.snapshotToAnnouncement(travelerId, UUID.randomUUID(), "xaf");

        assertThat(capturedSnapshots().get(0).getUnitPriceNet()).isEqualByComparingTo("6560");
    }

    @Test
    void snapshotToAnnouncement_sameCurrency_copiesNetUnchanged_withoutRateLookup() {
        UUID travelerId = UUID.randomUUID();
        when(gridRepo.findByTravelerIdOrderByPositionAsc(travelerId))
                .thenReturn(List.of(gridItem("Valise", "10.55", 0)));

        service.snapshotToAnnouncement(travelerId, UUID.randomUUID(), "EUR");

        BigDecimal net = capturedSnapshots().get(0).getUnitPriceNet();
        assertThat(net).isEqualTo(new BigDecimal("10.55"));
        verifyNoInteractions(exchangeRateLookup);
    }

    @Test
    void snapshotToAnnouncement_blankCurrency_fallsBackToGridCurrency() {
        UUID travelerId = UUID.randomUUID();
        when(gridRepo.findByTravelerIdOrderByPositionAsc(travelerId))
                .thenReturn(List.of(gridItem("Valise", "10.55", 0)));

        service.snapshotToAnnouncement(travelerId, UUID.randomUUID(), " ");

        assertThat(capturedSnapshots().get(0).getUnitPriceNet()).isEqualTo(new BigDecimal("10.55"));
        verifyNoInteractions(exchangeRateLookup);
    }

    @Test
    void snapshotToAnnouncement_convertsXofGridToEurAnnouncement() {
        UUID travelerId = UUID.randomUUID();
        when(activeCurrencyResolver.resolve(travelerId)).thenReturn("XOF");
        when(gridRepo.findByTravelerIdOrderByPositionAsc(travelerId)).thenReturn(List.of(
                gridItem("Valise", "6560", 0),
                gridItem("Carton", "17000", 1)));

        service.snapshotToAnnouncement(travelerId, UUID.randomUUID(), "EUR");

        // 6 560 / 655,957 = 10,0006… → 10,00 ; 17 000 / 655,957 = 25,9163… → 25,92.
        assertThat(capturedSnapshots()).extracting(AnnouncementPriceGridItemEntity::getUnitPriceNet)
                .containsExactly(new BigDecimal("10.00"), new BigDecimal("25.92"));
    }

    @Test
    void snapshotToAnnouncement_tinyConvertedNet_isRaisedToSmallestUnit() {
        // 1 F CFA ≈ 0,0015 € : arrondi à 0,00, il deviendrait un article gratuit.
        UUID travelerId = UUID.randomUUID();
        when(activeCurrencyResolver.resolve(travelerId)).thenReturn("XOF");
        when(gridRepo.findByTravelerIdOrderByPositionAsc(travelerId))
                .thenReturn(List.of(gridItem("Enveloppe", "1", 0)));

        service.snapshotToAnnouncement(travelerId, UUID.randomUUID(), "EUR");

        assertThat(capturedSnapshots().get(0).getUnitPriceNet()).isEqualTo(new BigDecimal("0.01"));
    }
}
