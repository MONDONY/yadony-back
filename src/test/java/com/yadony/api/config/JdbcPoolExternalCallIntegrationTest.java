package com.yadony.api.config;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.StorageService;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.messaging.FirestoreService;
import com.yadony.api.messaging.MessagingImageEntity;
import com.yadony.api.messaging.MessagingImageRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * YADONY-BACK-STAGING-W : une requête qui attend un service externe lent ne doit plus
 * tenir de connexion JDBC pendant cette attente.
 *
 * <p>Pool réduit à 2 connexions, délai d'emprunt de 500 ms, appel externe simulé de
 * 800 ms, 8 requêtes simultanées. Si une connexion restait tenue pendant l'appel (ancienne
 * transaction englobante), au plus 2 requêtes avanceraient et les autres échoueraient en
 * 503 {@code service-busy} au bout de 500 ms. GET /auth/me empruntait de plus une seconde
 * connexion (UserSeenEvent en REQUIRES_NEW) en tenant la première : sur un pool de 2, deux
 * requêtes simultanées suffisaient à le bloquer.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=500"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Pool JDBC : appel externe lent hors connexion")
class JdbcPoolExternalCallIntegrationTest {

    private static final long SLOW_CALL_MS = 800;
    private static final int CONCURRENT_REQUESTS = 8;

    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    @Autowired UserRepository userRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired MessagingImageRepository imageRepository;
    @MockitoSpyBean StorageService storageService;
    @MockitoBean FirestoreService firestoreService;
    @MockitoBean FirebaseContactService firebaseContactService;

    /** Connexions actives relevées pendant chaque appel externe simulé. */
    private final List<Integer> activeDuringExternalCall = new CopyOnWriteArrayList<>();

    private UserEntity sender;
    private UserEntity traveler;

    @BeforeEach
    void seed() {
        activeDuringExternalCall.clear();
        sender = persistUser("Awa");
        traveler = persistUser("Moussa");
    }

    @Test
    @DisplayName("GET /auth/me : Firebase lent, aucune connexion tenue, 8 requêtes simultanées en 200")
    void authMe_slowFirebase_holdsNoConnection() throws Exception {
        when(firebaseContactService.getContact(anyString())).thenAnswer(inv -> {
            slowExternalCall();
            return new FirebaseContactService.Contact("+33600000000", "awa@example.test");
        });

        assertAllSucceed(() -> get("/auth/me").with(authentication(as(sender))));

        assertThat(activeDuringExternalCall).hasSize(CONCURRENT_REQUESTS);
    }

    @Test
    @DisplayName("Lecture d'une photo de messagerie : Firestore et R2 lents, aucune connexion tenue")
    void messagingImage_slowFirestoreAndR2_holdsNoConnection() throws Exception {
        // Identifiant de colis sans ligne : la lecture d'une photo ne consulte pas le colis.
        UUID bidId = UUID.randomUUID();
        ConversationEntity conversation = conversationRepository.saveAndFlush(new ConversationEntity(
                bidId, sender.getId(), traveler.getId(), "conv_pool_" + UUID.randomUUID()));
        imageRepository.saveAndFlush(new MessagingImageEntity(conversation.getId(), bidId, "PoolImg1",
                sender.getId(), "messaging/x/PoolImg1_full.jpg", "messaging/x/PoolImg1_thumb.jpg"));
        when(firestoreService.findMessage(any(), any())).thenAnswer(inv -> {
            slowExternalCall();
            return Optional.of(new FirestoreService.MessageSnapshot("uid", false));
        });
        doAnswer(inv -> {
            slowExternalCall();
            return Optional.of(new byte[]{1, 2, 3});
        }).when(storageService).downloadBytes("messaging/x/PoolImg1_full.jpg");

        assertAllSucceed(() -> get("/conversations/{id}/messages/{m}/image", conversation.getId(), "PoolImg1")
                .with(authentication(as(traveler))));

        // Deux appels externes par requête (Firestore puis R2).
        assertThat(activeDuringExternalCall).hasSize(2 * CONCURRENT_REQUESTS);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Les requêtes partent ensemble ; chacune doit répondre 200. Une seule doit aussi,
     * pendant son appel externe, observer un pool sans aucune connexion empruntée par
     * elle-même : on le vérifie à part, requête isolée, où toute connexion active ne
     * peut être que la sienne.
     */
    private void assertAllSucceed(Callable<RequestBuilder> request) throws Exception {
        // 1. Requête isolée : pendant l'appel externe, plus aucune connexion active.
        int status = mockMvc.perform(request.call()).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        assertThat(activeDuringExternalCall).isNotEmpty().allMatch(active -> active == 0);
        activeDuringExternalCall.clear();

        // 2. Rafale : sur 2 connexions, toutes passent malgré les 800 ms d'attente chacune.
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                RequestBuilder builder = request.call();
                results.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(builder).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).as("statuts HTTP de la rafale").containsOnly(200);
        } finally {
            pool.shutdownNow();
        }
    }

    private void slowExternalCall() throws InterruptedException {
        Thread.sleep(SLOW_CALL_MS);
        activeDuringExternalCall.add(activeConnections());
    }

    private int activeConnections() {
        return ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-pool-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER, Role.TRAVELER)));
        u.setTotalTrips(0);
        return userRepository.save(u);
    }
}
