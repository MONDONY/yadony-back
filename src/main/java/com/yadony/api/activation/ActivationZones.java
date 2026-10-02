package com.yadony.api.activation;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

import static java.util.Map.entry;

/** Fuseau d'envoi des relances, déduit du pays de l'utilisateur (Europe/Paris par défaut). */
final class ActivationZones {

    static final ZoneId DEFAULT = ZoneId.of("Europe/Paris");

    private static final Map<String, ZoneId> ZONES = Map.ofEntries(
            entry("GB", ZoneId.of("Europe/London")), entry("IE", ZoneId.of("Europe/Dublin")),
            entry("PT", ZoneId.of("Europe/Lisbon")), entry("FI", ZoneId.of("Europe/Helsinki")),
            entry("EE", ZoneId.of("Europe/Tallinn")), entry("LV", ZoneId.of("Europe/Riga")),
            entry("LT", ZoneId.of("Europe/Vilnius")), entry("GR", ZoneId.of("Europe/Athens")),
            entry("CY", ZoneId.of("Asia/Nicosia")), entry("CA", ZoneId.of("America/Toronto")),
            entry("US", ZoneId.of("America/New_York")),
            entry("SN", ZoneId.of("Africa/Dakar")), entry("CI", ZoneId.of("Africa/Abidjan")),
            entry("ML", ZoneId.of("Africa/Bamako")), entry("BF", ZoneId.of("Africa/Ouagadougou")),
            entry("GW", ZoneId.of("Africa/Bissau")), entry("TG", ZoneId.of("Africa/Lome")),
            entry("BJ", ZoneId.of("Africa/Porto-Novo")), entry("NE", ZoneId.of("Africa/Niamey")),
            entry("CM", ZoneId.of("Africa/Douala")), entry("CF", ZoneId.of("Africa/Bangui")),
            entry("TD", ZoneId.of("Africa/Ndjamena")), entry("CG", ZoneId.of("Africa/Brazzaville")),
            entry("GQ", ZoneId.of("Africa/Malabo")), entry("GA", ZoneId.of("Africa/Libreville")));

    private ActivationZones() {}

    static ZoneId zoneFor(String country) {
        if (country == null) {
            return DEFAULT;
        }
        return ZONES.getOrDefault(country.trim().toUpperCase(Locale.ROOT), DEFAULT);
    }

    static boolean isWithinWindow(String country, Instant now, int startHour, int endHour) {
        int hour = now.atZone(zoneFor(country)).getHour();
        return hour >= startHour && hour < endHour;
    }
}
