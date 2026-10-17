package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.gppro.events.GpProStatusChangedEvent;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.payments.currency.ExchangeRateEntity;
import com.yadony.api.payments.currency.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Filtre, départage et dénormalisation GP Pro de la recherche d'annonces, sur base réelle. */
@SpringBootTest
@ActiveProfiles("test")
class AnnouncementGpProIntegrationTest {

    @Autowired private AnnouncementService announcementService;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private ExchangeRateRepository exchangeRateRepository;
    @Autowired private ApplicationEventPublisher publisher;

    @BeforeEach
    void cleanDb() {
        if (cacheManager.getCache("announcements-search") != null) {
            cacheManager.getCache("announcements-search").clear();
        }
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        exchangeRateRepository.deleteAll();
        exchangeRateRepository.save(new ExchangeRateEntity("EUR", BigDecimal.ONE));
    }

    private Page<AnnouncementSearchResponse> search(String sortBy, String sortDir, Boolean gpProOnly) {
        return announcementService.searchAnnouncements(
                "Abidjan", "Paris", null, null, null, null, null, null, gpProOnly, null,
                null, null, null, null, null, null,
                sortBy, sortDir, PageRequest.of(0, 20), null, null);
    }

    private List<UUID> ids(Page<AnnouncementSearchResponse> page) {
        return page.getContent().stream().map(AnnouncementSearchResponse::id).toList();
    }

    @Test
    void sortByDateKeepsDateOrderAndBreaksTiesWithGpPro() {
        UserEntity plain = persistUser(false);
        UserEntity gp = persistUser(true);
        AnnouncementEntity a = persistAnnouncement(plain, 10, "5.00", false);
        AnnouncementEntity b = persistAnnouncement(gp, 10, "5.00", true);
        AnnouncementEntity c = persistAnnouncement(gp, 12, "5.00", true);

        assertThat(ids(search("date", "asc", null))).containsExactly(b.getId(), a.getId(), c.getId());
    }

    @Test
    void sortByPriceAscendingStaysAscendingAcrossGpPro() {
        UserEntity plain = persistUser(false);
        UserEntity gp = persistUser(true);
        AnnouncementEntity cheap = persistAnnouncement(plain, 10, "3.00", false);
        AnnouncementEntity mid = persistAnnouncement(gp, 10, "5.00", true);
        AnnouncementEntity midPlain = persistAnnouncement(plain, 10, "5.00", false);
        AnnouncementEntity dear = persistAnnouncement(gp, 10, "9.00", true);

        assertThat(ids(search("price", "asc", null)))
                .containsExactly(cheap.getId(), mid.getId(), midPlain.getId(), dear.getId());
    }

    @Test
    void paidProBlockStillFirst() {
        UserEntity plain = persistUser(false);
        UserEntity gp = persistUser(true);
        AnnouncementEntity paidPro = persistAnnouncement(plain, 20, "5.00", false);
        paidPro.setTravelerIsPro(true);
        announcementRepository.save(paidPro);
        AnnouncementEntity gpSoon = persistAnnouncement(gp, 10, "5.00", true);

        assertThat(ids(search("date", "asc", null))).containsExactly(paidPro.getId(), gpSoon.getId());
    }

    @Test
    void gpProOnlyKeepsOnlyGpProTravelers() {
        UserEntity plain = persistUser(false);
        UserEntity gp = persistUser(true);
        persistAnnouncement(plain, 10, "5.00", false);
        AnnouncementEntity b = persistAnnouncement(gp, 11, "5.00", true);

        assertThat(ids(search("date", "asc", true))).containsExactly(b.getId());
    }

    @Test
    void gpProStatusChangeUpdatesActiveAnnouncementsAndEvictsCache() {
        UserEntity traveler = persistUser(false);
        AnnouncementEntity active = persistAnnouncement(traveler, 10, "5.00", false);
        AnnouncementEntity cancelled = persistAnnouncement(traveler, 11, "5.00", false);
        cancelled.setStatus(AnnouncementStatus.CANCELLED);
        announcementRepository.save(cancelled);

        // Remplit le cache avec l'état « pas GP Pro ».
        assertThat(search("date", "asc", true).getTotalElements()).isZero();

        traveler.setGpPro(true);
        userRepository.save(traveler);
        publisher.publishEvent(new GpProStatusChangedEvent(traveler.getId(), true));
        // Idempotent : un second événement identique ne change rien.
        publisher.publishEvent(new GpProStatusChangedEvent(traveler.getId(), true));

        assertThat(announcementRepository.findById(active.getId()).orElseThrow().isTravelerIsGpPro()).isTrue();
        assertThat(announcementRepository.findById(cancelled.getId()).orElseThrow().isTravelerIsGpPro()).isFalse();
        // Cache vidé : la recherche reflète le nouvel état.
        assertThat(ids(search("date", "asc", true))).containsExactly(active.getId());

        publisher.publishEvent(new GpProStatusChangedEvent(traveler.getId(), false));
        assertThat(announcementRepository.findById(active.getId()).orElseThrow().isTravelerIsGpPro()).isFalse();
    }

    private UserEntity persistUser(boolean gpPro) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setFirstName("Test");
        user.setLastName("GpPro");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        roles.add(Role.SENDER);
        user.setRoles(roles);
        user.setGpPro(gpPro);
        return userRepository.save(user);
    }

    private AnnouncementEntity persistAnnouncement(UserEntity traveler, int daysAhead, String price, boolean gpPro) {
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(traveler.getId());
        announcement.setDepartureCity("Abidjan");
        announcement.setArrivalCity("Paris");
        announcement.setDepartureDate(LocalDate.now().plusDays(daysAhead));
        announcement.setDepartureTime(LocalTime.of(10, 0));
        announcement.setArrivalTime(LocalTime.of(18, 0));
        announcement.setTransportMode(TransportMode.PLANE);
        announcement.setPickupAddressLabel("Abidjan pickup");
        announcement.setPickupLat(new BigDecimal("5.345000"));
        announcement.setPickupLng(new BigDecimal("-4.024000"));
        announcement.setDeliveryAddressLabel("Paris Centre");
        announcement.setDeliveryLat(new BigDecimal("48.860000"));
        announcement.setDeliveryLng(new BigDecimal("2.350000"));
        announcement.setAvailableKg(new BigDecimal("10.00"));
        announcement.setTotalKg(new BigDecimal("10.00"));
        announcement.setPricePerKg(new BigDecimal(price));
        announcement.setPricePerKgEur(new BigDecimal(price));
        announcement.setCurrency("EUR");
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setTravelerIsGpPro(gpPro);
        return announcementRepository.save(announcement);
    }
}
