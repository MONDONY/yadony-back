package com.yadony.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserBlockEntity;
import com.yadony.api.auth.UserBlockJpaRepository;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /announcements} en deux blocs, sur base réelle : contrat JSON du bloc 2
 * ({@code similarAnnouncements[].unmet[]}), score jamais exposé, ancien contrat préservé.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("GET /announcements — recherche en deux blocs classés")
class AnnouncementSearchRankingIT {

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserBlockJpaRepository userBlockRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired CacheManager cacheManager;

    private UserEntity viewer;
    private UserEntity traveler;

    @BeforeEach
    void seed() {
        userBlockRepository.deleteAll();
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        var cache = cacheManager.getCache("announcements-search");
        if (cache != null) {
            cache.clear();
        }
        viewer = user("rank-viewer");
        traveler = user("rank-traveler");
        traveler.setCancellationCount(1);
        traveler.setNoShowCount(1);
        traveler.setTotalDeliveries(3);
        traveler.setAverageRating(new BigDecimal("4.5"));
        traveler.setRatingCount(2);
        traveler = userRepository.save(traveler);
    }

    private UserEntity user(String uid) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        u.setRoles(new HashSet<>(List.of(Role.TRAVELER, Role.SENDER)));
        return userRepository.save(u);
    }

    private AnnouncementEntity trip(UserEntity owner, LocalDate date) {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(owner.getId());
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDepartureDate(date);
        e.setAvailableKg(new BigDecimal("8"));
        e.setTotalKg(new BigDecimal("8"));
        e.setPricePerKg(new BigDecimal("12"));
        e.setPricePerKgEur(new BigDecimal("12"));
        e.setCurrency("EUR");
        e.setStatus(AnnouncementStatus.ACTIVE);
        e.setTransportMode(TransportMode.PLANE);
        e.setPickupAddressLabel("Test pickup");
        e.setPickupLat(BigDecimal.valueOf(48.8566));
        e.setPickupLng(BigDecimal.valueOf(2.3522));
        e.setDeliveryAddressLabel("Test delivery");
        e.setDeliveryLat(BigDecimal.valueOf(14.6928));
        e.setDeliveryLng(BigDecimal.valueOf(-17.4467));
        return announcementRepository.save(e);
    }

    private JsonNode search(String from, String to, String extra) throws Exception {
        String body = mockMvc.perform(get("/announcements?departureCity=Paris&arrivalCity=Dakar"
                                + "&departureDateFrom=" + from + "&departureDateTo=" + to + extra)
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                viewer.getFirebaseUid(), null, List.of(new SimpleGrantedAuthority("ROLE_SENDER"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").exists())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static List<String> allKeys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        collectKeys(node, keys);
        return keys;
    }

    private static void collectKeys(JsonNode node, List<String> keys) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                keys.add(name);
                collectKeys(node.get(name), keys);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectKeys(child, keys));
        }
    }

    @Test
    @DisplayName("une annonce hors dates du même trajet arrive dans le bloc 2 avec l'écart DATE")
    void offDateTrip_listedAsSimilarWithDateCode() throws Exception {
        LocalDate today = LocalDate.now();
        AnnouncementEntity exact = trip(traveler, today.plusDays(10));
        AnnouncementEntity offDate = trip(traveler, today.plusDays(25));

        JsonNode page = search(today.plusDays(9).toString(), today.plusDays(12).toString(), "");

        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("id").asText()).isEqualTo(exact.getId().toString());
        JsonNode similar = page.get("similarAnnouncements");
        assertThat(similar).hasSize(1);
        assertThat(similar.get(0).get("announcement").get("id").asText()).isEqualTo(offDate.getId().toString());
        assertThat(similar.get(0).get("unmet").get(0).get("code").asText()).isEqualTo("DATE");
        assertThat(similar.get(0).get("unmet").get(0).get("requested").asText())
                .isEqualTo(today.plusDays(12).toString());
        assertThat(similar.get(0).get("unmet").get(0).get("actual").asText())
                .isEqualTo(today.plusDays(25).toString());
    }

    @Test
    @DisplayName("aucune clé de score ni de fiabilité dans la réponse")
    void response_neverExposesScoreOrReliability() throws Exception {
        LocalDate today = LocalDate.now();
        trip(traveler, today.plusDays(10));
        trip(traveler, today.plusDays(25));

        JsonNode page = search(today.plusDays(9).toString(), today.plusDays(12).toString(), "");

        assertThat(page.get("similarAnnouncements")).isNotEmpty();
        assertThat(allKeys(page)).noneMatch(k -> {
            String lower = k.toLowerCase();
            return lower.contains("score") || lower.contains("reliab")
                    || lower.contains("cancellationcount") || lower.contains("noshowcount");
        });
    }

    @Test
    @DisplayName("sept annonces exactes : pas de bloc 2, clé absente du JSON")
    void sevenExact_noSimilarKey() throws Exception {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 7; i++) {
            trip(traveler, today.plusDays(10));
        }
        trip(traveler, today.plusDays(25));

        JsonNode page = search(today.plusDays(9).toString(), today.plusDays(12).toString(), "");

        assertThat(page.get("totalElements").asLong()).isEqualTo(7);
        assertThat(page.has("similarAnnouncements")).isFalse();
        assertThat(page.has("bookableElements")).isFalse();
    }

    @Test
    @DisplayName("le bloc 2 exclut un voyageur bloqué par le lecteur")
    void similarBlock_excludesBlockedTraveler() throws Exception {
        LocalDate today = LocalDate.now();
        UserEntity blocked = user("rank-blocked");
        trip(blocked, today.plusDays(25));
        AnnouncementEntity visible = trip(traveler, today.plusDays(26));
        UserBlockEntity block = new UserBlockEntity();
        block.setBlockerId(viewer.getId());
        block.setBlockedId(blocked.getId());
        userBlockRepository.save(block);

        JsonNode page = search(today.plusDays(9).toString(), today.plusDays(12).toString(), "");

        JsonNode similar = page.get("similarAnnouncements");
        assertThat(similar).hasSize(1);
        assertThat(similar.get(0).get("announcement").get("id").asText()).isEqualTo(visible.getId().toString());
    }

    @Test
    @DisplayName("appel compteur (size=1) : totalElements toujours lisible")
    void counterCall_readsTotalElements() throws Exception {
        LocalDate today = LocalDate.now();
        trip(traveler, today.plusDays(10));
        trip(traveler, today.plusDays(11));

        JsonNode page = search(today.plusDays(9).toString(), today.plusDays(12).toString(), "&page=0&size=1");

        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.get("content")).hasSize(1);
    }
}
