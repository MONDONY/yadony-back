package com.yadony.api.admin.broadcast;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.TransportMode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lot D — le ciblage doit etre COMPORTEMENTAL. Le test le prouve en creant un compte
 * qui n'a ni bid ni annonce : il doit sortir de SENDERS et de TRAVELERS, alors qu'un
 * ciblage par role l'aurait ramene dans les deux (V193 donne SENDER+TRAVELER a tous).
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
class BroadcastAudienceServiceIT {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired BroadcastAudienceService service;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;

    private UUID persistUser(UserStatus status) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("broadcast-audience-" + UUID.randomUUID());
        user.setStatus(status);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER, Role.TRAVELER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }

    private UUID persistAnnouncement(UUID travelerId, String departureCity, String arrivalCity) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity(departureCity);
        a.setArrivalCity(arrivalCity);
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("12 rue de Rivoli");
        a.setPickupLat(new BigDecimal("48.8566"));
        a.setPickupLng(new BigDecimal("2.3522"));
        a.setDeliveryAddressLabel("Avenue Bourguiba");
        a.setDeliveryLat(new BigDecimal("14.6928"));
        a.setDeliveryLng(new BigDecimal("-17.4467"));
        a.setAvailableKg(new BigDecimal("20"));
        a.setTotalKg(new BigDecimal("20"));
        a.setPricePerKg(new BigDecimal("12"));
        return announcementRepository.saveAndFlush(a).getId();
    }

    private void persistBid(UUID announcementId, UUID senderId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(senderId);
        bid.setWeightKg(new BigDecimal("5"));
        bidRepository.saveAndFlush(bid);
    }

    @Test
    void sendersAndTravelersAreBehavioural_notRoleBased() {
        UUID traveler = persistUser(UserStatus.ACTIVE);
        UUID sender = persistUser(UserStatus.ACTIVE);
        UUID idle = persistUser(UserStatus.ACTIVE);
        UUID announcement = persistAnnouncement(traveler, "Paris", "Dakar");
        persistBid(announcement, sender);

        var travelers = service.page(
                new BroadcastTarget(BroadcastTargetType.TRAVELERS, null, null, null), 0);
        var senders = service.page(
                new BroadcastTarget(BroadcastTargetType.SENDERS, null, null, null), 0);

        assertThat(travelers.getContent()).contains(traveler).doesNotContain(sender, idle);
        assertThat(senders.getContent()).contains(sender).doesNotContain(traveler, idle);
    }

    @Test
    void allTargetsOnlyActiveAccounts() {
        UUID active = persistUser(UserStatus.ACTIVE);
        UUID banned = persistUser(UserStatus.BANNED);

        var all = service.page(new BroadcastTarget(BroadcastTargetType.ALL, null, null, null), 0);

        assertThat(all.getContent()).contains(active).doesNotContain(banned);
    }

    @Test
    void corridorMatchesTravelerAndSenderOnBothCities_caseInsensitively() {
        UUID traveler = persistUser(UserStatus.ACTIVE);
        UUID sender = persistUser(UserStatus.ACTIVE);
        UUID otherCorridor = persistUser(UserStatus.ACTIVE);
        persistBid(persistAnnouncement(traveler, "Paris", "Dakar"), sender);
        persistAnnouncement(otherCorridor, "Lyon", "Abidjan");

        var page = service.page(
                new BroadcastTarget(BroadcastTargetType.CORRIDOR, "paris", "DAKAR", null), 0);

        assertThat(page.getContent()).contains(traveler, sender).doesNotContain(otherCorridor);
    }

    @Test
    void userTargetReturnsExactlyThatAccount() {
        UUID target = persistUser(UserStatus.ACTIVE);
        persistUser(UserStatus.ACTIVE);

        var page = service.page(
                new BroadcastTarget(BroadcastTargetType.USER, null, null, target), 0);

        assertThat(page.getContent()).containsExactly(target);
    }

    @Test
    void countMatchesTotalElements() {
        persistUser(UserStatus.ACTIVE);
        BroadcastTarget target = new BroadcastTarget(BroadcastTargetType.ALL, null, null, null);

        assertThat(service.count(target))
                .isEqualTo(service.page(target, 0).getTotalElements());
    }

    @Test
    void corridorWithoutBothCitiesIsRejected() {
        assertThatThrownBy(() ->
                new BroadcastTarget(BroadcastTargetType.CORRIDOR, "Paris", "  ", null))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("ville de depart");
    }

    @Test
    void userTargetWithoutUserIdIsRejected() {
        assertThatThrownBy(() ->
                new BroadcastTarget(BroadcastTargetType.USER, null, null, null))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("identifiant d'utilisateur");
    }
}
