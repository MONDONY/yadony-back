package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;

class PawapayValidationHintsTest {

    @Test
    void forProvider_knownCode_returnsConfiguredHint() {
        PawapayValidationHints hints = new PawapayValidationHints(
                Map.of("ORANGE_CIV", "SMS_LINK", "MTN_MOMO_CIV", "PIN_PROMPT"));

        assertThat(hints.forProvider("ORANGE_CIV")).isEqualTo(ValidationHint.SMS_LINK);
        assertThat(hints.forProvider("MTN_MOMO_CIV")).isEqualTo(ValidationHint.PIN_PROMPT);
    }

    @Test
    void forProvider_unknownOrNull_returnsGeneric() {
        PawapayValidationHints hints = new PawapayValidationHints(Map.of("ORANGE_CIV", "SMS_LINK"));

        assertThat(hints.forProvider("MOOV_CIV")).isEqualTo(ValidationHint.GENERIC);
        assertThat(hints.forProvider(null)).isEqualTo(ValidationHint.GENERIC);
    }

    @Test
    void forProvider_invalidValueInConfig_fallsBackToGeneric() {
        Map<String, String> config = new HashMap<>();
        config.put("X", "FOO");
        config.put("Y", null);
        config.put("Z", "  ");

        PawapayValidationHints hints = assertThatConstructs(config);

        assertThat(hints.forProvider("X")).isEqualTo(ValidationHint.GENERIC);
        assertThat(hints.forProvider("Y")).isEqualTo(ValidationHint.GENERIC);
        assertThat(hints.forProvider("Z")).isEqualTo(ValidationHint.GENERIC);
    }

    @Test
    void forProvider_nullMap_returnsGeneric() {
        assertThat(new PawapayValidationHints(null).forProvider("ORANGE_CIV")).isEqualTo(ValidationHint.GENERIC);
    }

    @Test
    void forProvider_isCaseAndSeparatorInsensitive() {
        PawapayValidationHints hints = new PawapayValidationHints(Map.of("ORANGE_CIV", " sms_link "));

        assertThat(hints.forProvider("orange_civ")).isEqualTo(ValidationHint.SMS_LINK);
        assertThat(hints.forProvider("ORANGECIV")).as("Spring peut aplatir la clé").isEqualTo(ValidationHint.SMS_LINK);
    }

    @Test
    void springWiring_readsInlineMapFromProperty() {
        new ApplicationContextRunner()
                .withUserConfiguration(HintsConfig.class)
                .withPropertyValues("yadony.pawapay.validation-hints={ORANGE_CIV: 'SMS_LINK', BAD_OP: 'FOO'}")
                .run(ctx -> {
                    PawapayValidationHints hints = ctx.getBean(PawapayValidationHints.class);
                    assertThat(hints.forProvider("ORANGE_CIV")).isEqualTo(ValidationHint.SMS_LINK);
                    assertThat(hints.forProvider("BAD_OP")).isEqualTo(ValidationHint.GENERIC);
                });
    }

    @Test
    void springWiring_missingProperty_startsWithGenericOnly() {
        new ApplicationContextRunner()
                .withUserConfiguration(HintsConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(PawapayValidationHints.class).forProvider("ORANGE_CIV"))
                            .isEqualTo(ValidationHint.GENERIC);
                });
    }

    @Test
    void applicationYml_declaresOrangeCivAsSmsLink() {
        new ApplicationContextRunner()
                .withUserConfiguration(HintsConfig.class)
                .withInitializer(ctx -> {
                    try {
                        var sources = new YamlPropertySourceLoader()
                                .load("application", new ClassPathResource("application.yml"));
                        sources.forEach(ps -> ctx.getEnvironment().getPropertySources().addLast(ps));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .run(ctx -> {
                    PawapayValidationHints hints = ctx.getBean(PawapayValidationHints.class);
                    assertThat(hints.forProvider("ORANGE_CIV")).isEqualTo(ValidationHint.SMS_LINK);
                    assertThat(hints.forProvider("MTN_MOMO_CIV")).isEqualTo(ValidationHint.GENERIC);
                });
    }

    private static PawapayValidationHints assertThatConstructs(Map<String, String> config) {
        return new PawapayValidationHints(config);
    }

    @Configuration
    @Import(PawapayValidationHints.class)
    static class HintsConfig {}
}
