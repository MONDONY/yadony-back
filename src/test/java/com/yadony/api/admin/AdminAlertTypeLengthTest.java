package com.yadony.api.admin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tout type d'alerte construit « préfixe + identifiant » doit tenir dans {@code admin_alerts.type}
 * ({@link AdminAlertEscalator#TYPE_MAX_LENGTH}) avec un UUID (36 caractères), le cas le plus long.
 * Un type trop long fait lever {@code raiseOnce} : l'alerte ne part jamais et la transaction de
 * l'appelant est annulée (constat du 10/10/2026 : {@code DELIVERY_PAYMENT_NOT_IN_ESCROW_<uuid>} =
 * 67, {@code COMMISSION_3DS_UNCONFIRMED_<uuid>} = 63).
 *
 * <p>Relevé dans les sources : littéraux passés à {@code raiseOnce("…" + …)} et constantes
 * {@code *PREFIX* = "…"} des classes qui lèvent des alertes.
 */
class AdminAlertTypeLengthTest {

    private static final int UUID_LENGTH = 36;
    private static final Path SOURCES = Path.of("src/main/java/com/yadony/api");
    private static final Pattern INLINE = Pattern.compile("raiseOnce\\(\\s*\"([^\"]+)\"\\s*\\+");
    private static final Pattern CONSTANT =
            Pattern.compile("static\\s+final\\s+String\\s+\\w*(?:PREFIX|ALERT_TYPE)\\w*\\s*=\\s*\"([^\"]+)\"");

    @Test
    void everyPrefixedAlertTypeFitsTheColumnWithAUuid() throws IOException {
        List<String> prefixes = new ArrayList<>();
        List<String> tooLong = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                boolean raisesAlerts = source.contains("raiseOnce(");
                collect(INLINE.matcher(source), file, prefixes, tooLong);
                if (raisesAlerts) {
                    collect(CONSTANT.matcher(source), file, prefixes, tooLong);
                }
            }
        }
        assertThat(prefixes).as("préfixes relevés").contains("DELIVERY_NOT_ESCROW_", "COMMISSION_3DS_UNCONF_",
                "ESCROW_CAPTURE_FAILED_", "PAYOUT_STRIPE_UNUSABLE_");
        assertThat(tooLong).as("types d'alerte au-delà de %d caractères avec un UUID",
                AdminAlertEscalator.TYPE_MAX_LENGTH).isEmpty();
    }

    private static void collect(Matcher m, Path file, List<String> prefixes, List<String> tooLong) {
        while (m.find()) {
            String prefix = m.group(1);
            prefixes.add(prefix);
            if (prefix.length() + UUID_LENGTH > AdminAlertEscalator.TYPE_MAX_LENGTH) {
                tooLong.add(file.getFileName() + " : " + prefix + " (" + (prefix.length() + UUID_LENGTH) + ")");
            }
        }
    }
}
