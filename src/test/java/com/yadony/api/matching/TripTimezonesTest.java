package com.yadony.api.matching;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Déduction du fuseau d'un trajet depuis sa ville de départ. Le référentiel est simulé à
 * partir du VRAI fichier GeoNames embarqué, avec les règles des requêtes de
 * {@link AnnouncementRepository} : les cas Cotonou, Abidjan, Toronto… sont ceux que
 * donnera la base.
 */
class TripTimezonesTest {

    private record City(String country, long population, String zone) {}

    private static final Map<String, List<City>> BY_NAME = new HashMap<>();
    private static TripTimezones.Lookup geonames;

    @BeforeAll
    static void loadGeoNames() throws Exception {
        try (var is = TripTimezonesTest.class.getClassLoader().getResourceAsStream("geonames/cities5000.txt.gz");
             var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(is), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] c = line.split("\t", -1);
                if (c.length < 18 || !c[7].startsWith("PPL")) continue;
                long pop = c[14].isEmpty() ? 0 : Long.parseLong(c[14]);
                BY_NAME.computeIfAbsent(c[1].toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(new City(c[8].toUpperCase(Locale.ROOT), pop, c[17]));
            }
        }
        geonames = new TripTimezones.Lookup(
                (name, country) -> BY_NAME.getOrDefault(name.trim().toLowerCase(Locale.ROOT), List.of()).stream()
                        .sorted(Comparator.comparing((City x) -> x.country().equals(country) ? 0 : 1)
                                .thenComparing(City::population, Comparator.reverseOrder()))
                        .map(City::zone).findFirst(),
                country -> BY_NAME.values().stream().flatMap(List::stream)
                        .filter(x -> x.country().equals(country))
                        .max(Comparator.comparingLong(City::population)).map(City::zone));
    }

    @ParameterizedTest(name = "{0} ({1}) → {2}")
    @CsvSource({
            "Cotonou, '', Africa/Porto-Novo",
            "Abidjan, '', Africa/Abidjan",
            "Paris, '', Europe/Paris",
            "Toronto, '', America/Toronto",
            "Bamako, ML, Africa/Bamako",
            "Houston, US, America/Chicago",
            "'  cotonou ', BJ, Africa/Porto-Novo",
    })
    @DisplayName("ville du référentiel → fuseau GeoNames de la ville")
    void resolve_knownCity(String city, String country, String expected) {
        assertThat(TripTimezones.resolve(city, country.isEmpty() ? null : country, geonames))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("homonyme : le pays du trajet départage avant la population (Paris, US ≠ Europe/Paris)")
    void resolve_homonym_prefersTripCountry() {
        assertThat(TripTimezones.resolve("Paris", "US", geonames)).startsWith("America/");
        assertThat(TripTimezones.resolve("Paris", "FR", geonames)).isEqualTo("Europe/Paris");
    }

    @Test
    @DisplayName("ville inconnue, pays connu → fuseau principal du pays")
    void resolve_unknownCity_knownCountry() {
        assertThat(TripTimezones.resolve("Atlantide", "CA", geonames)).isEqualTo("America/Toronto");
        assertThat(TripTimezones.resolve("Atlantide", "ci", geonames)).isEqualTo("Africa/Abidjan");
    }

    @Test
    @DisplayName("ville et pays inconnus, ville vide ou nulle → repli Europe/Paris")
    void resolve_unknown_fallsBackToParis() {
        assertThat(TripTimezones.resolve("Atlantide", null, geonames)).isEqualTo(TripTimezones.DEFAULT_ZONE);
        assertThat(TripTimezones.resolve("Atlantide", "XX", geonames)).isEqualTo("Europe/Paris");
        assertThat(TripTimezones.resolve("  ", "FRA", geonames)).isEqualTo("Europe/Paris");
        assertThat(TripTimezones.resolve(null, null, geonames)).isEqualTo("Europe/Paris");
    }

    @Test
    @DisplayName("fuseau invalide, vide ou réponse nulle du référentiel → écarté comme manquant")
    void resolve_invalidZone_isIgnored() {
        TripTimezones.Lookup broken = new TripTimezones.Lookup(
                (name, country) -> Optional.of("Mars/Olympus"), country -> Optional.of("  "));
        assertThat(TripTimezones.resolve("Abidjan", "CI", broken)).isEqualTo("Europe/Paris");

        TripTimezones.Lookup nulls = new TripTimezones.Lookup((name, country) -> null, country -> null);
        assertThat(TripTimezones.resolve("Abidjan", "CI", nulls)).isEqualTo("Europe/Paris");
    }

    @Test
    @DisplayName("pays inconnu : la recherche par ville reçoit une chaîne vide (aucune liaison nulle en SQL)")
    void resolve_withoutCountry_passesEmptyCountry() {
        List<String> seen = new ArrayList<>();
        TripTimezones.Lookup spy = new TripTimezones.Lookup(
                (name, country) -> { seen.add(name + "|" + country); return Optional.empty(); },
                country -> Optional.empty());
        TripTimezones.resolve(" Abidjan ", null, spy);
        assertThat(seen).containsExactly("Abidjan|");
    }

    @Test
    @DisplayName("modification, même ville et même pays → fuseau en place conservé sans recherche")
    void resolveOnUpdate_sameCity_keepsZone() {
        AtomicInteger calls = new AtomicInteger();
        TripTimezones.Lookup counting = new TripTimezones.Lookup(
                (name, country) -> { calls.incrementAndGet(); return Optional.empty(); },
                country -> { calls.incrementAndGet(); return Optional.empty(); });
        assertThat(TripTimezones.resolveOnUpdate("abidjan ", "ci", "Abidjan", "CI", "Africa/Abidjan", counting))
                .isEqualTo("Africa/Abidjan");
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("modification, ville changée → fuseau de la nouvelle ville")
    void resolveOnUpdate_changedCity_resolvesAgain() {
        assertThat(TripTimezones.resolveOnUpdate("Cotonou", "BJ", "Paris", "FR", "Europe/Paris", geonames))
                .isEqualTo("Africa/Porto-Novo");
    }

    @Test
    @DisplayName("modification, même ville mais pays changé → recherche à nouveau")
    void resolveOnUpdate_changedCountry_resolvesAgain() {
        assertThat(TripTimezones.resolveOnUpdate("Paris", "US", "Paris", "FR", "Europe/Paris", geonames))
                .startsWith("America/");
    }

    @Test
    @DisplayName("modification, même ville mais fuseau en place invalide → recherche à nouveau")
    void resolveOnUpdate_invalidCurrentZone_resolvesAgain() {
        assertThat(TripTimezones.resolveOnUpdate("Abidjan", "CI", "Abidjan", "CI", "n'importe quoi", geonames))
                .isEqualTo("Africa/Abidjan");
        assertThat(TripTimezones.resolveOnUpdate("Abidjan", null, null, null, null, geonames))
                .isEqualTo("Africa/Abidjan");
    }

    @Test
    @DisplayName("zoneOf : fuseau valide gardé, nul, vide ou inconnu → Europe/Paris")
    void zoneOf_fallsBackOnInvalid() {
        assertThat(TripTimezones.zoneOf("Africa/Abidjan")).isEqualTo(ZoneId.of("Africa/Abidjan"));
        assertThat(TripTimezones.zoneOf(null)).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(TripTimezones.zoneOf("")).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(TripTimezones.zoneOf("Mars/Olympus")).isEqualTo(ZoneId.of("Europe/Paris"));
    }

    @Test
    @DisplayName("Lookup.of branche les deux requêtes du repository")
    void lookupOf_delegatesToRepository() {
        AnnouncementRepository repo = org.mockito.Mockito.mock(AnnouncementRepository.class);
        org.mockito.Mockito.when(repo.findTimezoneByCityName("Cotonou", "BJ"))
                .thenReturn(Optional.of("Africa/Porto-Novo"));
        org.mockito.Mockito.when(repo.findMainTimezoneByCountryCode("CI"))
                .thenReturn(Optional.of("Africa/Abidjan"));
        TripTimezones.Lookup lookup = TripTimezones.Lookup.of(repo);
        assertThat(TripTimezones.resolve("Cotonou", "BJ", lookup)).isEqualTo("Africa/Porto-Novo");
        assertThat(TripTimezones.resolve("Atlantide", "CI", lookup)).isEqualTo("Africa/Abidjan");
    }
}
