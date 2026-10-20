package com.yadony.api.payments;

import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.notifications.NotificationEmailLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Bouton « Terminer mon inscription » de l'e-mail de relance Stripe Connect : il mène à
 * {@link StripeOnboardingResumeController}, qui crée un lien Stripe neuf au clic.
 *
 * <p>Les adresses sont déduites de {@code yadony.stripe.connect.return-url}
 * ({@code …/payments/onboarding/return}), déjà posée et juste dans chaque environnement :
 * une variable de plus pourrait rester sur la valeur de prod en staging.
 */
@Component
public class StripeOnboardingResumeEmailLink implements NotificationEmailLink {

    private static final Logger log = LoggerFactory.getLogger(StripeOnboardingResumeEmailLink.class);

    static final String TYPE = "STRIPE_ONBOARDING_INCOMPLETE";

    private final StripeOnboardingResumeTokens tokens;
    /** {@code …/payments/onboarding}, ou nul si la configuration ne permet pas de le déduire. */
    private final String base;

    public StripeOnboardingResumeEmailLink(StripeOnboardingResumeTokens tokens,
                                           StripeConnectProperties properties) {
        this.tokens = tokens;
        this.base = onboardingBase(properties.returnUrl());
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Optional<Cta> ctaFor(UUID userId) {
        if (base == null) {
            return Optional.empty();
        }
        return Optional.of(new Cta(resumeUrl(tokens.issue(userId)),
                "email.notif.cta.stripe-onboarding",
                "email.notif.type.STRIPE_ONBOARDING_INCOMPLETE.link-detail"));
    }

    /** Adresse du bouton de l'e-mail, et du retour de Stripe quand son lien a expiré. */
    String resumeUrl(String token) {
        return base + "/resume?t=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    /** Page affichée quand Stripe rend la main, formulaire envoyé. */
    String doneUrl() {
        return base + "/resume/done";
    }

    private static String onboardingBase(String returnUrl) {
        if (returnUrl == null || !returnUrl.endsWith("/return")) {
            // Jamais bloquant au démarrage : l'e-mail garde alors le bouton « Ouvrir Yadony ».
            log.warn("yadony.stripe.connect.return-url ne finit pas par /return, "
                    + "relance Stripe sans lien direct : {}", returnUrl);
            return null;
        }
        return returnUrl.substring(0, returnUrl.length() - "/return".length());
    }
}
