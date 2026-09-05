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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PawapayOperationRepositoryTest {

    @Autowired private PawapayOperationRepository repository;
    @Autowired private JdbcTemplate jdbc;

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

    // Revue ronde 1, point 1 (CRITIQUE) : markSubmittedIfStillCreated est le UPDATE gardé
    // qui remplace le read-modify-write de markSubmitted. Preuve directe, base réelle
    // (H2), que la garde WHERE status = CREATED est bien ce qui protège cette paire —
    // pas @Version, jamais incrémenté par applyTransition (bulk JPQL).

    @Test
    void markSubmittedIfStillCreated_onlyFromCreated() {
        PawapayOperationEntity o = op(UUID.randomUUID(), PawapayOperationKind.DEPOSIT);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // Un callback (applyTransition, bulk JPQL) fait sortir la ligne de CREATED sans
        // toucher version : c'est précisément la condition du désastre décrit en revue.
        int movedByCallback = repository.applyTransition(o.getId(), PawapayOperationStatus.COMPLETED, null, null,
                "ptx", null, "{}", now, null, now, now);
        assertThat(movedByCallback).isEqualTo(1);

        int result = repository.markSubmittedIfStillCreated(o.getId(), PawapayOperationStatus.ACCEPTED, now,
                null, null, null, now);

        assertThat(result).as("la ligne n'est plus CREATED, markSubmitted ne doit rien écraser").isZero();
        assertThat(repository.findById(o.getId()).orElseThrow().getStatus())
                .as("le statut posé par le callback doit survivre").isEqualTo(PawapayOperationStatus.COMPLETED);
    }

    @Test
    void markSubmittedIfStillCreated_fromCreated_succeeds_andSetsFinalizedAtOnlyWhenRejected() {
        PawapayOperationEntity accepted = op(UUID.randomUUID(), PawapayOperationKind.PAYOUT);
        PawapayOperationEntity rejected = op(UUID.randomUUID(), PawapayOperationKind.PAYOUT);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        int acceptedResult = repository.markSubmittedIfStillCreated(accepted.getId(), PawapayOperationStatus.ACCEPTED,
                now, null, null, null, now);
        int rejectedResult = repository.markSubmittedIfStillCreated(rejected.getId(), PawapayOperationStatus.SUBMIT_REJECTED,
                now, "PROVIDER_TEMPORARILY_UNAVAILABLE", "down", now, now);

        assertThat(acceptedResult).isEqualTo(1);
        assertThat(rejectedResult).isEqualTo(1);
        PawapayOperationEntity reloadedAccepted = repository.findById(accepted.getId()).orElseThrow();
        assertThat(reloadedAccepted.getStatus()).isEqualTo(PawapayOperationStatus.ACCEPTED);
        assertThat(reloadedAccepted.getSubmittedAt()).isNotNull();
        assertThat(reloadedAccepted.getFinalizedAt()).isNull();
        PawapayOperationEntity reloadedRejected = repository.findById(rejected.getId()).orElseThrow();
        assertThat(reloadedRejected.getStatus()).isEqualTo(PawapayOperationStatus.SUBMIT_REJECTED);
        assertThat(reloadedRejected.getFailureCode()).isEqualTo("PROVIDER_TEMPORARILY_UNAVAILABLE");
        assertThat(reloadedRejected.getFinalizedAt()).isNotNull();
    }

    // Revue ronde 1, point 5 : raw_callback transporte accountDetails.phoneNumber en
    // clair (callback tâche 9, réponse de statut tâche 10) — sans chiffrement, le
    // chiffrement de msisdn juste à côté serait décoratif.

    @Test
    void applyTransition_encryptsRawCallback_roundTripsThroughJpa() {
        PawapayOperationEntity o = op(UUID.randomUUID(), PawapayOperationKind.DEPOSIT);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String raw = "{\"status\":\"COMPLETED\",\"payer\":{\"accountDetails\":{\"phoneNumber\":\"221771234567\"}}}";

        repository.applyTransition(o.getId(), PawapayOperationStatus.COMPLETED, null, null, "ptx", null, raw,
                now, null, now, now);

        assertThat(repository.findById(o.getId()).orElseThrow().getRawCallback()).isEqualTo(raw);
    }

    @Test
    void applyTransition_rawCallback_isNotStoredInPlaintext() {
        PawapayOperationEntity o = op(UUID.randomUUID(), PawapayOperationKind.DEPOSIT);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String raw = "{\"status\":\"COMPLETED\",\"payer\":{\"accountDetails\":{\"phoneNumber\":\"221771234567\"}}}";

        repository.applyTransition(o.getId(), PawapayOperationStatus.COMPLETED, null, null, "ptx", null, raw,
                now, null, now, now);

        String storedColumn = jdbc.queryForObject(
                "SELECT raw_callback FROM pawapay_operations WHERE id = ?", String.class, o.getId());
        assertThat(storedColumn).as("colonne chiffrée : ne doit pas contenir le JSON en clair")
                .isNotEqualTo(raw)
                .doesNotContain("221771234567")
                .doesNotContain("phoneNumber");
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
