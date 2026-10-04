package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.dto.BidResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie que les caches {@code bids-me} / {@code traveler-bids-me} (cf.
 * CacheConfig) évitent réellement un second aller-retour DB — pas seulement
 * que l'annotation {@code @Cacheable} est présente syntaxiquement.
 *
 * <p>Contexte : ces deux endpoints ("/bids/me", "/travelers/me/bids") sont
 * tirés en rafale par l'app à chaque changement d'onglet et se sont révélés
 * être la première cause de saturation du rate-limit nginx en usage réel
 * (voir investigation VPS staging associée). Une TTL courte de 8 s absorbe
 * ces rafales sans risque de staleness perceptible.
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonalEndpointsCachingIntegrationTest {

    @Autowired private BidService bidService;
    @Autowired private BidRepository bidRepository;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void cleanDb() {
        bidRepository.deleteAll();
        announcementRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void getMyBids_secondCallWithinTtl_returnsCachedResultDespiteDbDeletion() {
        UserEntity sender = persistUser("uid-cache-sender-" + UUID.randomUUID());
        UserEntity traveler = persistUser("uid-cache-traveler-" + UUID.randomUUID());
        AnnouncementEntity announcement = persistAnnouncement(traveler.getId());
        persistBid(announcement.getId(), sender.getId());

        List<BidResponse> firstCall = bidService.getMyBids(sender.getFirebaseUid());
        assertThat(firstCall).hasSize(1);

        // Supprime en base DIRECTEMENT, en contournant le service : si le
        // cache fonctionne, le 2e appel doit renvoyer le même résultat que le
        // 1er malgré la suppression — sinon ce test ne prouverait que le
        // comportement de la DB, pas celui du cache.
        bidRepository.deleteAll();

        List<BidResponse> secondCall = bidService.getMyBids(sender.getFirebaseUid());
        assertThat(secondCall).hasSize(1);
    }

    @Test
    void getMyBids_differentUsers_haveIndependentCacheEntries() {
        UserEntity senderA = persistUser("uid-cache-a-" + UUID.randomUUID());
        UserEntity senderB = persistUser("uid-cache-b-" + UUID.randomUUID());
        UserEntity traveler = persistUser("uid-cache-traveler-b-" + UUID.randomUUID());
        AnnouncementEntity announcement = persistAnnouncement(traveler.getId());
        persistBid(announcement.getId(), senderA.getId());

        List<BidResponse> forSenderA = bidService.getMyBids(senderA.getFirebaseUid());
        List<BidResponse> forSenderB = bidService.getMyBids(senderB.getFirebaseUid());

        // La clé de cache est #firebaseUid : deux utilisateurs différents ne
        // doivent jamais voir les bids l'un de l'autre.
        assertThat(forSenderA).hasSize(1);
        assertThat(forSenderB).isEmpty();
    }

    @Test
    void getTravelerBids_secondCallWithinTtl_returnsCachedResultDespiteDbDeletion() {
        UserEntity sender = persistUser("uid-cache-sender2-" + UUID.randomUUID());
        UserEntity traveler = persistUser("uid-cache-traveler2-" + UUID.randomUUID());
        AnnouncementEntity announcement = persistAnnouncement(traveler.getId());
        persistBid(announcement.getId(), sender.getId());

        Page<BidResponse> firstCall =
                bidService.getTravelerBids(traveler.getFirebaseUid(), null, null, null, 0, 20);
        assertThat(firstCall.getContent()).hasSize(1);

        bidRepository.deleteAll();

        Page<BidResponse> secondCall =
                bidService.getTravelerBids(traveler.getFirebaseUid(), null, null, null, 0, 20);
        assertThat(secondCall.getContent()).hasSize(1);
    }

    @Test
    void rejectBid_evictsTravelerBidsCache_soTheReloadedListIsNotStale() {
        UserEntity sender = persistUser("uid-cache-sender3-" + UUID.randomUUID());
        UserEntity traveler = persistUser("uid-cache-traveler3-" + UUID.randomUUID());
        AnnouncementEntity announcement = persistAnnouncement(traveler.getId());
        BidEntity bid = persistBid(announcement.getId(), sender.getId());
        bid.setStatus(BidStatus.PAYMENT_ESCROWED);
        bidRepository.save(bid);

        Page<BidResponse> before =
                bidService.getTravelerBids(traveler.getFirebaseUid(), null, null, null, 0, 20);
        assertThat(before.getContent()).extracting(BidResponse::status).containsExactly("PAYMENT_ESCROWED");

        bidService.rejectBid(bid.getId(), traveler.getFirebaseUid(), null);

        // FLUTTER-B9 : dans les 8 s du premier chargement, l'app rechargeait la liste et
        // recevait encore la demande « en attente », bouton Accepter compris.
        Page<BidResponse> after =
                bidService.getTravelerBids(traveler.getFirebaseUid(), null, null, null, 0, 20);
        assertThat(after.getContent()).extracting(BidResponse::status).doesNotContain("PAYMENT_ESCROWED");
    }

    private UserEntity persistUser(String firebaseUid) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(firebaseUid);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        roles.add(Role.SENDER);
        u.setRoles(roles);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(7));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    private BidEntity persistBid(UUID announcementId, UUID senderId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(senderId);
        bid.setWeightKg(new BigDecimal("5.00"));
        bid.setStatus(BidStatus.PENDING);
        return bidRepository.save(bid);
    }
}
