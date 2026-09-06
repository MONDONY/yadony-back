package com.yadony.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaymentRepositoryMobileMoneyTest {

    @Autowired private PaymentRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private PaymentEntity pawapayPayment(PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setRail(PaymentRail.PAWAPAY);
        p.setStripePaymentIntentId(null);
        p.setAmount(new BigDecimal("15000"));
        p.setCommissionAmount(new BigDecimal("1800"));
        p.setCurrency("XOF");
        p.setStatus(status);
        return repository.saveAndFlush(p);
    }

    @Test
    void pawapayPayment_persistsWithoutPaymentIntent_andDefaultsRailToStripe() {
        PaymentEntity p = pawapayPayment(PaymentStatus.PENDING);
        assertThat(repository.findById(p.getId())).get()
                .satisfies(saved -> {
                    assertThat(saved.getRail()).isEqualTo(PaymentRail.PAWAPAY);
                    assertThat(saved.getStripePaymentIntentId()).isNull();
                });
        assertThat(new PaymentEntity().getRail()).isEqualTo(PaymentRail.STRIPE);
    }

    @Test
    void markEscrowIfPending_movesOnce_andRecordsCaptureTime() {
        PaymentEntity p = pawapayPayment(PaymentStatus.PENDING);
        assertThat(repository.markEscrowIfPending(p.getId(), Instant.now())).isEqualTo(1);
        assertThat(repository.markEscrowIfPending(p.getId(), Instant.now())).isZero();
        String status = jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, p.getId());
        Object capturedAt = jdbc.queryForObject("SELECT captured_at FROM payments WHERE id = ?", Object.class, p.getId());
        assertThat(status).isEqualTo("ESCROW");
        assertThat(capturedAt).isNotNull();
    }

    @Test
    void markCancelledIfPending_onlyFromPending() {
        PaymentEntity pending = pawapayPayment(PaymentStatus.PENDING);
        PaymentEntity escrow = pawapayPayment(PaymentStatus.ESCROW);
        assertThat(repository.markCancelledIfPending(pending.getId())).isEqualTo(1);
        assertThat(repository.markCancelledIfPending(escrow.getId())).isZero();
    }

    // ── Un claim atomique ne doit JAMAIS être suivi d'une mutation de l'entité gérée chargée
    // en amont : PaymentEntity n'a ni @DynamicUpdate ni @Version, un setter la rend sale et le
    // flush régénère un UPDATE de TOUTES les colonnes avec les valeurs en mémoire, écrasant le
    // claim. Les deux tests ci-dessous fixent les deux seules façons sûres de continuer après
    // un claim : ne rien toucher, ou relire l'entité par entityManager.refresh. ───────────────

    /**
     * Sans setter après le claim, rien n'est re-flushé : le {@code RELEASED} posé par
     * {@code markReleasedIfEscrow} survit au flush final malgré le snapshot {@code ESCROW} que
     * l'entité {@code p} garde en mémoire (bulk JPQL sans {@code clearAutomatically}).
     */
    @Test
    void markReleasedIfEscrow_withoutTouchingTheEntity_doesNotRevertStatus() {
        PaymentEntity p = pawapayPayment(PaymentStatus.ESCROW);

        assertThat(repository.markReleasedIfEscrow(p.getId(), LocalDateTime.now(ZoneOffset.UTC))).isEqualTo(1);
        repository.flush();

        String status = jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, p.getId());
        assertThat(status).isEqualTo("RELEASED");
        assertThat(p.getStatus()).as("snapshot périmé en mémoire, jamais ré-écrit").isEqualTo(PaymentStatus.ESCROW);
    }

    /**
     * {@code entityManager.refresh(p)} relit la ligne réelle DANS la même transaction (elle y
     * voit ses propres écritures non commitées) et rend l'entité à nouveau PROPRE : Hibernate
     * n'a plus rien à réécrire pour elle. Contrairement à une liste de {@code p.setXxx(...)} —
     * qui salit l'entité et ne protège que les colonnes explicitement listées — un refresh
     * couvre TOUTES les colonnes, présentes et futures. La preuve porte sur
     * {@code Statistics#getEntityUpdateCount()} : aucun UPDATE supplémentaire n'est généré au
     * flush qui suit le refresh. C'est le geste des endpoints admin (force-release, refund)
     * avant de construire leur réponse.
     */
    @Test
    void markReleasedIfEscrow_thenRefresh_generatesNoUpdate() {
        PaymentEntity p = pawapayPayment(PaymentStatus.ESCROW);

        assertThat(repository.markReleasedIfEscrow(p.getId(), LocalDateTime.now(ZoneOffset.UTC))).isEqualTo(1);

        Statistics stats = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        long updatesBeforeRefresh = stats.getEntityUpdateCount();

        entityManager.refresh(p);
        repository.flush(); // matérialise toute écriture Hibernate encore en attente sur l'entité

        assertThat(stats.getEntityUpdateCount())
                .as("refresh doit rendre l'entité propre : aucun UPDATE ne doit être régénéré au flush qui suit")
                .isEqualTo(updatesBeforeRefresh);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(p.getEscrowReleasedAt()).isNotNull();
    }

    @Test
    void rawInsertWithoutRail_stillWorks_thanksToColumnDefault() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, bid_id, stripe_payment_intent_id, amount, currency, "
                + "commission_amount, status, legacy_destination_charge, disputed, created_at, updated_at) "
                + "VALUES (?, ?, ?, 30.00, 'EUR', 3.60, 'PENDING', false, false, NOW(), NOW())",
                id, UUID.randomUUID(), "pi_" + id);
        String rail = jdbc.queryForObject("SELECT rail FROM payments WHERE id = ?", String.class, id);
        assertThat(rail).isEqualTo("STRIPE");
    }
}
