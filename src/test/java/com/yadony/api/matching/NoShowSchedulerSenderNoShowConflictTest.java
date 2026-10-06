package com.yadony.api.matching;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.events.VoyageurNoShowEvent;
import com.yadony.api.tracking.TrackingEventEntity;
import com.yadony.api.tracking.TrackingEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conflit entre le no-show automatique du voyageur ({@link NoShowScheduler}) et la
 * déclaration d'absence de l'expéditeur par le voyageur. Tant que l'expéditeur a ses
 * 24 h pour confirmer ou contester (ou qu'un litige est ouvert), le cron ne doit ni
 * pénaliser le voyageur ni rembourser l'expéditeur.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({NoShowScheduler.class, NoShowService.class})
@RecordApplicationEvents
@DisplayName("NoShowScheduler — absence de l'expéditeur déjà déclarée")
class NoShowSchedulerSenderNoShowConflictTest {

    @Autowired NoShowScheduler scheduler;
    @Autowired BidRepository bidRepository;
    @Autowired UserRepository userRepository;
    @Autowired CancellationRepository cancellationRepository;
    @Autowired TestEntityManager em;
    @Autowired ApplicationEvents events;

    @MockitoBean AuditService auditService;

    @Test
    void declarationEnAttenteDeConfirmation_leCronNeDeclarePasLeVoyageurAbsent() {
        Fixture f = acceptedBidPastDeadline();
        reportSenderNoShow(f.bid, CancellationStatus.PENDING_CONFIRMATION);

        scheduler.detectNoShows();

        assertTravelerNotPenalized(f);
    }

    @Test
    void declarationContestee_leCronNeDeclarePasLeVoyageurAbsent() {
        Fixture f = acceptedBidPastDeadline();
        reportSenderNoShow(f.bid, CancellationStatus.CONTESTED);

        scheduler.detectNoShows();

        assertTravelerNotPenalized(f);
    }

    @Test
    void declarationConfirmee_leCronNeDeclarePasLeVoyageurAbsent() {
        // Filet : le listener synchrone passe normalement le bid en CANCELLED à la
        // confirmation ; même si le bid était resté ACCEPTED, le cron ne doit pas inverser.
        Fixture f = acceptedBidPastDeadline();
        reportSenderNoShow(f.bid, CancellationStatus.CONFIRMED);

        scheduler.detectNoShows();

        assertTravelerNotPenalized(f);
    }

    @Test
    void sansDeclaration_leCronDeclareLeVoyageurAbsent() {
        Fixture f = acceptedBidPastDeadline();

        scheduler.detectNoShows();
        em.flush();
        em.clear();

        assertThat(bidRepository.findById(f.bid.getId()).orElseThrow().getStatus())
                .isEqualTo(BidStatus.NO_SHOW);
        assertThat(userRepository.findById(f.traveler.getId()).orElseThrow().getNoShowCount())
                .isEqualTo(1);
        assertThat(events.stream(VoyageurNoShowEvent.class)).hasSize(1);
    }

    @Test
    void declarationClasseeParLAdmin_leCronReprendSonCoursNormal() {
        // RESOLVED = l'admin a rejeté la déclaration : « le bid continue normalement ».
        Fixture f = acceptedBidPastDeadline();
        reportSenderNoShow(f.bid, CancellationStatus.RESOLVED);

        scheduler.detectNoShows();
        em.flush();
        em.clear();

        assertThat(bidRepository.findById(f.bid.getId()).orElseThrow().getStatus())
                .isEqualTo(BidStatus.NO_SHOW);
    }

    @Test
    void declarationDeLivraison_neBloquePasLeCronDeRemise() {
        // Une ligne de portée DELIVERY (destinataire absent) ne concerne pas la remise.
        Fixture f = acceptedBidPastDeadline();
        CancellationEntity delivery = new CancellationEntity();
        delivery.setBidId(f.bid.getId());
        delivery.setCancelledBy(f.traveler.getId());
        delivery.setReason("RECIPIENT_NO_SHOW");
        delivery.setScope(CancellationScope.DELIVERY);
        delivery.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        cancellationRepository.saveAndFlush(delivery);

        scheduler.detectNoShows();
        em.flush();
        em.clear();

        assertThat(bidRepository.findById(f.bid.getId()).orElseThrow().getStatus())
                .isEqualTo(BidStatus.NO_SHOW);
    }

    @Test
    void scanDeRemiseFait_leCronNeFaitRien() {
        Fixture f = acceptedBidPastDeadline();
        TrackingEventEntity depart = new TrackingEventEntity();
        depart.setBidId(f.bid.getId());
        depart.setEventType(TrackingEventType.DEPART);
        depart.setScannedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(4));
        em.persistAndFlush(depart);

        scheduler.detectNoShows();

        assertTravelerNotPenalized(f);
    }

    private void assertTravelerNotPenalized(Fixture f) {
        em.flush();
        em.clear();
        BidEntity reloaded = bidRepository.findById(f.bid.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BidStatus.ACCEPTED);
        assertThat(reloaded.getNoShowAt()).isNull();
        assertThat(userRepository.findById(f.traveler.getId()).orElseThrow().getNoShowCount())
                .isZero();
        // VoyageurNoShowEvent déclenche le remboursement intégral de l'expéditeur.
        assertThat(events.stream(VoyageurNoShowEvent.class)).isEmpty();
    }

    private void reportSenderNoShow(BidEntity bid, CancellationStatus status) {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(bid.getId());
        c.setCancelledBy(UUID.randomUUID());
        c.setReason(CancellationReason.SENDER_NO_SHOW.name());
        c.setScope(CancellationScope.HANDOVER);
        c.setNoShowStatus(status);
        c.setContestationDeadline(OffsetDateTime.now().plusHours(22));
        cancellationRepository.saveAndFlush(c);
    }

    private Fixture acceptedBidPastDeadline() {
        UserEntity traveler = new UserEntity();
        traveler.setFirebaseUid("uid-" + UUID.randomUUID());
        traveler.setUsername("trav-" + UUID.randomUUID().toString().substring(0, 12));
        traveler.setRoles(new HashSet<>(Set.of(Role.TRAVELER)));
        traveler = userRepository.saveAndFlush(traveler);

        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDepartureDate(LocalDate.now().plusDays(2));
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
        a.setStatus(AnnouncementStatus.ACTIVE);
        UUID announcementId = em.persistAndFlush(a).getId();

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(UUID.randomUUID());
        bid.setWeightKg(new BigDecimal("5.0"));
        bid.setStatus(BidStatus.ACCEPTED);
        // Échéance de remise dépassée de 3 h (> 1 h de grâce du cron).
        bid.setHandoverDeadline(LocalDateTime.now(ZoneOffset.UTC).minusHours(3));
        bid = bidRepository.saveAndFlush(bid);
        return new Fixture(traveler, bid);
    }

    private record Fixture(UserEntity traveler, BidEntity bid) {}
}
