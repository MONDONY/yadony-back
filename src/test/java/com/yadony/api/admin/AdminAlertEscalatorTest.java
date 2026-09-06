package com.yadony.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.common.stripe.AdminAlertService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Déduplication commune des alertes du rail mobile money. Le {@code TransactionTemplate} est
 * réel, construit sur un gestionnaire MOCKÉ : {@code getTransaction(...)} rend null, la callback
 * s'exécute quand même, {@code commit(null)} est un no-op — suffisant pour vérifier ce qui est
 * écrit et sous quelle propagation.
 */
@ExtendWith(MockitoExtension.class)
class AdminAlertEscalatorTest {

    @Mock AdminAlertRepository repository;
    @Mock AdminAlertService alerts;
    @Mock PlatformTransactionManager transactionManager;

    private AdminAlertEscalator escalator;

    @BeforeEach
    void setUp() {
        escalator = new AdminAlertEscalator(repository, alerts, new ObjectMapper(), transactionManager);
    }

    @Test
    void raiseOnce_persistsTheDedupRow_thenRaises() {
        String type = "MM_PAYOUT_ORPHAN_" + UUID.randomUUID();
        when(repository.findByTypeAndResolved(type, false)).thenReturn(List.of());

        boolean raised = escalator.raiseOnce(type, "Payout déjà en vol", Map.of("paymentId", "p-1", "source", "delivery"));

        assertThat(raised).isTrue();
        ArgumentCaptor<AdminAlertEntity> saved = ArgumentCaptor.forClass(AdminAlertEntity.class);
        InOrder order = inOrder(repository, alerts);
        order.verify(repository).save(saved.capture());
        order.verify(alerts).raise(eq(type), eq("Payout déjà en vol"), any());
        assertThat(saved.getValue().getType()).isEqualTo(type);
        assertThat(saved.getValue().isResolved()).isFalse();
        assertThat(saved.getValue().getPayload()).contains("\"paymentId\":\"p-1\"").contains("\"source\":\"delivery\"");
    }

    /**
     * Une alerte non résolue du même type bloque la suivante — quel que soit le contenu de son
     * payload : PostgreSQL reformate un {@code jsonb} à la relecture (espace après chaque « : »,
     * ordre des clés non garanti), un marqueur de sous-chaîne ne matcherait plus jamais.
     */
    @Test
    void raiseOnce_skipsWhenAnUnresolvedAlertOfTheSameTypeExists_regardlessOfPayload() {
        String type = "PAWAPAY_BALANCE_LOW_XOF";
        AdminAlertEntity existing = new AdminAlertEntity();
        existing.setType(type);
        existing.setPayload("{\"balance\": \"10\", \"currency\": \"XOF\", \"threshold\": \"100000\"}");
        when(repository.findByTypeAndResolved(type, false)).thenReturn(List.of(existing));

        boolean raised = escalator.raiseOnce(type, "Solde bas", Map.of("currency", "XOF"));

        assertThat(raised).isFalse();
        verify(repository, never()).save(any());
        verifyNoInteractions(alerts);
    }

    /**
     * Deux appels consécutifs pour le même incident (un scheduler qui resélectionne la même
     * ligne à chaque tick, une relance admin répétée) : la ligne posée par le premier bloque le
     * second.
     */
    @Test
    void raiseOnce_raisesOnlyOnce_acrossTwoConsecutiveCalls() {
        String type = "MM_EXP_NO_PAYMENT_" + UUID.randomUUID();
        when(repository.findByTypeAndResolved(type, false))
                .thenReturn(List.of())
                .thenReturn(List.of(new AdminAlertEntity()));

        assertThat(escalator.raiseOnce(type, "x", Map.of("bidId", "b"))).isTrue();
        assertThat(escalator.raiseOnce(type, "x", Map.of("bidId", "b"))).isFalse();

        verify(repository).save(any());
        verify(alerts).raise(eq(type), any(), any());
    }

    /**
     * La ligne de dédup est écrite dans une transaction INDÉPENDANTE ({@code REQUIRES_NEW}) :
     * appelée juste avant un {@code throw} (transaction ambiante annulée), elle survit quand
     * même — sinon la recherche suivante retrouverait toujours zéro ligne et la dédup serait
     * inerte tout en paraissant présente.
     */
    @Test
    void raiseOnce_writesTheDedupRow_inItsOwnRequiresNewTransaction() {
        String type = "PAWAPAY_REFUND_REJECTED_" + UUID.randomUUID();
        when(repository.findByTypeAndResolved(type, false)).thenReturn(List.of());

        escalator.raiseOnce(type, "x", Map.of());

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * {@code admin_alerts.type} est {@code VARCHAR(60)} : un type trop long ferait échouer
     * l'INSERT en silence dans le {@code catch} de boucle des schedulers appelants. Refusé ici,
     * bruyamment, avant toute écriture.
     */
    @Test
    void raiseOnce_rejectsATypeLongerThanTheColumn() {
        String tooLong = "MM_EXPIRE_DEPOSIT_COMPLETED_" + UUID.randomUUID(); // 64

        assertThatThrownBy(() -> escalator.raiseOnce(tooLong, "x", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("admin_alerts.type");
        verifyNoInteractions(repository, alerts);
    }
}
