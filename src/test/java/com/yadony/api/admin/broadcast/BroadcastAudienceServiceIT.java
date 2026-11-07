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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    @Test
    void userTargetExcludesBannedAndPendingDeletion_butKeepsSuspended() {
        UUID banned = persistUser(UserStatus.BANNED);
        UUID pending = persistUser(UserStatus.PENDING_DELETION);
        UUID suspended = persistUser(UserStatus.SUSPENDED);

        assertThat(service.count(new BroadcastTarget(BroadcastTargetType.USER, null, null, banned))).isZero();
        assertThat(service.count(new BroadcastTarget(BroadcastTargetType.USER, null, null, pending))).isZero();
        // Un compte suspendu doit pouvoir etre prevenu de sa suspension.
        assertThat(service.count(new BroadcastTarget(BroadcastTargetType.USER, null, null, suspended))).isEqualTo(1);
    }

    @Test
    void targetUser_reportsNameAndReachability() {
        UUID active = persistUser(UserStatus.ACTIVE);
        UserEntity named = userRepository.findById(active).orElseThrow();
        named.setFirstName("Awa");
        named.setLastName("Diop");
        userRepository.saveAndFlush(named);
        UUID banned = persistUser(UserStatus.BANNED);
        UUID pending = persistUser(UserStatus.PENDING_DELETION);
        UUID deleted = persistUser(UserStatus.ACTIVE);
        UserEntity toDelete = userRepository.findById(deleted).orElseThrow();
        toDelete.setDeletedAt(java.time.LocalDateTime.now());
        userRepository.saveAndFlush(toDelete);

        var activeView = service.targetUser(new BroadcastTarget(BroadcastTargetType.USER, null, null, active));
        assertThat(activeView).isPresent();
        assertThat(activeView.get().reachable()).isTrue();
        assertThat(activeView.get().displayName()).contains("Awa");

        assertThat(service.targetUser(new BroadcastTarget(BroadcastTargetType.USER, null, null, banned))
                .orElseThrow().reachable()).isFalse();
        assertThat(service.targetUser(new BroadcastTarget(BroadcastTargetType.USER, null, null, pending))
                .orElseThrow().reachable()).isFalse();
        assertThat(service.targetUser(new BroadcastTarget(BroadcastTargetType.USER, null, null, deleted))
                .orElseThrow().reachable()).isFalse();
    }

    @Test
    void targetUser_unknownUser_is404() {
        UUID ghost = UUID.randomUUID();
        assertThatThrownBy(() -> service.targetUser(new BroadcastTarget(BroadcastTargetType.USER, null, null, ghost)))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("user-not-found");
    }

    @Test
    void targetUser_isEmptyForSegmentTargets() {
        assertThat(service.targetUser(new BroadcastTarget(BroadcastTargetType.ALL, null, null, null))).isEmpty();
    }

    // ── Cible RULE et règles équivalentes aux anciennes cibles ──────────────

    private static com.yadony.api.admin.broadcast.rule.AudienceRule.Group parseRule(String json) {
        try {
            return com.yadony.api.admin.broadcast.rule.AudienceRule.parse(
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private java.util.List<UUID> allPages(BroadcastTarget target) {
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        org.springframework.data.domain.Page<UUID> page;
        int n = 0;
        do {
            page = service.page(target, n++);
            ids.addAll(page.getContent());
        } while (page.hasNext());
        return ids;
    }

    /** Les anciennes cibles et leur règle équivalente renvoient exactement les mêmes comptes. */
    private void assertSameAudience(BroadcastTarget legacy) {
        BroadcastTarget viaRule = new BroadcastTarget(BroadcastTargetType.RULE, null, null, null,
                BroadcastAudienceService.equivalentRule(legacy));
        assertThat(allPages(viaRule)).containsExactlyElementsOf(allPages(legacy));
        assertThat(service.count(viaRule)).isEqualTo(service.count(legacy));
    }

    private void seedBehaviours() {
        UUID traveler = persistUser(UserStatus.ACTIVE);
        UUID sender = persistUser(UserStatus.ACTIVE);
        UUID other = persistUser(UserStatus.ACTIVE);
        persistUser(UserStatus.ACTIVE);
        persistUser(UserStatus.SUSPENDED);
        persistBid(persistAnnouncement(traveler, "Paris", "Dakar"), sender);
        persistAnnouncement(traveler, "Paris", "Dakar");
        persistAnnouncement(other, "Lyon", "Abidjan");
    }

    @Test
    void equivalentRule_SENDERS_memesIdsQueLAncienneRequete() {
        seedBehaviours();
        assertSameAudience(new BroadcastTarget(BroadcastTargetType.SENDERS, null, null, null));
    }

    @Test
    void equivalentRule_TRAVELERS_memesIdsQueLAncienneRequete() {
        seedBehaviours();
        assertSameAudience(new BroadcastTarget(BroadcastTargetType.TRAVELERS, null, null, null));
    }

    @Test
    void equivalentRule_CORRIDOR_memesIdsQueLAncienneRequete() {
        seedBehaviours();
        assertSameAudience(new BroadcastTarget(BroadcastTargetType.CORRIDOR, "paris", "DAKAR", null));
    }

    @Test
    void equivalentRule_ALL_memesIdsQueLAncienneRequete() {
        seedBehaviours();
        assertSameAudience(new BroadcastTarget(BroadcastTargetType.ALL, null, null, null));
    }

    @Test
    void equivalentRule_USER_pasDeRegle() {
        assertThatThrownBy(() -> BroadcastAudienceService.equivalentRule(
                new BroadcastTarget(BroadcastTargetType.USER, null, null, UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rule_exclutCompteSuspendu() {
        UUID active = persistUser(UserStatus.ACTIVE);
        UUID suspended = persistUser(UserStatus.SUSPENDED);
        BroadcastTarget rule = new BroadcastTarget(BroadcastTargetType.RULE, null, null, null,
                parseRule("{\"op\":\"AND\",\"children\":[]}"));

        assertThat(allPages(rule)).contains(active).doesNotContain(suspended);
    }

    @Test
    void countPushReachable_toutesCibles_etRecipients() {
        UUID traveler = persistUser(UserStatus.ACTIVE);
        UUID target = persistUser(UserStatus.SUSPENDED);
        persistAnnouncement(traveler, "Paris", "Dakar");
        jdbc.update("INSERT INTO user_devices (user_id, device_id, device_name, platform, fcm_token)"
                + " VALUES (?, 'd1', 'n', 'ios', 'tok')", traveler);
        jdbc.update("INSERT INTO user_devices (user_id, device_id, device_name, platform, fcm_token)"
                + " VALUES (?, 'd2', 'n', 'ios', 'tok')", target);

        assertThat(service.countPushReachable(
                new BroadcastTarget(BroadcastTargetType.TRAVELERS, null, null, null))).isEqualTo(1);
        assertThat(service.countPushReachable(
                new BroadcastTarget(BroadcastTargetType.USER, null, null, target))).isEqualTo(1);
        var rows = service.recipients(new BroadcastTarget(BroadcastTargetType.USER, null, null, target), 0, 10);
        assertThat(rows.getContent()).extracting(r -> r.userId()).containsExactly(target);
    }
}
