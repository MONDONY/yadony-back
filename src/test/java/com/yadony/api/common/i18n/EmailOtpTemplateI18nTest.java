package com.yadony.api.common.i18n;

import com.yadony.api.config.I18nConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rend le gabarit {@code email-otp} avec le {@link TemplateEngine} tel
 * qu'auto-configuré par Spring Boot (bean {@code messageSource} de
 * {@link I18nConfig} + intégration Thymeleaf/Spring), sans passer par
 * {@link MessagesResolver} ni {@link com.yadony.api.emailotp.ResendEmailService}.
 * Vérifie que le moteur auto-configuré résout bien les clés {@code i18n/messages}
 * (aucune chaîne {@code ??clé??}) dans les deux langues.
 * <p>
 * Contexte volontairement restreint à {@link ThymeleafAutoConfiguration} et
 * {@link I18nConfig} : le {@code TemplateEngine} de production n'a aucune
 * configuration Thymeleaf propre à l'application (voir
 * {@code ResendEmailService}, seul appelant), tout vient de l'auto-configuration
 * Spring Boot. Charger le contexte Spring Boot complet ({@code @SpringBootTest}
 * sans {@code classes}) ajouterait la base H2, Firebase, Stripe et les tâches
 * planifiées sans rien apporter à ce qui est vérifié ici.
 */
@SpringBootTest(
        classes = {ThymeleafAutoConfiguration.class, I18nConfig.class, EmailOtpTemplateI18nTest.TestSupport.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class EmailOtpTemplateI18nTest {

    @Autowired
    private TemplateEngine templateEngine;

    @Test
    void rendLeGabaritOtpEnFrancais() {
        String html = render(Locale.FRENCH);

        assertThat(html).contains("Voici votre code de vérification").contains("123456 est votre code Yadony");
        assertThat(html).doesNotContain("??");
    }

    @Test
    void rendLeGabaritOtpEnAnglais() {
        String html = render(Locale.ENGLISH);

        assertThat(html).contains("Here is your verification code").contains("123456 is your Yadony code");
        assertThat(html).doesNotContain("??");
    }

    @Test
    void afficheUnChiffreParCaseDansLOrdre() {
        String html = render(Locale.FRENCH);

        assertThat(html.split("class=\"digit\"", -1)).hasSize(7);
        assertThat(html.replaceAll("\\s+", ""))
                .containsSubsequence(">1</td>", ">2</td>", ">3</td>", ">4</td>", ">5</td>", ">6</td>");
    }

    /** Gabarit des e-mails de notification (étape 5 du plan bruit) : clés résolues dans les deux langues. */
    @Test
    void rendLeGabaritDeNotificationDansLesDeuxLangues() {
        for (Locale locale : new Locale[]{Locale.FRENCH, Locale.ENGLISH}) {
            Context context = new Context(locale);
            context.setVariable("eyebrow", "E");
            context.setVariable("heading", "H");
            context.setVariable("body", "B");
            context.setVariable("detail", "D");
            context.setVariable("optional", true);
            context.setVariable("appUrl", "https://yadony.com");
            context.setVariable("lang", locale.getLanguage());
            String html = templateEngine.process("email-notification", context);

            assertThat(html).doesNotContain("??").contains("https://yadony.com");
            assertThat(html).contains(locale == Locale.FRENCH ? "Ouvrir Yadony" : "Open Yadony");
        }
    }

    private String render(Locale locale) {
        Context context = new Context(locale);
        context.setVariable("otpCode", "123456");
        context.setVariable("lang", locale.getLanguage());
        return templateEngine.process("email-otp", context);
    }

    /**
     * Fournit le seul bean que {@link I18nConfig} attend en plus de ce test :
     * {@link UserLanguageLookup}, requis par son bean {@code messagesResolver}
     * mais sans rapport avec le rendu du gabarit vérifié ici.
     */
    @Configuration
    static class TestSupport {

        @Bean
        UserLanguageLookup userLanguageLookup() {
            return userId -> Optional.empty();
        }
    }
}
