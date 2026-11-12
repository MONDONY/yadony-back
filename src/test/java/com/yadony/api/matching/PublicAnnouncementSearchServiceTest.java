package com.yadony.api.matching;

import com.yadony.api.common.PageResponse;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.matching.dto.PublicAnnouncementCard;
import com.yadony.api.matching.dto.TravelerProfileDto;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicAnnouncementSearchServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final UUID TRAVELER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private AnnouncementService announcementService;
    @InjectMocks private PublicAnnouncementSearchService service;

    @Test
    void appelleLeServiceExistantEnLecteurAnonyme() {
        PublicAnnouncementQuery q = PublicAnnouncementQuery.of(
                "Paris", "Dakar", TODAY.plusDays(3), TODAY.plusDays(20),
                new BigDecimal("5"), new BigDecimal("12"), new BigDecimal("4"), true,
                "plane", "DOCUMENTS", 2, TODAY);
        when(announcementService.searchAnnouncements(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(PageRequest.of(2, 20)));

        service.search(q);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<String> sortBy = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sortDir = ArgumentCaptor.forClass(String.class);
        verify(announcementService).searchAnnouncements(
                org.mockito.ArgumentMatchers.eq("paris"),          // departureCity
                org.mockito.ArgumentMatchers.eq("dakar"),          // arrivalCity
                org.mockito.ArgumentMatchers.eq(TODAY.plusDays(3)),  // departureDateFrom
                org.mockito.ArgumentMatchers.eq(TODAY.plusDays(20)), // departureDateTo
                org.mockito.ArgumentMatchers.eq(new BigDecimal("5")),  // minAvailableKg
                isNull(),                                          // maxAvailableKg
                org.mockito.ArgumentMatchers.eq(new BigDecimal("12")), // maxPricePerKg
                org.mockito.ArgumentMatchers.eq(new BigDecimal("4")),  // minRating
                isNull(),                                          // kiloProOnly
                isNull(),                                          // weekendOnly
                org.mockito.ArgumentMatchers.eq("PLANE"),          // transportMode
                isNull(),                                          // kycVerifiedOnly
                org.mockito.ArgumentMatchers.eq("DOCUMENTS"),      // contentType
                isNull(),                                          // userLat
                isNull(),                                          // userLng
                isNull(),                                          // radiusKm
                sortBy.capture(),
                sortDir.capture(),
                pageable.capture(),
                isNull(),                                          // viewerFirebaseUid
                org.mockito.ArgumentMatchers.eq(true),             // urgent
                // Vue publique : ni bloc 2 ni conversions de devise (I3).
                org.mockito.ArgumentMatchers.eq(AnnouncementSearchExtras.PUBLIC_VIEW));

        assertThat(sortBy.getValue()).isEqualTo("date");
        assertThat(sortDir.getValue()).isEqualTo("asc");
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(q.page());
    }

    @Test
    void transmetTailleMinimaleEtVilleDePassageDansLaVuePublique() {
        PublicAnnouncementQuery q = PublicAnnouncementQuery.of(
                null, null, null, null, null, null, null, null, null, null, "L", "Tanger", 0, TODAY);
        when(announcementService.searchAnnouncements(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        service.search(q);

        ArgumentCaptor<AnnouncementSearchExtras> extras = ArgumentCaptor.forClass(AnnouncementSearchExtras.class);
        verify(announcementService).searchAnnouncements(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), extras.capture());
        assertThat(extras.getValue().publicView()).isTrue();
        assertThat(extras.getValue().minItemSize()).isEqualTo(ItemSize.L);
        assertThat(extras.getValue().viaCity()).isEqualTo("tanger");
    }

    @Test
    void convertitChaqueResultatEnCartePublique() {
        PublicAnnouncementQuery q = PublicAnnouncementQuery.of(
                "Paris", "Dakar", null, null, null, null, null, null, null, null, null, TODAY);
        AnnouncementSearchResponse source = source();
        when(announcementService.searchAnnouncements(
                anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), anyString(), anyString(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(AnnouncementSearchExtras.PUBLIC_VIEW)))
                .thenReturn(new PageImpl<>(List.of(source), PageRequest.of(0, 20), 1));

        PageResponse<PublicAnnouncementCard> result = service.search(q);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.content()).hasSize(1);
        PublicAnnouncementCard card = result.content().get(0);
        assertThat(card).isEqualTo(PublicAnnouncementCard.from(source));
        assertThat(card.id()).isEqualTo(source.id());
        assertThat(card.pricePerKgDisplay()).isEqualByComparingTo("9.00");
        assertThat(card.traveler().displayName()).isEqualTo("Awa D.");
        // Aucun identifiant de voyageur ne sort dans la carte.
        assertThat(card.toString()).doesNotContain(TRAVELER_ID.toString());
        assertThat(PublicAnnouncementCard.class.getRecordComponents())
                .extracting(c -> c.getName())
                .doesNotContain("travelerId", "pricePerKg", "description");
    }

    @Test
    void transactionEnLectureSeuleBorneeA5Secondes() throws Exception {
        // Spec 5.5.3 : une requête publique lente ne retient pas une connexion plus de 5 s.
        Transactional tx = PublicAnnouncementSearchService.class
                .getMethod("search", PublicAnnouncementQuery.class)
                .getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.timeout()).isEqualTo(5);
    }

    @Test
    void cacheSynchronisePourUneSeuleLectureParCle() throws Exception {
        // Rafale de visiteurs sur la même clé froide : un seul calcul, les autres attendent.
        Cacheable cacheable = PublicAnnouncementSearchService.class
                .getMethod("search", PublicAnnouncementQuery.class)
                .getAnnotation(Cacheable.class);
        assertThat(cacheable).isNotNull();
        assertThat(cacheable.value()).containsExactly("public-announcements-search");
        assertThat(cacheable.key()).isEqualTo("#q");
        assertThat(cacheable.sync()).isTrue();
    }

    private static AnnouncementSearchResponse source() {
        TravelerProfileDto traveler = new TravelerProfileDto(
                TRAVELER_ID, "Awa D.", 4.8, 12, false, true, true, true,
                "https://cdn.example.com/avatar.jpg", true);
        return new AnnouncementSearchResponse(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                TRAVELER_ID,
                "Paris", "Dakar",
                LocalDate.of(2026, 12, 20),
                LocalTime.of(10, 30), LocalTime.of(18, 0),
                LocalDate.of(2026, 12, 21),
                null, null,
                new BigDecimal("15.0"), new BigDecimal("23.0"),
                new BigDecimal("8.00"), new BigDecimal("9.00"),
                TransportMode.PLANE, 1, "ACTIVE", 3L, traveler,
                "Appelez-moi au +33612345678",
                List.of("DOCUMENTS"), List.of("LIQUIDS"), List.of("CASH"),
                CapacityUnit.KG_EXACT,
                LocalDateTime.of(2026, 11, 1, 9, 0), LocalDateTime.of(2026, 11, 2, 9, 0),
                PricingMode.KG, List.of(),
                LocalDateTime.of(2026, 12, 19, 18, 0),
                false, true, "EUR", true, Set.of(PaymentMethod.CASH),
                new BigDecimal("5250.00"), "XOF", new BigDecimal("6500.00"),
                42L, "Europe/Paris", null, null);
    }
}
