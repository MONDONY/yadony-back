package com.yadony.api.admin.broadcast.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.billing.ProSubscriptionStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.TransportMode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chaque champ d'audience contre un vrai Postgres : un compte qui correspond, un compte qui ne
 * correspond pas. Les ids sont lus par {@code SELECT u.id FROM users u WHERE <where compile>}.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
class AudienceFieldsIT {

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

    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired JdbcTemplate jdbc;

    // ── Aides ───────────────────────────────────────────────────────────────

    private UUID user() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("audience-fields-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        u.setRoles(Set.of(Role.SENDER, Role.TRAVELER));
        u.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(u).getId();
    }

    private void setUser(UUID id, String column, Object value) {
        jdbc.update("UPDATE users SET " + column + " = ? WHERE id = ?", value, id);
    }

    /** Ids des comptes qui satisfont la règle {@code field / operator / value}. */
    private Set<UUID> match(String field, String operator, String valueJson) {
        try {
            String json = "{\"op\":\"AND\",\"children\":[{\"field\":\"" + field + "\",\"operator\":\""
                    + operator + "\",\"value\":" + valueJson + "}]}";
            CompiledRule rule = AudienceSqlCompiler.compile(AudienceRule.parse(MAPPER.readTree(json)));
            List<UUID> ids = new NamedParameterJdbcTemplate(jdbc).query(
                    "SELECT u.id FROM users u WHERE " + rule.where(), rule.params(),
                    (rs, i) -> rs.getObject(1, UUID.class));
            return Set.copyOf(ids);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void subscription(UUID userId, ProSubscriptionStatus status) {
        jdbc.update("INSERT INTO pro_subscriptions (user_id, status, source) VALUES (?, ?, 'STRIPE')",
                userId, status.name());
    }

    // ── Statut du compte ────────────────────────────────────────────────────

    @Test
    void gpPro_active_inclutPastDueEtLegacyGrace() {
        UUID active = user();
        UUID pastDue = user();
        UUID grace = user();
        UUID canceled = user();
        UUID never = user();
        subscription(active, ProSubscriptionStatus.ACTIVE);
        subscription(pastDue, ProSubscriptionStatus.PAST_DUE);
        subscription(grace, ProSubscriptionStatus.LEGACY_GRACE);
        subscription(canceled, ProSubscriptionStatus.CANCELED);

        assertThat(match("gp_pro", "IS", "\"ACTIVE\"")).contains(active, pastDue, grace)
                .doesNotContain(canceled, never);
    }

    @Test
    void gpPro_expired_exclutJamaisAbonne() {
        UUID canceled = user();
        UUID expired = user();
        UUID active = user();
        UUID never = user();
        subscription(canceled, ProSubscriptionStatus.CANCELED);
        subscription(expired, ProSubscriptionStatus.EXPIRED);
        subscription(active, ProSubscriptionStatus.ACTIVE);

        assertThat(match("gp_pro", "IS", "\"EXPIRED\"")).contains(canceled, expired)
                .doesNotContain(active, never);
    }

    @Test
    void gpPro_never_neRenvoieQueLesSansAbonnement() {
        UUID never = user();
        UUID active = user();
        UUID softDeleted = user();
        subscription(active, ProSubscriptionStatus.ACTIVE);
        subscription(softDeleted, ProSubscriptionStatus.ACTIVE);
        jdbc.update("UPDATE pro_subscriptions SET deleted_at = now() WHERE user_id = ?", softDeleted);

        assertThat(match("gp_pro", "IS", "\"NEVER\"")).contains(never, softDeleted).doesNotContain(active);
        assertThat(match("gp_pro", "IS", "\"ACTIVE\"")).doesNotContain(softDeleted);
    }

    @Test
    void kyc_in_filtreSurLesStatuts() {
        UUID verified = user();
        UUID rejected = user();
        UUID fresh = user();
        setUser(verified, "kyc_status", KycStatus.VERIFIED.name());
        setUser(rejected, "kyc_status", KycStatus.REJECTED.name());

        assertThat(match("kyc", "IN", "[\"VERIFIED\",\"REJECTED\"]")).contains(verified, rejected)
                .doesNotContain(fresh);
        assertThat(match("kyc", "IN", "[\"NOT_STARTED\"]")).contains(fresh).doesNotContain(verified, rejected);
    }

    @Test
    void stripeConnect_in_filtreSurLesStatuts() {
        UUID done = user();
        UUID pending = user();
        UUID none = user();
        setUser(done, "stripe_account_status", StripeAccountStatus.ONBOARDING_COMPLETE.name());
        setUser(pending, "stripe_account_status", StripeAccountStatus.PENDING_ONBOARDING.name());

        assertThat(match("stripe_connect", "IN", "[\"ONBOARDING_COMPLETE\"]")).contains(done)
                .doesNotContain(pending, none);
        assertThat(match("stripe_connect", "IN", "[\"NOT_CREATED\",\"PENDING_ONBOARDING\"]"))
                .contains(none, pending).doesNotContain(done);
    }

    @Test
    void mobileMoney_in_filtreSurLesStatuts() {
        UUID active = user();
        UUID idle = user();
        setUser(active, "mobile_money_status", MobileMoneyPayoutStatus.ACTIVE.name());

        assertThat(match("mobile_money", "IN", "[\"ACTIVE\"]")).contains(active).doesNotContain(idle);
        assertThat(match("mobile_money", "IN", "[\"NOT_CONFIGURED\"]")).contains(idle).doesNotContain(active);
    }

    @Test
    void accountType_distinguePersonnelEtPro() {
        UUID personal = user();
        UUID business = user();
        setUser(business, "is_pro_account", true);

        assertThat(match("account_type", "IS", "\"BUSINESS\"")).contains(business).doesNotContain(personal);
        assertThat(match("account_type", "IS", "\"PERSONAL\"")).contains(personal).doesNotContain(business);
    }

    @Test
    void walletBalance_positiveEtZero() {
        UUID funded = user();
        UUID empty = user();
        UUID noWallet = user();
        UUID deletedWallet = user();
        jdbc.update("INSERT INTO wallet_accounts (user_id, balance, currency) VALUES (?, 12.50, 'EUR')", funded);
        jdbc.update("INSERT INTO wallet_accounts (user_id, balance, currency) VALUES (?, 0, 'EUR')", empty);
        jdbc.update("INSERT INTO wallet_accounts (user_id, balance, currency, deleted_at) VALUES (?, 8, 'EUR', now())",
                deletedWallet);

        assertThat(match("wallet_balance", "IS", "\"POSITIVE\"")).contains(funded)
                .doesNotContain(empty, noWallet, deletedWallet);
        assertThat(match("wallet_balance", "IS", "\"ZERO\"")).contains(empty, noWallet, deletedWallet)
                .doesNotContain(funded);
    }

    private void redeem(UUID userId, UUID promoId, boolean released) {
        jdbc.update("INSERT INTO promo_redemptions (id, promo_code_id, user_id, bid_id, applied_rate, redeemed_at,"
                + " released_at) VALUES (?, ?, ?, ?, 0.5, now(), " + (released ? "now()" : "NULL") + ")",
                UUID.randomUUID(), promoId, userId, UUID.randomUUID());
    }

    private UUID promo(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO promo_codes (id, code, rate, created_at) VALUES (?, ?, 0.5, now())", id, code);
        return id;
    }

    @Test
    void promoUsed_everEtNever() {
        UUID used = user();
        UUID unused = user();
        UUID released = user();
        UUID promo = promo("ETE50");
        redeem(used, promo, false);
        redeem(released, promo, true);

        assertThat(match("promo_used", "EVER", "null")).contains(used).doesNotContain(unused, released);
        assertThat(match("promo_used", "NEVER", "null")).contains(unused, released).doesNotContain(used);
    }

    @Test
    void promoUsed_code_insensibleALaCasse() {
        UUID a = user();
        UUID b = user();
        redeem(a, promo("BIENVENUE20"), false);
        redeem(b, promo("AUTRE10"), false);

        assertThat(match("promo_used", "IS", "\"bienvenue20\"")).contains(a).doesNotContain(b);
    }

    @Test
    void referral_referred_etReferrer() {
        UUID referrer = user();
        UUID referee = user();
        UUID bystander = user();
        jdbc.update("INSERT INTO referral_invitations (referrer_user_id, referee_user_id, status)"
                + " VALUES (?, ?, 'REWARDED')", referrer, referee);

        assertThat(match("referral", "IS", "\"REFERRED\"")).contains(referee).doesNotContain(referrer, bystander);
        assertThat(match("referral", "IS", "\"REFERRER\"")).contains(referrer).doesNotContain(referee, bystander);
    }

    @Test
    void referral_referrer_exclutInvitationSansFilleul() {
        UUID pendingOnly = user();
        UUID deletedOnly = user();
        UUID filleul = user();
        jdbc.update("INSERT INTO referral_invitations (referrer_user_id, referee_phone, status)"
                + " VALUES (?, '+33600000000', 'PENDING')", pendingOnly);
        jdbc.update("INSERT INTO referral_invitations (referrer_user_id, referee_user_id, status, deleted_at)"
                + " VALUES (?, ?, 'REWARDED', now())", deletedOnly, filleul);

        assertThat(match("referral", "IS", "\"REFERRER\"")).doesNotContain(pendingOnly, deletedOnly);
        assertThat(match("referral", "IS", "\"REFERRED\"")).doesNotContain(filleul);
    }
}
