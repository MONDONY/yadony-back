package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.AccountLink;
import com.stripe.param.AccountLinkCreateParams;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Reprise de l'onboarding Stripe Connect depuis le bouton de l'e-mail de relance.
 *
 * <p>Le clic arrive d'un navigateur, sans jeton Firebase : seul le jeton signé de
 * l'e-mail ({@link StripeOnboardingResumeTokens}) dit de qui il s'agit. Avant de créer
 * un lien Stripe, on revérifie tout ce que l'app vérifie : compte actif, identité
 * vérifiée, compte Connect encore en attente. Le moindre doute renvoie vers l'app
 * plutôt que vers Stripe.
 */
@Service
public class StripeOnboardingResumeService {

    private static final Logger log = LoggerFactory.getLogger(StripeOnboardingResumeService.class);

    /** Issue du clic : où envoyer le navigateur. */
    public sealed interface Outcome {
        /** Lien Stripe neuf, valable quelques minutes. */
        record Redirect(String url) implements Outcome {}
        /** Plus rien à faire : l'inscription est déjà terminée. */
        record AlreadyComplete() implements Outcome {}
        /** Reprise impossible depuis le navigateur : continuer dans l'app. */
        record OpenApp() implements Outcome {}
    }

    private final UserRepository userRepository;
    private final StripeGateway stripeGateway;
    private final AuditService auditService;

    public StripeOnboardingResumeService(UserRepository userRepository,
                                         StripeGateway stripeGateway,
                                         AuditService auditService) {
        this.userRepository = userRepository;
        this.stripeGateway = stripeGateway;
        this.auditService = auditService;
    }

    /**
     * @param refreshUrl où Stripe renvoie si son lien a expiré : le lien de l'e-mail
     *                   lui-même, qui en recrée un neuf
     * @param returnUrl  où Stripe renvoie une fois le formulaire envoyé
     */
    public Outcome resume(UUID userId, String refreshUrl, String returnUrl) {
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            return new Outcome.OpenApp();
        }
        if (user.getStripeAccountStatus() == StripeAccountStatus.ONBOARDING_COMPLETE) {
            return new Outcome.AlreadyComplete();
        }
        if (user.getStripeAccountStatus() != StripeAccountStatus.PENDING_ONBOARDING
                || user.getStripeAccountId() == null
                || user.getKycStatus() != KycStatus.VERIFIED) {
            return new Outcome.OpenApp();
        }
        try {
            AccountLink link = stripeGateway.createAccountLink(AccountLinkCreateParams.builder()
                    .setAccount(user.getStripeAccountId())
                    .setRefreshUrl(refreshUrl)
                    .setReturnUrl(returnUrl)
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .build());
            auditService.log("USER", user.getId(), "STRIPE_ONBOARDING_RESUMED_FROM_EMAIL",
                    user.getId(), Map.of());
            return new Outcome.Redirect(link.getUrl());
        } catch (StripeException | RuntimeException e) {
            // Compte supprimé chez Stripe, panne : l'app sait recréer le compte, pas cette page.
            log.warn("Reprise Stripe depuis l'e-mail impossible pour {} : {}",
                    user.getId(), e.getClass().getSimpleName());
            return new Outcome.OpenApp();
        }
    }
}
