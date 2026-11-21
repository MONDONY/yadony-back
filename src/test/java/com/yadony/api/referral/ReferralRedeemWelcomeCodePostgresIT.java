package com.yadony.api.referral;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.promo.PromoCodeEntity;
import com.yadony.api.promo.PromoCodeOrigin;
import com.yadony.api.promo.PromoCodeRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Vrai chemin {@code ReferralService.redeemCode} sur PostgreSQL pour un filleul AVEC prénom :
 * le rachat verrouille la ligne {@code users} du filleul ({@code lockForUpdate}), puis le code
 * PRENOM05 est inséré dans une transaction à part (REQUIRES_NEW) dont la clé étrangère
 * {@code owner_user_id} prend un verrou FOR KEY SHARE sur la même ligne. Compatible tant que
 * {@code lockForUpdate} reste un UPDATE hors colonne clé ; avec un SELECT ... FOR UPDATE le
 * test se bloquerait (d'où le délai strict).
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Rachat du code parrain avec prénom — code de bienvenue (PostgreSQL)")
class ReferralRedeemWelcomeCodePostgresIT {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
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

    @Autowired private ReferralService referralService;
    @Autowired private ReferralInvitationRepository invitationRepository;
    @Autowired private PromoCodeRepository promoCodeRepository;
    @Autowired private UserRepository userRepository;

    private UserEntity persistUser(String firstName) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("redeem-welcome-it-" + UUID.randomUUID());
        user.setFirstName(firstName);
        user.setLastName("Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    @Test
    @DisplayName("redeemCode d'un filleul prénommé : termine sans blocage, ELODIE05 lié à l'invitation")
    void redeemCode_refereeWithFirstName_completesAndLinksWelcomeCode() {
        UserEntity referrer = persistUser("Marc");
        UserEntity referee = persistUser("Élodie");
        String referrerCode = referralService.generateCodeForUser(referrer.getId()).getCode();

        assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> referralService.redeemCode(referee.getFirebaseUid(), referrerCode));

        ReferralInvitationEntity invitation = invitationRepository
                .findByRefereeUserIdAndStatus(referee.getId(), "SIGNED_UP").orElseThrow();
        assertThat(invitation.getWelcomePromoCodeId()).isNotNull();
        PromoCodeEntity welcome = promoCodeRepository.findById(invitation.getWelcomePromoCodeId()).orElseThrow();
        assertThat(welcome.getCode()).isEqualTo("ELODIE05");
        assertThat(welcome.getOrigin()).isEqualTo(PromoCodeOrigin.REFERRAL_WELCOME);
        assertThat(welcome.getOwnerUserId()).isEqualTo(referee.getId());
    }
}
