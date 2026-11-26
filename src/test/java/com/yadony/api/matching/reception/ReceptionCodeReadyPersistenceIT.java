package com.yadony.api.matching.reception;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.events.RecipientLinkConfirmedEvent;
import com.yadony.api.notifications.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * « Code de retrait prêt » appelé par le proxy Spring (transaction REQUIRES_NEW réelle) : la notification doit
 * être enregistrée pour le destinataire. Avec {@code readOnly = true}, le INSERT de {@code NotificationService.persist}
 * (qui rejoint la transaction) n'était jamais flushé, sans exception : le push partait avec un id inexistant.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReceptionCodeReadyPersistenceIT {

    @Autowired ReceptionNotificationListener listener;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired BidRecipientLinkRepository linkRepository;
    @Autowired NotificationRepository notificationRepository;

    @Test
    void lienConfirmeApresRemise_laNotificationCodePretEstEnregistree() {
        UserEntity sender = user("Awa", Role.SENDER);
        UserEntity traveler = user("Moussa", Role.TRAVELER);
        UserEntity recipient = user("Fatou", Role.SENDER);
        AnnouncementEntity announcement = announcement(traveler);
        BidEntity bid = bid(announcement, sender);
        BidRecipientLinkEntity link = new BidRecipientLinkEntity(bid.getId(), recipient.getId());
        link.respond(ReceptionLinkStatus.CONFIRMED, OffsetDateTime.now());
        linkRepository.save(link);

        listener.onRecipientLinkConfirmed(new RecipientLinkConfirmedEvent(bid.getId()));

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notificationRepository.countByUserIdAndReadAtIsNull(recipient.getId())).isEqualTo(1));
    }

    private UserEntity user(String firstName, Role role) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-code-ready-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity announcement(UserEntity traveler) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(java.time.LocalDate.now().plusDays(1));
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

    private BidEntity bid(AnnouncementEntity announcement, UserEntity sender) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("3.00"));
        bid.setStatus(BidStatus.HANDED_OVER);
        bid.setRecipientName("Fatou Diop");
        bid.setRecipientPhone("+221 77 123 45 67");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        bid.setTrackingNumber("DON-" + suffix);
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setConfirmationCode("123456");
        return bidRepository.save(bid);
    }
}
