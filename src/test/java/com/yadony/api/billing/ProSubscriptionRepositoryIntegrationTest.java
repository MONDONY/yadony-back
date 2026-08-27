package com.yadony.api.billing;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ProSubscriptionRepository — requêtes des tâches planifiées")
class ProSubscriptionRepositoryIntegrationTest {

    @Autowired ProSubscriptionRepository repository;
    @Autowired UserRepository userRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        userRepository.deleteAll();

        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-billing-repo-001");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER));
        user.setCountry("FR");
        userId = userRepository.save(user).getId();
    }

    private ProSubscriptionEntity persist(ProSubscriptionStatus status,
                                          ProSubscriptionSource source) {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setUserId(userId);
        sub.setStatus(status);
        sub.setSource(source);
        return repository.save(sub);
    }

    @Test
    @DisplayName("une grâce échue est retournée, une grâce en cours ne l'est pas")
    void findsExpiredLegacyGraceOnly() {
        ProSubscriptionEntity expired = persist(ProSubscriptionStatus.LEGACY_GRACE,
                ProSubscriptionSource.LEGACY_FREE);
        expired.setGraceExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        repository.save(expired);

        var found = repository.findByStatusAndGraceExpiresAtBefore(
                ProSubscriptionStatus.LEGACY_GRACE, Instant.now());
        assertThat(found).extracting(ProSubscriptionEntity::getId).containsExactly(expired.getId());

        expired.setGraceExpiresAt(Instant.now().plus(10, ChronoUnit.DAYS));
        repository.save(expired);

        assertThat(repository.findByStatusAndGraceExpiresAtBefore(
                ProSubscriptionStatus.LEGACY_GRACE, Instant.now())).isEmpty();
    }

    @Test
    @DisplayName("un impayé au-delà du seuil de dunning est retourné")
    void findsExhaustedDunning() {
        ProSubscriptionEntity pastDue = persist(ProSubscriptionStatus.PAST_DUE,
                ProSubscriptionSource.STRIPE);
        pastDue.setPastDueSince(Instant.now().minus(6, ChronoUnit.DAYS));
        repository.save(pastDue);

        var found = repository.findByStatusAndPastDueSinceBefore(
                ProSubscriptionStatus.PAST_DUE, Instant.now().minus(5, ChronoUnit.DAYS));
        assertThat(found).hasSize(1);
    }

    @Test
    @DisplayName("une résiliation dont la période est écoulée est retournée")
    void findsEndedCancellation() {
        ProSubscriptionEntity active = persist(ProSubscriptionStatus.ACTIVE,
                ProSubscriptionSource.STRIPE);
        active.setCancelAtPeriodEnd(true);
        active.setCurrentPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS));
        repository.save(active);

        var found = repository.findByStatusAndCancelAtPeriodEndTrueAndCurrentPeriodEndBefore(
                ProSubscriptionStatus.ACTIVE, Instant.now());
        assertThat(found).hasSize(1);
    }

    @Test
    @DisplayName("findByUserId retrouve l'abonnement du voyageur")
    void findsByUserId() {
        persist(ProSubscriptionStatus.ACTIVE, ProSubscriptionSource.STRIPE);
        assertThat(repository.findByUserId(userId)).isPresent();
        assertThat(repository.findByUserId(UUID.randomUUID())).isEmpty();
    }
}
