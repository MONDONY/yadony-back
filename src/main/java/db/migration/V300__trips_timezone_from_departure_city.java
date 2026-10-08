package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.zip.GZIPInputStream;

/**
 * V300 : fuseau horaire des trajets déduit de la ville de départ.
 *
 * <p>Jusqu'ici {@code announcements.timezone} valait toujours « Europe/Paris » (défaut de
 * V42, jamais réécrit) : l'instant de départ {@code departure_at}, la bascule « en cours »,
 * l'échéance de remise et le verrou d'annulation tombaient avec le décalage du fuseau de
 * Paris (Abidjan 2 h trop tôt, Cotonou 1 h, Houston 7 h…).
 *
 * <p>Trois étapes, dans la transaction de la migration :
 * <ol>
 *   <li>colonne {@code cities.timezone} (fuseau IANA GeoNames, colonne 18 du fichier) ;</li>
 *   <li>remplissage de cette colonne pour les villes déjà importées, depuis le même fichier
 *       {@code geonames/cities5000.txt.gz} que {@code GeoNamesDataLoader}. Java plutôt que
 *       SQL parce que le fuseau n'est que dans ce fichier. Sur une base neuve la table est
 *       vide ici : le chargeur pose le fuseau à l'import ;</li>
 *   <li>trajets À VENIR seulement (non supprimés, statut DRAFT/ACTIVE/FULL/REMOVED_BY_ADMIN,
 *       départ futur avant ou après correction) : fuseau de la ville de départ — la plus
 *       peuplée de ce nom, celle du pays du trajet en priorité — sinon fuseau principal du
 *       pays (ville la plus peuplée du pays), sinon Europe/Paris ; puis
 *       {@code departure_at = (departure_date + departure_time) AT TIME ZONE fuseau},
 *       formule de V134. Les trajets partis, terminés ou annulés ne bougent pas.</li>
 * </ol>
 * audit_log n'est pas touché.
 */
public class V300__trips_timezone_from_departure_city extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V300__trips_timezone_from_departure_city.class);

    static final String GEONAMES_RESOURCE = "geonames/cities5000.txt.gz";
    private static final int BATCH_SIZE = 1000;
    private static final int COL_ID = 0;
    private static final int COL_TIMEZONE = 17;

    static final String ADD_COLUMN =
            "ALTER TABLE cities ADD COLUMN IF NOT EXISTS timezone VARCHAR(40)";

    static final String UPCOMING_TRIPS = """
            WITH target AS (
                SELECT a.id,
                       COALESCE(
                           (SELECT c.timezone FROM cities c
                             WHERE LOWER(c.name) = LOWER(TRIM(a.departure_city))
                             ORDER BY CASE WHEN c.country_code = UPPER(a.departure_country_code)
                                           THEN 0 ELSE 1 END,
                                      c.population DESC
                             LIMIT 1),
                           (SELECT c.timezone FROM cities c
                             WHERE c.country_code = UPPER(a.departure_country_code)
                               AND c.timezone IS NOT NULL
                             ORDER BY c.population DESC
                             LIMIT 1)) AS tz
                  FROM announcements a
                 WHERE a.deleted_at IS NULL
                   AND a.status IN ('DRAFT', 'ACTIVE', 'FULL', 'REMOVED_BY_ADMIN')
                   AND a.departure_date IS NOT NULL
            ), resolved AS (
                SELECT t.id,
                       CASE WHEN EXISTS (SELECT 1 FROM pg_timezone_names z WHERE z.name = t.tz)
                            THEN t.tz ELSE 'Europe/Paris' END AS tz
                  FROM target t
            )
            UPDATE announcements a
               SET timezone = r.tz,
                   departure_at = CASE
                       WHEN a.departure_time IS NULL THEN a.departure_at
                       ELSE ((a.departure_date + a.departure_time) AT TIME ZONE r.tz)
                   END
              FROM resolved r
             WHERE a.id = r.id
               AND (a.timezone IS DISTINCT FROM r.tz
                    OR (a.departure_at IS NULL AND a.departure_time IS NOT NULL))
               AND (a.departure_at > now()
                    OR (a.departure_time IS NOT NULL
                        AND ((a.departure_date + a.departure_time) AT TIME ZONE r.tz) > now())
                    OR (a.departure_time IS NULL AND a.departure_date >= CURRENT_DATE))
            """;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute(ADD_COLUMN);
        }
        long cities = fillCityTimezones(connection);
        int trips;
        try (Statement statement = connection.createStatement()) {
            trips = statement.executeUpdate(UPCOMING_TRIPS);
        }
        log.info("[V300] fuseau posé sur {} villes, {} trajets à venir recalés", cities, trips);
    }

    /** Fuseau GeoNames des villes déjà importées qui n'en ont pas encore. */
    static long fillCityTimezones(Connection connection) throws Exception {
        if (!hasCityWithoutTimezone(connection)) {
            return 0;
        }
        InputStream resource = V300__trips_timezone_from_departure_city.class.getClassLoader()
                .getResourceAsStream(GEONAMES_RESOURCE);
        if (resource == null) {
            log.warn("[V300] {} introuvable : fuseau des villes non rempli", GEONAMES_RESOURCE);
            return 0;
        }
        long updated = 0;
        try (InputStream is = resource;
             GZIPInputStream gzip = new GZIPInputStream(is);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8));
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE cities SET timezone = ? WHERE id = ? AND timezone IS NULL")) {
            int pending = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split("\t", -1);
                if (cols.length <= COL_TIMEZONE) {
                    continue;
                }
                String zone = cols[COL_TIMEZONE].trim();
                Long id = parseId(cols[COL_ID]);
                if (zone.isEmpty() || zone.length() > 40 || id == null) {
                    continue;
                }
                update.setString(1, zone);
                update.setLong(2, id);
                update.addBatch();
                if (++pending == BATCH_SIZE) {
                    updated += sum(update.executeBatch());
                    pending = 0;
                }
            }
            if (pending > 0) {
                updated += sum(update.executeBatch());
            }
        }
        return updated;
    }

    private static boolean hasCityWithoutTimezone(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT EXISTS (SELECT 1 FROM cities WHERE timezone IS NULL)")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static Long parseId(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long sum(int[] counts) {
        long total = 0;
        for (int count : counts) {
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }
}
