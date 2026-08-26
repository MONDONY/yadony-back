package com.yadony.api.payments;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import com.yadony.api.common.deletion.UserDeletionImpactContributor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Un paiement en séquestre engage l'argent d'un tiers et une obligation Stripe. Anonymiser le
 * compte le laisserait orphelin, sans personne à créditer ni à rembourser : c'est bloquant.
 */
@Component
public class PaymentDeletionImpactContributor implements UserDeletionImpactContributor {

    private final PaymentRepository paymentRepository;

    public PaymentDeletionImpactContributor(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Override
    public List<ImpactFinding> contribute(UUID userId) {
        if (!paymentRepository.hasActiveEscrowForUser(userId)) {
            return List.of();
        }
        return List.of(ImpactFinding.plain(ImpactSeverity.BLOCKING, "ACTIVE_ESCROW", 1));
    }
}
