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
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FLUTTER-JH : les trajets complets à venir apparaissent dans la recherche publique, après
 * les réservables, sans compter dans le nombre de trajets réservables ; une offre sur un
 * trajet complet reste refusée par le serveur.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("GET /announcements?includeFull=true — trajets complets visibles (FLUTTER-JH)")
class AnnouncementSearchIncludeFullIntegrationTest {

    @Autowired MockMvc mockMvc;
    // Espion : H2 ne connaît pas le verrou PostgreSQL « FOR NO KEY UPDATE » de
    // findByIdForUpdate ; on lui sert l'annonce lue normalement, la garde reste la vraie.
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired CacheManager cacheManager;

    private UUID activeSoon;
    private UUID activeLater;
    private UUID fullSoon;
    private UUID fullPast;
    private UUID cancelled;

    @BeforeEach
    void seed() {
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        var cache = cacheManager.getCache("announcements-search");
        if (cache != null) {
            cache.clear();
        }
        UserEntity traveler = user("full-traveler", Role.TRAVELER);
        user("full-sender", Role.SENDER);
        // Le complet part AVANT les réservables : seul le regroupement par statut le
        // place après eux.
        fullSoon = trip(traveler, AnnouncementStatus.FULL, LocalDate.now().plusDays(2), "0");
        activeSoon = trip(traveler, AnnouncementStatus.ACTIVE, LocalDate.now().plusDays(5), "8");
        activeLater = trip(traveler, AnnouncementStatus.ACTIVE, LocalDate.now().plusDays(9), "8");
        fullPast = trip(traveler, AnnouncementStatus.FULL, LocalDate.now().minusDays(1), "0");
        cancelled = trip(traveler, AnnouncementStatus.CANCELLED, LocalDate.now().plusDays(3), "8");
    }

    @Test
    @DisplayName("sans includeFull : réservables seuls, réponse inchangée (ancienne app, compteur)")
    void withoutIncludeFull_onlyBookable() throws Exception {
        JsonNode page = search(null, 0, 50);
        assertThat(ids(page)).containsExactly(activeSoon, activeLater);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.has("bookableElements")).isFalse();
    }

    @Test
    @DisplayName("includeFull : réservables d'abord, puis complets à venir ; ni départ passé ni annulé")
    void withIncludeFull_fullAfterBookable() throws Exception {
        JsonNode page = search(true, 0, 50);
        assertThat(ids(page)).containsExactly(activeSoon, activeLater, fullSoon);
        assertThat(ids(page)).doesNotContain(fullPast, cancelled);
        assertThat(page.get("content").get(2).get("status").asText()).isEqualTo("FULL");
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("bookableElements").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("includeFull : la pagination traverse réservables puis complets")
    void withIncludeFull_paginatesAcrossBothGroups() throws Exception {
        assertThat(ids(search(true, 0, 2))).containsExactly(activeSoon, activeLater);
        JsonNode second = search(true, 1, 2);
        assertThat(ids(second)).containsExactly(fullSoon);
        assertThat(second.get("last").asBoolean()).isTrue();
        assertThat(second.get("bookableElements").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("includeFull : les filtres s'appliquent aussi aux complets (corridor)")
    void withIncludeFull_filtersApplyToFullTrips() throws Exception {
        JsonNode page = objectMapper.readTree(mockMvc.perform(get("/announcements")
                        .param("includeFull", "true").param("arrivalCity", "Bamako").with(as("full-reader")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ids(page)).isEmpty();
        assertThat(page.get("bookableElements").asLong()).isZero();
    }

    @Test
    @DisplayName("une offre sur un trajet complet reste refusée (409 announcement-not-active)")
    void bidOnFullTrip_isRefused() throws Exception {
        String body = """
                {"weightKg": 2, "description": "Vêtements", "contentCategory": "OTHER",
                 "recipientName": "Awa", "recipientPhone": "+221771234567", "disclaimerSigned": true}
                """;
        AnnouncementEntity full = announcementRepository.findById(fullSoon).orElseThrow();
        org.mockito.Mockito.doReturn(java.util.Optional.of(full))
                .when(announcementRepository).findByIdForUpdate(fullSoon);
        mockMvc.perform(post("/announcements/" + fullSoon + "/bids")
                        .with(as("full-sender"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("announcement-not-active"));
    }

    private JsonNode search(Boolean includeFull, int page, int size) throws Exception {
        var request = get("/announcements")
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size))
                .with(as("full-reader"));
        if (includeFull != null) {
            request = request.param("includeFull", includeFull.toString());
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

    private UserEntity user(String uid, Role role) {
        var user = new UserEntity();
        user.setFirebaseUid(uid);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(new HashSet<>(List.of(role)));
        return userRepository.save(user);
    }

    private UUID trip(UserEntity traveler, AnnouncementStatus status, LocalDate departure, String availableKg) {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(traveler.getId());
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDepartureDate(departure);
        e.setAvailableKg(new BigDecimal(availableKg));
        e.setTotalKg(new BigDecimal("8"));
        e.setPricePerKg(new BigDecimal("12"));
        e.setCurrency("EUR");
        e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
        e.setStatus(status);
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
