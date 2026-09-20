package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.payments.currency.ExchangeRateEntity;
import com.yadony.api.payments.currency.ExchangeRateRepository;
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

/**
 * La feuille de filtre de l'app compte les résultats avec {@code page=0&size=1}
 * (elle ne lit que {@code totalElements}) juste avant que la liste ne charge
 * {@code page=0&size=20} avec les mêmes filtres. Les deux appels doivent
 * occuper des entrées de cache distinctes : la clé de {@code announcements-search}
 * portait le numéro de page sans la taille, et la liste relisait la page à un
 * seul élément mise en cache par le compteur (« Rechercher (4) », 1 résultat
 * affiché).
 */
@SpringBootTest
@ActiveProfiles("test")
class AnnouncementSearchCachePageSizeIntegrationTest {

    @Autowired private AnnouncementService announcementService;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private ExchangeRateRepository exchangeRateRepository;

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

    @Test
    void searchWithLargerPageSizeIsNotServedFromTheCountPageCachedWithSizeOne() {
        UserEntity viewer = persistUser("viewer-" + UUID.randomUUID());
        UserEntity traveler = persistUser("traveler-" + UUID.randomUUID());
        persistAnnouncement(traveler.getId(), 10);
        persistAnnouncement(traveler.getId(), 11);
        persistAnnouncement(traveler.getId(), 12);

        Page<AnnouncementSearchResponse> count = search(viewer.getFirebaseUid(), 1);
        assertThat(count.getContent()).hasSize(1);
        assertThat(count.getTotalElements()).isEqualTo(3);

        Page<AnnouncementSearchResponse> list = search(viewer.getFirebaseUid(), 20);
        assertThat(list.getTotalElements()).isEqualTo(3);
        assertThat(list.getContent()).hasSize(3);
    }

    private Page<AnnouncementSearchResponse> search(String viewerFirebaseUid, int size) {
        return announcementService.searchAnnouncements(
                "Abidjan", "Paris", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                "date", "asc", PageRequest.of(0, size), viewerFirebaseUid, null);
    }

    private UserEntity persistUser(String firebaseUid) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        user.setFirstName("Test");
        user.setLastName("Cache");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        roles.add(Role.SENDER);
        user.setRoles(roles);
        return userRepository.save(user);
    }

    private void persistAnnouncement(UUID travelerId, int daysAhead) {
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(travelerId);
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
        announcement.setPricePerKg(new BigDecimal("5.00"));
        announcement.setCurrency("EUR");
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcementRepository.save(announcement);
    }
}
