package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V171 — normalisation du vocabulaire des types de contenu.
 *
 * <p>Le profil "test" tourne sur H2 avec Flyway désactivé : les migrations n'y sont jamais
 * exécutées. On démarre donc un PostgreSQL embarqué (zonky, même dépendance que le harnais
 * e2e), on migre jusqu'à V170, on sème des données legacy, puis on applique V171 et on
 * vérifie le résultat.
 *
 * <p>Chaque test repart d'une base vierge (clean + migrate jusqu'à V170 dans {@link
 * #resetSchema()}) pour éviter toute contamination entre cas (déduplication, collisions
 * accepted/refused, etc. sont sensibles aux données déjà en base).
 */
class V171ContentCategoriesMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    /** Les 9 codes enum majuscules legacy → libellé canonique. */
    private static final Map<String, String> LEGACY_CODES = new LinkedHashMap<>();
    /** Les 14 libellés legacy → libellé canonique. */
    private static final Map<String, String> LEGACY_LABELS = new LinkedHashMap<>();

    static {
        LEGACY_CODES.put("VETEMENTS", "Vêtements & tissus");
        LEGACY_CODES.put("MEDICAMENTS", "Médicaments traditionnels");
        LEGACY_CODES.put("ALIMENTATION", "Alimentation sèche");
        LEGACY_CODES.put("HIFI", "Téléphone & électronique");
        LEGACY_CODES.put("DOCUMENTS", "Documents & administratif");
        LEGACY_CODES.put("TELEPHONE", "Téléphone & électronique");
        LEGACY_CODES.put("COSMETIQUES", "Cosmétiques & parfums");
        LEGACY_CODES.put("CADEAUX", "Cadeaux & jouets");
        LEGACY_CODES.put("AUTRE", "Autre");

        LEGACY_LABELS.put("Téléphones & hi-fi", "Téléphone & électronique");
        LEGACY_LABELS.put("Matériel informatique", "Téléphone & électronique");
        LEGACY_LABELS.put("Électronique", "Téléphone & électronique");
        LEGACY_LABELS.put("Hi-fi", "Téléphone & électronique");
        LEGACY_LABELS.put("Téléphone", "Téléphone & électronique");
        LEGACY_LABELS.put("Alim. sèche", "Alimentation sèche");
        LEGACY_LABELS.put("Nourriture", "Alimentation sèche");
        LEGACY_LABELS.put("Cosmétiques", "Cosmétiques & parfums");
        LEGACY_LABELS.put("Cosmét.", "Cosmétiques & parfums");
        LEGACY_LABELS.put("Vêtements", "Vêtements & tissus");
        LEGACY_LABELS.put("Médicaments", "Médicaments traditionnels");
        LEGACY_LABELS.put("Documents", "Documents & administratif");
        LEGACY_LABELS.put("Cadeaux", "Cadeaux & jouets");
        LEGACY_LABELS.put("Autres", "Autre");
    }

    @BeforeAll
    static void startDatabase() throws Exception {
        // locale explicite : sans elle, initdb hérite de la locale du host (souvent "C" en CI/macOS),
        // où lower() ne foldcase pas les majuscules accentuées multi-octets ('É' reste 'É' au lieu de
        // 'é') — contrairement au Postgres 16 réel (image officielle Docker, LANG=en_US.utf8) utilisé
        // en dev/prod. Sans ce réglage, ce test ne reproduit pas fidèlement le comportement de lower()
        // de la migration V171 en production.
        postgres = EmbeddedPostgres.builder()
                .setLocaleConfig("locale", "en_US.UTF-8")
                .start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void resetSchema() {
        Flyway upToV170 = flywayUpTo("170");
        upToV170.clean();
        upToV170.migrate();
    }

    private Flyway flywayUpTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .target(targetVersion)
                .cleanDisabled(false)
                .load();
    }

    private void migrateToV171() {
        flywayUpTo("171").migrate();
    }

    private List<String> queryStrings(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /** Comme {@link #queryStrings} mais tolère une valeur NULL en base (retournée comme null). */
    private String queryNullableString(String sql) throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void exec(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** Exécute un INSERT ... RETURNING id et renvoie l'UUID généré sous forme de chaîne. */
    private String insertReturningId(String sql) throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Lit le corps de V171 pour tester son idempotence en le rejouant. */
    private String readMigrationBody() throws Exception {
        try (var in = getClass().getResourceAsStream("/db/migration/V171__unify_content_categories.sql")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // ─── FIX 6 : couverture des 9 codes ET des 14 libellés, un cas par bras du CASE ──

    @Test
    void v171_migratesAllLegacyCodesAndLabels_onBids() throws Exception {
        String annId = seedMinimalAnnouncement();
        Map<String, String> allMappings = new LinkedHashMap<>();
        allMappings.putAll(LEGACY_CODES);
        allMappings.putAll(LEGACY_LABELS);
        assertThat(allMappings).hasSize(23);

        Map<String, String> bidIdByRaw = new LinkedHashMap<>();
        for (String raw : allMappings.keySet()) {
            bidIdByRaw.put(raw, seedMinimalBid(annId, raw));
        }

        migrateToV171();

        for (Map.Entry<String, String> e : allMappings.entrySet()) {
            String bidId = bidIdByRaw.get(e.getKey());
            assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                    .as("mapping de '%s'", e.getKey())
                    .containsExactly(e.getValue());
        }
    }

    // ─── FIX 1 : package_requests au format libellés joints par virgule ─────────────

    @Test
    void v171_migratesPackageRequests_commaJoinedLabelFormat() throws Exception {
        String reqId = seedMinimalPackageRequest("Vêtements,Médicaments");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly("Vêtements & tissus, Médicaments traditionnels");
    }

    @Test
    void v171_migratesPackageRequests_legacyCodeFormat() throws Exception {
        String reqId = seedMinimalPackageRequest("VETEMENTS");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly("Vêtements & tissus");
    }

    // ─── FIX B (task-2) : la copie du CASE de package_requests n'était exercée que sur
    //     5 bras/21 — on boucle ici sur les 23 valeurs (9 codes + 14 libellés), comme le
    //     fait déjà v171_migratesAllLegacyCodesAndLabels_onBids pour bids. Chaque valeur
    //     legacy est semée sur une package_request DISTINCTE (une seule valeur par ligne)
    //     pour que l'assertion reste discriminante par bras du CASE.

    @Test
    void v171_migratesAllLegacyCodesAndLabels_onPackageRequests() throws Exception {
        Map<String, String> allMappings = new LinkedHashMap<>();
        allMappings.putAll(LEGACY_CODES);
        allMappings.putAll(LEGACY_LABELS);
        assertThat(allMappings).hasSize(23);

        Map<String, String> reqIdByRaw = new LinkedHashMap<>();
        for (String raw : allMappings.keySet()) {
            reqIdByRaw.put(raw, seedMinimalPackageRequest(raw));
        }

        migrateToV171();

        for (Map.Entry<String, String> e : allMappings.entrySet()) {
            String reqId = reqIdByRaw.get(e.getKey());
            assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                    .as("mapping de '%s'", e.getKey())
                    .containsExactly(e.getValue());
        }
    }

    // ─── FIX B (task-2) : announcement_accepted_types / refused_types n'exerçaient que
    //     ~6 bras/14. Les codes enum ne s'appliquent pas à ces deux tables (texte libre
    //     uniquement, jamais de code enum) — on boucle donc sur les 14 libellés
    //     seulement. Chaque libellé est semé sur une announcement DISTINCTE (un seul item
    //     par annonce) pour que la déduplication intra-table (FIX A / bloc 3 du SQL) ne
    //     fusionne pas deux bras convergents (ex. 'Hi-fi' et 'Téléphone') sur la même
    //     annonce et ne casse l'assertion par bras.

    @Test
    void v171_migratesAllLegacyLabels_onAnnouncementAcceptedTypes() throws Exception {
        assertThat(LEGACY_LABELS).hasSize(14);

        Map<String, String> annIdByRaw = new LinkedHashMap<>();
        for (String raw : LEGACY_LABELS.keySet()) {
            String annId = seedMinimalAnnouncement();
            annIdByRaw.put(raw, annId);
            exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES ('"
                    + annId + "', '" + raw.replace("'", "''") + "')");
        }

        migrateToV171();

        for (Map.Entry<String, String> e : LEGACY_LABELS.entrySet()) {
            String annId = annIdByRaw.get(e.getKey());
            assertThat(queryStrings(
                    "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                    .as("mapping de '%s'", e.getKey())
                    .containsExactly(e.getValue());
        }
    }

    @Test
    void v171_migratesAllLegacyLabels_onAnnouncementRefusedTypes() throws Exception {
        assertThat(LEGACY_LABELS).hasSize(14);

        Map<String, String> annIdByRaw = new LinkedHashMap<>();
        for (String raw : LEGACY_LABELS.keySet()) {
            String annId = seedMinimalAnnouncement();
            annIdByRaw.put(raw, annId);
            exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES ('"
                    + annId + "', '" + raw.replace("'", "''") + "')");
        }

        migrateToV171();

        for (Map.Entry<String, String> e : LEGACY_LABELS.entrySet()) {
            String annId = annIdByRaw.get(e.getKey());
            assertThat(queryStrings(
                    "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                    .as("mapping de '%s'", e.getKey())
                    .containsExactly(e.getValue());
        }
    }

    // ─── FIX 2 : collision accepted/refused sur la même annonce ─────────────────────

    @Test
    void v171_resolvesAcceptedRefusedCollision_keepsAcceptance() throws Exception {
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Alim. sèche')");
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Nourriture')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Alimentation sèche");
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                .isEmpty();
    }

    @Test
    void v171_resolvesAcceptedRefusedCollision_hifiTelephoneVariant() throws Exception {
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Hi-fi')");
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Téléphone'), ('" + annId + "', 'Électronique')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Téléphone & électronique");
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                .isEmpty();
    }

    @Test
    void v171_collisionResolution_doesNotAffectOtherAnnouncements() throws Exception {
        // Un refus qui ne collisionne pas (annonce différente de celle qui accepte) doit survivre.
        String annWithAcceptance = seedMinimalAnnouncement();
        String annWithRefusalOnly = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annWithAcceptance + "', 'Alim. sèche')");
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annWithRefusalOnly + "', 'Nourriture')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annWithRefusalOnly + "'"))
                .containsExactly("Alimentation sèche");
    }

    // ─── FIX A (task-2) : dédup intra-table (bloc 3 du SQL), sans collision accepted/
    //     refused derrière pour ne pas masquer le résultat. Deux libellés legacy
    //     convergents ('Hi-fi' et 'Téléphone' → 'Téléphone & électronique') sur la MÊME
    //     annonce, du même côté (accepted seul, puis refused seul) : sans le DELETE de
    //     dédup, les deux lignes réécrites resteraient distinctes et la table
    //     contiendrait deux lignes identiques après migration.

    @Test
    void v171_deduplicatesAcceptedTypes_convergentLegacyLabelsWithoutRefusalCollision() throws Exception {
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Hi-fi'), ('" + annId + "', 'Téléphone')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Téléphone & électronique");
    }

    @Test
    void v171_deduplicatesRefusedTypes_convergentLegacyLabelsWithoutAcceptedCollision() throws Exception {
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Hi-fi'), ('" + annId + "', 'Téléphone')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Téléphone & électronique");
    }

    // ─── FIX C (task-2) : contradiction préexistante sur du texte libre (bloc 4 du SQL,
    //     effet de bord assumé — cf. commentaire ajouté dans le SQL). Un accepted et un
    //     refused valant déjà le MÊME texte libre non reconnu par le CASE ('Poissons')
    //     avant même la migration : le refus est supprimé, l'acceptation reste.

    @Test
    void v171_preexistingFreeTextContradiction_refusalRemovedAcceptanceKept() throws Exception {
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Poissons')");
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Poissons')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Poissons");
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                .isEmpty();
    }

    // ─── FIX 3 : déduplication de bids.content_category ──────────────────────────────

    @Test
    void v171_deduplicatesBidsContentCategory_preservingFirstOccurrenceOrder() throws Exception {
        String annId = seedMinimalAnnouncement();
        String bidId = seedMinimalBid(annId, "Hi-fi, Téléphone");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Téléphone & électronique");
    }

    @Test
    void v171_deduplicatesPackageRequestsContentCategory() throws Exception {
        String reqId = seedMinimalPackageRequest("Hi-fi, Téléphone, Poissons");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly("Téléphone & électronique, Poissons");
    }

    // ─── FIX 5 : insensibilité à la casse ─────────────────────────────────────────────

    @Test
    void v171_isCaseInsensitive_lowercaseFreeTextMatchesLegacyLabel() throws Exception {
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'hi-fi')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Téléphone & électronique");
    }

    @Test
    void v171_isCaseInsensitive_onBidsCommaJoinedString() throws Exception {
        String annId = seedMinimalAnnouncement();
        String bidId = seedMinimalBid(annId, "HI-FI, téléphone");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Téléphone & électronique");
    }

    // ─── Valeurs libres préservées, casse comprise ───────────────────────────────────

    @Test
    void v171_preservesUnknownFreeTextValues_withOriginalCasing() throws Exception {
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Poissons')");
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Liquides')");
        String bidId = seedMinimalBid(annId, "Poissons, Liquides");
        String reqId = seedMinimalPackageRequest("Poissons");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Poissons");
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Liquides");
        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Poissons, Liquides");
        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly("Poissons");
    }

    // ─── Cas limites ───────────────────────────────────────────────────────────────

    @Test
    void v171_handlesNullBidContentCategory() throws Exception {
        String annId = seedMinimalAnnouncement();
        String senderId = seedMinimalUser();
        String bidId = insertReturningId(
                "INSERT INTO bids (announcement_id, sender_id, weight_kg, declared_value_eur, content_category) "
                        + "VALUES ('" + annId + "', '" + senderId + "', 5.00, 50.00, NULL) RETURNING id");

        migrateToV171();

        assertThat(queryNullableString("SELECT content_category FROM bids WHERE id='" + bidId + "'")).isNull();
    }

    @Test
    void v171_handlesEmptyStringContentCategory() throws Exception {
        String annId = seedMinimalAnnouncement();
        String bidId = seedMinimalBid(annId, "");
        String reqId = seedMinimalPackageRequest("");

        migrateToV171();

        assertThat(queryNullableString("SELECT content_category FROM bids WHERE id='" + bidId + "'")).isEqualTo("");
        assertThat(queryNullableString("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .isEqualTo("");
    }

    @Test
    void v171_handlesSingleItemWithoutComma() throws Exception {
        String annId = seedMinimalAnnouncement();
        String bidId = seedMinimalBid(annId, "Hi-fi");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Téléphone & électronique");
    }

    @Test
    void v171_handlesIrregularWhitespace() throws Exception {
        String annId = seedMinimalAnnouncement();
        String bidId = seedMinimalBid(annId, " Vêtements , Hi-fi ");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Vêtements & tissus, Téléphone & électronique");
    }

    @Test
    void v171_alreadyCanonicalValue_isUnchangedOnFirstPass() throws Exception {
        String annId = seedMinimalAnnouncement();
        String bidId = seedMinimalBid(annId, "Téléphone & électronique, Autre");
        String reqId = seedMinimalPackageRequest("Documents & administratif");
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Cadeaux & jouets')");

        migrateToV171();

        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Téléphone & électronique, Autre");
        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly("Documents & administratif");
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactly("Cadeaux & jouets");
    }

    // ─── Idempotence : rejouer le corps complet de la migration ne change rien ──────

    @Test
    void v171_isIdempotent_whenBodyIsReplayed() throws Exception {
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Hi-fi'), "
                + "('" + annId + "', 'Téléphone'), "
                + "('" + annId + "', 'Alim. sèche'), "
                + "('" + annId + "', 'Poissons')");
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Médicaments'), "
                + "('" + annId + "', 'Liquides')");
        String bidId = seedMinimalBid(annId, "Vêtements, Hi-fi, Poissons");
        String reqId = seedMinimalPackageRequest("VETEMENTS");

        migrateToV171();

        List<String> acceptedAfterMigration = queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "' ORDER BY content_type");
        List<String> refusedAfterMigration = queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "' ORDER BY content_type");
        String bidAfterMigration = queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'").get(0);
        String reqAfterMigration =
                queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'").get(0);

        // Rejouer le corps complet de V171 (y compris ALTER COLUMN TYPE TEXT, idempotent lui aussi).
        exec(readMigrationBody());

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "' ORDER BY content_type"))
                .isEqualTo(acceptedAfterMigration);
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "' ORDER BY content_type"))
                .isEqualTo(refusedAfterMigration);
        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly(bidAfterMigration);
        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly(reqAfterMigration);
    }

    // ─── C1 : corridor_alert_content_categories (5e emplacement) ────────────────────

    @Test
    void v171_migratesCorridorAlertContentCategories() throws Exception {
        String alertId = seedMinimalCorridorAlert();
        exec("INSERT INTO corridor_alert_content_categories (alert_id, content_category) VALUES "
                + "('" + alertId + "', 'Nourriture')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_category FROM corridor_alert_content_categories WHERE alert_id='" + alertId + "'"))
                .containsExactly("Alimentation sèche");
    }

    @Test
    void v171_migratesAllLegacyLabels_onCorridorAlertContentCategories() throws Exception {
        assertThat(LEGACY_LABELS).hasSize(14);

        Map<String, String> alertIdByRaw = new LinkedHashMap<>();
        for (String raw : LEGACY_LABELS.keySet()) {
            String alertId = seedMinimalCorridorAlert();
            alertIdByRaw.put(raw, alertId);
            exec("INSERT INTO corridor_alert_content_categories (alert_id, content_category) VALUES ('"
                    + alertId + "', '" + raw.replace("'", "''") + "')");
        }

        migrateToV171();

        for (Map.Entry<String, String> e : LEGACY_LABELS.entrySet()) {
            String alertId = alertIdByRaw.get(e.getKey());
            assertThat(queryStrings(
                    "SELECT content_category FROM corridor_alert_content_categories WHERE alert_id='" + alertId + "'"))
                    .as("mapping de '%s'", e.getKey())
                    .containsExactly(e.getValue());
        }
    }

    @Test
    void v171_deduplicatesCorridorAlertContentCategories_convergentLegacyLabels() throws Exception {
        String alertId = seedMinimalCorridorAlert();
        exec("INSERT INTO corridor_alert_content_categories (alert_id, content_category) VALUES "
                + "('" + alertId + "', 'Hi-fi'), ('" + alertId + "', 'Téléphone')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_category FROM corridor_alert_content_categories WHERE alert_id='" + alertId + "'"))
                .containsExactly("Téléphone & électronique");
    }

    @Test
    void v171_corridorAlertDedup_doesNotAffectOtherAlerts() throws Exception {
        String alertWithDuplicate = seedMinimalCorridorAlert();
        String otherAlert = seedMinimalCorridorAlert();
        exec("INSERT INTO corridor_alert_content_categories (alert_id, content_category) VALUES "
                + "('" + alertWithDuplicate + "', 'Hi-fi'), ('" + alertWithDuplicate + "', 'Téléphone')");
        exec("INSERT INTO corridor_alert_content_categories (alert_id, content_category) VALUES "
                + "('" + otherAlert + "', 'Nourriture')");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT content_category FROM corridor_alert_content_categories WHERE alert_id='" + otherAlert + "'"))
                .containsExactly("Alimentation sèche");
    }

    // ─── C1 (bug adjacent) : AlertService.fitsAlertCategory sur colis multi-catégories ──
    // Test dédié au bug (pas à la migration SQL) dans AlertServiceMatchesTest —
    // v171_migratesCorridorAlertContentCategories ci-dessus couvre uniquement la migration.

    // ─── I1 : trip_recurrences.accepted_categories / trip_templates.accepted_categories ──

    @Test
    void v171_migratesTripRecurrenceAcceptedCategories_commaJoined() throws Exception {
        String recId = seedMinimalTripRecurrence("Hi-fi,Vêtements");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT accepted_categories FROM trip_recurrences WHERE id='" + recId + "'"))
                .containsExactly("Téléphone & électronique, Vêtements & tissus");
    }

    @Test
    void v171_migratesTripRecurrenceAcceptedCategories_legacyCode() throws Exception {
        String recId = seedMinimalTripRecurrence("VETEMENTS");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT accepted_categories FROM trip_recurrences WHERE id='" + recId + "'"))
                .containsExactly("Vêtements & tissus");
    }

    @Test
    void v171_migratesTripRecurrenceAcceptedCategories_dedupAndPreservesUnknown() throws Exception {
        String recId = seedMinimalTripRecurrence("Hi-fi, Téléphone, Poissons");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT accepted_categories FROM trip_recurrences WHERE id='" + recId + "'"))
                .containsExactly("Téléphone & électronique, Poissons");
    }

    @Test
    void v171_migratesTripTemplateAcceptedCategories_commaJoined() throws Exception {
        String tplId = seedMinimalTripTemplate("Hi-fi,Vêtements");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT accepted_categories FROM trip_templates WHERE id='" + tplId + "'"))
                .containsExactly("Téléphone & électronique, Vêtements & tissus");
    }

    @Test
    void v171_handlesNullTripRecurrenceAcceptedCategories() throws Exception {
        String recId = seedMinimalTripRecurrence(null);

        migrateToV171();

        assertThat(queryNullableString(
                "SELECT accepted_categories FROM trip_recurrences WHERE id='" + recId + "'")).isNull();
    }

    // ─── I2 : automation_rules.conditions (JSONB) ────────────────────────────────────

    @Test
    void v171_migratesAutomationRuleConditions_contentTypeValue() throws Exception {
        String ruleId = seedMinimalAutomationRule(
                "[{\"field\":\"content_type\",\"operator\":\"eq\",\"value\":\"Vêtements\"}]");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT conditions->0->>'value' FROM automation_rules WHERE id='" + ruleId + "'"))
                .containsExactly("Vêtements & tissus");
        assertThat(queryStrings(
                "SELECT conditions->0->>'field' FROM automation_rules WHERE id='" + ruleId + "'"))
                .containsExactly("content_type");
        assertThat(queryStrings(
                "SELECT conditions->0->>'operator' FROM automation_rules WHERE id='" + ruleId + "'"))
                .containsExactly("eq");
    }

    @Test
    void v171_migratesAutomationRuleConditions_preservesNonContentTypeConditionAndOrder() throws Exception {
        String ruleId = seedMinimalAutomationRule(
                "[{\"field\":\"weight_kg\",\"operator\":\"gte\",\"value\":\"5\"},"
                        + "{\"field\":\"content_type\",\"operator\":\"eq\",\"value\":\"Hi-fi\"}]");

        migrateToV171();

        // Ordre préservé : weight_kg reste en position 0, intact ; content_type migré en position 1.
        assertThat(queryStrings(
                "SELECT conditions->0->>'field' FROM automation_rules WHERE id='" + ruleId + "'"))
                .containsExactly("weight_kg");
        assertThat(queryStrings(
                "SELECT conditions->0->>'value' FROM automation_rules WHERE id='" + ruleId + "'"))
                .containsExactly("5");
        assertThat(queryStrings(
                "SELECT conditions->0->>'operator' FROM automation_rules WHERE id='" + ruleId + "'"))
                .containsExactly("gte");
        assertThat(queryStrings(
                "SELECT conditions->1->>'field' FROM automation_rules WHERE id='" + ruleId + "'"))
                .containsExactly("content_type");
        assertThat(queryStrings(
                "SELECT conditions->1->>'value' FROM automation_rules WHERE id='" + ruleId + "'"))
                .containsExactly("Téléphone & électronique");
        assertThat(queryStrings(
                "SELECT jsonb_array_length(conditions)::text FROM automation_rules WHERE id='" + ruleId + "'"))
                .containsExactly("2");
    }

    @Test
    void v171_automationRuleConditions_withoutContentType_isUntouched() throws Exception {
        String ruleId = seedMinimalAutomationRule(
                "[{\"field\":\"weight_kg\",\"operator\":\"gte\",\"value\":\"5\"}]");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT conditions->0->>'value' FROM automation_rules WHERE id='" + ruleId + "'"))
                .containsExactly("5");
    }

    @Test
    void v171_automationRuleConditions_alreadyCanonical_isUnchanged() throws Exception {
        String ruleId = seedMinimalAutomationRule(
                "[{\"field\":\"content_type\",\"operator\":\"eq\",\"value\":\"Vêtements & tissus\"}]");

        migrateToV171();

        assertThat(queryStrings(
                "SELECT conditions->0->>'value' FROM automation_rules WHERE id='" + ruleId + "'"))
                .containsExactly("Vêtements & tissus");
    }

    // ─── Helpers de seed ────────────────────────────────────────────────────────
    // Le minimum de colonnes NOT NULL sans DEFAULT, déterminé en lisant le schéma réel :
    // V1 (users), V3 (announcements/bids), V34 (adresses précises announcements),
    // V35/V41 (transport_mode/total_kg announcements), V57 (package_requests),
    // V64 (transport_mode package_requests).

    private String seedMinimalUser() throws Exception {
        String uid = "uid-" + UUID.randomUUID();
        return insertReturningId(
                "INSERT INTO users (firebase_uid) VALUES ('" + uid + "') RETURNING id");
    }

    private String seedMinimalAnnouncement() throws Exception {
        String travelerId = seedMinimalUser();
        return insertReturningId(
                "INSERT INTO announcements (traveler_id, departure_city, arrival_city, departure_date, "
                        + "available_kg, price_per_kg, transport_mode, total_kg, "
                        + "pickup_address_label, pickup_lat, pickup_lng, "
                        + "delivery_address_label, delivery_lat, delivery_lng) VALUES ("
                        + "'" + travelerId + "', 'Paris', 'Dakar', CURRENT_DATE + 7, "
                        + "20.00, 15.00, 'PLANE', 20.00, "
                        + "'12 rue de Paris', 48.8566, 2.3522, "
                        + "'Plateau, Dakar', 14.6928, -17.4467) RETURNING id");
    }

    private String seedMinimalBid(String announcementId, String contentCategory) throws Exception {
        String senderId = seedMinimalUser();
        return insertReturningId(
                "INSERT INTO bids (announcement_id, sender_id, weight_kg, declared_value_eur, content_category) "
                        + "VALUES ('" + announcementId + "', '" + senderId + "', 5.00, 50.00, "
                        + "'" + contentCategory.replace("'", "''") + "') RETURNING id");
    }

    private String seedMinimalPackageRequest(String contentCategory) throws Exception {
        String senderId = seedMinimalUser();
        return insertReturningId(
                "INSERT INTO package_requests (sender_id, departure_city, arrival_city, desired_date, "
                        + "weight_kg, parcel_size, content_category, transport_mode) VALUES ("
                        + "'" + senderId + "', 'Lyon', 'Abidjan', CURRENT_DATE + 7, "
                        + "5.00, 'MEDIUM', '" + contentCategory.replace("'", "''") + "', 'PLANE') RETURNING id");
    }

    /** V148/V150 : owner_id (renommé depuis traveler_id par V149), departure_city, arrival_city NOT NULL sans DEFAULT. */
    private String seedMinimalCorridorAlert() throws Exception {
        String ownerId = seedMinimalUser();
        return insertReturningId(
                "INSERT INTO corridor_alerts (owner_id, departure_city, arrival_city) VALUES ("
                        + "'" + ownerId + "', 'Paris', 'Bamako') RETURNING id");
    }

    /** V110 : user_id, departure_city, arrival_city, available_kg, price_per_kg, pickup/delivery,
     *  weekdays NOT NULL sans DEFAULT. accepted_categories nullable — {@code null} accepté ici. */
    private String seedMinimalTripRecurrence(String acceptedCategories) throws Exception {
        String userId = seedMinimalUser();
        String categoriesSql = acceptedCategories == null
                ? "NULL" : "'" + acceptedCategories.replace("'", "''") + "'";
        return insertReturningId(
                "INSERT INTO trip_recurrences (user_id, departure_city, arrival_city, available_kg, price_per_kg, "
                        + "accepted_categories, pickup_label, pickup_lat, pickup_lng, "
                        + "delivery_label, delivery_lat, delivery_lng, weekdays) VALUES ("
                        + "'" + userId + "', 'Paris', 'Dakar', 20.0, 15.0, "
                        + categoriesSql + ", "
                        + "'12 rue de Paris', 48.8566, 2.3522, "
                        + "'Plateau, Dakar', 14.6928, -17.4467, '1111111') RETURNING id");
    }

    /** V108 : user_id, label, departure_city, arrival_city, price_per_kg NOT NULL sans DEFAULT. */
    private String seedMinimalTripTemplate(String acceptedCategories) throws Exception {
        String userId = seedMinimalUser();
        return insertReturningId(
                "INSERT INTO trip_templates (user_id, label, departure_city, arrival_city, price_per_kg, "
                        + "accepted_categories) VALUES ("
                        + "'" + userId + "', 'Mon trajet', 'Paris', 'Dakar', 15.0, "
                        + "'" + acceptedCategories.replace("'", "''") + "') RETURNING id");
    }

    /** V81 : traveler_id, rule_type (CHECK IN ('PRESET','CUSTOM')), name NOT NULL sans DEFAULT. */
    private String seedMinimalAutomationRule(String conditionsJson) throws Exception {
        String travelerId = seedMinimalUser();
        return insertReturningId(
                "INSERT INTO automation_rules (traveler_id, rule_type, name, conditions) VALUES ("
                        + "'" + travelerId + "', 'CUSTOM', 'Règle test', "
                        + "'" + conditionsJson.replace("'", "''") + "'::jsonb) RETURNING id");
    }
}
