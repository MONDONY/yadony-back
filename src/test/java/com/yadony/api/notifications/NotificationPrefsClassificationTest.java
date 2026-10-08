package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLUTTER-GB : un type de notification absent de {@code TYPE_TO_PREF} part toujours, quoi
 * que l'utilisateur ait réglé. Ce test lit le code de production et exige que chaque type
 * émis soit soit rattaché à un interrupteur, soit déclaré volontairement non réglable.
 */
class NotificationPrefsClassificationTest {

    /** {@code "type", "X"} dans un {@code Map.of(...)} de données de notification. */
    private static final Pattern DATA_TYPE = Pattern.compile("\"type\",\\s*\"([A-Za-z_]+)\"");
    /** Constantes de type : {@code NOTIFICATION_TYPE = "X"}, {@code TYPE = "X"}, {@code *Notifications}. */
    private static final Pattern CONSTANT_TYPE = Pattern.compile(
            "static final String (?:NOTIFICATION_TYPE|TYPE) = \"([A-Za-z_]+)\"");
    private static final Pattern NOTIFICATIONS_CONSTANT = Pattern.compile(
            "public static final String [A-Z_]+ = \"([A-Za-z_]+)\"");

    /** Chaînes captées par le motif sans être des types de notification. */
    private static final Set<String> NOT_NOTIFICATIONS = Set.of(
            "IMAGE",    // type d'un message Firestore (FirestoreService)
            "MMO",      // type de partie pawaPay (PawapayClient)
            "payments"  // type de ressource d'une réponse admin (AdminPaymentController)
    );

    @Test
    void everyEmittedTypeIsEitherControlledByAPreferenceOrDeclaredAlwaysOn() throws IOException {
        Set<String> unclassified = new TreeSet<>();
        for (String type : emittedTypes()) {
            if (NOT_NOTIFICATIONS.contains(type)) continue;
            boolean controlled = NotificationPrefsService.TYPE_TO_PREF.containsKey(type);
            boolean alwaysOn = NotificationPrefsService.isAlwaysOn(type);
            if (!controlled && !alwaysOn) unclassified.add(type);
        }
        assertThat(unclassified)
                .as("Types émis sans réglage ni déclaration « toujours actif » : les ajouter à "
                        + "TYPE_TO_PREF ou à ALWAYS_ON dans NotificationPrefsService")
                .isEmpty();
    }

    @Test
    void noTypeIsBothControlledAndAlwaysOn() {
        Set<String> both = new TreeSet<>();
        for (String type : NotificationPrefsService.TYPE_TO_PREF.keySet()) {
            if (NotificationPrefsService.isAlwaysOn(type)) both.add(type);
        }
        assertThat(both).as("Un type toujours actif ne doit pas figurer dans TYPE_TO_PREF").isEmpty();
    }

    @Test
    void scanFindsTheKnownEmitters() throws IOException {
        // Garde-fou du test lui-même : si le motif cessait de capter quoi que ce soit,
        // le premier test passerait à vide.
        assertThat(emittedTypes()).contains(
                "TRIP_ARRIVED", "CALL_MISSED", "FIRST_ACTION_REMINDER", "BID_CREATED",
                "automation_capacity_free", "negotiation_deposit_pending");
    }

    private static Set<String> emittedTypes() throws IOException {
        Set<String> types = new TreeSet<>();
        Path root = Path.of("src/main/java/com/yadony/api");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                collect(DATA_TYPE.matcher(source), types);
                collect(CONSTANT_TYPE.matcher(source), types);
                if (file.getFileName().toString().endsWith("Notifications.java")) {
                    collect(NOTIFICATIONS_CONSTANT.matcher(source), types);
                }
            }
        }
        return types;
    }

    private static void collect(Matcher m, Set<String> into) {
        while (m.find()) into.add(m.group(1));
    }
}
