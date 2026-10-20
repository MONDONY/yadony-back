package com.yadony.api.payments;

import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.notifications.NotificationEmailLink.Cta;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StripeOnboardingResumeEmailLinkTest {

    private final StripeOnboardingResumeTokens tokens = new StripeOnboardingResumeTokens("cle-de-test");

    private static StripeConnectProperties withReturnUrl(String returnUrl) {
        return new StripeConnectProperties("4215", "desc", "https://yadony.com", returnUrl,
                "https://x/refresh", "yadony://a", "yadony://b");
    }

    @Test
    void cta_pointsToTheResumePageOfTheSameEnvironment() {
        StripeOnboardingResumeEmailLink link = new StripeOnboardingResumeEmailLink(tokens,
                withReturnUrl("https://api-staging.yadony.com/api/v1/payments/onboarding/return"));
        UUID userId = UUID.randomUUID();

        Cta cta = link.ctaFor(userId).orElseThrow();

        assertThat(link.type()).isEqualTo("STRIPE_ONBOARDING_INCOMPLETE");
        assertThat(cta.url()).startsWith("https://api-staging.yadony.com/api/v1/payments/onboarding/resume?t=");
        String token = cta.url().substring(cta.url().indexOf("?t=") + 3);
        assertThat(tokens.verify(token)).contains(userId);
        assertThat(cta.labelKey()).isEqualTo("email.notif.cta.stripe-onboarding");
        assertThat(cta.detailKey()).isEqualTo("email.notif.type.STRIPE_ONBOARDING_INCOMPLETE.link-detail");
        assertThat(link.doneUrl())
                .isEqualTo("https://api-staging.yadony.com/api/v1/payments/onboarding/resume/done");
    }

    @Test
    void unexpectedReturnUrl_keepsTheDefaultButton() {
        assertThat(new StripeOnboardingResumeEmailLink(tokens, withReturnUrl("https://yadony.com/ailleurs"))
                .ctaFor(UUID.randomUUID())).isEmpty();
        assertThat(new StripeOnboardingResumeEmailLink(tokens, withReturnUrl(null))
                .ctaFor(UUID.randomUUID())).isEmpty();
    }
}
