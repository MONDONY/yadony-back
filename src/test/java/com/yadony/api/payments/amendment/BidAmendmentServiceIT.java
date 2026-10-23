package com.yadony.api.payments.amendment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.amendment.dto.AmendmentCreateResponse;
import com.yadony.api.payments.amendment.dto.AmendmentLineRequest;
import com.yadony.api.payments.amendment.dto.AmendmentOptionsResponse;
import com.yadony.api.payments.amendment.dto.AmendmentQuoteRequest;
import com.yadony.api.payments.amendment.dto.CreateAmendmentRequest;
import com.yadony.api.payments.amendment.events.BidAmendmentAcceptedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentClosedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRequestedEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.supplement.BidSupplementRail;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cycle de vie d'une modification de colis (création, annulation, refus, acceptation, expiration) et
 * réservation des kilos, sur un vrai PostgreSQL (V347, verrous de ligne réels). Le rail espèces (B5)
 * n'existe pas encore : un rail de test le remplace, PROPOSED à la création, acceptée à l'acceptation.
 */
@SpringBootTest(properties = "yadony.bid-amendment.enabled=true")
@ActiveProfiles("e2e")
@RecordApplicationEvents
@DisplayName("BidAmendmentService : cycle de vie et capacité (PostgreSQL)")
class BidAmendmentServiceIT {

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

    /** Rail espèces bouchonné tant que B5 n'est pas livré : PROPOSED à la création, acceptée à l'acceptation. */
    @TestConfiguration
    static class StubCashRailConfig {

        @Bean
        BidAmendmentRailHandler stubCashRail(ObjectProvider<BidAmendmentService> service,
                                             BidAmendmentItemRepository items, BidAmendmentWindow window) {
            return new BidAmendmentRailHandler() {
                @Override
                public BidSupplementRail rail() {
                    return BidSupplementRail.CASH;
                }

                @Override
                public AmendmentAcceptResponse accept(BidAmendmentEntity a, UserEntity traveler,
                                                      AmendmentAcceptRequest request) {
                    service.getObject().markAccepted(a);
                    return new AmendmentAcceptResponse(
                            BidAmendmentViews.of(a, items.findByAmendmentId(a.getId()), false, window), null);
                }
            };
        }
    }

    @Autowired BidAmendmentService service;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApplicationEvents events;

    private UserEntity traveler;
    private UserEntity sender;

    @BeforeEach
    void setUp() {
        traveler = persistUser();
        sender = persistUser();
    }

    // ── Création et capacité ────────────────────────────────────────────────

    @Test
    @DisplayName("création : 3 kg retirés de l'annonce (6 → 3), PROPOSED, événement Requested et audit")
    void create_reservesCapacityAndPublishesRequested() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());

        AmendmentCreateResponse created = service.create(bidId, sender.getFirebaseUid(), kg("3"));

        assertThat(availableKg(annId)).isEqualByComparingTo("3.00");
        BidAmendmentResponse view = created.amendment();
        assertThat(view.status()).isEqualTo("PROPOSED");
        assertThat(view.rail()).isEqualTo("CASH");
        assertThat(view.addedWeightKg()).isEqualByComparingTo("3");
        // 3 kg × 10 € = 30 € net, commission figée 12 % = 3,60 €, brut 33,60 €.
        assertThat(view.grossAmount()).isEqualByComparingTo("33.60");
        assertThat(view.netAmount()).isNull();
        assertThat(view.blocksHandover()).isTrue();
        assertThat(jdbc.queryForObject("SELECT capacity_reserved_kg FROM bid_amendments WHERE id = ?",
                BigDecimal.class, view.id())).isEqualByComparingTo("3.00");
        assertThat(jdbc.queryForObject("SELECT net_amount FROM bid_amendments WHERE id = ?",
                BigDecimal.class, view.id())).isEqualByComparingTo("30.00");

        List<BidAmendmentRequestedEvent> requested = events.stream(BidAmendmentRequestedEvent.class).toList();
        assertThat(requested).hasSize(1);
        assertThat(requested.get(0).amendmentId()).isEqualTo(view.id());
        assertThat(requested.get(0).netAmount()).isEqualByComparingTo("30.00");
        assertThat(requested.get(0).grossAmount()).isEqualByComparingTo("33.60");
        assertThat(requested.get(0).timezone()).isEqualTo("Europe/Paris");
        assertThat(auditActions(view.id())).containsExactly("BID_AMENDMENT_REQUESTED");
        assertThat(jdbc.queryForObject("SELECT payload::text FROM audit_log WHERE entity_id = ?", String.class,
                view.id())).contains(bidId.toString()).doesNotContain("\"net\"").doesNotContain("commission");
    }

    @Test
    @DisplayName("création avec articles : instantané des lignes, aucun kilo retiré")
    void create_gridItems_snapshotsLinesWithoutCapacity() {
        UUID annId = persistAnnouncement("6.00", true);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID itemId = gridItemId(annId);

        AmendmentCreateResponse created = service.create(bidId, sender.getFirebaseUid(),
                new CreateAmendmentRequest(null, List.of(new AmendmentLineRequest(itemId, 2)), PaymentMethod.CASH));

        assertThat(availableKg(annId)).isEqualByComparingTo("6.00");
        assertThat(created.amendment().items()).singleElement().satisfies(item -> {
            assertThat(item.label()).isEqualTo("Téléphone");
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.netAmount()).isNull();
            assertThat(item.grossAmount()).isEqualByComparingTo("33.60");
        });
        assertThat(created.amendment().grossAmount()).isEqualByComparingTo("33.60");
    }

    @RepeatedTest(3)
    @DisplayName("course aux derniers kilos : deux colis, une seule modification, availableKg à 0")
    void create_concurrentLastKilos_secondGets409() throws Exception {
        // La grille garde le trajet « modifiable » même à 0 kg : le perdant passe l'éligibilité et
        // bute vraiment sur la capacité, sous le verrou de l'annonce.
        UUID annId = persistAnnouncement("3.00", true);
        UUID bidA = persistAcceptedBid(annId, sender.getId());
        UserEntity otherSender = persistUser();
        UUID bidB = persistAcceptedBid(annId, otherSender.getId());

        CyclicBarrier start = new CyclicBarrier(2);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<UUID> created = Collections.synchronizedList(new ArrayList<>());
        Thread a = race(start, bidA, sender, created, errors);
        Thread b = race(start, bidB, otherSender, created, errors);
        a.start();
        b.start();
        a.join(30_000);
        b.join(30_000);

        assertThat(created).hasSize(1);
        assertThat(errors).singleElement().isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getErrorCode()).isIn("amendment-capacity-unavailable", "amendment-already-open");
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        assertThat(availableKg(annId)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bid_amendments WHERE announcement_id = ?",
                Integer.class, annId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM announcements WHERE id = ?", String.class, annId))
                .isEqualTo("FULL");
    }

    @Test
    @DisplayName("création alors qu'une modification est ouverte : 409 amendment-already-open, rien de réservé")
    void create_secondWhileOpen_409AlreadyOpen() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        service.create(bidId, sender.getFirebaseUid(), kg("1"));

        assertThatThrownBy(() -> service.create(bidId, sender.getFirebaseUid(), kg("1")))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("amendment-already-open");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThat(availableKg(annId)).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("création au-delà des kilos libres : 409 amendment-capacity-unavailable")
    void create_moreThanAvailable_409Capacity() {
        UUID annId = persistAnnouncement("2.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());

        assertThatThrownBy(() -> service.create(bidId, sender.getFirebaseUid(), kg("3")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-capacity-unavailable"));
        assertThat(availableKg(annId)).isEqualByComparingTo("2.00");
        assertThat(amendmentCount(bidId)).isZero();
    }

    @Test
    @DisplayName("capacité libre (KG_FREE) : aucun kilo retiré, rien à rendre")
    void create_kgFree_reservesNothing() {
        UUID annId = persistAnnouncement("6.00", false);
        jdbc.update("UPDATE announcements SET capacity_unit = 'KG_FREE' WHERE id = ?", annId);
        UUID bidId = persistAcceptedBid(annId, sender.getId());

        AmendmentCreateResponse created = service.create(bidId, sender.getFirebaseUid(), kg("10"));

        assertThat(availableKg(annId)).isEqualByComparingTo("6.00");
        assertThat(jdbc.queryForObject("SELECT capacity_reserved_kg FROM bid_amendments WHERE id = ?",
                BigDecimal.class, created.amendment().id())).isEqualByComparingTo("0");
        UserEntity otherSender = persistUser();
        UUID otherBid = persistAcceptedBid(annId, otherSender.getId());
        assertThat(service.options(otherBid, otherSender.getFirebaseUid()).availableKg())
                .as("capacité libre : pas de plafond de kilos").isNull();
    }

    @Test
    @DisplayName("création hors bornes (net > 300 €) : 422 amendment-amount-out-of-range")
    void create_amountAboveMax_422() {
        UUID annId = persistAnnouncement("50.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());

        assertThatThrownBy(() -> service.create(bidId, sender.getFirebaseUid(), kg("31")))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("amendment-amount-out-of-range");
                    assertThat(e.getProperties()).containsEntry("currency", "EUR");
                });
        assertThat(availableKg(annId)).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("rail non proposé par le trajet : 422 amendment-rail-unavailable")
    void create_railNotOffered_422() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());

        assertThatThrownBy(() -> service.create(bidId, sender.getFirebaseUid(),
                new CreateAmendmentRequest(new BigDecimal("1"), null, PaymentMethod.STRIPE)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-rail-unavailable"));
    }

    @Test
    @DisplayName("article répété dans une demande : 422 amendment-item-duplicate")
    void create_duplicateItem_422() {
        UUID annId = persistAnnouncement("6.00", true);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID itemId = gridItemId(annId);

        assertThatThrownBy(() -> service.create(bidId, sender.getFirebaseUid(), new CreateAmendmentRequest(null,
                List.of(new AmendmentLineRequest(itemId, 1), new AmendmentLineRequest(itemId, 1)),
                PaymentMethod.CASH)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-item-duplicate"));
    }

    // ── Options et devis ────────────────────────────────────────────────────

    @Test
    @DisplayName("options : kilos libres, prix affiché, articles, rails avec composant, bornes")
    void options_listsWhatCanBeAdded() {
        UUID annId = persistAnnouncement("6.00", true);
        UUID bidId = persistAcceptedBid(annId, sender.getId());

        AmendmentOptionsResponse options = service.options(bidId, sender.getFirebaseUid());

        assertThat(options.currency()).isEqualTo("EUR");
        assertThat(options.availableKg()).isEqualByComparingTo("6.00");
        assertThat(options.pricePerKgDisplay()).isNotNull();
        assertThat(options.gridItems()).singleElement().satisfies(i -> assertThat(i.label()).isEqualTo("Téléphone"));
        assertThat(options.paymentMethods()).containsExactly("CASH");
        assertThat(options.expiresAt()).isNotNull();
        assertThat(options.minAmount()).isEqualByComparingTo("1.00");
        assertThat(options.maxAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("devis : brut seul ; hors bornes refusé ; voyageur refusé")
    void quote_grossOnly_andRefusesOutOfRange() {
        UUID annId = persistAnnouncement("50.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());

        assertThat(service.quote(bidId, sender.getFirebaseUid(), new AmendmentQuoteRequest(new BigDecimal("3"), null))
                .grossAmount()).isEqualByComparingTo("33.60");
        assertThatThrownBy(() -> service.quote(bidId, sender.getFirebaseUid(),
                new AmendmentQuoteRequest(new BigDecimal("31"), null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-amount-out-of-range"));
        assertThatThrownBy(() -> service.quote(bidId, traveler.getFirebaseUid(),
                new AmendmentQuoteRequest(new BigDecimal("3"), null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-eligible"));
    }

    // ── Annulation, refus, acceptation ─────────────────────────────────────

    @Test
    @DisplayName("annulation par l'expéditeur : CANCELLED, kilos rendus, événement Cancelled")
    void cancel_bySender_releasesCapacity() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID id = service.create(bidId, sender.getFirebaseUid(), kg("3")).amendment().id();

        BidAmendmentResponse view = service.cancel(bidId, id, sender.getFirebaseUid());

        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.closedReason()).isEqualTo("CANCELLED_BY_SENDER");
        assertThat(view.blocksHandover()).isFalse();
        assertThat(availableKg(annId)).isEqualByComparingTo("6.00");
        assertThat(reservedKg(id)).isEqualByComparingTo("0");
        assertThat(events.stream(BidAmendmentClosedEvent.Cancelled.class)).singleElement().satisfies(e -> {
            assertThat(e.previousStatus()).isEqualTo(BidAmendmentStatus.PROPOSED);
            assertThat(e.closedReason()).isEqualTo(BidAmendmentClosedReason.CANCELLED_BY_SENDER);
        });
        assertThat(auditActions(id)).containsExactly("BID_AMENDMENT_REQUESTED", "BID_AMENDMENT_CANCELLED");

        // Une demande close ne s'annule pas deux fois.
        assertThatThrownBy(() -> service.cancel(bidId, id, sender.getFirebaseUid()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-pending"));
        assertThat(availableKg(annId)).isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("annulation par le voyageur : 403, rien ne bouge")
    void cancel_byTraveler_403() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID id = service.create(bidId, sender.getFirebaseUid(), kg("3")).amendment().id();

        assertThatThrownBy(() -> service.cancel(bidId, id, traveler.getFirebaseUid()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(status(id)).isEqualTo("PROPOSED");
        assertThat(availableKg(annId)).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("refus du voyageur : DECLINED, kilos rendus, événement Declined")
    void decline_byTraveler_releasesAndPublishesClosed() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID id = service.create(bidId, sender.getFirebaseUid(), kg("3")).amendment().id();

        BidAmendmentResponse view = service.decline(bidId, id, traveler.getFirebaseUid());

        assertThat(view.status()).isEqualTo("DECLINED");
        assertThat(view.netAmount()).isEqualByComparingTo("30.00");
        assertThat(availableKg(annId)).isEqualByComparingTo("6.00");
        assertThat(events.stream(BidAmendmentClosedEvent.Declined.class)).singleElement().satisfies(e -> {
            assertThat(e.amendmentId()).isEqualTo(id);
            assertThat(e.closedReason()).isEqualTo(BidAmendmentClosedReason.DECLINED_BY_TRAVELER);
            assertThat(e.rail()).isEqualTo(BidSupplementRail.CASH);
        });
        assertThat(auditActions(id)).containsExactly("BID_AMENDMENT_REQUESTED", "BID_AMENDMENT_DECLINED");
    }

    @Test
    @DisplayName("refus par l'expéditeur : 403")
    void decline_bySender_403() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID id = service.create(bidId, sender.getFirebaseUid(), kg("3")).amendment().id();

        assertThatThrownBy(() -> service.decline(bidId, id, sender.getFirebaseUid()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("refus d'une demande échue : 409 amendment-expired, kilos toujours réservés")
    void decline_expired_409() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID id = service.create(bidId, sender.getFirebaseUid(), kg("3")).amendment().id();
        lapse(id);

        assertThatThrownBy(() -> service.decline(bidId, id, traveler.getFirebaseUid()))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("amendment-expired");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThat(status(id)).isEqualTo("PROPOSED");
        assertThat(availableKg(annId)).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("acceptation : déléguée au rail, ACCEPTED, kilos gardés, événement Accepted et audit")
    void accept_delegatesToRail_andMarksAccepted() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID id = service.create(bidId, sender.getFirebaseUid(), kg("3")).amendment().id();

        AmendmentAcceptResponse accepted = service.accept(bidId, id, traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(null, null));

        assertThat(accepted.amendment().status()).isEqualTo("ACCEPTED");
        assertThat(accepted.amendment().blocksHandover()).isFalse();
        assertThat(availableKg(annId)).isEqualByComparingTo("3.00");
        assertThat(jdbc.queryForObject("SELECT accepted_at IS NOT NULL AND responded_at IS NOT NULL "
                + "FROM bid_amendments WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(events.stream(BidAmendmentAcceptedEvent.class)).singleElement()
                .satisfies(e -> assertThat(e.needsPayment()).isFalse());
        assertThat(auditActions(id)).containsExactly("BID_AMENDMENT_REQUESTED", "BID_AMENDMENT_ACCEPTED");

        // Acceptée : ni refus, ni annulation par l'expéditeur.
        assertThatThrownBy(() -> service.decline(bidId, id, traveler.getFirebaseUid()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-pending"));
        assertThatThrownBy(() -> service.cancel(bidId, id, sender.getFirebaseUid()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-pending"));
    }

    @Test
    @DisplayName("acceptation d'une demande échue : 409 amendment-expired")
    void accept_expired_409() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID id = service.create(bidId, sender.getFirebaseUid(), kg("3")).amendment().id();
        lapse(id);

        assertThatThrownBy(() -> service.accept(bidId, id, traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(null, null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-expired"));
    }

    // ── Expiration ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("expiration d'une demande échue : EXPIRED, kilos rendus, événement Expired")
    void expire_proposed_releasesAndExpires() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID id = service.create(bidId, sender.getFirebaseUid(), kg("3")).amendment().id();
        assertThat(service.expire(id)).as("pas encore échue").isFalse();
        lapse(id);

        assertThat(service.expire(id)).isTrue();

        assertThat(status(id)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT closed_reason FROM bid_amendments WHERE id = ?", String.class, id))
                .isEqualTo("EXPIRED");
        assertThat(availableKg(annId)).isEqualByComparingTo("6.00");
        assertThat(events.stream(BidAmendmentClosedEvent.Expired.class)).hasSize(1);
        assertThat(auditActions(id)).containsExactly("BID_AMENDMENT_REQUESTED", "BID_AMENDMENT_EXPIRED");
    }

    @Test
    @DisplayName("expiration rejouée : une seule clôture, kilos rendus une seule fois")
    void expire_isIdempotent() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID id = service.create(bidId, sender.getFirebaseUid(), kg("3")).amendment().id();
        lapse(id);

        assertThat(service.expire(id)).isTrue();
        assertThat(service.expire(id)).isFalse();
        assertThat(service.expire(UUID.randomUUID())).isFalse();

        assertThat(availableKg(annId)).isEqualByComparingTo("6.00");
        assertThat(events.stream(BidAmendmentClosedEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("autorisation carte jamais confirmée : CANCELLED (AUTHORIZATION_NOT_CONFIRMED) après la fenêtre")
    void expire_authorizationPending_cancelsAfterWindow() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID id = service.create(bidId, sender.getFirebaseUid(), kg("3")).amendment().id();
        jdbc.update("UPDATE bid_amendments SET status = 'AUTHORIZATION_PENDING' WHERE id = ?", id);
        assertThat(service.expire(id)).as("dans la fenêtre d'autorisation").isFalse();
        jdbc.update("UPDATE bid_amendments SET created_at = created_at - interval '31 minutes' WHERE id = ?", id);

        assertThat(service.expire(id)).isTrue();

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT closed_reason FROM bid_amendments WHERE id = ?", String.class, id))
                .isEqualTo("AUTHORIZATION_NOT_CONFIRMED");
        assertThat(availableKg(annId)).isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("paiement mobile money non reçu : EXPIRED (PAYMENT_NOT_RECEIVED) après paymentExpiresAt")
    void expire_paymentPending_expiresAfterPaymentDeadline() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID id = service.create(bidId, sender.getFirebaseUid(), kg("3")).amendment().id();
        // PAYMENT_PENDING n'existe qu'en mobile money (CHECK chk_bid_amendments_no_payout_cash) : sans
        // composant pawaPay livré, rien n'empêche l'expiration.
        jdbc.update("UPDATE bid_amendments SET rail = 'PAWAPAY', status = 'PAYMENT_PENDING', "
                + "payment_expires_at = created_at + interval '10 minutes' WHERE id = ?", id);
        assertThat(service.expire(id)).isFalse();
        jdbc.update("UPDATE bid_amendments SET payment_expires_at = created_at - interval '1 minute' WHERE id = ?", id);

        assertThat(service.expire(id)).isTrue();

        assertThat(status(id)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT closed_reason FROM bid_amendments WHERE id = ?", String.class, id))
                .isEqualTo("PAYMENT_NOT_RECEIVED");
    }

    @Test
    @DisplayName("commission espèces jamais confirmée : CANCELLED (COMMISSION_NOT_CONFIRMED) après la fenêtre")
    void expire_commissionPending_cancelsAfterWindow() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        UUID id = service.create(bidId, sender.getFirebaseUid(), kg("3")).amendment().id();
        jdbc.update("UPDATE bid_amendments SET status = 'COMMISSION_PENDING', "
                + "responded_at = created_at WHERE id = ?", id);
        assertThat(service.expire(id)).isFalse();
        jdbc.update("UPDATE bid_amendments SET responded_at = responded_at - interval '31 minutes' WHERE id = ?", id);

        assertThat(service.expire(id)).isTrue();

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT closed_reason FROM bid_amendments WHERE id = ?", String.class, id))
                .isEqualTo("COMMISSION_NOT_CONFIRMED");
        assertThat(availableKg(annId)).isEqualByComparingTo("6.00");
    }

    // ── Vues ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("vue expéditeur : JSON sans net, commission ni statut de commission ; voyageur : tout")
    void views_senderNeverSeesNet() throws Exception {
        UUID annId = persistAnnouncement("6.00", true);
        UUID bidId = persistAcceptedBid(annId, sender.getId());
        AmendmentCreateResponse created = service.create(bidId, sender.getFirebaseUid(), new CreateAmendmentRequest(
                new BigDecimal("1"), List.of(new AmendmentLineRequest(gridItemId(annId), 1)), PaymentMethod.CASH));
        UUID id = created.amendment().id();
        jdbc.update("UPDATE bid_amendments SET commission_status = 'CHARGED' WHERE id = ?", id);

        JsonNode senderJson = objectMapper.readTree(objectMapper.writeValueAsString(created));
        assertThat(senderJson.at("/amendment/grossAmount").isNull()).isFalse();
        assertThat(senderJson.at("/amendment").hasNonNull("netAmount")).isFalse();
        assertThat(senderJson.at("/amendment").hasNonNull("commissionAmount")).isFalse();
        assertThat(senderJson.at("/amendment").hasNonNull("commissionStatus")).isFalse();
        assertThat(senderJson.at("/amendment/items/0").hasNonNull("netAmount")).isFalse();
        assertThat(senderJson.at("/amendment/items/0").hasNonNull("grossAmount")).isTrue();

        BidAmendmentResponse senderCancelled = service.cancel(bidId, id, sender.getFirebaseUid());
        JsonNode cancelledJson = objectMapper.readTree(objectMapper.writeValueAsString(senderCancelled));
        assertThat(cancelledJson.hasNonNull("netAmount")).isFalse();
        assertThat(cancelledJson.hasNonNull("commissionAmount")).isFalse();
        assertThat(cancelledJson.hasNonNull("commissionStatus")).isFalse();

        UUID id2 = service.create(bidId, sender.getFirebaseUid(), kg("1")).amendment().id();
        BidAmendmentResponse travelerView = service.decline(bidId, id2, traveler.getFirebaseUid());
        assertThat(travelerView.netAmount()).isEqualByComparingTo("10.00");
        assertThat(travelerView.commissionAmount()).isEqualByComparingTo("1.20");
    }

    // ── Garde-fous ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("colis inconnu : 404 ; modification inconnue : 404 amendment-not-found")
    void unknownBidOrAmendment_404() {
        UUID annId = persistAnnouncement("6.00", false);
        UUID bidId = persistAcceptedBid(annId, sender.getId());

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), sender.getFirebaseUid(), kg("1")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.cancel(bidId, UUID.randomUUID(), sender.getFirebaseUid()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-found"));
    }

    // ── Outils ──────────────────────────────────────────────────────────────

    private Thread race(CyclicBarrier start, UUID bidId, UserEntity who, List<UUID> created, List<Throwable> errors) {
        return new Thread(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                created.add(service.create(bidId, who.getFirebaseUid(), kg("3")).amendment().id());
            } catch (Throwable t) {
                errors.add(t);
            }
        });
    }

    /**
     * Échéance de réponse passée. Décalage relatif aux colonnes écrites par Hibernate (UTC via
     * {@code hibernate.jdbc.time_zone}), jamais {@code now()} : le fuseau de la JVM fausserait la comparaison.
     */
    private void lapse(UUID id) {
        jdbc.update("UPDATE bid_amendments SET expires_at = created_at - interval '1 minute' WHERE id = ?", id);
    }

    private static CreateAmendmentRequest kg(String kg) {
        return new CreateAmendmentRequest(new BigDecimal(kg), null, PaymentMethod.CASH);
    }

    private BigDecimal availableKg(UUID annId) {
        return jdbc.queryForObject("SELECT available_kg FROM announcements WHERE id = ?", BigDecimal.class, annId);
    }

    private BigDecimal reservedKg(UUID id) {
        return jdbc.queryForObject("SELECT capacity_reserved_kg FROM bid_amendments WHERE id = ?", BigDecimal.class, id);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM bid_amendments WHERE id = ?", String.class, id);
    }

    private int amendmentCount(UUID bidId) {
        return jdbc.queryForObject("SELECT count(*) FROM bid_amendments WHERE bid_id = ?", Integer.class, bidId);
    }

    private List<String> auditActions(UUID id) {
        return jdbc.queryForList("SELECT action FROM audit_log WHERE entity_id = ? ORDER BY created_at, action",
                String.class, id);
    }

    private UUID gridItemId(UUID annId) {
        return jdbc.queryForObject("SELECT id FROM announcement_price_grid_items WHERE announcement_id = ?",
                UUID.class, annId);
    }

    private UserEntity persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("amendment-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    /** Trajet espèces à 10 €/kg ; {@code withGrid} : un article « Téléphone » à 15 € net. */
    private UUID persistAnnouncement(String availableKg, boolean withGrid) {
        return tx.execute(status -> {
            AnnouncementEntity announcement = new AnnouncementEntity();
            announcement.setTravelerId(traveler.getId());
            announcement.setDepartureCity("Paris");
            announcement.setArrivalCity("Dakar");
            announcement.setDepartureDate(LocalDate.now().plusDays(10));
            announcement.setTransportMode(TransportMode.PLANE);
            announcement.setPickupAddressLabel("Gare du Nord, Paris");
            announcement.setPickupLat(new BigDecimal("48.880756"));
            announcement.setPickupLng(new BigDecimal("2.354987"));
            announcement.setDeliveryAddressLabel("Aéroport de Dakar");
            announcement.setDeliveryLat(new BigDecimal("14.670833"));
            announcement.setDeliveryLng(new BigDecimal("-17.073056"));
            announcement.setAvailableKg(new BigDecimal(availableKg));
            announcement.setTotalKg(new BigDecimal("60.00"));
            announcement.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
            announcement.setPricePerKg(new BigDecimal("10.00"));
            announcement.setCurrency("EUR");
            announcement.setTimezone("Europe/Paris");
            announcement.setStatus(AnnouncementStatus.ACTIVE);
            announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH, PaymentMethod.STRIPE));
            entityManager.persist(announcement);
            if (withGrid) {
                AnnouncementPriceGridItemEntity item = new AnnouncementPriceGridItemEntity();
                item.setAnnouncementId(announcement.getId());
                item.setLabel("Téléphone");
                item.setUnitPriceNet(new BigDecimal("15.00"));
                item.setPosition(0);
                entityManager.persist(item);
            }
            return announcement.getId();
        });
    }

    private UUID persistAcceptedBid(UUID announcementId, UUID senderId) {
        return tx.execute(status -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(senderId);
            bid.setPaymentMethod(PaymentMethod.CASH);
            bid.setCurrency("EUR");
            bid.setWeightKg(new BigDecimal("2"));
            bid.setCommissionRate(new BigDecimal("0.12"));
            bid.setStatus(BidStatus.ACCEPTED);
            bid.setQrToken("qr-" + UUID.randomUUID());
            bid.setTrackingNumber("DON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            entityManager.persist(bid);
            return bid.getId();
        });
    }
}
