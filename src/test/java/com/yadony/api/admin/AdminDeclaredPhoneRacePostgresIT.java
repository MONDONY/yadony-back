package com.yadony.api.admin;

import com.yadony.api.auth.DeclaredPhoneHasher;
import com.yadony.api.auth.DeclaredPhoneLevel;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Deux administrateurs valident en même temps deux comptes qui déclarent le même numéro. Le contrôle
 * {@code existsBy...} ne voit pas l'écriture non encore validée de l'autre : seul l'index unique partiel
 * de V393 les départage. Le second reçoit 409 {@code declared-phone-conflict}, pas une erreur 500, et
 * n'est pas validé. PostgreSQL réel : sous H2 (profil test) la migration ne tourne pas.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Validation simultanée d'un numéro déclaré : l'index unique V393 tranche (PostgreSQL)")
class AdminDeclaredPhoneRacePostgresIT {

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
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired private AdminDeclaredPhoneService service;
    @Autowired private UserRepository userRepository;
    @Autowired private DeclaredPhoneHasher hasher;
    @MockitoBean private FirebaseContactService firebaseContact;
    @Autowired private DataSource dataSource;

    @Test
    void twoValidationsAtOnce_secondWaitsOnTheIndexThenGets409() throws Exception {
        String phone = "+22507" + String.format("%08d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000L));
        UserEntity a = persist("race-a-" + UUID.randomUUID(), hasher.hash(phone));
        UserEntity b = persist("race-b-" + UUID.randomUUID(), hasher.hash(phone));
        when(firebaseContact.isAvailable()).thenReturn(true);
        when(firebaseContact.readContactStrict(b.getFirebaseUid()))
                .thenReturn(Optional.of(new FirebaseContactService.Contact(null, null, phone)));
        when(firebaseContact.findPhoneOwner(phone)).thenReturn(FirebaseContactService.PhoneOwnerLookup.notFound());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection firstAdmin = dataSource.getConnection()) {
            firstAdmin.setAutoCommit(false);
            // Premier administrateur : la validation de A est écrite mais pas encore validée en base.
            try (PreparedStatement ps = firstAdmin.prepareStatement("UPDATE users SET declared_phone_level = "
                    + "'YADONY_VERIFIED', declared_phone_verified_at = now() WHERE id = ?")) {
                ps.setObject(1, a.getId());
                ps.executeUpdate();
            }

            Future<Throwable> second = pool.submit(() -> {
                try {
                    service.verify(b.getId(), UUID.randomUUID(), phone);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            boolean waitedOnIndex = waitUntilAnUpdateWaitsOnALock(Duration.ofSeconds(10));
            firstAdmin.commit();
            Throwable outcome = second.get(30, TimeUnit.SECONDS);

            assertThat(waitedOnIndex).as("la seconde validation a passé le contrôle existsBy et attend l'index")
                    .isTrue();
            assertThat(outcome).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getErrorCode()).isEqualTo("declared-phone-conflict");
            });
        } finally {
            pool.shutdownNow();
        }
        assertThat(userRepository.findById(a.getId()).orElseThrow().getDeclaredPhoneLevel())
                .isEqualTo(DeclaredPhoneLevel.YADONY_VERIFIED);
        UserEntity loser = userRepository.findById(b.getId()).orElseThrow();
        assertThat(loser.getDeclaredPhoneLevel()).isEqualTo(DeclaredPhoneLevel.DECLARED);
        assertThat(loser.getDeclaredPhoneVerifiedBy()).isNull();
    }

    /** Une écriture sur {@code users} attend un verrou (ici, la ligne d'index non validée de l'autre transaction). */
    private boolean waitUntilAnUpdateWaitsOnALock(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        try (Connection probe = dataSource.getConnection(); Statement s = probe.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = "
                        + "'Lock' AND query ILIKE '%update users%' AND pid <> pg_backend_pid()")) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return true;
                    }
                }
                Thread.sleep(50);
            }
        }
        return false;
    }

    private UserEntity persist(String firebaseUid, String hash) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(firebaseUid);
        u.setFirstName("Course");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        u.setRoles(roles);
        u.setDeclaredPhoneHash(hash);
        u.setDeclaredPhoneLevel(DeclaredPhoneLevel.DECLARED);
        return userRepository.save(u);
    }
}
