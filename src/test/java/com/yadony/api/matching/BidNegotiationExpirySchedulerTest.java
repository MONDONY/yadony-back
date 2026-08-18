package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.events.BidNegotiationExpiredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Expiration des fils de négociation de trajet")
class BidNegotiationExpirySchedulerTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private BidNegotiationExpiryRunner runner;

    private final MatchingNegotiationConfig config = new MatchingNegotiationConfig(3, 1, 24, "-");

    private BidNegotiationExpiryScheduler scheduler;
    private BidNegotiationExpiryRunner realRunner;

    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = new BidNegotiationExpiryScheduler(bidRepository, runner, config);
        realRunner = new BidNegotiationExpiryRunner(
                bidRepository, announcementRepository, eventPublisher, auditService);
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BidEntity negotiatingBid() {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(ANNOUNCEMENT_ID);
        b.setSenderId(SENDER_ID);
        b.setStatus(BidStatus.NEGOTIATING);
        setId(b, BID_ID);
        return b;
    }

    private AnnouncementEntity announcement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(TRAVELER_ID);
        setId(a, ANNOUNCEMENT_ID);
        return a;
    }

    @Test
    @DisplayName("un fil inactif au-delà du seuil est confié au runner")
    void staleThreadIsExpired() {
        when(bidRepository.findStaleNegotiations(any(LocalDateTime.class)))
                .thenReturn(List.of(negotiatingBid()));

        scheduler.runExpiration();

        ArgumentCaptor<LocalDateTime> threshold = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(bidRepository).findStaleNegotiations(threshold.capture());
        // 1 h de seuil dans le profil test : le seuil doit être dans le passé.
        assertThat(threshold.getValue()).isBefore(LocalDateTime.now(ZoneOffset.UTC));
        verify(runner).expire(BID_ID, "INACTIVE");
    }

    @Test
    @DisplayName("aucun fil éligible → aucune transition")
    void recentThreadIsIgnored() {
        scheduler.runExpiration();

        verify(runner, never()).expire(any(), any());
    }

    @Test
    @DisplayName("un fil sur un trajet déjà parti expire quel que soit son âge")
    void departedTripThreadIsExpired() {
        when(bidRepository.findNegotiationsOnDepartedTrips(any(LocalDate.class)))
                .thenReturn(List.of(negotiatingBid()));

        scheduler.runExpiration();

        verify(runner).expire(BID_ID, "TRIP_DEPARTED");
    }

    @Test
    @DisplayName("un conflit d'écriture concurrente saute l'élément sans casser le lot")
    void optimisticLockFailureSkipsItemOnly() {
        BidEntity first = negotiatingBid();
        BidEntity second = new BidEntity();
        second.setAnnouncementId(ANNOUNCEMENT_ID);
        second.setSenderId(SENDER_ID);
        second.setStatus(BidStatus.NEGOTIATING);
        UUID secondId = UUID.randomUUID();
        setId(second, secondId);

        when(bidRepository.findStaleNegotiations(any(LocalDateTime.class)))
                .thenReturn(List.of(first, second));
        doThrow(new ObjectOptimisticLockingFailureException(BidEntity.class, BID_ID))
                .when(runner).expire(BID_ID, "INACTIVE");

        assertThatCode(scheduler::runExpiration).doesNotThrowAnyException();

        verify(runner).expire(secondId, "INACTIVE");
    }

    @Test
    @DisplayName("le runner éteint le fil sans le transformer en colis expiré")
    void runnerExpiresThread() {
        BidEntity bid = negotiatingBid();
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement()));

        realRunner.expire(BID_ID, "INACTIVE");

        // EXPIRED est un statut de COLIS : le poser ici faisait réapparaître le fil
        // périmé dans « Mes envois » comme une demande de colis expirée.
        assertThat(bid.getStatus()).isEqualTo(BidStatus.NEGOTIATION_CLOSED);
        assertThat(BidStatus.NEGOTIATION_STATUSES).contains(bid.getStatus());
        verify(bidRepository).save(bid);
        verify(auditService).log(eq("BID"), eq(BID_ID), eq("BID_NEGOTIATION_EXPIRED"), eq(null), anyMap());

        ArgumentCaptor<BidNegotiationExpiredEvent> event =
                ArgumentCaptor.forClass(BidNegotiationExpiredEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().senderId()).isEqualTo(SENDER_ID);
        assertThat(event.getValue().travelerId()).isEqualTo(TRAVELER_ID);
        assertThat(event.getValue().reason()).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("deux passages successifs ne produisent qu'une seule transition")
    void secondPassIsIdempotent() {
        BidEntity bid = negotiatingBid();
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement()));

        realRunner.expire(BID_ID, "INACTIVE");
        realRunner.expire(BID_ID, "INACTIVE");

        assertThat(bid.getStatus()).isEqualTo(BidStatus.NEGOTIATION_CLOSED);
        verify(bidRepository, times(1)).save(bid);
        verify(eventPublisher, times(1)).publishEvent(any(BidNegotiationExpiredEvent.class));
        verify(auditService, times(1)).log(eq("BID"), eq(BID_ID), eq("BID_NEGOTIATION_EXPIRED"),
                eq(null), anyMap());
    }

    @Test
    @DisplayName("un fil déjà accepté entre-temps n'est jamais écrasé")
    void alreadyMovedOnThreadIsSkipped() {
        BidEntity bid = negotiatingBid();
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

        realRunner.expire(BID_ID, "INACTIVE");

        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        verify(bidRepository, never()).save(any(BidEntity.class));
        verify(eventPublisher, never()).publishEvent(any(BidNegotiationExpiredEvent.class));
    }
}
