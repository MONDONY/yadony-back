package com.yadony.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.payments.currency.ExchangeRateEntity;
import com.yadony.api.payments.currency.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /public/announcements} : recherche anonyme, lecture seule, liste blanche de champs.
 *
 * <p>Aucune requête de ce test ne porte de jeton : c'est le point. Les caches sont vidés avant
 * chaque cas, car la recherche publique (60 s) et la recherche authentifiée (5 min) en dessous
 * rejoueraient sinon le résultat d'un cas précédent.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PublicAnnouncementSearchControllerIT {

    private static final String PHONE_IN_DESCRIPTION = "+33 6 12 34 56 78";
    private static final String PUBLIC_PATH = "/public/announcements";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private ExchangeRateRepository exchangeRateRepository;
    @MockBean private FirebaseAuth firebaseAuth;

    private UUID travelerId;

    @BeforeEach
    void seedTraveler() {
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        for (String name : List.of("public-announcements-search", "announcements-search")) {
            if (cacheManager.getCache(name) != null) {
                cacheManager.getCache(name).clear();
            }
        }
        // Les trajets XOF sont convertis dans la devise du lecteur : taux requis.
        for (String[] rate : new String[][] {{"EUR", "1"}, {"XOF", "655.957"}}) {
            if (exchangeRateRepository.findById(rate[0]).isEmpty()) {
                exchangeRateRepository.save(new ExchangeRateEntity(rate[0], new BigDecimal(rate[1])));
            }
        }
        UserEntity traveler = new UserEntity();
        traveler.setFirebaseUid("uid-public-search-traveler");
        traveler.setFirstName("Moussa");
        traveler.setLastName("Diallo");
        traveler.setStatus(UserStatus.ACTIVE);
        traveler.setKycStatus(KycStatus.VERIFIED);
        traveler.setRoles(new HashSet<>(List.of(Role.TRAVELER)));
        traveler.setAvatarUrl("https://cdn.example.test/avatars/moussa-secret.jpg");
        travelerId = userRepository.save(traveler).getId();
    }

    @Test
    void repondEn200SansJeton() throws Exception {
        seed("Paris", "Dakar", AnnouncementStatus.ACTIVE);

        mockMvc.perform(get(PUBLIC_PATH).param("departureCity", "Paris"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].departureCity").value("Paris"))
                .andExpect(jsonPath("$.content[0].pricePerKgDisplay").exists())
                .andExpect(jsonPath("$.content[0].traveler.displayName").exists());
    }

    @Test
    void laCarteNeServQueLaListeBlancheDeClefs() throws Exception {
        UserEntity traveler = userRepository.findById(travelerId).orElseThrow();
        traveler.setAverageRating(new BigDecimal("4.5"));
        userRepository.save(traveler);
        AnnouncementEntity free = seed("Paris", "Dakar", AnnouncementStatus.ACTIVE);
        free.setCapacityUnit(CapacityUnit.KG_FREE);
        free.setAvailableKg(BigDecimal.ONE);
        free.setAcceptedContentTypes(new java.util.ArrayList<>(List.of("DOCUMENTS")));
        announcementRepository.save(free);

        JsonNode card = objectMapper.readTree(getOk(PUBLIC_PATH + "?departureCity=Paris"))
                .path("content").get(0);

        Set<String> keys = new HashSet<>();
        card.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder(
                "id", "departureCity", "arrivalCity", "departureDate", "availableKg",
                "capacityUnit", "pricePerKgDisplay", "currency", "pricingMode", "transportMode",
                "urgent", "acceptedContentTypes", "traveler");
        Set<String> travelerKeys = new HashSet<>();
        card.path("traveler").fieldNames().forEachRemaining(travelerKeys::add);
        assertThat(travelerKeys).containsExactlyInAnyOrder(
                "displayName", "averageRating", "totalTrips", "identityVerified");
        // Kilo libre : availableKg vaut 1 techniquement, capacityUnit dit au client de ne pas l'afficher.
        assertThat(card.path("capacityUnit").asText()).isEqualTo("KG_FREE");
        assertThat(card.path("pricingMode").asText()).isEqualTo("KG");
        assertThat(card.path("acceptedContentTypes").get(0).asText()).isEqualTo("DOCUMENTS");
    }

    @Test
    void lesAnnoncesNonPubliquesOuInactivesSontAbsentes() throws Exception {
        AnnouncementEntity visible = seed("Paris", "Dakar", AnnouncementStatus.ACTIVE);
        AnnouncementEntity cancelled = seed("Paris", "Dakar", AnnouncementStatus.CANCELLED);
        AnnouncementEntity full = seed("Paris", "Dakar", AnnouncementStatus.FULL);
        AnnouncementEntity draft = seed("Paris", "Dakar", AnnouncementStatus.DRAFT);
        AnnouncementEntity removed = seed("Paris", "Dakar", AnnouncementStatus.REMOVED_BY_ADMIN);
        AnnouncementEntity privateTrip = seed("Paris", "Dakar", AnnouncementStatus.ACTIVE);
        privateTrip.setLinkedPackageRequestId(UUID.randomUUID());
        privateTrip.setSurplusPublished(false);
        announcementRepository.save(privateTrip);
        AnnouncementEntity pastDeadline = seed("Paris", "Dakar", AnnouncementStatus.ACTIVE);
        pastDeadline.setHandoverDeadline(LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
        announcementRepository.save(pastDeadline);

        String body = getOk(PUBLIC_PATH + "?departureCity=Paris&arrivalCity=Dakar");

        assertThat(body).contains(visible.getId().toString());
        for (AnnouncementEntity hidden : List.of(cancelled, full, draft, removed, privateTrip, pastDeadline)) {
            assertThat(body).doesNotContain(hidden.getId().toString());
        }
        assertThat(objectMapper.readTree(body).path("content")).hasSize(1);
    }

    @Test
    void neContientAucunChampInterdit() throws Exception {
        AnnouncementEntity a = seed("Paris", "Dakar", AnnouncementStatus.ACTIVE);
        a.setDescription("Appelez-moi au " + PHONE_IN_DESCRIPTION + " pour les détails");
        a.setArrivalInstructions("Sonner interphone 42B, code Sentinelle1974");
        announcementRepository.save(a);

        String body = getOk(PUBLIC_PATH + "?departureCity=Paris");

        assertThat(objectMapper.readTree(body).path("content")).isNotEmpty();
        for (String forbidden : List.of("travelerId", "phone", "email", "avatarUrl", "description",
                "pickupAddress", "deliveryAddress", "pricePerKg\"", "convertedPricePerKg",
                "arrivalInstructions", "Sentinelle1974", "moussa-secret", "commission", "lat\"", "lng\"")) {
            assertThat(body).as("sous-chaîne interdite : %s", forbidden).doesNotContain(forbidden);
        }
        assertThat(body).doesNotContain(PHONE_IN_DESCRIPTION);
        assertThat(body).doesNotContain("12 34 56 78");
        assertThat(body).doesNotContain(travelerId.toString());
    }

    @Test
    void refuseUnParametreInconnu() throws Exception {
        mockMvc.perform(get(PUBLIC_PATH).param("userLat", "48.8"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("invalid-search"));
        mockMvc.perform(get(PUBLIC_PATH).param("sortBy", "price"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("invalid-search"));
    }

    @Test
    void refuseLaPage5() throws Exception {
        mockMvc.perform(get(PUBLIC_PATH).param("page", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get(PUBLIC_PATH).param("page", "4"))
                .andExpect(status().isOk());
    }

    @Test
    void refuseUneValeurMalFormee() throws Exception {
        for (String[] bad : new String[][] {
                {"page", "abc"}, {"departureDateFrom", "notadate"}, {"minRating", "x"}, {"urgent", "peutetre"}}) {
            mockMvc.perform(get(PUBLIC_PATH).param(bad[0], bad[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Test
    void refuseUnExposantGeantRapidement() {
        // Avant la borne : 10^20000004 matérialisé par setScale(4), 9,4 s et une connexion tenue.
        assertTimeout(Duration.ofSeconds(5), () ->
                mockMvc.perform(get(PUBLIC_PATH).param("maxPricePerKg", "1E+20000000"))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.code").value("invalid-search")));
    }

    @Test
    void annonceAuPrixMixteSansPrixAuKilo() throws Exception {
        AnnouncementEntity mixed = seed("Paris", "Dakar", AnnouncementStatus.ACTIVE);
        mixed.setPricingMode(PricingMode.MIXED);
        // Comme en base : un trajet à grille seule porte 0 au kilo (contrainte V94).
        mixed.setPricePerKg(BigDecimal.ZERO);
        announcementRepository.save(mixed);

        mockMvc.perform(get(PUBLIC_PATH).param("departureCity", "Paris"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].pricePerKgDisplay").doesNotExist());
    }

    @Test
    void annonceEnXof() throws Exception {
        AnnouncementEntity xof = seed("Paris", "Dakar", AnnouncementStatus.ACTIVE);
        xof.setCurrency("XOF");
        xof.setPricePerKg(new BigDecimal("5000"));
        announcementRepository.save(xof);

        mockMvc.perform(get(PUBLIC_PATH).param("departureCity", "Paris"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].currency").value("XOF"));
    }

    @Test
    void unTauxDeChangeManquantNeCassePasLaRecherchePublique() throws Exception {
        // La carte publique sert le prix dans la devise de l'annonce : aucune conversion n'est
        // calculée, donc un taux absent ne fait plus échouer la page en 422.
        Optional<ExchangeRateEntity> previousGbp = exchangeRateRepository.findById("GBP");
        exchangeRateRepository.deleteById("GBP");
        Cache rates = cacheManager.getCache("exchange-rates");
        if (rates != null) {
            rates.evict("GBP");
        }
        try {
            AnnouncementEntity gbp = seed("Paris", "Dakar", AnnouncementStatus.ACTIVE);
            gbp.setCurrency("GBP");
            announcementRepository.save(gbp);

            mockMvc.perform(get(PUBLIC_PATH).param("departureCity", "Paris"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].currency").value("GBP"));

            // La recherche de l'app garde son comportement : conversion, donc 422 sans taux.
            mockMvc.perform(get("/announcements").param("departureCity", "Paris")
                            .with(authentication(new UsernamePasswordAuthenticationToken(
                                    "uid-public-search-viewer", null,
                                    List.of(new SimpleGrantedAuthority("ROLE_SENDER"))))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("exchange-rate-missing"));
        } finally {
            // Remet GBP dans l'état trouvé (les IT s'enchaînent dans une même JVM).
            previousGbp.ifPresent(e -> exchangeRateRepository.save(
                    new ExchangeRateEntity("GBP", e.getUnitsPerEur())));
        }
    }

    @Test
    void ajouteCacheControl() throws Exception {
        mockMvc.perform(get(PUBLIC_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=30"));
    }

    @Test
    void laRechercheDesInvitesResteFermeeAuxAnonymes() throws Exception {
        mockMvc.perform(get("/announcements"))
                .andExpect(status().isUnauthorized());
    }

    private String getOk(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.has("content")).isTrue();
        return body;
    }

    private AnnouncementEntity seed(String departureCity, String arrivalCity, AnnouncementStatus status) {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(travelerId);
        e.setDepartureCity(departureCity);
        e.setArrivalCity(arrivalCity);
        e.setDepartureDate(LocalDate.now(ZoneOffset.UTC).plusDays(7));
        e.setAvailableKg(new BigDecimal("8"));
        e.setTotalKg(new BigDecimal("8"));
        e.setPricePerKg(new BigDecimal("12"));
        e.setCurrency("EUR");
        e.setStatus(status);
        e.setTransportMode(TransportMode.PLANE);
        e.setPickupAddressLabel("12 rue Secrète, Paris");
        e.setPickupLat(BigDecimal.valueOf(48.8566));
        e.setPickupLng(BigDecimal.valueOf(2.3522));
        e.setDeliveryAddressLabel("Villa 4231, Dakar");
        e.setDeliveryLat(BigDecimal.valueOf(14.6928));
        e.setDeliveryLng(BigDecimal.valueOf(-17.4467));
        e.setHandoverDeadline(LocalDateTime.now(ZoneOffset.UTC).plusDays(5));
        return announcementRepository.save(e);
    }
}
