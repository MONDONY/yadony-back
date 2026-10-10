package com.yadony.api.activation;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Horodate la première vérification KYC : point de départ des relances « première action ».
 * Clôt aussi le parcours d'onboarding : l'identité en est la dernière étape, et l'app ne posait
 * {@code onboarding_seen_at} qu'en quittant l'écran KYC pendant le parcours. Une vérification
 * aboutie ailleurs (profil, webhook reçu app fermée) laissait des comptes complets « non terminés ».
 */
@Component
public class ActivationKycListener {

    private final UserRepository userRepository;

    public ActivationKycListener(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // fallbackExecution : le webhook Didit (et Stripe) publie l'événement hors transaction ;
    // sans repli, Spring ignorerait l'écouteur et aucune relance ne partirait.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onKycVerified(UserKycVerifiedEvent event) {
        userRepository.findById(event.getUserId()).ifPresent(user -> {
            if (user.getKycVerifiedAt() != null && user.getOnboardingSeenAt() != null) {
                return;
            }
            Instant now = Instant.now();
            if (user.getKycVerifiedAt() == null) {
                user.setKycVerifiedAt(now);
            }
            if (user.getOnboardingSeenAt() == null) {
                user.setOnboardingSeenAt(now);
            }
            userRepository.save(user);
        });
    }
}
