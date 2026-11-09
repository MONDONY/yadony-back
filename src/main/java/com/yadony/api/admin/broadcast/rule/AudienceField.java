package com.yadony.api.admin.broadcast.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.billing.ProSubscriptionStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.kyc.KycVerificationStatus;
import com.yadony.api.matching.AnnouncementStatus;
import org.springframework.http.HttpStatus;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Catalogue ferme des champs ciblables. Tout ce qui finit dans le SQL vient d'ici : un champ
 * inconnu, un opérateur non permis ou une valeur de mauvais type donnent un 422 avant toute
 * generation. Les valeurs passent toujours par {@link ParamSink#bind}.
 *
 * <p>Le ciblage est comportemental : jamais de filtre sur {@code user_roles} (depuis V193,
 * tout le monde porte SENDER + TRAVELER).
 */
public enum AudienceField {

    KILO_PRO("kilo_pro", Family.STATUS, "Kilo Pro",
            Map.of(AudienceOperator.IS, ValueType.BOOLEAN), List.of(),
            (op, value, p) -> "u.kilo_pro = " + p.bind(value.asBoolean())),

    LANGUAGE("language", Family.STATUS, "Langue",
            Map.of(AudienceOperator.IN, ValueType.ENUM_MULTI),
            List.of(new ValueOption("fr", "Français"), new ValueOption("en", "Anglais")),
            (op, value, p) -> "u.preferred_language IN (" + p.bind(texts(value)) + ")"),

    GP_PRO("gp_pro", Family.STATUS, "Abonnement GP Pro",
            Map.of(AudienceOperator.IS, ValueType.ENUM),
            List.of(new ValueOption("ACTIVE", "Actif"), new ValueOption("EXPIRED", "Expiré"),
                    new ValueOption("NEVER", "Jamais abonné")),
            (op, value, p) -> {
                String any = "EXISTS (SELECT 1 FROM pro_subscriptions s WHERE s.user_id = u.id AND s.deleted_at IS NULL)";
                String access = "EXISTS (SELECT 1 FROM pro_subscriptions s WHERE s.user_id = u.id AND s.deleted_at IS NULL"
                        + " AND s.status IN (" + p.bind(proAccessStatuses()) + "))";
                return switch (value.asText()) {
                    case "ACTIVE" -> access;
                    case "EXPIRED" -> any + " AND NOT " + access;
                    default -> "NOT " + any;
                };
            }),

    KYC("kyc", Family.STATUS, "Identité",
            Map.of(AudienceOperator.IN, ValueType.ENUM_MULTI),
            List.of(new ValueOption(KycStatus.NOT_STARTED.name(), "Pas commencée"),
                    new ValueOption(KycStatus.PENDING.name(), "En cours"),
                    new ValueOption(KycStatus.VERIFIED.name(), "Vérifiée"),
                    new ValueOption(KycStatus.REJECTED.name(), "Refusée")),
            (op, value, p) -> "u.kyc_status IN (" + p.bind(texts(value)) + ")"),

    // Une vérification fermée ou expirée repasse l'utilisateur à NOT_STARTED (KycStatusTransitionService
    // .markRestartable) : l'état « pas commencée » mélange donc ceux qui n'ont jamais ouvert le parcours
    // et ceux qui l'ont quitté. Seuls ces derniers ont une ligne de vérification encore en attente, sans
    // soumission. Le délai de deux heures écarte ceux qui sont encore dans la fenêtre du fournisseur.
    KYC_ABANDONED("kyc_abandoned", Family.STATUS, "Identité commencée puis abandonnée",
            Map.of(AudienceOperator.IS, ValueType.BOOLEAN), List.of(),
            (op, value, p) -> (value.asBoolean() ? "" : "NOT ")
                    + "(u.kyc_status = " + p.bind(KycStatus.NOT_STARTED.name())
                    + " AND EXISTS (SELECT 1 FROM kyc_schema.kyc_verifications k WHERE k.user_id = u.id"
                    + " AND k.deleted_at IS NULL AND k.status = " + p.bind(KycVerificationStatus.PENDING.name())
                    + " AND k.submitted_at IS NULL AND k.updated_at < now() - make_interval(hours => 2)))"),

    STRIPE_CONNECT("stripe_connect", Family.MONEY, "Compte de versement Stripe",
            Map.of(AudienceOperator.IN, ValueType.ENUM_MULTI),
            List.of(new ValueOption(StripeAccountStatus.NOT_CREATED.name(), "Pas créé"),
                    new ValueOption(StripeAccountStatus.PENDING_ONBOARDING.name(), "Inscription en cours"),
                    new ValueOption(StripeAccountStatus.ONBOARDING_COMPLETE.name(), "Terminé"),
                    new ValueOption(StripeAccountStatus.REJECTED.name(), "Refusé"),
                    new ValueOption(StripeAccountStatus.DISABLED.name(), "Désactivé")),
            (op, value, p) -> "u.stripe_account_status IN (" + p.bind(texts(value)) + ")"),

    MOBILE_MONEY("mobile_money", Family.MONEY, "Mobile money",
            Map.of(AudienceOperator.IN, ValueType.ENUM_MULTI),
            Arrays.stream(MobileMoneyPayoutStatus.values())
                    .map(s -> new ValueOption(s.name(), s.name())).toList(),
            (op, value, p) -> "u.mobile_money_status IN (" + p.bind(texts(value)) + ")"),

    ACCOUNT_TYPE("account_type", Family.STATUS, "Type de compte",
            Map.of(AudienceOperator.IS, ValueType.ENUM),
            List.of(new ValueOption("PERSONAL", "Particulier"), new ValueOption("BUSINESS", "Professionnel")),
            (op, value, p) -> "u.is_pro_account = " + ("BUSINESS".equals(value.asText()) ? "true" : "false")),

    // Source du solde : wallet_accounts, comme l'écran des portefeuilles de l'admin (AdminFinanceSql).
    WALLET_BALANCE("wallet_balance", Family.MONEY, "Solde Yadony",
            Map.of(AudienceOperator.IS, ValueType.ENUM),
            List.of(new ValueOption("POSITIVE", "Positif"), new ValueOption("ZERO", "Nul")),
            (op, value, p) -> ("POSITIVE".equals(value.asText()) ? "" : "NOT ")
                    + "EXISTS (SELECT 1 FROM wallet_accounts w WHERE w.user_id = u.id"
                    + " AND w.deleted_at IS NULL AND w.balance > 0)"),

    // Un code rendu (released_at renseigné) n'a pas été consommé : il ne compte pas comme utilisé.
    PROMO_USED("promo_used", Family.MONEY, "Code promo utilisé",
            Map.of(AudienceOperator.EVER, ValueType.NONE, AudienceOperator.NEVER, ValueType.NONE,
                    AudienceOperator.IS, ValueType.TEXT),
            List.of(),
            (op, value, p) -> switch (op) {
                case EVER -> "EXISTS (SELECT 1 FROM promo_redemptions r WHERE r.user_id = u.id AND r.released_at IS NULL)";
                case NEVER -> "NOT EXISTS (SELECT 1 FROM promo_redemptions r WHERE r.user_id = u.id AND r.released_at IS NULL)";
                default -> "EXISTS (SELECT 1 FROM promo_redemptions r JOIN promo_codes c ON c.id = r.promo_code_id"
                        + " WHERE r.user_id = u.id AND r.released_at IS NULL AND upper(c.code) = upper("
                        + p.bind(value.asText().trim()) + "))";
            }),

    REFERRAL("referral", Family.STATUS, "Parrainage",
            Map.of(AudienceOperator.IS, ValueType.ENUM),
            List.of(new ValueOption("REFERRED", "Filleul"), new ValueOption("REFERRER", "Parrain")),
            (op, value, p) -> "REFERRED".equals(value.asText())
                    ? "EXISTS (SELECT 1 FROM referral_invitations i WHERE i.referee_user_id = u.id AND i.deleted_at IS NULL)"
                    : "EXISTS (SELECT 1 FROM referral_invitations i WHERE i.referrer_user_id = u.id"
                            + " AND i.referee_user_id IS NOT NULL AND i.deleted_at IS NULL)"),

    PUBLISHED_TRIP("published_trip", Family.ACTIVITY, "A publié un trajet", activityOps(), List.of(),
            (op, value, p) -> existsSince(op, value, p, "announcements a", "a.traveler_id = u.id", "a")),

    CREATED_REQUEST("created_request", Family.ACTIVITY, "A fait une demande d'envoi", activityOps(), List.of(),
            (op, value, p) -> existsSince(op, value, p, "bids b", "b.sender_id = u.id", "b")),

    // Trajet encore à vendre (ACTIVE ou FULL) dont le départ tombe dans les n prochains jours : le moment
    // où une annonce « arrive à expiration » pour le voyageur. Un trajet déjà parti n'est pas retenu.
    TRIP_DEPARTING("trip_departing", Family.ACTIVITY, "Trajet actif qui part bientôt",
            Map.of(AudienceOperator.WITHIN_DAYS, ValueType.INT), List.of(),
            (op, value, p) -> "EXISTS (SELECT 1 FROM announcements a WHERE a.traveler_id = u.id"
                    + " AND a.deleted_at IS NULL AND a.status IN ("
                    + p.bind(List.of(AnnouncementStatus.ACTIVE.name(), AnnouncementStatus.FULL.name())) + ")"
                    + " AND a.departure_date >= current_date"
                    + " AND a.departure_date <= current_date + make_interval(days => " + p.bind(value.asInt()) + "))"),

    COMPLETED_TRIPS("completed_trips", Family.ACTIVITY, "Trajets réalisés", countOps(), List.of(),
            (op, value, p) -> "u.total_trips " + cmp(op) + " " + p.bind(value.asInt())),

    COMPLETED_SHIPMENTS("completed_shipments", Family.ACTIVITY, "Colis envoyés", countOps(), List.of(),
            (op, value, p) -> "u.total_shipments " + cmp(op) + " " + p.bind(value.asInt())),

    LAST_SEEN("last_seen", Family.ACTIVITY, "Dernière visite", daysOps(), List.of(),
            (op, value, p) -> op == AudienceOperator.WITHIN_DAYS
                    ? "u.last_seen_at >= now() - make_interval(days => " + p.bind(value.asInt()) + ")"
                    : "(u.last_seen_at IS NULL OR u.last_seen_at < now() - make_interval(days => "
                            + p.bind(value.asInt()) + "))"),

    SIGNED_UP("signed_up", Family.ACTIVITY, "Inscription", daysOps(), List.of(),
            (op, value, p) -> op == AudienceOperator.WITHIN_DAYS
                    ? "u.created_at >= now() - make_interval(days => " + p.bind(value.asInt()) + ")"
                    : "u.created_at < now() - make_interval(days => " + p.bind(value.asInt()) + ")"),

    CORRIDOR_INTEREST("corridor_interest", Family.GEO, "Intérêt pour un corridor",
            Map.of(AudienceOperator.MATCHES, ValueType.CORRIDOR), List.of(),
            (op, value, p) -> corridorSql(value, p)),

    CORRIDOR_ALERT("corridor_alert", Family.GEO, "A une alerte de corridor",
            Map.of(AudienceOperator.IS, ValueType.BOOLEAN), List.of(),
            (op, value, p) -> (value.asBoolean() ? "" : "NOT ")
                    + "EXISTS (SELECT 1 FROM corridor_alerts c WHERE c.owner_id = u.id AND c.active AND c.deleted_at IS NULL)"),

    COUNTRY("country", Family.GEO, "Pays de résidence",
            Map.of(AudienceOperator.IN, ValueType.ENUM_MULTI), countryOptions(),
            (op, value, p) -> "upper(u.country) IN (" + p.bind(texts(value)) + ")"),

    INTENT_DESTINATION("intent_destination", Family.GEO, "Pays de destination déclaré",
            Map.of(AudienceOperator.IN, ValueType.ENUM_MULTI), countryOptions(),
            (op, value, p) -> "upper(u.intent_destination_country) IN (" + p.bind(texts(value)) + ")");

    /** Generateur du fragment booleen, appele apres validation. */
    @FunctionalInterface
    interface SqlBuilder {
        String build(AudienceOperator op, JsonNode value, ParamSink params);
    }

    private static final Set<String> CORRIDOR_SOURCES = new LinkedHashSet<>(List.of("PUBLISHED", "REQUESTED", "ALERT"));

    /**
     * Tous les pays ISO 3166, libellés en français et triés par libellé. Classe imbriquée : un champ
     * statique de l'enum serait encore nul pendant la création des constantes (init paresseuse).
     */
    private static final class Countries {
        static final Map<String, String> ALL = build();

        private static Map<String, String> build() {
            Map<String, String> byCode = new LinkedHashMap<>();
            for (String cc : Locale.getISOCountries()) {
                byCode.put(cc, Locale.of("", cc).getDisplayCountry(Locale.FRENCH));
            }
            Collator collator = Collator.getInstance(Locale.FRENCH);
            Map<String, String> sorted = new LinkedHashMap<>();
            byCode.entrySet().stream()
                    .sorted((x, y) -> collator.compare(x.getValue(), y.getValue()))
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));
            return sorted;
        }
    }

    private static final int MAX_DAYS = 3650;
    private static final int MAX_COUNT = 10_000;
    private static final int MAX_TEXT = 100;

    private final String key;
    private final Family family;
    private final String label;
    private final Map<AudienceOperator, ValueType> operators;
    private final List<ValueOption> values;
    private final SqlBuilder sql;

    AudienceField(String key, Family family, String label, Map<AudienceOperator, ValueType> operators,
                  List<ValueOption> values, SqlBuilder sql) {
        this.key = key;
        this.family = family;
        this.label = label;
        this.operators = new EnumMap<>(operators);
        this.values = List.copyOf(values);
        this.sql = sql;
    }

    public String key() { return key; }

    public Family family() { return family; }

    public String label() { return label; }

    public Set<AudienceOperator> operators() { return operators.keySet(); }

    /** Type de valeur attendu pour cet operateur (R1 : porte par le couple champ + operateur). */
    public ValueType valueType(AudienceOperator op) { return operators.get(op); }

    /** Valeurs proposees (renomme {@code options} : {@code values()} est deja la methode statique de l'enum). */
    public List<ValueOption> options() { return values; }

    /** Valide l'operateur et la valeur, puis renvoie le fragment booleen sur l'alias {@code u}. */
    public String toSql(AudienceOperator op, JsonNode value, ParamSink params) {
        ValueType type = operators.get(op);
        if (type == null) {
            throw invalid("L'opérateur " + op + " n'est pas permis pour le champ « " + label + " »");
        }
        validate(op, type, value);
        return sql.build(op, value, params);
    }

    public static AudienceField fromKey(String key) {
        for (AudienceField f : AudienceField.values()) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        throw invalid("Champ d'audience inconnu");
    }

    /** Visible du package pour tester les types sans champ du catalogue (NONE). */
    void validate(AudienceOperator op, ValueType type, JsonNode value) {
        switch (type) {
            case NONE -> {
                if (value != null && !value.isNull()) {
                    throw invalid("L'opérateur " + op + " du champ « " + label + " » n'accepte aucune valeur");
                }
            }
            case BOOLEAN -> {
                if (value == null || !value.isBoolean()) {
                    throw bad("un booléen");
                }
            }
            case ENUM -> {
                if (value == null || !value.isTextual() || !knownEnum(value.asText())) {
                    throw bad("une valeur connue");
                }
            }
            case ENUM_MULTI -> {
                if (value == null || !value.isArray() || value.isEmpty()) {
                    throw bad("une liste non vide de valeurs connues");
                }
                for (JsonNode v : value) {
                    if (!v.isTextual() || !knownEnum(v.asText())) {
                        throw bad("une liste de valeurs connues");
                    }
                }
            }
            case INT -> {
                int max = op == AudienceOperator.WITHIN_DAYS || op == AudienceOperator.OLDER_THAN_DAYS
                        ? MAX_DAYS : MAX_COUNT;
                if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                        || value.asInt() < 0 || value.asInt() > max) {
                    throw bad("un entier entre 0 et " + max);
                }
            }
            case TEXT -> {
                if (!validText(value)) {
                    throw bad("un texte non vide de " + MAX_TEXT + " caractères au plus");
                }
            }
            case CORRIDOR -> validateCorridor(value);
        }
    }

    private void validateCorridor(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw bad("un corridor");
        }
        JsonNode kind = value.get("kind");
        if (kind == null || !kind.isTextual() || !Set.of("CITY", "COUNTRY").contains(kind.asText())) {
            throw bad("un corridor de type CITY ou COUNTRY");
        }
        boolean country = "COUNTRY".equals(kind.asText());
        int bounds = 0;
        for (String name : List.of("origin", "destination")) {
            JsonNode b = value.get(name);
            if (b == null || b.isNull()) {
                continue;
            }
            if (!b.isTextual() || b.asText().length() > MAX_TEXT
                    || (country && !b.asText().isBlank()
                    && !Countries.ALL.containsKey(b.asText().trim().toUpperCase(Locale.ROOT)))) {
                throw bad(country ? "un corridor dont les bornes sont des codes pays ISO à 2 lettres"
                        : "un corridor dont les villes font " + MAX_TEXT + " caractères au plus");
            }
            if (!b.asText().isBlank()) {
                bounds++;
            }
        }
        if (bounds == 0) {
            throw bad("un corridor avec au moins une borne (origin ou destination)");
        }
        JsonNode sources = value.get("sources");
        if (sources != null && !sources.isNull()) {
            if (!sources.isArray() || sources.isEmpty()) {
                throw bad("un corridor dont les sources forment une liste non vide");
            }
            for (JsonNode n : sources) {
                if (!n.isTextual() || !CORRIDOR_SOURCES.contains(n.asText())) {
                    throw bad("un corridor dont les sources sont PUBLISHED, REQUESTED ou ALERT");
                }
            }
        }
    }

    private boolean knownEnum(String v) {
        return values.stream().anyMatch(o -> o.key().equals(v));
    }


    private static Map<AudienceOperator, ValueType> activityOps() {
        return Map.of(AudienceOperator.EVER, ValueType.NONE, AudienceOperator.NEVER, ValueType.NONE,
                AudienceOperator.WITHIN_DAYS, ValueType.INT);
    }

    private static Map<AudienceOperator, ValueType> countOps() {
        return Map.of(AudienceOperator.GTE, ValueType.INT, AudienceOperator.LTE, ValueType.INT);
    }

    private static Map<AudienceOperator, ValueType> daysOps() {
        return Map.of(AudienceOperator.WITHIN_DAYS, ValueType.INT, AudienceOperator.OLDER_THAN_DAYS, ValueType.INT);
    }

    private static String cmp(AudienceOperator op) {
        return op == AudienceOperator.GTE ? ">=" : "<=";
    }

    /** Statuts d'abonnement qui ouvrent l'accès PRO, lus depuis l'enum (jamais en dur). */
    private static List<String> proAccessStatuses() {
        return Arrays.stream(ProSubscriptionStatus.values())
                .filter(ProSubscriptionStatus::grantsProAccess).map(Enum::name).toList();
    }

    private static List<ValueOption> countryOptions() {
        return Countries.ALL.entrySet().stream().map(e -> new ValueOption(e.getKey(), e.getValue())).toList();
    }

    /** {@code [NOT] EXISTS} sur une table d'activité, éventuellement bornée aux n derniers jours. */
    private static String existsSince(AudienceOperator op, JsonNode value, ParamSink p,
                                      String table, String owner, String alias) {
        String sql = "EXISTS (SELECT 1 FROM " + table + " WHERE " + owner + " AND " + alias + ".deleted_at IS NULL";
        if (op == AudienceOperator.WITHIN_DAYS) {
            sql += " AND " + alias + ".created_at >= now() - make_interval(days => " + p.bind(value.asInt()) + ")";
        }
        sql += ")";
        return op == AudienceOperator.NEVER ? "NOT " + sql : sql;
    }

    /**
     * Échappe les jokers d'une saisie destinée à un {@code ILIKE ... ESCAPE '\'} : la saisie
     * est comparée telle quelle, jamais interprétée comme un motif.
     */
    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** OU des EXISTS des sources demandées ; chaque borne absente est ignorée. */
    private static String corridorSql(JsonNode value, ParamSink p) {
        boolean country = "COUNTRY".equals(value.get("kind").asText());
        String origin = optText(value.get("origin"));
        String destination = optText(value.get("destination"));
        String o = origin == null ? null : p.bind(country ? origin.toUpperCase(Locale.ROOT) : escapeLike(origin));
        String d = destination == null ? null : p.bind(country ? destination.toUpperCase(Locale.ROOT) : escapeLike(destination));

        List<String> sources = new ArrayList<>();
        if (value.hasNonNull("sources")) {
            value.get("sources").forEach(n -> sources.add(n.asText()));
        } else {
            sources.addAll(CORRIDOR_SOURCES);
        }
        List<String> parts = new ArrayList<>();
        if (sources.contains("PUBLISHED")) {
            parts.add("EXISTS (SELECT 1 FROM announcements a WHERE a.traveler_id = u.id AND a.deleted_at IS NULL"
                    + bounds(country, o, d, "a.departure_city", "a.arrival_city",
                            "a.departure_country_code", "a.arrival_country_code") + ")");
        }
        if (sources.contains("REQUESTED")) {
            parts.add("EXISTS (SELECT 1 FROM bids b JOIN announcements a2 ON a2.id = b.announcement_id"
                    + " WHERE b.sender_id = u.id AND b.deleted_at IS NULL AND a2.deleted_at IS NULL"
                    + bounds(country, o, d, "a2.departure_city", "a2.arrival_city",
                            "a2.departure_country_code", "a2.arrival_country_code") + ")");
        }
        if (sources.contains("ALERT")) {
            parts.add("EXISTS (SELECT 1 FROM corridor_alerts c WHERE c.owner_id = u.id AND c.active"
                    + " AND c.deleted_at IS NULL"
                    + bounds(country, o, d, "c.departure_city", "c.arrival_city",
                            "c.departure_country_code", "c.arrival_country_code") + ")");
        }
        return "(" + String.join(" OR ", parts) + ")";
    }

    private static String bounds(boolean country, String o, String d,
                                 String depCity, String arrCity, String depCountry, String arrCountry) {
        StringBuilder sb = new StringBuilder();
        if (o != null) {
            sb.append(" AND ").append(country ? "upper(" + depCountry + ") = " + o
                    : depCity + " ILIKE " + o + " ESCAPE '\\'");
        }
        if (d != null) {
            sb.append(" AND ").append(country ? "upper(" + arrCountry + ") = " + d
                    : arrCity + " ILIKE " + d + " ESCAPE '\\'");
        }
        return sb.toString();
    }

    private static String optText(JsonNode v) {
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static boolean validText(JsonNode v) {
        return v != null && v.isTextual() && !v.asText().isBlank() && v.asText().length() <= MAX_TEXT;
    }

    private YadonyBusinessException bad(String expected) {
        return invalid("La valeur du champ « " + label + " » doit être " + expected);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static YadonyBusinessException invalid(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "broadcast-rule-invalid", "Unprocessable Entity", detail);
    }
}
