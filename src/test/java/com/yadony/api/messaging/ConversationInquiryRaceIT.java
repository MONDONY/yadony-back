package com.yadony.api.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Course sur le premier appel de {@code POST /conversations/inquiry}, sur PostgreSQL embarqué (Flyway
 * complet) : l'index partiel {@code conversations_inquiry_active_idx} n'existe pas sous H2. Le perdant
 * de la course a lu « aucune conversation » avant que le gagnant ne commite : la lecture est simulée
 * vide au premier appel, l'insertion viole réellement l'index, et le contrôleur reprend une fois.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
@DisplayName("POST /conversations/inquiry - course sur l'index unique")
class ConversationInquiryRaceIT {

    private static EmbeddedPostgres postgres;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @MockitoSpyBean ConversationRepository conversationRepository;
    @MockitoBean FirestoreService firestoreService;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) postgres.close();
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    @DisplayName("le perdant de la course reprend une fois et reçoit la conversation du gagnant")
    void loserOfTheRace_retriesAndGetsTheWinnersConversation() throws Exception {
        UserEntity asker = persistUser("Fatou");
        UserEntity owner = persistUser("Moussa");
        AnnouncementEntity announcement = persistAnnouncement(owner);
        String body = objectMapper.writeValueAsString(Map.of("announcementId", announcement.getId()));

        // Le gagnant : appel normal, la conversation est commitée.
        String winner = mockMvc.perform(post("/conversations/inquiry").with(authentication(as(asker)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String winnerId = objectMapper.readTree(winner).get("id").asText();

        // Le perdant : sa première lecture ne voit pas encore le gagnant, la seconde le voit.
        ConversationEntity winnerEntity = conversationRepository.findById(UUID.fromString(winnerId)).orElseThrow();
        doReturn(Optional.empty(), Optional.of(winnerEntity))
                .when(conversationRepository).findByAskerAndAnnouncement(any(), any());

        mockMvc.perform(post("/conversations/inquiry").with(authentication(as(asker)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(winnerId));

        long open = conversationRepository.findAll().stream()
                .filter(c -> asker.getId().equals(c.getAskerId())
                        && announcement.getId().equals(c.getAnnouncementId())).count();
        assertThat(open).isEqualTo(1);
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-race-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement(UserEntity traveler) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(7));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }
}
