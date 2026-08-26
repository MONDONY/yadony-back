package com.yadony.api.payments.wallet;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletDeletionImpactContributorTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock WalletAccountRepository accountRepository;
    @Mock WalletRefundRequestRepository refundRepository;

    private WalletDeletionImpactContributor contributor() {
        return new WalletDeletionImpactContributor(accountRepository, refundRepository);
    }

    private WalletAccountEntity accountWith(String balance) {
        WalletAccountEntity account = new WalletAccountEntity();
        account.setUserId(USER_ID);
        account.setBalance(new BigDecimal(balance));
        account.setCurrency("EUR");
        return account;
    }

    @Test
    @DisplayName("un solde positif bloque la suppression")
    void positiveBalance_isBlocking() {
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(accountWith("12.50")));
        // L'implémentation interroge systématiquement les deux repositories pour produire un rapport
        // complet d'emblée — ce stub est donc effectivement appelé, pas lenient().
        when(refundRepository.findAllByUserIdAndStatus(any(), any())).thenReturn(List.of());

        List<ImpactFinding> findings = contributor().contribute(USER_ID);

        assertThat(findings).extracting(ImpactFinding::code).contains("WALLET_POSITIVE_BALANCE");
        assertThat(findings.getFirst().severity()).isEqualTo(ImpactSeverity.BLOCKING);
    }

    @Test
    @DisplayName("un solde nul ne bloque pas")
    void zeroBalance_reportsNothing() {
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(accountWith("0.00")));
        when(refundRepository.findAllByUserIdAndStatus(any(), any())).thenReturn(List.of());

        assertThat(contributor().contribute(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("une demande de remboursement en attente bloque, et une seule fois par statut")
    void pendingRefund_isBlocking() {
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of());
        when(refundRepository.findAllByUserIdAndStatus(USER_ID, WalletRefundRequestStatus.PENDING))
                .thenReturn(List.of(new WalletRefundRequestEntity(), new WalletRefundRequestEntity()));
        when(refundRepository.findAllByUserIdAndStatus(USER_ID, WalletRefundRequestStatus.PROCESSING))
                .thenReturn(List.of());

        List<ImpactFinding> findings = contributor().contribute(USER_ID);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().code()).isEqualTo("WALLET_REFUND_PENDING");
        assertThat(findings.getFirst().count()).isEqualTo(2);
    }

    // Les deux statuts appellent la même action — attendre que le remboursement soit soldé.
    // Les distinguer à l'écran ajouterait du bruit sans changer la décision de l'administrateur.
    @Test
    @DisplayName("les demandes en attente et en cours de traitement sont un seul et même obstacle")
    void pendingAndProcessingRefunds_produceSingleFindingWithCombinedCount() {
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of());
        when(refundRepository.findAllByUserIdAndStatus(USER_ID, WalletRefundRequestStatus.PENDING))
                .thenReturn(List.of(new WalletRefundRequestEntity()));
        when(refundRepository.findAllByUserIdAndStatus(USER_ID, WalletRefundRequestStatus.PROCESSING))
                .thenReturn(List.of(new WalletRefundRequestEntity()));

        List<ImpactFinding> findings = contributor().contribute(USER_ID);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().code()).isEqualTo("WALLET_REFUND_PENDING");
        assertThat(findings.getFirst().count()).isEqualTo(2);
    }
}
