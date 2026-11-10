package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KycReminderUnsubscribeLinksTest {

    private final KycReminderUnsubscribeTokens tokens = new KycReminderUnsubscribeTokens("cle-de-test");
    private final UUID userId = UUID.randomUUID();

    @Test
    void lienVersLaPagePubliqueDeLApi_avecUnJetonVerifiable() {
        String url = new KycReminderUnsubscribeLinks(tokens, "https://api.yadony.com/").urlFor(userId).orElseThrow();

        assertThat(url).startsWith("https://api.yadony.com/api/v1/public/notifications/kyc-reminders/unsubscribe?t=");
        String token = java.net.URLDecoder.decode(url.substring(url.indexOf("?t=") + 3), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(tokens.verify(token)).contains(userId);
    }

    @Test
    void origineAbsenteOuInvalide_pasDeLien() {
        assertThat(new KycReminderUnsubscribeLinks(tokens, "").urlFor(userId)).isEmpty();
        assertThat(new KycReminderUnsubscribeLinks(tokens, null).urlFor(userId)).isEmpty();
        assertThat(new KycReminderUnsubscribeLinks(tokens, "api.yadony.com").urlFor(userId)).isEmpty();
        assertThat(new KycReminderUnsubscribeLinks(tokens, "http://localhost:8080").urlFor(null)).isEmpty();
    }
}
