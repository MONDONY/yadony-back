package com.yadony.api.activation;

import com.yadony.api.alerts.CorridorAlertRepository;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.city.CityRepository;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ActivationRepositoryIT {

    @Autowired ActivationRepository repository;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired PackageRequestRepository packageRequestRepository;
    @Autowired BidRepository bidRepository;
    @Autowired CorridorAlertRepository corridorAlertRepository;
    @Autowired NegotiationThreadRepository negotiationThreadRepository;
    @Autowired CityRepository cityRepository;
    ActivationTestData data;

    @BeforeEach
    void setUp() {
        data = new ActivationTestData(userRepository, announcementRepository, packageRequestRepository,
                bidRepository, corridorAlertRepository, negotiationThreadRepository, cityRepository);
    }

    private UUID verifiedUser() {
        return data.user(KycStatus.VERIFIED, null, 0);
    }

    @Test
    void hasFirstAction_isZero_forFreshUser() {
        assertThat(repository.hasFirstAction(verifiedUser())).isZero();
    }

    @Test
    void hasFirstAction_isOne_forEachKindOfAction() {
        UUID traveler = verifiedUser();
        data.announcement(traveler, "Paris", "Dakar", "SN", LocalDate.now().plusDays(3), AnnouncementStatus.ACTIVE);
        UUID sender = verifiedUser();
        UUID request = data.packageRequest(sender, "Paris", "Abidjan", LocalDate.now().plusDays(5), PackageRequestStatus.OPEN);
        UUID alerter = verifiedUser();
        data.corridorAlert(alerter);
        UUID bidder = verifiedUser();
        data.bid(bidder, data.announcement(verifiedUser(), "Lyon", "Bamako", "ML",
                LocalDate.now().plusDays(4), AnnouncementStatus.ACTIVE));
        UUID negotiator = verifiedUser();
        data.negotiationThread(negotiator, request);

        assertThat(repository.hasFirstAction(traveler)).isEqualTo(1);
        assertThat(repository.hasFirstAction(sender)).isEqualTo(1);
        assertThat(repository.hasFirstAction(alerter)).isEqualTo(1);
        assertThat(repository.hasFirstAction(bidder)).isEqualTo(1);
        assertThat(repository.hasFirstAction(negotiator)).isEqualTo(1);
    }

    @Test
    void hasFirstAction_ignoresSoftDeletedAndDrafts() {
        UUID u = verifiedUser();
        data.announcement(u, "Paris", "Dakar", "SN", LocalDate.now().plusDays(3), AnnouncementStatus.DRAFT);
        data.softDeleteAnnouncement(
                data.announcement(u, "Paris", "Dakar", "SN", LocalDate.now().plusDays(3), AnnouncementStatus.ACTIVE));
        assertThat(repository.hasFirstAction(u)).isZero();
    }

    @Test
    void tripsTowards_matchesCountryCode_orCityCountry_withinWindow_excludingOwnTrips() {
        UUID me = verifiedUser();
        UUID other = verifiedUser();
        data.city("Yamoussoukro-test", "CI");
        UUID first = data.announcement(other, "Paris", "Abidjan", "CI", LocalDate.now().plusDays(2), AnnouncementStatus.ACTIVE);
        data.announcement(other, "Lyon", "Yamoussoukro-test", null, LocalDate.now().plusDays(10), AnnouncementStatus.ACTIVE);
        data.announcement(other, "Paris", "Abidjan", "CI", LocalDate.now().plusDays(40), AnnouncementStatus.ACTIVE);
        data.announcement(me, "Paris", "Abidjan", "CI", LocalDate.now().plusDays(2), AnnouncementStatus.ACTIVE);
        data.announcement(other, "Paris", "Dakar", "SN", LocalDate.now().plusDays(2), AnnouncementStatus.ACTIVE);
        data.announcement(other, "Paris", "Abidjan", "CI", LocalDate.now().plusDays(3), AnnouncementStatus.CANCELLED);

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(15);
        assertThat(repository.countTripsTowards("CI", from, to, me)).isEqualTo(2);
        var rows = repository.findTripsTowards("CI", from, to, me, 3);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getDepartureDate()).isBefore(rows.get(1).getDepartureDate());
        assertThat(UUID.fromString(rows.get(0).getId())).isEqualTo(first);
        assertThat(rows.get(0).getAvailableKg().doubleValue()).isEqualTo(10.0);
        assertThat(rows.get(0).getCurrency()).isNotBlank();
    }

    @Test
    void tripsTowards_respectsLimit() {
        UUID me = verifiedUser();
        UUID other = verifiedUser();
        for (int i = 1; i <= 4; i++) {
            data.announcement(other, "Paris", "Abidjan", "CI", LocalDate.now().plusDays(i), AnnouncementStatus.ACTIVE);
        }
        LocalDate from = LocalDate.now();
        assertThat(repository.findTripsTowards("CI", from, from.plusDays(15), me, 3)).hasSize(3);
        assertThat(repository.countTripsTowards("CI", from, from.plusDays(15), me)).isEqualTo(4);
    }

    @Test
    void packagesTowards_resolveCountryThroughCities_openOnly() {
        UUID me = verifiedUser();
        UUID other = verifiedUser();
        data.city("Thies-test", "SN");
        UUID open = data.packageRequest(other, "Paris", "Thies-test", LocalDate.now().plusDays(4), PackageRequestStatus.OPEN);
        data.packageRequest(other, "Paris", "Thies-test", LocalDate.now().plusDays(6), PackageRequestStatus.NEGOTIATING);
        data.packageRequest(other, "Paris", "Thies-test", LocalDate.now().plusDays(6), PackageRequestStatus.CANCELLED);
        data.packageRequest(me, "Paris", "Thies-test", LocalDate.now().plusDays(6), PackageRequestStatus.OPEN);

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(15);
        assertThat(repository.countPackagesTowards("SN", from, to, me)).isEqualTo(2);
        var rows = repository.findPackagesTowards("SN", from, to, me, 3);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getWeightKg().doubleValue()).isEqualTo(2.0);
        assertThat(UUID.fromString(rows.get(0).getId())).isEqualTo(open);
    }

    @Test
    void candidates_firstAndSecondReminders() {
        Instant now = Instant.now();
        UUID due1 = data.user(KycStatus.VERIFIED, now.minus(30, ChronoUnit.HOURS), 0);
        UUID notYet = data.user(KycStatus.VERIFIED, now.minus(10, ChronoUnit.HOURS), 0);
        UUID due2 = data.user(KycStatus.VERIFIED, now.minus(80, ChronoUnit.HOURS), 1);
        UUID secondTooEarly = data.user(KycStatus.VERIFIED, now.minus(50, ChronoUnit.HOURS), 1);
        UUID done = data.user(KycStatus.VERIFIED, now.minus(100, ChronoUnit.HOURS), 2);
        UUID notVerified = data.user(KycStatus.PENDING, now.minus(100, ChronoUnit.HOURS), 0);
        UUID noDate = data.user(KycStatus.VERIFIED, null, 0);

        var ids = repository.findFirstActionReminderCandidates(now.minus(24, ChronoUnit.HOURS),
                now.minus(72, ChronoUnit.HOURS));

        assertThat(ids).contains(due1, due2).doesNotContain(notYet, secondTooEarly, done, notVerified, noDate);
    }

    @Test
    void candidates_excludeUserWithFirstAction() {
        Instant now = Instant.now();
        UUID acted = data.user(KycStatus.VERIFIED, now.minus(30, ChronoUnit.HOURS), 0);
        data.corridorAlert(acted);
        var ids = repository.findFirstActionReminderCandidates(now.minus(24, ChronoUnit.HOURS),
                now.minus(72, ChronoUnit.HOURS));
        assertThat(ids).doesNotContain(acted);
    }
}
