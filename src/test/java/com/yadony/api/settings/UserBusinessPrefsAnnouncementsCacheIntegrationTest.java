package com.yadony.api.settings;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementService;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class UserBusinessPrefsAnnouncementsCacheIntegrationTest {

    @Autowired private AnnouncementService announcementService;
    @Autowired private UserBusinessPrefsService userBusinessPrefsService;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private UserBusinessPrefsRepository userBusinessPrefsRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void cleanDb() {
        if (cacheManager.getCache("announcements-search") != null) {
            cacheManager.getCache("announcements-search").clear();
        }
        userBusinessPrefsRepository.deleteAll();
        announcementRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void upsert_evictsAnnouncementsSearchCacheWhenViewerCurrencyChanges() {
        UserEntity viewer = persistUser("viewer-cache-" + UUID.randomUUID(), "Viewer");
        UserEntity cadTraveler = persistUser("traveler-cad-" + UUID.randomUUID(), "Cad");
        UserEntity eurTraveler = persistUser("traveler-eur-" + UUID.randomUUID(), "Eur");

        userBusinessPrefsService.upsert(viewer.getFirebaseUid(), prefs("CAD"));

        AnnouncementEntity cadAnnouncement = persistAnnouncement(cadTraveler.getId(), "CAD", "Montreal");
        AnnouncementEntity eurAnnouncement = persistAnnouncement(eurTraveler.getId(), "EUR", "Paris");

        Page<AnnouncementSearchResponse> firstSearch = search(viewer.getFirebaseUid());
        assertThat(firstSearch.getContent()).extracting(AnnouncementSearchResponse::id)
                .containsExactly(cadAnnouncement.getId());

        announcementRepository.deleteById(cadAnnouncement.getId());

        Page<AnnouncementSearchResponse> cachedSearch = search(viewer.getFirebaseUid());
        assertThat(cachedSearch.getContent()).extracting(AnnouncementSearchResponse::id)
                .containsExactly(cadAnnouncement.getId());

        userBusinessPrefsService.upsert(viewer.getFirebaseUid(), prefs("EUR"));

        Page<AnnouncementSearchResponse> afterEviction = search(viewer.getFirebaseUid());
        assertThat(afterEviction.getContent()).extracting(AnnouncementSearchResponse::id)
                .containsExactly(eurAnnouncement.getId());
    }

    private Page<AnnouncementSearchResponse> search(String viewerFirebaseUid) {
        return announcementService.searchAnnouncements(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                "date", "asc", PageRequest.of(0, 10), viewerFirebaseUid, null);
    }

    private UserBusinessPrefsDto prefs(String currencyCode) {
        return new UserBusinessPrefsDto("kg", currencyCode, 10, 23, 0, null, null, null);
    }

    private UserEntity persistUser(String firebaseUid, String firstName) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        user.setFirstName(firstName);
        user.setLastName("Tester");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        roles.add(Role.SENDER);
        user.setRoles(roles);
        return userRepository.save(user);
    }

    private AnnouncementEntity persistAnnouncement(UUID travelerId, String currency, String departureCity) {
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(travelerId);
        announcement.setDepartureCity(departureCity);
        announcement.setArrivalCity("Dakar");
        announcement.setDepartureDate(LocalDate.now().plusDays(10));
        announcement.setDepartureTime(LocalTime.of(10, 0));
        announcement.setArrivalTime(LocalTime.of(18, 0));
        announcement.setTransportMode(TransportMode.PLANE);
        announcement.setPickupAddressLabel(departureCity + " pickup");
        announcement.setPickupLat(new BigDecimal("48.860000"));
        announcement.setPickupLng(new BigDecimal("2.350000"));
        announcement.setDeliveryAddressLabel("Dakar Centre");
        announcement.setDeliveryLat(new BigDecimal("14.693000"));
        announcement.setDeliveryLng(new BigDecimal("-17.447000"));
        announcement.setAvailableKg(new BigDecimal("10.00"));
        announcement.setTotalKg(new BigDecimal("10.00"));
        announcement.setPricePerKg(new BigDecimal("5.00"));
        announcement.setCurrency(currency);
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(announcement);
    }
}
