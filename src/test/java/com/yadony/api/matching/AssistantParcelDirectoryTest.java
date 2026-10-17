package com.yadony.api.matching;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.assistant.ParcelRef;
import com.yadony.api.common.assistant.ParcelRole;
import com.yadony.api.matching.reception.BidRecipientLinkEntity;
import com.yadony.api.matching.reception.BidRecipientLinkRepository;
import com.yadony.api.matching.reception.ReceptionLinkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accès aux colis par l'assistant, sur une vraie base : seuls l'expéditeur, le voyageur et le
 * destinataire confirmé voient un colis, et un numéro saisi par un autre ne révèle rien.
 */
@DataJpaTest
@ActiveProfiles("test")
class AssistantParcelDirectoryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private BidRecipientLinkRepository linkRepository;
    @Autowired private TestEntityManager em;

    private AssistantParcelDirectory directory;
    private UserEntity sender;
    private UserEntity traveler;
    private UserEntity recipient;
    private UserEntity stranger;
    private UUID tripId;

    @BeforeEach
    void setUp() {
        directory = new AssistantParcelDirectory(userRepository, bidRepository, announcementRepository, linkRepository);
        sender = user(Role.SENDER);
        traveler = user(Role.TRAVELER);
        recipient = user(Role.SENDER);
        stranger = user(Role.SENDER);
        tripId = trip(traveler.getId());
    }

    private UserEntity user(Role role) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setUsername("u-" + UUID.randomUUID().toString().substring(0, 12));
        u.setRoles(new HashSet<>(Set.of(role)));
        return userRepository.saveAndFlush(u);
    }

    private UUID trip(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.of(2026, 10, 20));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport de Dakar");
        a.setDeliveryLat(new BigDecimal("14.670000"));
        a.setDeliveryLng(new BigDecimal("-17.070000"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        return em.persistAndFlush(a).getId();
    }

    private BidEntity bid(BidStatus status, String number) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(tripId);
        b.setSenderId(sender.getId());
        b.setStatus(status);
        b.setTrackingNumber(number);
        return em.persistAndFlush(b);
    }

    private void age(BidEntity bid, long days) {
        em.getEntityManager().createNativeQuery("UPDATE bids SET updated_at = :ts WHERE id = :id")
                .setParameter("ts", LocalDateTime.now(ZoneOffset.UTC).minusDays(days))
                .setParameter("id", bid.getId())
                .executeUpdate();
        em.clear();
    }

    private void link(BidEntity bid, UserEntity user, ReceptionLinkStatus status) {
        BidRecipientLinkEntity l = new BidRecipientLinkEntity(bid.getId(), user.getId());
        if (status != ReceptionLinkStatus.PENDING) l.respond(status, OffsetDateTime.now());
        em.persistAndFlush(l);
    }

    @Test
    void chacunRetrouveLeColisSousSonRole() {
        BidEntity b = bid(BidStatus.IN_TRANSIT, "DON-7K3M9QXA");
        link(b, recipient, ReceptionLinkStatus.CONFIRMED);

        assertThat(directory.byTrackingNumber(sender.getFirebaseUid(), "DON-7K3M9QXA"))
                .map(ParcelRef::role).contains(ParcelRole.SENDER);
        assertThat(directory.byTrackingNumber(traveler.getFirebaseUid(), "DON-7K3M9QXA"))
                .map(ParcelRef::role).contains(ParcelRole.TRAVELER);
        assertThat(directory.byId(recipient.getFirebaseUid(), b.getId()))
                .map(ParcelRef::role).contains(ParcelRole.RECIPIENT);
        ParcelRef ref = directory.byId(sender.getFirebaseUid(), b.getId()).orElseThrow();
        assertThat(ref.departureCity()).isEqualTo("Paris");
        assertThat(ref.arrivalCity()).isEqualTo("Dakar");
        assertThat(ref.active()).isTrue();
    }

    @Test
    void unAutreNeVoitRienNiParNumeroNiParIdentifiant() {
        BidEntity b = bid(BidStatus.IN_TRANSIT, "DON-7K3M9QXA");
        assertThat(directory.byTrackingNumber(stranger.getFirebaseUid(), "DON-7K3M9QXA")).isEmpty();
        assertThat(directory.byId(stranger.getFirebaseUid(), b.getId())).isEmpty();
        assertThat(directory.byTrackingNumber("uid-inconnu", "DON-7K3M9QXA")).isEmpty();
        assertThat(directory.byTrackingNumber(sender.getFirebaseUid(), "DON-ZZZZ9999")).isEmpty();
    }

    @Test
    void destinataireNonConfirmeOuQuiARefuseNeVoitRien() {
        BidEntity pending = bid(BidStatus.IN_TRANSIT, "DON-AAAA2222");
        link(pending, recipient, ReceptionLinkStatus.PENDING);
        BidEntity declined = bid(BidStatus.IN_TRANSIT, "DON-BBBB3333");
        link(declined, stranger, ReceptionLinkStatus.DECLINED);

        assertThat(directory.byId(recipient.getFirebaseUid(), pending.getId())).isEmpty();
        assertThat(directory.byTrackingNumber(stranger.getFirebaseUid(), "DON-BBBB3333")).isEmpty();
        assertThat(directory.recentParcels(recipient.getFirebaseUid(), 5)).isEmpty();
    }

    @Test
    void uneDiscussionDePrixNEstPasUnColis() {
        BidEntity b = bid(BidStatus.NEGOTIATING, null);
        assertThat(directory.byId(sender.getFirebaseUid(), b.getId())).isEmpty();
        assertThat(directory.recentParcels(sender.getFirebaseUid(), 5)).isEmpty();
    }

    @Test
    void envoiRetireDeSaListeNEstPlusAccessible() {
        BidEntity b = bid(BidStatus.COMPLETED, "DON-7K3M9QXA");
        b.setDeletedBySender(true);
        em.persistAndFlush(b);
        assertThat(directory.byTrackingNumber(sender.getFirebaseUid(), "DON-7K3M9QXA")).isEmpty();
        assertThat(directory.recentParcels(sender.getFirebaseUid(), 5)).isEmpty();
        assertThat(directory.byTrackingNumber(traveler.getFirebaseUid(), "DON-7K3M9QXA")).isPresent();
    }

    @Test
    void leVoyageurNeVoitLeNumeroQuUneFoisLeColisEnMain() {
        BidEntity accepted = bid(BidStatus.ACCEPTED, "DON-AAAA2222");
        BidEntity handed = bid(BidStatus.HANDED_OVER, "DON-BBBB3333");
        assertThat(directory.byId(traveler.getFirebaseUid(), accepted.getId()).orElseThrow().trackingNumber()).isNull();
        assertThat(directory.byId(traveler.getFirebaseUid(), handed.getId()).orElseThrow().trackingNumber())
                .isEqualTo("DON-BBBB3333");
        assertThat(directory.byId(sender.getFirebaseUid(), accepted.getId()).orElseThrow().trackingNumber())
                .isEqualTo("DON-AAAA2222");
    }

    @Test
    void colisEnCoursDAbordPuisClosRecentsSansLesVieux() {
        BidEntity old = bid(BidStatus.COMPLETED, "DON-AAAA2222");
        age(old, AssistantParcelDirectory.RECENT_DAYS + 1);
        BidEntity recentEnded = bid(BidStatus.CANCELLED, "DON-BBBB3333");
        BidEntity active = bid(BidStatus.ACCEPTED, "DON-CCCC4444");
        age(active, 3);
        BidEntity oldActive = bid(BidStatus.IN_TRANSIT, "DON-DDDD5555");
        age(oldActive, 40);

        List<ParcelRef> parcels = directory.recentParcels(sender.getFirebaseUid(), 5);
        assertThat(parcels).extracting(ParcelRef::bidId)
                .containsExactly(active.getId(), oldActive.getId(), recentEnded.getId());
        assertThat(parcels).extracting(ParcelRef::active).containsExactly(true, true, false);
        assertThat(directory.recentParcels(sender.getFirebaseUid(), 1)).hasSize(1);
        assertThat(directory.recentParcels(sender.getFirebaseUid(), 0)).isEmpty();
    }

    @Test
    void lesColisDuVoyageurEtDuDestinataireSontProposes() {
        BidEntity b = bid(BidStatus.ARRIVED, "DON-7K3M9QXA");
        link(b, recipient, ReceptionLinkStatus.CONFIRMED);
        assertThat(directory.recentParcels(traveler.getFirebaseUid(), 5)).extracting(ParcelRef::role)
                .containsExactly(ParcelRole.TRAVELER);
        assertThat(directory.recentParcels(recipient.getFirebaseUid(), 5)).extracting(ParcelRef::role)
                .containsExactly(ParcelRole.RECIPIENT);
        assertThat(directory.recentParcels("uid-inconnu", 5)).isEmpty();
    }
}
