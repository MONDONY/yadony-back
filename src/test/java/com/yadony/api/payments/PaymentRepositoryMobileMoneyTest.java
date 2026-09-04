package com.yadony.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
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
