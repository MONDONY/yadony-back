package com.yadony.api.payments.wallet;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import com.yadony.api.common.deletion.UserDeletionImpactContributor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Le RGPD self-service a délibérément cessé de bloquer sur un solde positif : refuser
 * l'effacement à quelqu'un parce qu'il lui reste de l'argent serait contraire à son droit,
 * un ticket de remboursement est ouvert à la place.
 *
 * <p>Ici c'est l'inverse, et c'est volontaire : la plateforme prend l'initiative, elle ne peut
 * pas effacer un compte qu'elle doit encore créditer.
 */
@Component
public class WalletDeletionImpactContributor implements UserDeletionImpactContributor {

    private static final List<WalletRefundRequestStatus> UNRESOLVED =
            List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING);

    private final WalletAccountRepository accountRepository;
    private final WalletRefundRequestRepository refundRepository;

    public WalletDeletionImpactContributor(WalletAccountRepository accountRepository,
                                           WalletRefundRequestRepository refundRepository) {
        this.accountRepository = accountRepository;
        this.refundRepository = refundRepository;
    }

    @Override
    public List<ImpactFinding> contribute(UUID userId) {
        List<ImpactFinding> findings = new ArrayList<>();

        long credited = accountRepository.findAllByUserId(userId).stream()
                .filter(a -> a.getBalance() != null && a.getBalance().compareTo(BigDecimal.ZERO) > 0)
                .count();
        if (credited > 0) {
            findings.add(ImpactFinding.plain(
                    ImpactSeverity.BLOCKING, "WALLET_POSITIVE_BALANCE", Math.toIntExact(credited)));
        }

        int unresolved = UNRESOLVED.stream()
                .mapToInt(status -> refundRepository.findAllByUserIdAndStatus(userId, status).size())
                .sum();
        if (unresolved > 0) {
            findings.add(ImpactFinding.plain(
                    ImpactSeverity.BLOCKING, "WALLET_REFUND_PENDING", unresolved));
        }

        return findings;
    }
}
