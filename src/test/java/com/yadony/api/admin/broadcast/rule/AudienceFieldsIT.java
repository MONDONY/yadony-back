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

    // ── Activité ────────────────────────────────────────────────────────────

    private UUID announcement(UUID travelerId, String from, String to, String fromCc, String toCc) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity(from);
        a.setArrivalCity(to);
        a.setDepartureCountryCode(fromCc);
        a.setArrivalCountryCode(toCc);
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("12 rue de Rivoli");
        a.setPickupLat(new BigDecimal("48.8566"));
        a.setPickupLng(new BigDecimal("2.3522"));
        a.setDeliveryAddressLabel("Avenue Bourguiba");
        a.setDeliveryLat(new BigDecimal("14.6928"));
        a.setDeliveryLng(new BigDecimal("-17.4467"));
        a.setAvailableKg(new BigDecimal("20"));
        a.setTotalKg(new BigDecimal("20"));
        a.setPricePerKg(new BigDecimal("12"));
        return announcementRepository.saveAndFlush(a).getId();
    }

    private UUID bid(UUID announcementId, UUID senderId) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(senderId);
        b.setWeightKg(new BigDecimal("5"));
        return bidRepository.saveAndFlush(b).getId();
    }

    private void alert(UUID ownerId, String from, String to, String fromCc, String toCc, boolean active) {
        jdbc.update("INSERT INTO corridor_alerts (owner_id, departure_city, arrival_city, departure_country_code,"
                + " arrival_country_code, active) VALUES (?, ?, ?, ?, ?, ?)",
                ownerId, from, to, fromCc, toCc, active);
    }

    private void kycRow(UUID userId, String status, String hoursAgo, boolean submitted) {
        jdbc.update("INSERT INTO kyc_schema.kyc_verifications (user_id, provider, status, submitted_at, updated_at)"
                        + " VALUES (?, 'DIDIT', ?, " + (submitted ? "now()" : "NULL")
                        + ", now() - make_interval(hours => ?))",
                userId, status, Integer.parseInt(hoursAgo));
    }

    @Test
    void kycAbandoned_neRetientQueLesParcoursQuittesSansSoumission() {
        UUID abandoned = user();
        UUID neverStarted = user();
        UUID stillInFlow = user();
        UUID submitted = user();
        UUID verified = user();
        UUID deletedRow = user();
        kycRow(abandoned, "PENDING", "5", false);
        kycRow(stillInFlow, "PENDING", "0", false);
        kycRow(submitted, "PENDING", "5", true);
        setUser(submitted, "kyc_status", KycStatus.PENDING.name());
        kycRow(verified, "VERIFIED", "5", false);
        setUser(verified, "kyc_status", KycStatus.VERIFIED.name());
        kycRow(deletedRow, "PENDING", "5", false);
        jdbc.update("UPDATE kyc_schema.kyc_verifications SET deleted_at = now() WHERE user_id = ?", deletedRow);

        assertThat(match("kyc_abandoned", "IS", "true")).contains(abandoned)
                .doesNotContain(neverStarted, stillInFlow, submitted, verified, deletedRow);
        assertThat(match("kyc_abandoned", "IS", "false")).contains(neverStarted, stillInFlow, submitted, verified,
                deletedRow).doesNotContain(abandoned);
    }

    @Test
    void tripDeparting_retientLesTrajetsActifsQuiPartentDansLaFenetre() {
        UUID soon = user();
        UUID later = user();
        UUID departed = user();
        UUID cancelled = user();
        UUID full = user();
        UUID none = user();
        UUID soonAnnouncement = announcement(soon, "Paris", "Dakar", "FR", "SN");
        UUID laterAnnouncement = announcement(later, "Paris", "Dakar", "FR", "SN");
        UUID departedAnnouncement = announcement(departed, "Paris", "Dakar", "FR", "SN");
        UUID cancelledAnnouncement = announcement(cancelled, "Paris", "Dakar", "FR", "SN");
        UUID fullAnnouncement = announcement(full, "Paris", "Dakar", "FR", "SN");
        jdbc.update("UPDATE announcements SET departure_date = current_date + 2 WHERE id = ?", soonAnnouncement);
        jdbc.update("UPDATE announcements SET departure_date = current_date + 20 WHERE id = ?", laterAnnouncement);
        jdbc.update("UPDATE announcements SET departure_date = current_date - 1 WHERE id = ?", departedAnnouncement);
        jdbc.update("UPDATE announcements SET departure_date = current_date + 2, status = 'CANCELLED' WHERE id = ?",
                cancelledAnnouncement);
        jdbc.update("UPDATE announcements SET departure_date = current_date + 3, status = 'FULL' WHERE id = ?",
                fullAnnouncement);

        assertThat(match("trip_departing", "WITHIN_DAYS", "3")).contains(soon, full)
                .doesNotContain(later, departed, cancelled, none);
        assertThat(match("trip_departing", "WITHIN_DAYS", "30")).contains(soon, later, full)
                .doesNotContain(departed, cancelled, none);
    }

    @Test
    void publishedTrip_everEtNever() {
        UUID traveler = user();
        UUID idle = user();
        announcement(traveler, "Paris", "Dakar", "FR", "SN");

        assertThat(match("published_trip", "EVER", "null")).contains(traveler).doesNotContain(idle);
        assertThat(match("published_trip", "NEVER", "null")).contains(idle).doesNotContain(traveler);
    }

    @Test
    void publishedTrip_withinDays_exclutAnnonceAncienne() {
        UUID recent = user();
        UUID old = user();
        announcement(recent, "Paris", "Dakar", "FR", "SN");
        UUID oldAnnouncement = announcement(old, "Paris", "Dakar", "FR", "SN");
        jdbc.update("UPDATE announcements SET created_at = now() - interval '40 days' WHERE id = ?", oldAnnouncement);

        assertThat(match("published_trip", "WITHIN_DAYS", "30")).contains(recent).doesNotContain(old);
    }

    @Test
    void createdRequest_utiliseLesBidsEtLaFenetre() {
        UUID sender = user();
        UUID oldSender = user();
        UUID traveler = user();
        UUID announcement = announcement(traveler, "Paris", "Dakar", "FR", "SN");
        bid(announcement, sender);
        UUID oldBid = bid(announcement, oldSender);
        jdbc.update("UPDATE bids SET created_at = now() - interval '90 days' WHERE id = ?", oldBid);

        assertThat(match("created_request", "EVER", "null")).contains(sender, oldSender).doesNotContain(traveler);
        assertThat(match("created_request", "WITHIN_DAYS", "30")).contains(sender).doesNotContain(oldSender, traveler);
        assertThat(match("created_request", "NEVER", "null")).contains(traveler).doesNotContain(sender);
    }

    @Test
    void completedTrips_gteEtLte() {
        UUID three = user();
        UUID zero = user();
        setUser(three, "total_trips", 3);

        assertThat(match("completed_trips", "GTE", "3")).contains(three).doesNotContain(zero);
        assertThat(match("completed_trips", "LTE", "2")).contains(zero).doesNotContain(three);
    }

    @Test
    void completedShipments_gteEtLte() {
        UUID five = user();
        UUID zero = user();
        setUser(five, "total_shipments", 5);

        assertThat(match("completed_shipments", "GTE", "5")).contains(five).doesNotContain(zero);
        assertThat(match("completed_shipments", "LTE", "4")).contains(zero).doesNotContain(five);
    }

    @Test
    void lastSeen_within_etOlderThan() {
        UUID recent = user();
        UUID old = user();
        setUser(recent, "last_seen_at", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(2)));
        setUser(old, "last_seen_at", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(60)));

        assertThat(match("last_seen", "WITHIN_DAYS", "7")).contains(recent).doesNotContain(old);
        assertThat(match("last_seen", "OLDER_THAN_DAYS", "30")).contains(old).doesNotContain(recent);
    }

    @Test
    void lastSeen_olderThan_inclutJamaisVu() {
        UUID never = user();
        UUID recent = user();
        setUser(recent, "last_seen_at", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(1)));

        assertThat(match("last_seen", "OLDER_THAN_DAYS", "30")).contains(never).doesNotContain(recent);
        assertThat(match("last_seen", "WITHIN_DAYS", "30")).doesNotContain(never);
    }

    @Test
    void signedUp_within_etOlderThan() {
        UUID fresh = user();
        UUID old = user();
        setUser(old, "created_at", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(200)));

        assertThat(match("signed_up", "WITHIN_DAYS", "30")).contains(fresh).doesNotContain(old);
        assertThat(match("signed_up", "OLDER_THAN_DAYS", "100")).contains(old).doesNotContain(fresh);
    }

    // ── Géographie ──────────────────────────────────────────────────────────

    private static String corridor(String kind, String origin, String destination, String sources) {
        StringBuilder sb = new StringBuilder("{\"kind\":\"" + kind + "\"");
        if (origin != null) {
            sb.append(",\"origin\":\"").append(origin).append('"');
        }
        if (destination != null) {
            sb.append(",\"destination\":\"").append(destination).append('"');
        }
        if (sources != null) {
            sb.append(",\"sources\":").append(sources);
        }
        return sb.append('}').toString();
    }

    @Test
    void corridorInterest_origineSeule() {
        UUID traveler = user();
        UUID sender = user();
        UUID other = user();
        UUID announcement = announcement(traveler, "Paris", "Dakar", "FR", "SN");
        bid(announcement, sender);
        announcement(other, "Lyon", "Abidjan", "FR", "CI");

        assertThat(match("corridor_interest", "MATCHES", corridor("CITY", "paris", null, null)))
                .contains(traveler, sender).doesNotContain(other);
        assertThat(match("corridor_interest", "MATCHES", corridor("CITY", null, "ABIDJAN", null)))
                .contains(other).doesNotContain(traveler, sender);
        assertThat(match("corridor_interest", "MATCHES", corridor("CITY", "Paris", "Abidjan", null)))
                .doesNotContain(traveler, sender, other);
    }

    @Test
    void corridorInterest_pays_viaCodesPays() {
        UUID traveler = user();
        UUID other = user();
        UUID alerter = user();
        announcement(traveler, "Paris", "Dakar", "FR", "SN");
        announcement(other, "Lyon", "Abidjan", "FR", "CI");
        alert(alerter, "Marseille", "Dakar", "fr", "sn", true);

        assertThat(match("corridor_interest", "MATCHES", corridor("COUNTRY", "fr", "SN", null)))
                .contains(traveler, alerter).doesNotContain(other);
        assertThat(match("corridor_interest", "MATCHES", corridor("COUNTRY", null, "CI", null)))
                .contains(other).doesNotContain(traveler, alerter);
    }

    @Test
    void corridorInterest_sourceAlerteSeule() {
        UUID traveler = user();
        UUID alerter = user();
        UUID inactive = user();
        announcement(traveler, "Paris", "Dakar", "FR", "SN");
        alert(alerter, "Paris", "Dakar", "FR", "SN", true);
        alert(inactive, "Paris", "Dakar", "FR", "SN", false);

        assertThat(match("corridor_interest", "MATCHES", corridor("CITY", "Paris", "Dakar", "[\"ALERT\"]")))
                .contains(alerter).doesNotContain(traveler, inactive);
        assertThat(match("corridor_interest", "MATCHES", corridor("CITY", "Paris", "Dakar", "[\"PUBLISHED\"]")))
                .contains(traveler).doesNotContain(alerter);
        assertThat(match("corridor_interest", "MATCHES", corridor("CITY", "Paris", "Dakar", "[\"REQUESTED\"]")))
                .doesNotContain(traveler, alerter);
    }

    @Test
    void corridorInterest_jokerEchappe() {
        UUID traveler = user();
        UUID literal = user();
        announcement(traveler, "Paris", "Dakar", "FR", "SN");
        announcement(literal, "Par%", "Dakar", "FR", "SN");

        Set<UUID> hits = match("corridor_interest", "MATCHES", corridor("CITY", "Par%", null, null));
        assertThat(hits).contains(literal).doesNotContain(traveler);
        assertThat(match("corridor_interest", "MATCHES", corridor("CITY", "P_ris", null, null)))
                .doesNotContain(traveler);
        assertThat(match("corridor_interest", "MATCHES", corridor("CITY", "Pa\\\\ris", null, null)))
                .doesNotContain(traveler);
    }

    @Test
    void corridorInterest_bornesAbsentes_422() {
        for (String v : List.of("{\"kind\":\"CITY\"}", "{\"kind\":\"CITY\",\"origin\":\"  \",\"destination\":\"\"}")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> match("corridor_interest", "MATCHES", v))
                    .isInstanceOfSatisfying(com.yadony.api.common.YadonyBusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo("broadcast-rule-invalid"));
        }
    }

    @Test
    void corridorInterest_sansDoublon() {
        UUID traveler = user();
        announcement(traveler, "Paris", "Dakar", "FR", "SN");
        announcement(traveler, "Paris", "Dakar", "FR", "SN");
        announcement(traveler, "Paris", "Dakar", "FR", "SN");
        alert(traveler, "Paris", "Dakar", "FR", "SN", true);

        String json = "{\"op\":\"AND\",\"children\":[{\"field\":\"corridor_interest\",\"operator\":\"MATCHES\","
                + "\"value\":" + corridor("CITY", "Paris", "Dakar", null) + "}]}";
        try {
            CompiledRule rule = AudienceSqlCompiler.compile(AudienceRule.parse(MAPPER.readTree(json)));
            List<UUID> ids = new NamedParameterJdbcTemplate(jdbc).query(
                    "SELECT u.id FROM users u WHERE " + rule.where(), rule.params(),
                    (rs, i) -> rs.getObject(1, UUID.class));
            assertThat(ids).containsOnlyOnce(traveler);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void corridorAlert_isBoolean() {
        UUID with = user();
        UUID inactive = user();
        UUID without = user();
        alert(with, "Paris", "Dakar", "FR", "SN", true);
        alert(inactive, "Paris", "Dakar", "FR", "SN", false);

        assertThat(match("corridor_alert", "IS", "true")).contains(with).doesNotContain(inactive, without);
        assertThat(match("corridor_alert", "IS", "false")).contains(inactive, without).doesNotContain(with);
    }

    @Test
    void country_etIntentDestination_insensiblesALaCasse() {
        UUID fr = user();
        UUID sn = user();
        UUID none = user();
        setUser(fr, "country", "fr");
        setUser(sn, "country", "SN");
        setUser(fr, "intent_destination_country", "CI");

        assertThat(match("country", "IN", "[\"FR\"]")).contains(fr).doesNotContain(sn, none);
        assertThat(match("country", "IN", "[\"FR\",\"SN\"]")).contains(fr, sn);
        assertThat(match("intent_destination", "IN", "[\"CI\"]")).contains(fr).doesNotContain(sn, none);
    }

    @Test
    void corridorInterest_sourcesNull_valentToutesLesSources() {
        UUID traveler = user();
        UUID other = user();
        announcement(traveler, "Paris", "Dakar", "FR", "SN");
        announcement(other, "Lyon", "Abidjan", "FR", "CI");

        String v = "{\"kind\":\"CITY\",\"origin\":\"Paris\",\"sources\":null}";
        assertThat(match("corridor_interest", "MATCHES", v)).contains(traveler).doesNotContain(other);
    }

    @Test
    void corridorInterest_sourceDemandeeEnModePays() {
        UUID traveler = user();
        UUID sender = user();
        UUID other = user();
        bid(announcement(traveler, "Paris", "Dakar", "FR", "SN"), sender);

        assertThat(match("corridor_interest", "MATCHES", corridor("COUNTRY", "FR", "SN", "[\"REQUESTED\"]")))
                .contains(sender).doesNotContain(traveler, other);
    }

    @Test
    void country_toutPaysIso_accepte_etInconnuRefuse() {
        UUID jp = user();
        setUser(jp, "country", "JP");

        assertThat(match("country", "IN", "[\"JP\"]")).contains(jp);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> match("country", "IN", "[\"ZZ\"]"))
                .isInstanceOfSatisfying(com.yadony.api.common.YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("broadcast-rule-invalid"));
    }
}
