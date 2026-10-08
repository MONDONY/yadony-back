package com.yadony.api.common;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.country.CountryRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nombre de requêtes SQL des listes les plus lues, indépendant de la taille de la page.
 *
 * <p>Test de charge k6 du 08/10/2026 (staging, 200 utilisateurs) : la liste des
 * conversations montait à 15 s, les notes reçues à 7,5 s et « Mes trajets » à 3,3 s,
 * parce que chaque élément lisait l'interlocuteur, le colis, le trajet et ses compteurs
 * un par un, chaque lecture empruntant sa propre connexion. On mesure chaque liste avec 2
 * puis 6 colis acceptés : le nombre de requêtes ne doit pas grandir avec la liste.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Listes conversations, notes reçues, Mes trajets : requêtes SQL sans N+1")
class ListPagesQueryCountIntegrationTest {

    private static final List<String> PAGES = List.of("/conversations", "/ratings/me/received", "/announcements/my");

    @Autowired MockMvc mockMvc;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired RatingRepository ratingRepository;
    @Autowired CountryRepository countryRepository;

    private Statistics statistics;
    private UserEntity traveler;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        // Référentiel des pays peuplé, comme en prod : un pays absent n'est jamais mis en
        // cache par le contexte de persistance et serait relu à chaque carte.
        countryRepository.insertIfAbsent("FR", "France", "🇫🇷");
        countryRepository.insertIfAbsent("SN", "Sénégal", "🇸🇳");
        traveler = persistUser("Moussa");
    }

    @Test
    void lesTroisListesCoutentLeMemeNombreDeRequetesQuelleQueSoitLeurTaille() throws Exception {
        for (int i = 0; i < 2; i++) {
            persistDeal(i);
        }
        Map<String, Long> withTwo = measureAll(2);

        for (int i = 2; i < 6; i++) {
            persistDeal(i);
        }
        Map<String, Long> withSix = measureAll(6);

        assertThat(withSix).as("requêtes par liste, 2 éléments → 6 éléments").isEqualTo(withTwo);
    }

    private Map<String, Long> measureAll(int expectedItems) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String page : PAGES) {
            // Premier appel à blanc : caches applicatifs (taux de commission, pays…) chauds.
            mockMvc.perform(get(page).with(authentication(as(traveler)))).andExpect(status().isOk());
            statistics.clear();
            mockMvc.perform(get(page).with(authentication(as(traveler))))
                    .andExpect(status().isOk())
                    .andExpect(page.equals("/ratings/me/received")
                            ? jsonPath("$.ratings.length()").value(expectedItems)
                            : jsonPath("$.content.length()").value(expectedItems));
            counts.put(page, statistics.getPrepareStatementCount());
        }
        return counts;
    }

    private void persistDeal(int i) {
        UserEntity sender = persistUser("Awa" + i);

        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureCountryCode("FR");
        a.setArrivalCountryCode("SN");
        a.setDepartureDate(LocalDate.now().plusDays(7 + i));
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
        a = announcementRepository.save(a);

        // ACCEPTED : le cas le plus coûteux (téléphone, bouton d'appel, photos évalués).
        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(sender.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(BidStatus.ACCEPTED);
        b.setRecipientName("Fatou Diop");
        b.setRecipientPhone("+221 77 123 45 67");
        b.setTrackingNumber("DON-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        b.setTrackingToken(UUID.randomUUID().toString());
        b.setConfirmationCode("123456");
        b = bidRepository.save(b);

        conversationRepository.save(new ConversationEntity(b.getId(), sender.getId(), traveler.getId(),
                "conv_count_" + UUID.randomUUID()));

        RatingEntity r = new RatingEntity();
        r.setRaterId(sender.getId());
        r.setRatedUserId(traveler.getId());
        r.setBidId(b.getId());
        r.setStars(4 + (i % 2));
        r.setComment("Merci");
        ratingRepository.save(r);
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-count-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }
}
