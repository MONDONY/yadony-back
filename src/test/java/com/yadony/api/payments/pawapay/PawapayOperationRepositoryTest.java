package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PawapayOperationRepositoryTest {

    @Autowired private PawapayOperationRepository repository;

    private PawapayOperationEntity op(UUID paymentId, PawapayOperationKind kind) {
        return repository.saveAndFlush(new PawapayOperationEntity(UUID.randomUUID(), kind, paymentId, null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567"));
    }

    @Test
    void save_withAssignedId_insertsOnce_andMasksMsisdn() {
        UUID paymentId = UUID.randomUUID();
        PawapayOperationEntity saved = op(paymentId, PawapayOperationKind.DEPOSIT);
        assertThat(saved.getStatus()).isEqualTo(PawapayOperationStatus.CREATED);
        assertThat(saved.getMsisdnMasked()).isEqualTo("+221 •••• 67");
        assertThat(saved.getVersion()).isNotNull();
        assertThat(repository.findById(saved.getId())).get()
                .extracting(PawapayOperationEntity::getMsisdn).isEqualTo("221771234567");
    }

    @Test
    void applyTransition_movesOnlyFromNonFinal_andPublishesNothingItself() {
        PawapayOperationEntity o = op(UUID.randomUUID(), PawapayOperationKind.DEPOSIT);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        int first = repository.applyTransition(o.getId(), PawapayOperationStatus.PROCESSING, null, null,
                null, "https://wave.test/auth", "{\"status\":\"PROCESSING\"}", now, null, null, now);
        int second = repository.applyTransition(o.getId(), PawapayOperationStatus.COMPLETED, null, null,
                "ptx-1", null, "{\"status\":\"COMPLETED\"}", now, null, now, now);
        int third = repository.applyTransition(o.getId(), PawapayOperationStatus.FAILED, "X", "y",
                null, null, "{}", now, null, now, now);

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        assertThat(third).as("un état final est terminal").isZero();

        PawapayOperationEntity reloaded = repository.findById(o.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PawapayOperationStatus.COMPLETED);
        assertThat(reloaded.getAuthorizationUrl()).as("COALESCE garde l'URL Wave").isEqualTo("https://wave.test/auth");
        assertThat(reloaded.getProviderTransactionId()).isEqualTo("ptx-1");
        assertThat(reloaded.getFinalizedAt()).isNotNull();
    }

    @Test
    void liveLookup_ignoresDeadOperations() {
        UUID paymentId = UUID.randomUUID();
        PawapayOperationEntity dead = op(paymentId, PawapayOperationKind.PAYOUT);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        repository.applyTransition(dead.getId(), PawapayOperationStatus.FAILED, "INSUFFICIENT_BALANCE", "no funds",
                null, null, "{}", now, null, now, now);

        assertThat(repository.existsByPaymentIdAndKindAndStatusIn(paymentId, PawapayOperationKind.PAYOUT,
                PawapayOperationStatus.LIVE_OR_DONE)).isFalse();

        PawapayOperationEntity live = op(paymentId, PawapayOperationKind.PAYOUT);
        assertThat(repository.findFirstByPaymentIdAndKindAndStatusInOrderByCreatedAtDesc(paymentId,
                PawapayOperationKind.PAYOUT, PawapayOperationStatus.LIVE_OR_DONE))
                .get().extracting(PawapayOperationEntity::getId).isEqualTo(live.getId());
    }

    @Test
    void status_helpers() {
        assertThat(PawapayOperationStatus.COMPLETED.isFinal()).isTrue();
        assertThat(PawapayOperationStatus.FAILED.isFinal()).isTrue();
        assertThat(PawapayOperationStatus.SUBMIT_REJECTED.isFinal()).isTrue();
        assertThat(PawapayOperationStatus.ENQUEUED.isFinal()).isFalse();
        assertThat(PawapayOperationStatus.OPEN).isEqualTo(EnumSet.of(PawapayOperationStatus.CREATED,
                PawapayOperationStatus.ACCEPTED, PawapayOperationStatus.PROCESSING,
                PawapayOperationStatus.ENQUEUED, PawapayOperationStatus.IN_RECONCILIATION));
        assertThat(PawapayOperationStatus.fromApi("IN_RECONCILIATION")).contains(PawapayOperationStatus.IN_RECONCILIATION);
        assertThat(PawapayOperationStatus.fromApi("SOMETHING")).isEmpty();
        assertThat(PawapayOperationKind.DEPOSIT.idField()).isEqualTo("depositId");
        assertThat(PawapayOperationKind.REFUND.path()).isEqualTo("/v2/refunds");
    }
}
