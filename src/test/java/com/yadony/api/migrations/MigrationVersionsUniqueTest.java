package com.yadony.api.migrations;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deux PR fusionnées le même jour peuvent embarquer la même version Flyway : l'API ne démarre plus
 * (« Found more than one migration with version N ») et boucle en redémarrage. Ce test échoue dès la CI.
 */
class MigrationVersionsUniqueTest {

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");

    @Test
    void everyMigrationVersionIsUnique() throws IOException {
        Map<String, String> seen = new HashMap<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                Matcher m = VERSIONED.matcher(name);
                if (!m.matches()) continue;
                String previous = seen.put(m.group(1), name);
                assertThat(previous)
                        .as("version %s déjà prise par %s, renumérotez %s", m.group(1), previous, name)
                        .isNull();
            }
        }
    }
}
