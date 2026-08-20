package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.matching.events.AnnouncementInProgressEvent;
import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementInProgressScheduler (safety net)")
class AnnouncementInProgressSchedulerTest {

    @Mock private AnnouncementService announcementService;

    private AnnouncementInProgressScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AnnouncementInProgressScheduler(announcementService);
    }

    @Test
    @DisplayName("délègue au service lors du déclenchement horaire")
    void schedulerDelegatesToService() {
        scheduler.processInProgressTransitions();
        verify(announcementService, times(1)).triggerInProgressTransitions();
    }
}

@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementService — triggerInProgressTransitions")
class AnnouncementInProgressTransitionTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private org.springframework.cache.CacheManager cacheManager;

    private AnnouncementService service;

    private final UUID announcementId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        YadonyConfigProperties.Limits.NonPro nonPro = new YadonyConfigProperties.Limits.NonPro(2);
        YadonyConfigProperties.Limits limits = new YadonyConfigProperties.Limits(nonPro, null);
        YadonyConfigProperties config = new YadonyConfigProperties(
                new YadonyConfigProperties.Commission(new java.math.BigDecimal("0.12")), limits,
                new YadonyConfigProperties.Urgency(3), null);
        service = new AnnouncementService(
                announcementRepository, bidRepository,
                mock(com.yadony.api.auth.UserRepository.class),
                auditService, eventPublisher, config,
                com.yadony.api.config.PlatformSettingsTestFactory.withUrgencyThresholdDays(3),
                mock(PriceGridService.class),
                mock(com.yadony.api.country.FlagService.class),
                mock(com.yadony.api.common.StorageService.class),
                mock(com.yadony.api.favorites.FavoriteRepository.class),
                mock(com.yadony.api.payments.currency.ActiveCurrencyResolver.class, inv -> "EUR"),
                mock(com.yadony.api.payments.currency.ExchangeRateService.class),
                mock(AnnouncementSearchMapper.class),
                mock(com.yadony.api.requests.repository.PackageRequestRepository.class),
                mock(com.yadony.api.requests.repository.NegotiationThreadRepository.class),
                mock(com.yadony.api.notifications.NotificationDispatcher.class));
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private AnnouncementEntity activeAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        setId(a, announcementId);
        a.setTravelerId(travelerId);
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setAvailableKg(BigDecimal.valueOf(10));
        a.setTotalKg(BigDecimal.valueOf(20));
        // Date de départ passée → hasDeparted() vrai (timezone par défaut Europe/Paris).
        a.setDepartureDate(LocalDate.now().minusDays(1));
        return a;
    }

    private BidEntity pendingBid() {
        BidEntity b = new BidEntity();
        setId(b, bidId);
        b.setAnnouncementId(announcementId);
        b.setSenderId(senderId);
        b.setStatus(BidStatus.PENDING);
        b.setWeightKg(BigDecimal.valueOf(5));
        return b;
    }

    @Test
    @DisplayName("annonce ACTIVE avec bids ACCEPTED → IN_PROGRESS + bids PENDING expirés + events")
    void activeWithAcceptedBids_becomesInProgress_andExpiresPendingBids() {
        AnnouncementEntity ann = activeAnnouncement();
        BidEntity pending = pendingBid();

        when(announcementRepository.findActiveOrFullDepartingOnOrBefore(any(LocalDate.class)))
                .thenReturn(List.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(announcementId,
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                .thenReturn(true);
        when(bidRepository.findByAnnouncementIdAndStatusIn(announcementId, List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.NEGOTIATING)))
                .thenReturn(List.of(pending));

        service.triggerInProgressTransitions();

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.IN_PROGRESS);
        verify(announcementRepository).save(ann);

        assertThat(pending.getStatus()).isEqualTo(BidStatus.EXPIRED);
        verify(bidRepository).save(pending);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        List<Object> events = eventCaptor.getAllValues();
        assertThat(events).anyMatch(e -> e instanceof AnnouncementInProgressEvent);
        assertThat(events).anyMatch(e -> e instanceof BidExpiredOnDepartureEvent);
    }

    @Test
    @DisplayName("annonce ACTIVE sans bids ACCEPTED → directement COMPLETED")
    void activeWithNoAcceptedBids_becomesCompleted() {
        AnnouncementEntity ann = activeAnnouncement();

        when(announcementRepository.findActiveOrFullDepartingOnOrBefore(any(LocalDate.class)))
                .thenReturn(List.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(announcementId,
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                .thenReturn(false);
        when(bidRepository.findByAnnouncementIdAndStatusIn(announcementId, List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.NEGOTIATING)))
                .thenReturn(List.of());

        service.triggerInProgressTransitions();

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.COMPLETED);
        verify(eventPublisher, never()).publishEvent(any(AnnouncementInProgressEvent.class));
    }

    @Test
    @DisplayName("aucune annonce à traiter → rien")
    void noAnnouncements_doesNothing() {
        when(announcementRepository.findActiveOrFullDepartingOnOrBefore(any(LocalDate.class)))
                .thenReturn(List.of());

        service.triggerInProgressTransitions();

        verify(announcementRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("erreur sur une annonce → les autres continuent")
    void exceptionOnOneAnnouncement_othersStillProcessed() {
        AnnouncementEntity ann1 = activeAnnouncement();
        UUID ann2Id = UUID.randomUUID();
        AnnouncementEntity ann2 = new AnnouncementEntity();
        setId(ann2, ann2Id);
        ann2.setTravelerId(UUID.randomUUID());
        ann2.setStatus(AnnouncementStatus.ACTIVE);
        ann2.setAvailableKg(BigDecimal.valueOf(5));
        ann2.setTotalKg(BigDecimal.valueOf(10));
        ann2.setDepartureDate(LocalDate.now().minusDays(1));

        when(announcementRepository.findActiveOrFullDepartingOnOrBefore(any(LocalDate.class)))
                .thenReturn(List.of(ann1, ann2));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(announcementId),
                eq(List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED))))
                .thenThrow(new RuntimeException("DB error"));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ann2Id),
                eq(List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED))))
                .thenReturn(false);
        when(bidRepository.findByAnnouncementIdAndStatusIn(eq(ann2Id), eq(List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.NEGOTIATING))))
                .thenReturn(List.of());

        service.triggerInProgressTransitions();

        assertThat(ann2.getStatus()).isEqualTo(AnnouncementStatus.COMPLETED);
        verify(announcementRepository).save(ann2);
    }

    @Test
    @DisplayName("hasDeparted : évalué dans le fuseau du trajet (Dakar), pas Europe/Paris")
    void hasDeparted_usesTripTimezoneNotServerParis() {
        AnnouncementEntity dakar = new AnnouncementEntity();
        dakar.setTimezone("Africa/Dakar"); // GMT+0
        dakar.setDepartureDate(LocalDate.of(2026, 6, 20));
        dakar.setDepartureTime(LocalTime.of(23, 0));

        // 22:00 UTC = 22:00 à Dakar (avant 23:00) → pas parti.
        // (À Paris il serait déjà 00:00 le 21 → l'ancien code l'aurait cru parti.)
        Instant before = OffsetDateTime.of(2026, 6, 20, 22, 0, 0, 0, ZoneOffset.UTC).toInstant();
        assertThat(AnnouncementService.hasDeparted(dakar, before)).isFalse();

        // 23:30 UTC = 23:30 à Dakar (après 23:00) → parti.
        Instant after = OffsetDateTime.of(2026, 6, 20, 23, 30, 0, 0, ZoneOffset.UTC).toInstant();
        assertThat(AnnouncementService.hasDeparted(dakar, after)).isTrue();
    }

    @Test
    @DisplayName("hasDeparted : date nulle → non parti (défensif)")
    void hasDeparted_nullDate_returnsFalse() {
        AnnouncementEntity a = new AnnouncementEntity();
        assertThat(AnnouncementService.hasDeparted(a, Instant.now())).isFalse();
    }

    @Test
    @DisplayName("hasDeparted : sans heure, parti une fois la date locale passée")
    void hasDeparted_noTime_departedAfterLocalDate() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTimezone("Africa/Dakar");
        a.setDepartureDate(LocalDate.of(2026, 6, 20));

        // 2026-06-20 12:00 UTC → encore le 20 à Dakar → pas parti.
        Instant sameDay = OffsetDateTime.of(2026, 6, 20, 12, 0, 0, 0, ZoneOffset.UTC).toInstant();
        assertThat(AnnouncementService.hasDeparted(a, sameDay)).isFalse();

        // 2026-06-21 12:00 UTC → le 21 à Dakar → date passée → parti.
        Instant nextDay = OffsetDateTime.of(2026, 6, 21, 12, 0, 0, 0, ZoneOffset.UTC).toInstant();
        assertThat(AnnouncementService.hasDeparted(a, nextDay)).isTrue();
    }
}

/**
 * Verrou anti-fuite (Task 5) : la requête source du scheduler
 * ({@code findActiveOrFullDepartingOnOrBefore}) ne doit jamais renvoyer un
 * brouillon (DRAFT), même avec une date de départ passée — sinon le scheduler
 * le transitionnerait vers IN_PROGRESS/COMPLETED comme un vrai trajet publié.
 * Test au niveau repository (vraie requête JPQL, pas de mock) : c'est la seule
 * façon de garantir que le filtre {@code status IN (ACTIVE, FULL)} tient
 * réellement en base, indépendamment de ce qu'un mock pourrait laisser passer.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AnnouncementInProgressSchedulerDraftLeakTest {

    @Autowired
    AnnouncementRepository repository;

    private AnnouncementEntity newAnnouncement(AnnouncementStatus status, LocalDate departureDate) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDepartureDate(departureDate);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport Bamako-Sénou");
        a.setDeliveryLat(new BigDecimal("12.533579"));
        a.setDeliveryLng(new BigDecimal("-7.948969"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setTimezone("Europe/Paris");
        a.setStatus(status);
        return a;
    }

    @Test
    @DisplayName("scheduler_neverTransitionsDrafts : DRAFT avec départ passé jamais renvoyé")
    void draftDepartingInThePast_neverReturnedForInProgressTransition() {
        repository.saveAndFlush(newAnnouncement(AnnouncementStatus.DRAFT, LocalDate.now().minusDays(5)));

        List<AnnouncementEntity> result = repository.findActiveOrFullDepartingOnOrBefore(LocalDate.now());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("ACTIVE avec départ passé est renvoyé, le DRAFT voisin ne l'est pas")
    void activeDepartingInThePast_isReturned_draftIsNot() {
        repository.saveAndFlush(newAnnouncement(AnnouncementStatus.ACTIVE, LocalDate.now().minusDays(5)));
        repository.saveAndFlush(newAnnouncement(AnnouncementStatus.DRAFT, LocalDate.now().minusDays(5)));

        List<AnnouncementEntity> result = repository.findActiveOrFullDepartingOnOrBefore(LocalDate.now());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
    }
}
