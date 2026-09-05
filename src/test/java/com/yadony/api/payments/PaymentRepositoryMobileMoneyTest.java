package com.yadony.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
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
    void markEscrowIfPending_movesOnce_andRecordsDepositId() {
        PaymentEntity p = pawapayPayment(PaymentStatus.PENDING);
        UUID opId = UUID.randomUUID();
        assertThat(repository.markEscrowIfPending(p.getId(), opId, Instant.now())).isEqualTo(1);
        assertThat(repository.markEscrowIfPending(p.getId(), UUID.randomUUID(), Instant.now())).isZero();
        String status = jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, p.getId());
        UUID stored = jdbc.queryForObject("SELECT pawapay_deposit_id FROM payments WHERE id = ?", UUID.class, p.getId());
        assertThat(status).isEqualTo("ESCROW");
        assertThat(stored).isEqualTo(opId);
    }

    @Test
    void markCancelledIfPending_onlyFromPending() {
        PaymentEntity pending = pawapayPayment(PaymentStatus.PENDING);
        PaymentEntity escrow = pawapayPayment(PaymentStatus.ESCROW);
        assertThat(repository.markCancelledIfPending(pending.getId())).isEqualTo(1);
        assertThat(repository.markCancelledIfPending(escrow.getId())).isZero();
    }

    // ── Ronde 1, point 1 : le claim atomique markReleasedIfEscrow ne doit JAMAIS être suivi
    // d'une mutation de l'entité gérée chargée en amont (voir MobileMoneyPayoutInitiator) ────

    /**
     * Reproduit EXACTEMENT le défaut trouvé en revue : {@code p} est l'entité gérée renvoyée par
     * {@code saveAndFlush} (snapshot Hibernate {@code status = ESCROW}). {@code markReleasedIfEscrow}
     * est un bulk JPQL {@code @Modifying} SANS {@code clearAutomatically} : la base passe
     * {@code RELEASED}, mais {@code p} reste {@code ESCROW} en mémoire. Un setter sur ce {@code p}
     * après coup le rend sale ; au flush, Hibernate (pas de {@code @DynamicUpdate} sur
     * {@code PaymentEntity}) régénère un UPDATE de TOUTES les colonnes avec les valeurs en
     * mémoire — {@code status = 'ESCROW'} écrase silencieusement le {@code RELEASED} qui vient
     * d'être posé. Constaté rouge (voir task-16-report.md, section Ronde 1) avec
     * {@code p.setPawapayPayoutId(opId)} à la place de l'appel ci-dessous ; corrigé en
     * remplaçant cette mutation par {@link PaymentRepository#attachPayoutId}, qui n'écrit QUE la
     * colonne visée et ne touche jamais l'état Java de l'entité.
     */
    @Test
    void markReleasedIfEscrow_thenAttachPayoutId_doesNotRevertStatus() {
        PaymentEntity p = pawapayPayment(PaymentStatus.ESCROW);
        UUID opId = UUID.randomUUID();

        assertThat(repository.markReleasedIfEscrow(p.getId(), LocalDateTime.now(ZoneOffset.UTC))).isEqualTo(1);
        // Constaté rouge (voir task-16-report.md, Ronde 1) avec p.setPawapayPayoutId(opId) à la
        // place de la ligne ci-dessous : "expected RELEASED but was ESCROW" — le setter sur
        // l'entité gérée redevenait sale et écrasait le RELEASED au flush. L'UPDATE ciblé ne
        // touche jamais l'état Java de l'entité : rien à re-flusher.
        repository.attachPayoutId(p.getId(), opId);
        repository.flush();

        String status = jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, p.getId());
        UUID stored = jdbc.queryForObject("SELECT pawapay_payout_id FROM payments WHERE id = ?", UUID.class, p.getId());
        assertThat(status).isEqualTo("RELEASED");
        assertThat(stored).isEqualTo(opId);
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
