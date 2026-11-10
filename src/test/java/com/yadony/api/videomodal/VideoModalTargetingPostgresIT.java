package com.yadony.api.videomodal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.broadcast.rule.AudienceRule;
import com.yadony.api.admin.broadcast.rule.AudienceSqlCompiler;
import com.yadony.api.admin.broadcast.rule.RuleAudienceRepository;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.videomodal.dto.VideoModalEligibleResponse;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Situations, conditions de la Diffusion, estimation et plafond journalier sur PostgreSQL embarqué,
 * toutes migrations Flyway et {@code ddl-auto=validate} : les requêtes natives lisent les vraies tables
 * des offres, paiements, négociations, parrainage et profil.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Modals : situations et ciblage sur Postgres")
class VideoModalTargetingPostgresIT {

    private static EmbeddedPostgres postgres;

    @Autowired VideoModalSituationQueries queries;
    @Autowired VideoModalTargeting targeting;
    @Autowired VideoModalService service;
    @Autowired VideoModalCampaignRepository campaignRepository;
    @Autowired VideoModalResponseRepository responseRepository;
    @Autowired UserRepository userRepository;
    @Autowired RuleAudienceRepository ruleRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) postgres.close();
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

    @BeforeEach
    void clean() {
        responseRepository.deleteAll();
        campaignRepository.deleteAll();
    }

    // ── Données ────────────────────────────────────────────────────────────

    private UUID user() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-targeting-" + UUID.randomUUID());
        u.setFirstName("Awa");
        u.setLastName("Koné");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        u.setTotalTrips(0);
        return userRepository.save(u).getId();
    }

    private String uidOf(UUID user) {
        return jdbc.queryForObject("SELECT firebase_uid FROM users WHERE id = ?", String.class, user);
    }

    private UUID trip(UUID traveler) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                  available_kg, price_per_kg, transport_mode, total_kg,
                  pickup_address_label, pickup_lat, pickup_lng,
                  delivery_address_label, delivery_lat, delivery_lng, created_at, updated_at)
                VALUES (?, ?, 'Paris', 'Dakar', CURRENT_DATE + 10, 3.00, 15.00, 'PLANE', 3.00,
                  '12 rue de Paris', 48.8566, 2.3522, 'Plateau, Dakar', 14.6928, -17.4467, now(), now())
                """, id, traveler);
        return id;
    }

    private UUID bid(UUID trip, UUID sender, String status, Instant createdAt, Instant pendingSince, String paymentMethod) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bids (id, announcement_id, sender_id, weight_kg, description, recipient_name,
                  recipient_phone, status, payment_method, pending_since, created_at, updated_at)
                VALUES (?, ?, ?, 3.00, 'Vetements', 'Aminata', '+221701234567', ?, ?, ?, ?, now())
                """, id, trip, sender, status, paymentMethod,
                pendingSince == null ? null : OffsetDateTime.ofInstant(pendingSince, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        return id;
    }

    private void bidMessage(UUID bid, UUID author, String kind, Instant at) {
        jdbc.update("""
                INSERT INTO bid_negotiation_messages (id, bid_id, author_id, kind, proposed_gross_eur, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), bid, author, kind,
                kind.equals("PROPOSAL") || kind.equals("COUNTER") ? new java.math.BigDecimal("30.00") : null,
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    private UUID packageRequest(UUID sender) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO package_requests (id, sender_id, departure_city, arrival_city, desired_date, weight_kg,
                  parcel_size, content_category, status, transport_mode)
                VALUES (?, ?, 'Paris', 'Dakar', CURRENT_DATE + 10, 2.00, 'SMALL', 'CLOTHES', 'OPEN', 'PLANE')
                """, id, sender);
        return id;
    }

    private UUID thread(UUID request, UUID traveler, String status, Instant lastActivity) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO negotiation_threads (id, package_request_id, traveler_id, traveler_travel_date,
                  traveler_available_kg, status, current_price_eur, rounds_count, last_activity_at)
                VALUES (?, ?, ?, CURRENT_DATE + 10, 3.00, ?, 30.00, 1, ?)
                """, id, request, traveler, status, OffsetDateTime.ofInstant(lastActivity, ZoneOffset.UTC));
        return id;
    }

    private void threadMessage(UUID thread, UUID from, String kind, Instant at) {
        jdbc.update("""
                INSERT INTO negotiation_messages (id, thread_id, from_user_id, kind, proposed_price_eur, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), thread, from, kind,
                kind.equals("PROPOSAL") || kind.equals("COUNTER") ? new java.math.BigDecimal("30.00") : null,
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static final Instant T = Instant.parse("2026-11-01T10:00:00Z");

    // ── OFFER_ACCEPTED_UNPAID ──────────────────────────────────────────────

    @Test
    @DisplayName("offre acceptée non payée : accord de prix sur une demande carte, borne exacte à la seconde")
    void offreAccepteeCarte() {
        UUID traveler = user();
        UUID sender = user();
        UUID bid = bid(trip(traveler), sender, "AWAITING_PAYMENT", T.minusSeconds(86400), null, "STRIPE");
        bidMessage(bid, sender, "PROPOSAL", T.minusSeconds(86400));
        bidMessage(bid, traveler, "ACCEPT", T);

        assertThat(queries.offerAcceptedUnpaid(sender, at(T))).isTrue();            // exactement N heures
        assertThat(queries.offerAcceptedUnpaid(sender, at(T.plusSeconds(3600)))).isTrue();
        assertThat(queries.offerAcceptedUnpaid(sender, at(T.minusSeconds(1)))).isFalse(); // une seconde trop tôt
        assertThat(queries.offerAcceptedUnpaid(traveler, at(T.plusSeconds(3600)))).isFalse(); // pas le payeur
        assertThat(queries.offerAcceptedUnpaid(user(), at(T.plusSeconds(3600)))).isFalse();
    }

    @Test
    @DisplayName("offre acceptée non payée : une demande carte sans accord n'a été acceptée par personne")
    void offreCarteSansAccord() {
        UUID sender = user();
        bid(trip(user()), sender, "AWAITING_PAYMENT", T, null, "STRIPE");
        assertThat(queries.offerAcceptedUnpaid(sender, at(T.plusSeconds(86400 * 30L)))).isFalse();
    }

    @Test
    @DisplayName("offre acceptée non payée : mobile money, le paiement créé à l'acceptation du voyageur date l'accord")
    void offreAccepteeMobileMoney() {
        UUID sender = user();
        UUID bid = bid(trip(user()), sender, "AWAITING_PAYMENT", T.minusSeconds(86400), null, "MOBILE_MONEY");
        jdbc.update("""
                INSERT INTO payments (id, bid_id, rail, amount, currency, commission_amount, status, created_at, updated_at)
                VALUES (?, ?, 'PAWAPAY', 20.00, 'EUR', 2.00, 'PENDING', ?, now())
                """, UUID.randomUUID(), bid, at(T));

        assertThat(queries.offerAcceptedUnpaid(sender, at(T))).isTrue();
        assertThat(queries.offerAcceptedUnpaid(sender, at(T.minusSeconds(1)))).isFalse();
    }

    @Test
    @DisplayName("offre acceptée non payée : statut payé, supprimé ou autre = faux")
    void offreAccepteeStatuts() {
        UUID traveler = user();
        UUID sender = user();
        UUID trip = trip(traveler);
        for (String status : List.of("PENDING", "PAYMENT_ESCROWED", "ACCEPTED", "CANCELLED", "COMPLETED")) {
            UUID bid = bid(trip, sender, status, T, null, "STRIPE");
            bidMessage(bid, traveler, "ACCEPT", T);
        }
        UUID deleted = bid(trip, sender, "AWAITING_PAYMENT", T, null, "STRIPE");
        bidMessage(deleted, traveler, "ACCEPT", T);
        jdbc.update("UPDATE bids SET deleted_at = now() WHERE id = ?", deleted);

        assertThat(queries.offerAcceptedUnpaid(sender, at(T.plusSeconds(86400)))).isFalse();
    }

    @Test
    @DisplayName("offre acceptée non payée : demande de colis, fil en attente de paiement ou de dépôt depuis sa dernière activité")
    void offreAccepteeDemandeDeColis() {
        UUID sender = user();
        UUID traveler = user();
        UUID request = packageRequest(sender);
        thread(request, traveler, "AWAITING_PAYMENT", T);

        assertThat(queries.offerAcceptedUnpaid(sender, at(T))).isTrue();
        assertThat(queries.offerAcceptedUnpaid(sender, at(T.minusSeconds(1)))).isFalse();
        assertThat(queries.offerAcceptedUnpaid(traveler, at(T.plusSeconds(86400)))).isFalse();

        UUID other = user();
        UUID otherRequest = packageRequest(other);
        UUID deposit = thread(otherRequest, traveler, "AWAITING_DEPOSIT", T);
        assertThat(queries.offerAcceptedUnpaid(other, at(T))).isTrue();

        UUID third = user();
        UUID thirdRequest = packageRequest(third);
        thread(thirdRequest, traveler, "OPEN", T);
        assertThat(queries.offerAcceptedUnpaid(third, at(T.plusSeconds(86400)))).isFalse();
        UUID accepted = thread(packageRequest(third), traveler, "ACCEPTED", T);
        jdbc.update("UPDATE negotiation_threads SET deleted_at = now() WHERE id = ?", deposit);
        assertThat(queries.offerAcceptedUnpaid(other, at(T.plusSeconds(86400)))).isFalse();
        assertThat(accepted).isNotNull();
    }

    // ── OFFER_UNANSWERED ───────────────────────────────────────────────────

    @Test
    @DisplayName("offre sans réponse : demande en attente du voyageur, horloge pending_since sinon created_at")
    void offreSansReponse() {
        UUID traveler = user();
        UUID sender = user();
        UUID trip = trip(traveler);
        bid(trip, sender, "PENDING", T, null, "STRIPE");

        assertThat(queries.offerUnanswered(traveler, at(T))).isTrue();
        assertThat(queries.offerUnanswered(traveler, at(T.minusSeconds(1)))).isFalse();
        assertThat(queries.offerUnanswered(sender, at(T.plusSeconds(86400)))).isFalse(); // l'expéditeur ne « reçoit » pas

        // pending_since plus récent que created_at (accord de négociation) : c'est lui qui compte.
        UUID traveler2 = user();
        bid(trip(traveler2), sender, "PAYMENT_ESCROWED", T.minusSeconds(86400 * 5L), T.plusSeconds(7200), "STRIPE");
        assertThat(queries.offerUnanswered(traveler2, at(T.plusSeconds(7199)))).isFalse();
        assertThat(queries.offerUnanswered(traveler2, at(T.plusSeconds(7200)))).isTrue();
    }

    @Test
    @DisplayName("offre sans réponse : première proposition de prix d'un fil ouvert ; une contre-offre n'en est pas une")
    void offreSansReponseNegociation() {
        UUID traveler = user();
        UUID sender = user();
        UUID bid = bid(trip(traveler), sender, "NEGOTIATING", T.minusSeconds(100), null, "STRIPE");
        bidMessage(bid, sender, "PROPOSAL", T);

        assertThat(queries.offerUnanswered(traveler, at(T))).isTrue();
        assertThat(queries.offerUnanswered(traveler, at(T.minusSeconds(1)))).isFalse();

        bidMessage(bid, traveler, "COUNTER", T.plusSeconds(60));
        assertThat(queries.offerUnanswered(traveler, at(T.plusSeconds(86400)))).isFalse();
    }

    @Test
    @DisplayName("offre sans réponse : demande acceptée, refusée ou supprimée = faux")
    void offreSansReponseStatuts() {
        UUID traveler = user();
        UUID sender = user();
        UUID trip = trip(traveler);
        for (String status : List.of("ACCEPTED", "REJECTED", "CANCELLED", "AWAITING_PAYMENT", "NEGOTIATION_CLOSED")) {
            bid(trip, sender, status, T, null, "STRIPE");
        }
        UUID deleted = bid(trip, sender, "PENDING", T, null, "STRIPE");
        jdbc.update("UPDATE bids SET deleted_at = now() WHERE id = ?", deleted);
        assertThat(queries.offerUnanswered(traveler, at(T.plusSeconds(86400)))).isFalse();
    }

    // ── COUNTER_OFFER_PENDING ──────────────────────────────────────────────

    @Test
    @DisplayName("contre-offre en attente : adressée à l'autre partie seulement, dernier message du fil, borne exacte")
    void contreOffre() {
        UUID traveler = user();
        UUID sender = user();
        UUID bid = bid(trip(traveler), sender, "NEGOTIATING", T.minusSeconds(1000), null, "STRIPE");
        bidMessage(bid, sender, "PROPOSAL", T.minusSeconds(500));
        bidMessage(bid, traveler, "COUNTER", T);

        assertThat(queries.counterOfferPending(sender, at(T))).isTrue();                  // destinataire
        assertThat(queries.counterOfferPending(sender, at(T.minusSeconds(1)))).isFalse();
        assertThat(queries.counterOfferPending(traveler, at(T.plusSeconds(86400)))).isFalse(); // auteur
        assertThat(queries.counterOfferPending(user(), at(T.plusSeconds(86400)))).isFalse();

        bidMessage(bid, sender, "COUNTER", T.plusSeconds(10)); // la balle change de camp
        assertThat(queries.counterOfferPending(sender, at(T.plusSeconds(86400)))).isFalse();
        assertThat(queries.counterOfferPending(traveler, at(T.plusSeconds(10)))).isTrue();

        bidMessage(bid, traveler, "ACCEPT", T.plusSeconds(20)); // le dernier message n'est plus une contre-offre
        assertThat(queries.counterOfferPending(sender, at(T.plusSeconds(86400)))).isFalse();
        assertThat(queries.counterOfferPending(traveler, at(T.plusSeconds(86400)))).isFalse();
    }

    @Test
    @DisplayName("contre-offre en attente : fil d'une demande de colis (voyageur qui propose, expéditeur qui répond)")
    void contreOffreDemandeDeColis() {
        UUID sender = user();
        UUID traveler = user();
        UUID thread = thread(packageRequest(sender), traveler, "OPEN", T.minusSeconds(100));
        threadMessage(thread, traveler, "PROPOSAL", T.minusSeconds(100));
        assertThat(queries.counterOfferPending(sender, at(T.plusSeconds(86400)))).isFalse(); // une proposition n'est pas une contre-offre

        threadMessage(thread, sender, "COUNTER", T);
        assertThat(queries.counterOfferPending(traveler, at(T))).isTrue();
        assertThat(queries.counterOfferPending(traveler, at(T.minusSeconds(1)))).isFalse();
        assertThat(queries.counterOfferPending(sender, at(T.plusSeconds(86400)))).isFalse();

        jdbc.update("UPDATE negotiation_threads SET status = 'REJECTED' WHERE id = ?", thread);
        assertThat(queries.counterOfferPending(traveler, at(T.plusSeconds(86400)))).isFalse();
    }

    // ── Profil, parrainage ─────────────────────────────────────────────────

    @Test
    @DisplayName("profil incomplet : photo, ville ou « À propos » manquant ou vide ; complet = faux ; compte supprimé = faux")
    void profil() {
        UUID u = user();
        assertThat(queries.profileIncomplete(u)).isTrue(); // rien de renseigné
        jdbc.update("UPDATE users SET avatar_url = 'https://cdn/a.jpg', city = 'Paris', bio = 'Bonjour' WHERE id = ?", u);
        assertThat(queries.profileIncomplete(u)).isFalse();
        for (String missing : List.of("avatar_url", "city", "bio")) {
            jdbc.update("UPDATE users SET " + missing + " = NULL WHERE id = ?", u);
            assertThat(queries.profileIncomplete(u)).as(missing + " nul").isTrue();
            jdbc.update("UPDATE users SET " + missing + " = '   ' WHERE id = ?", u);
            assertThat(queries.profileIncomplete(u)).as(missing + " blanc").isTrue();
            jdbc.update("UPDATE users SET avatar_url = 'https://cdn/a.jpg', city = 'Paris', bio = 'Bonjour' WHERE id = ?", u);
        }
        jdbc.update("UPDATE users SET bio = NULL, deleted_at = now() WHERE id = ?", u);
        assertThat(queries.profileIncomplete(u)).isFalse();
        assertThat(queries.profileIncomplete(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("première livraison sans parrainage : compteurs de livraison, aucune invitation envoyée")
    void premiereLivraison() {
        UUID none = user();
        UUID shipper = user();
        UUID deliverer = user();
        UUID referrer = user();
        jdbc.update("UPDATE users SET total_shipments = 1 WHERE id = ?", shipper);
        jdbc.update("UPDATE users SET total_deliveries = 2 WHERE id = ?", deliverer);
        jdbc.update("UPDATE users SET total_shipments = 3 WHERE id = ?", referrer);

        assertThat(queries.firstDeliveryNoReferral(none)).isFalse();      // aucune livraison
        assertThat(queries.firstDeliveryNoReferral(shipper)).isTrue();
        assertThat(queries.firstDeliveryNoReferral(deliverer)).isTrue();
        assertThat(queries.firstDeliveryNoReferral(referrer)).isTrue();

        jdbc.update("INSERT INTO referral_invitations (id, referrer_user_id, referee_phone) VALUES (?, ?, '+221700000000')",
                UUID.randomUUID(), referrer);
        assertThat(queries.firstDeliveryNoReferral(referrer)).isFalse();

        UUID softDeleted = user();
        jdbc.update("UPDATE users SET total_shipments = 1 WHERE id = ?", softDeleted);
        jdbc.update("INSERT INTO referral_invitations (id, referrer_user_id, referee_phone, deleted_at) VALUES (?, ?, '+221700000001', now())",
                UUID.randomUUID(), softDeleted);
        assertThat(queries.firstDeliveryNoReferral(softDeleted)).isTrue(); // une invitation supprimée ne compte pas
    }

    // ── Retour après une absence ───────────────────────────────────────────

    @Test
    @DisplayName("touchLastSeen garde la dernière coupure : visite d'avant, heure du retour ; la reprise rapprochée ne l'écrase pas")
    void touchLastSeenGardeLaCoupure() {
        UUID u = user();
        LocalDateTime longAgo = LocalDateTime.now(ZoneOffset.UTC).minusDays(20).withNano(0);
        jdbc.update("UPDATE users SET last_seen_at = ? WHERE id = ?", longAgo, u);
        LocalDateTime before = column(u, "last_seen_at");

        // Les horodatages écrits par Hibernate dépendent du fuseau de la JVM (UTC en production) : on
        // compare donc les colonnes entre elles, pas à une valeur calculée côté test.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        userRepository.touchLastSeen(u, now);
        LocalDateTime lastSeen = column(u, "last_seen_at");
        assertThat(lastSeen).isAfter(before);
        assertThat(column(u, "absence_started_at")).isEqualTo(before);
        assertThat(column(u, "returned_at")).isEqualTo(lastSeen);

        // Trois heures plus tard : pas une nouvelle coupure (seuil de 6 h), la précédente est conservée.
        userRepository.touchLastSeen(u, now.plusHours(3));
        assertThat(column(u, "last_seen_at")).isEqualTo(lastSeen.plusHours(3));
        assertThat(column(u, "absence_started_at")).isEqualTo(before);
        assertThat(column(u, "returned_at")).isEqualTo(lastSeen);

        // Dix heures après le premier retour (plus de 6 h depuis la dernière visite) : nouvelle coupure.
        userRepository.touchLastSeen(u, now.plusHours(10));
        assertThat(column(u, "absence_started_at")).isEqualTo(lastSeen.plusHours(3));
        assertThat(column(u, "returned_at")).isEqualTo(lastSeen.plusHours(10));
    }

    private LocalDateTime column(UUID user, String name) {
        return jdbc.queryForObject("SELECT " + name + " FROM users WHERE id = ?", LocalDateTime.class, user);
    }

    @Test
    @DisplayName("première visite jamais vue : pas d'absence ; compte vieux de 20 jours qui revient : situation vraie")
    void retourApresAbsence() {
        UUID first = user();
        userRepository.touchLastSeen(first, LocalDateTime.now(ZoneOffset.UTC));
        assertThat(queries.lastAbsence(first)).isEmpty();
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.RETURN_AFTER_ABSENCE, 14), first)).isFalse();

        UUID back = user();
        jdbc.update("UPDATE users SET last_seen_at = now() - interval '20 days' WHERE id = ?", back);
        userRepository.touchLastSeen(back, LocalDateTime.now(ZoneOffset.UTC));
        assertThat(queries.lastAbsence(back)).isPresent();
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.RETURN_AFTER_ABSENCE, 14), back)).isTrue();
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.RETURN_AFTER_ABSENCE, 30), back)).isFalse();
        assertThat(queries.lastAbsence(UUID.randomUUID())).isEmpty();
    }

    // ── Règles de la Diffusion ─────────────────────────────────────────────

    @Test
    @DisplayName("DAYS_SINCE_INSTALL_NO_ACTION : inscrit depuis plus de N jours, sans trajet ni demande")
    void joursDepuisInstall() {
        UUID idle = user();
        UUID active = user();
        UUID fresh = user();
        jdbc.update("UPDATE users SET created_at = now() - interval '10 days' WHERE id IN (?, ?)", idle, active);
        trip(active);

        var seven = new VideoModalSituation(VideoModalSituationKind.DAYS_SINCE_INSTALL_NO_ACTION, 7);
        assertThat(targeting.holds(seven, idle)).isTrue();
        assertThat(targeting.holds(seven, active)).isFalse();
        assertThat(targeting.holds(seven, fresh)).isFalse();
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.DAYS_SINCE_INSTALL_NO_ACTION, 30), idle)).isFalse();

        UUID withBid = user();
        jdbc.update("UPDATE users SET created_at = now() - interval '10 days' WHERE id = ?", withBid);
        bid(trip(user()), withBid, "PENDING", Instant.now(), null, "STRIPE");
        assertThat(targeting.holds(seven, withBid)).isFalse();
    }

    @Test
    @DisplayName("KYC_ABANDONED : règle de la Diffusion exécutée sur les vraies tables (faux pour un compte sans vérification)")
    void kycAbandoned() {
        assertThat(targeting.holds(new VideoModalSituation(VideoModalSituationKind.KYC_ABANDONED, null), user())).isFalse();
    }

    @Test
    @DisplayName("matches : ce compte précis, compte actif seulement")
    void matches() throws Exception {
        UUID fr = user();
        UUID en = user();
        UUID suspended = user();
        jdbc.update("UPDATE users SET preferred_language = 'en' WHERE id = ?", en);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", suspended);
        var rule = AudienceSqlCompiler.compile(AudienceRule.parse(new ObjectMapper().readTree(
                "{\"op\":\"AND\",\"children\":[{\"field\":\"language\",\"operator\":\"IN\",\"value\":[\"fr\"]}]}")));

        assertThat(ruleRepository.matches(rule, fr)).isTrue();
        assertThat(ruleRepository.matches(rule, en)).isFalse();
        assertThat(ruleRepository.matches(rule, suspended)).isFalse();
        assertThat(ruleRepository.matches(rule, UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("estimation : conditions, plateformes (appareils enregistrés) et tirage sur les vrais comptes")
    void estimation() {
        UUID ios = user();
        UUID android = user();
        UUID noDevice = user();
        jdbc.update("UPDATE users SET country = 'BF' WHERE id IN (?, ?, ?)", ios, android, noDevice);
        for (Object[] device : new Object[][] {{ios, "ios"}, {android, "android"}, {android, "ios"}}) {
            jdbc.update("INSERT INTO user_devices (id, user_id, device_id, device_name, platform) VALUES (?, ?, ?, 'tel', ?)",
                    UUID.randomUUID(), device[0], "dev-" + UUID.randomUUID(), device[1]);
        }
        String conditions = "{\"children\":[{\"field\":\"country\",\"operator\":\"IN\",\"value\":[\"BF\"]}],\"op\":\"AND\"}";

        assertThat(targeting.estimate(conditions, List.of(), 100)).isEqualTo(new VideoModalTargeting.Estimate(3, 3));
        assertThat(targeting.estimate(conditions, List.of(VideoModalPlatform.IOS), 100).accounts()).isEqualTo(2);
        assertThat(targeting.estimate(conditions, List.of(VideoModalPlatform.ANDROID), 50))
                .isEqualTo(new VideoModalTargeting.Estimate(1, 1)); // 0,5 arrondi à 1
        // Compte avec deux appareils : compté une fois.
        assertThat(targeting.estimate(conditions, List.of(VideoModalPlatform.IOS, VideoModalPlatform.ANDROID), 100).accounts())
                .isEqualTo(2);
    }

    // ── Service complet sur Postgres ───────────────────────────────────────

    private VideoModalCampaignEntity activeCampaign(VideoModalSituation... situations) {
        VideoModalCampaignEntity c = new VideoModalCampaignEntity();
        c.setName("Situation");
        c.setYoutubeUrl("https://youtu.be/dQw4w9WgXcQ");
        c.setYoutubeVideoId("dQw4w9WgXcQ");
        c.setTitleFr("Bonjour");
        c.setTriggers(List.of());
        c.setSituations(List.of(situations));
        c.setActive(true);
        c.setVersionPublishedAt(Instant.now());
        return campaignRepository.save(c);
    }

    @Test
    @DisplayName("éligibilité de bout en bout : offre sans réponse depuis 24 h, servie avec APP_OPEN, pas avant 48 h")
    void eligibleOffreSansReponse() {
        UUID traveler = user();
        UUID sender = user();
        bid(trip(traveler), sender, "PENDING", Instant.now().minusSeconds(30 * 3600L), null, "STRIPE");
        VideoModalCampaignEntity day = activeCampaign(new VideoModalSituation(VideoModalSituationKind.OFFER_UNANSWERED, 24));
        VideoModalCampaignEntity twoDays = activeCampaign(new VideoModalSituation(VideoModalSituationKind.OFFER_UNANSWERED, 48));

        List<VideoModalEligibleResponse> result = service.eligible(uidOf(traveler), "fr", "layout,targeting");

        assertThat(result).extracting(VideoModalEligibleResponse::id).containsExactly(day.getId());
        assertThat(result.get(0).triggers()).containsExactly(VideoModalTrigger.APP_OPEN);
        assertThat(result).extracting(VideoModalEligibleResponse::id).doesNotContain(twoDays.getId());
        // L'expéditeur, lui, n'a rien reçu ; un visiteur non plus.
        assertThat(service.eligible(uidOf(sender), "fr", "targeting")).isEmpty();
        assertThat(service.eligible(null, "fr", "targeting")).isEmpty();
    }

    @Test
    @DisplayName("plafond journalier sur Postgres : un modal vu aujourd'hui = liste vide ; vu avant-hier = de nouveau proposé")
    void plafondJournalier() {
        UUID u = user();
        VideoModalCampaignEntity shown = activeCampaign(new VideoModalSituation(VideoModalSituationKind.PROFILE_INCOMPLETE, null));
        VideoModalCampaignEntity other = activeCampaign(new VideoModalSituation(VideoModalSituationKind.PROFILE_INCOMPLETE, null));
        assertThat(service.eligible(uidOf(u), "fr", "targeting")).hasSize(2);

        VideoModalResponseEntity row = new VideoModalResponseEntity(shown.getId(), u, 1);
        row.apply(VideoModalEvent.SHOWN, Instant.now());
        responseRepository.saveAndFlush(row);
        assertThat(service.eligible(uidOf(u), "fr", "targeting")).isEmpty();

        jdbc.update("UPDATE video_modal_responses SET last_shown_at = now() - interval '2 days' WHERE campaign_id = ?", shown.getId());
        assertThat(service.eligible(uidOf(u), "fr", "targeting")).extracting(VideoModalEligibleResponse::id)
                .containsExactlyInAnyOrder(shown.getId(), other.getId());
    }
}
