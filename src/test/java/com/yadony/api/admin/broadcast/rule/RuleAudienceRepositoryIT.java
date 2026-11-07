package com.yadony.api.admin.broadcast.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.broadcast.AdminBroadcastEntity;
import com.yadony.api.admin.broadcast.AdminBroadcastRepository;
import com.yadony.api.admin.broadcast.BroadcastTargetType;
import com.yadony.api.admin.broadcast.rule.RuleAudienceRepository.RecipientRow;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
class RuleAudienceRepositoryIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
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
    }

    @Autowired RuleAudienceRepository repository;
    @Autowired UserRepository userRepository;
    @Autowired AdminBroadcastRepository broadcastRepository;
    @Autowired JdbcTemplate jdbc;

    private UserEntity newUser(UserStatus status) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("rule-audience-" + UUID.randomUUID());
        u.setStatus(status);
        u.setKycStatus(KycStatus.NOT_STARTED);
        u.setRoles(Set.of(Role.SENDER, Role.TRAVELER));
        u.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return u;
    }

    private UUID user(UserStatus status) {
        return userRepository.saveAndFlush(newUser(status)).getId();
    }

    private static CompiledRule everyone() {
        return AudienceSqlCompiler.compile(new AudienceRule.Group(AudienceRule.Op.AND, List.of()));
    }

    private void device(UUID userId, String token) {
        jdbc.update("INSERT INTO user_devices (user_id, device_id, device_name, platform, fcm_token)"
                + " VALUES (?, ?, 'test', 'ios', ?)", userId, "dev-" + UUID.randomUUID(), token);
    }

    @Test
    void count_etPageIds_coherents_sur3Pages() {
        long before = repository.count(everyone());
        List<UserEntity> batch = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            batch.add(newUser(UserStatus.ACTIVE));
        }
        userRepository.saveAllAndFlush(batch);

        long total = repository.count(everyone());
        assertThat(total).isEqualTo(before + 450);

        Set<UUID> seen = new HashSet<>();
        int pages = 0;
        Page<UUID> page;
        do {
            page = repository.pageIds(everyone(), PageRequest.of(pages, 200));
            assertThat(page.getTotalElements()).isEqualTo(total);
            page.getContent().forEach(id -> assertThat(seen.add(id)).as("pas de doublon entre pages").isTrue());
            pages++;
        } while (page.hasNext());

        assertThat(pages).isEqualTo((int) Math.ceil(total / 200.0));
        assertThat(seen).hasSize((int) total);
    }

    @Test
    void countPushReachable_ignoreAppareilSansJeton() {
        long before = repository.countPushReachable(everyone());
        UUID withToken = user(UserStatus.ACTIVE);
        UUID twoTokens = user(UserStatus.ACTIVE);
        UUID withoutToken = user(UserStatus.ACTIVE);
        user(UserStatus.ACTIVE);
        device(withToken, "tok-1");
        device(twoTokens, "tok-2");
        device(twoTokens, "tok-3");
        device(withoutToken, null);

        assertThat(repository.countPushReachable(everyone())).isEqualTo(before + 2);
    }

    @Test
    void pageRecipients_neContientNiTelephoneNiEmail() {
        assertThat(Arrays.stream(RecipientRow.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("userId", "displayName", "country", "language", "pushReachable", "signedUpAt");
    }

    @Test
    void pageRecipients_nomAffichageEtJoignabilite() {
        UserEntity named = newUser(UserStatus.ACTIVE);
        named.setFirstName("Awa");
        named.setLastName("Diop");
        named.setCountry("FR");
        UUID namedId = userRepository.saveAndFlush(named).getId();
        UUID anonId = user(UserStatus.ACTIVE);
        device(namedId, "tok");

        List<RecipientRow> rows = repository.pageRecipients(everyone(), PageRequest.of(0, 1000)).getContent();
        RecipientRow n = rows.stream().filter(r -> r.userId().equals(namedId)).findFirst().orElseThrow();
        RecipientRow a = rows.stream().filter(r -> r.userId().equals(anonId)).findFirst().orElseThrow();

        assertThat(n.displayName()).isEqualTo("Awa Diop");
        assertThat(n.country()).isEqualTo("FR");
        assertThat(n.language()).isEqualTo("fr");
        assertThat(n.pushReachable()).isTrue();
        assertThat(n.signedUpAt()).isNotNull();
        assertThat(a.displayName()).isEqualTo(userRepository.findById(anonId).orElseThrow().getUsername());
        assertThat(a.pushReachable()).isFalse();
    }

    @Test
    void historique_copieLaRegleEnJsonbObjet() throws Exception {
        String rule = AudienceRule.parse(MAPPER.readTree(
                "{\"op\":\"AND\",\"children\":[{\"field\":\"created_request\",\"operator\":\"EVER\"}]}")).canonicalJson();
        AdminBroadcastEntity saved = broadcastRepository.saveAndFlush(new AdminBroadcastEntity(
                "T", "B", BroadcastTargetType.RULE, null, null, null, 3, UUID.randomUUID(), rule, null, "Mon segment"));

        String type = jdbc.queryForObject(
                "SELECT jsonb_typeof(audience_rule) FROM admin_broadcasts WHERE id = ?", String.class, saved.getId());
        assertThat(type).isEqualTo("object");
        assertThat(jdbc.queryForObject("SELECT segment_name FROM admin_broadcasts WHERE id = ?",
                String.class, saved.getId())).isEqualTo("Mon segment");
    }
}
