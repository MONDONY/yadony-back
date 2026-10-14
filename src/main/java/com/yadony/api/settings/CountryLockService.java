package com.yadony.api.settings;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.StripeReleaseIntentRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Verrou du pays de résidence. Le pays d'un compte Stripe Connect est immuable chez Stripe :
 * une fois le compte engagé, changer de pays côté yadony produirait une divergence
 * irrattrapable. Aucune colonne dédiée : l'état se dérive, pour ne pas dupliquer une source
 * de vérité.
 *
 * <p><b>Règle.</b> Le pays est verrouillé si et seulement si l'utilisateur porte un compte
 * Connect ({@code users.stripe_account_id} non vide) ET que ce compte est engagé :
 * <ul>
 *   <li>son statut n'est pas {@code PENDING_ONBOARDING} : {@code ONBOARDING_COMPLETE}, mais
 *       aussi {@code DISABLED} et {@code REJECTED} (Stripe a examiné ou coupé le compte, une
 *       vérification y est attachée) et tout état incohérent (identifiant présent avec
 *       {@code NOT_CREATED}) : par prudence, seul l'état en attente se libère ;</li>
 *   <li>ou l'onboarding a été terminé au moins une fois ({@code stripe_onboarding_completed_at}
 *       non nul : un compte revenu en attente parce que Stripe réclame une pièce reste engagé) ;</li>
 *   <li>ou de l'argent a pu y être dirigé : un versement carte ({@code stripe_release_intents})
 *       vise ce compte, ou un paiement carte a l'utilisateur pour voyageur bénéficiaire.</li>
 * </ul>
 * Un compte {@code PENDING_ONBOARDING} sans aucun de ces engagements (l'utilisateur a tapé
 * « Activer les paiements par carte » sans finir l'onboarding Stripe) ne verrouille pas : il
 * est détaché au changement de pays ({@code ResidenceCountryChangedEvent}) et un nouveau
 * compte sera créé dans le bon pays à la prochaine activation.
 */
@Component
public class CountryLockService {

    private final UserRepository userRepository;
    private final StripeReleaseIntentRepository releaseIntentRepository;
    private final PaymentRepository paymentRepository;

    public CountryLockService(UserRepository userRepository,
                              StripeReleaseIntentRepository releaseIntentRepository,
                              PaymentRepository paymentRepository) {
        this.userRepository = userRepository;
        this.releaseIntentRepository = releaseIntentRepository;
        this.paymentRepository = paymentRepository;
    }

    public boolean isLocked(UUID userId) {
        return userRepository.findById(userId).map(this::isLocked).orElse(false);
    }

    /**
     * Compte Connect en attente qu'un changement de pays détacherait : présent et non
     * verrouillant. Vide sans compte, ou quand le compte verrouille le pays.
     */
    public Optional<String> detachablePendingStripeAccountId(UUID userId) {
        return userRepository.findById(userId)
                .filter(user -> hasStripeAccount(user) && !isLocked(user))
                .map(UserEntity::getStripeAccountId);
    }

    private boolean isLocked(UserEntity user) {
        if (!hasStripeAccount(user)) {
            return false;
        }
        if (user.getStripeAccountStatus() != StripeAccountStatus.PENDING_ONBOARDING
                || user.getStripeOnboardingCompletedAt() != null) {
            return true;
        }
        return releaseIntentRepository.existsByDestination(user.getStripeAccountId())
                || paymentRepository.existsCardPaymentForTraveler(user.getId());
    }

    private static boolean hasStripeAccount(UserEntity user) {
        String accountId = user.getStripeAccountId();
        return accountId != null && !accountId.isBlank();
    }
}
