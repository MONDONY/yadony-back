package com.yadony.api.billing;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Downgrade PRO — effet de bout en bout le plus facile à casser silencieusement :
 * un downgrade ne doit dépublier aucune annonce. Des expéditeurs peuvent déjà
 * s'être engagés sur une annonce ; la retirer du marché parce qu'un abonnement
 * expire casserait des engagements pris.
 *
 * <p>La suspension des règles d'automatisation (l'autre effet du downgrade) est
 * couverte par {@code AutomationRuleProStatusListenerTest} (tests unitaires avec
 * repository mocké). Ce test-ci ne persiste pas d'{@code AutomationRuleEntity} :
 * ses colonnes {@code jsonb} (conditions/action) ne se relisent pas correctement
 * sous H2 dans ce projet (Flyway désactivé en test, schéma généré depuis les
 * entités JPA) — c'est un défaut d'infrastructure de test préexistant, sans lien
 * avec la logique de downgrade elle-même.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Downgrade PRO — effets de bout en bout")
class ProDowngradeEndToEndIntegrationTest {

    @Autowired ProSubscriptionService subscriptionService;
    @Autowired ProSubscriptionRepository subscriptionRepository;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;

    private UUID travelerId;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        announcementRepository.deleteAll();
        userRepository.deleteAll();

        UserEntity traveler = new UserEntity();
        traveler.setFirebaseUid("uid-downgrade-e2e-001");
        traveler.setStatus(UserStatus.ACTIVE);
        traveler.setKycStatus(KycStatus.PENDING);
        traveler.setRoles(Set.of(Role.TRAVELER));
        traveler.setCountry("FR");
        traveler.setProAccount(true);
        travelerId = userRepository.save(traveler).getId();
    }

    private ProSubscriptionEntity activeSubscription() {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setUserId(travelerId);
        sub.setStatus(ProSubscriptionStatus.ACTIVE);
        sub.setSource(ProSubscriptionSource.STRIPE);
        return subscriptionRepository.save(sub);
    }

    private AnnouncementEntity activeAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
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
        a.setTravelerIsPro(true);
        return announcementRepository.save(a);
    }

    @Test
    @DisplayName("l'expiration retire le statut PRO mais ne dépublie pas les annonces")
    void expirationDoesNotUnpublishAnnouncements() {
        ProSubscriptionEntity sub = activeSubscription();
        AnnouncementEntity announcement = activeAnnouncement();

        subscriptionService.expire(sub);

        assertThat(userRepository.findById(travelerId).orElseThrow().isProAccount())
                .as("le drapeau PRO doit tomber")
                .isFalse();

        AnnouncementEntity reloaded = announcementRepository.findById(announcement.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("un downgrade ne doit jamais dépublier une annonce : "
                        + "des expéditeurs peuvent déjà s'être engagés dessus")
                .isEqualTo(AnnouncementStatus.ACTIVE);
        assertThat(reloaded.isTravelerIsPro())
                .as("seul le badge PRO de l'annonce doit tomber")
                .isFalse();
    }
}
