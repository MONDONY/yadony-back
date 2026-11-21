package com.yadony.api.promo;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

/**
 * Collision sur le code de bienvenue PRENOM05, sur PostgreSQL : l'insertion se fait dans une
 * transaction à part, donc la violation d'unicité ne casse pas la transaction du rachat.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Code de bienvenue — collision PRENOM05 (PostgreSQL)")
class PromoWelcomeCodeCollisionIT {

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

    @Autowired private PromoService promoService;
    @MockitoSpyBean private PromoCodeRepository promoCodeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private UserEntity persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("welcome-collision-it-" + UUID.randomUUID());
        user.setFirstName("Amina");
        user.setLastName("Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    @Test
    @DisplayName("deux filleuls « Amina » : AMINA05 puis YADO-xxxx, dans la même transaction englobante")
    void secondAmina_getsRandomCode_andOuterTransactionSurvives() {
        UUID first = persistUser().getId();
        UUID second = persistUser().getId();
        LocalDateTime validTo = LocalDateTime.now(ZoneOffset.UTC).plusMonths(6);
        BigDecimal percent = new BigDecimal("0.05");

        PromoCodeEntity a = transactionTemplate.execute(s ->
                promoService.createReferralWelcomeCode(first, "Amina", percent, validTo));
        assertThat(a.getCode()).isEqualTo("AMINA05");

        // Course simulée : le pré-contrôle croit AMINA05 libre alors que la ligne existe, donc
        // l'insertion viole l'unicité. Le repli doit réussir dans la même transaction englobante.
        doReturn(false).when(promoCodeRepository).existsByCodeIncludingDeleted("AMINA05");
        PromoCodeEntity b = transactionTemplate.execute(status -> {
            PromoCodeEntity created = promoService.createReferralWelcomeCode(second, "Amina", percent, validTo);
            // La transaction englobante doit rester utilisable après la collision.
            promoCodeRepository.count();
            return created;
        });

        reset(promoCodeRepository);
        assertThat(b.getCode()).matches("YADO-[A-HJ-NP-Z2-9]{4}");
        assertThat(b.getOrigin()).isEqualTo(PromoCodeOrigin.REFERRAL_WELCOME);
        assertThat(promoCodeRepository.existsByCodeIncludingDeleted("AMINA05")).isTrue();
        assertThat(promoCodeRepository.existsByCodeIncludingDeleted(b.getCode())).isTrue();
    }
}
