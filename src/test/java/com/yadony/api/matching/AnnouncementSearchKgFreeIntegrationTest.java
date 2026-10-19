package com.yadony.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * YADONY-FLUTTER-PROD-Z : {@code GET /announcements?minAvailableKg=23} garde les trajets en
 * capacité libre (availableKg technique à 1) et exclut un trajet borné à 10 kg.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("GET /announcements?minAvailableKg — capacité libre incluse (PROD-Z)")
class AnnouncementSearchKgFreeIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired CacheManager cacheManager;

    private UUID kgFree;
    private UUID exact10;
    private UUID suitcase23;

    @BeforeEach
    void seed() {
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        var cache = cacheManager.getCache("announcements-search");
        if (cache != null) {
            cache.clear();
        }
        UserEntity traveler = new UserEntity();
        traveler.setFirebaseUid("kgfree-traveler");
        traveler.setStatus(UserStatus.ACTIVE);
        traveler.setKycStatus(KycStatus.VERIFIED);
        traveler.setRoles(new HashSet<>(List.of(Role.TRAVELER)));
        traveler = userRepository.save(traveler);
        kgFree = trip(traveler, CapacityUnit.KG_FREE, "1", 3);
        exact10 = trip(traveler, CapacityUnit.KG_EXACT, "10", 4);
        suitcase23 = trip(traveler, CapacityUnit.SUITCASE_23KG, "23", 5);
    }

    @Test
    @DisplayName("minAvailableKg=23 : kilo libre et valise 23 kg inclus, 10 kg exact exclu ; total cohérent")
    void minAvailableKg23_includesKgFree_excludesExact10() throws Exception {
        JsonNode page = search("23");
        assertThat(ids(page)).containsExactlyInAnyOrder(kgFree, suitcase23);
        assertThat(ids(page)).doesNotContain(exact10);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("sans minAvailableKg : les trois trajets")
    void withoutMin_allTrips() throws Exception {
        assertThat(ids(search(null))).containsExactlyInAnyOrder(kgFree, exact10, suitcase23);
    }

    @Test
    @DisplayName("deux minimums différents ne partagent pas la même entrée de cache")
    void differentMinimums_areNotServedFromSameCacheEntry() throws Exception {
        assertThat(ids(search("5"))).containsExactlyInAnyOrder(kgFree, exact10, suitcase23);
        assertThat(ids(search("23"))).containsExactlyInAnyOrder(kgFree, suitcase23);
    }

    private JsonNode search(String minKg) throws Exception {
        var request = get("/announcements").param("size", "50").with(as("kgfree-reader"));
        if (minKg != null) {
            request = request.param("minAvailableKg", minKg);
        }
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("content").forEach(n -> ids.add(UUID.fromString(n.get("id").asText())));
        return ids;
    }

    private static RequestPostProcessor as(String uid) {
        return authentication(new UsernamePasswordAuthenticationToken(uid, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"))));
    }

    private UUID trip(UserEntity traveler, CapacityUnit unit, String availableKg, int daysAhead) {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(traveler.getId());
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDepartureDate(LocalDate.now().plusDays(daysAhead));
        e.setCapacityUnit(unit);
        e.setAvailableKg(new BigDecimal(availableKg));
        e.setTotalKg(new BigDecimal(availableKg));
        e.setPricePerKg(new BigDecimal("12"));
        e.setCurrency("EUR");
        e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
        e.setStatus(AnnouncementStatus.ACTIVE);
        e.setTransportMode(TransportMode.PLANE);
        e.setPickupAddressLabel("Test pickup");
        e.setPickupLat(BigDecimal.valueOf(48.8566));
        e.setPickupLng(BigDecimal.valueOf(2.3522));
        e.setDeliveryAddressLabel("Test delivery");
        e.setDeliveryLat(BigDecimal.valueOf(14.6928));
        e.setDeliveryLng(BigDecimal.valueOf(-17.4467));
        return announcementRepository.save(e).getId();
    }
}
