package com.yadony.api.videomodal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.yadony.api.admin.broadcast.rule.AudienceRule;
import com.yadony.api.admin.broadcast.rule.AudienceSqlCompiler;
import com.yadony.api.admin.broadcast.rule.CompiledRule;
import com.yadony.api.admin.broadcast.rule.RuleAudienceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Ciblage fin d'une campagne pour UN compte : conditions de la Diffusion, situations constatées
 * par le serveur, tirage stable. Réutilise le moteur de règles de {@code admin/broadcast} (arbre
 * {@link AudienceRule}, compilateur SQL, comptage) au lieu de le dupliquer ; la seule porte vers ce
 * moteur est cette classe.
 */
@Component
public class VideoModalTargeting {

    /** Un retour n'est « récent » que dans ce délai : au-delà, la situation « retour » est passée. */
    static final Duration RETURN_WINDOW = Duration.ofHours(24);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RuleAudienceRepository ruleRepository;
    private final VideoModalSituationQueries situations;
    private final Clock clock;

    @Autowired
    public VideoModalTargeting(RuleAudienceRepository ruleRepository, VideoModalSituationQueries situations) {
        this(ruleRepository, situations, Clock.systemUTC());
    }

    VideoModalTargeting(RuleAudienceRepository ruleRepository, VideoModalSituationQueries situations, Clock clock) {
        this.ruleRepository = ruleRepository;
        this.situations = situations;
        this.clock = clock;
    }

    // ── Conditions (règles de la Diffusion) ─────────────────────────────────

    /**
     * Valide l'arbre reçu de l'admin avec le moteur de la Diffusion (forme, champs, opérateurs,
     * valeurs : 422 {@code broadcast-rule-invalid}) et renvoie son JSON canonique, ou {@code null}
     * si l'arbre est absent ou sans condition.
     */
    public String normalizeConditions(JsonNode json) {
        if (json == null || json.isNull() || json.isMissingNode()) return null;
        AudienceRule.Group group = AudienceRule.parse(json);
        if (group.children().isEmpty()) return null;
        AudienceSqlCompiler.compile(group);
        return group.canonicalJson();
    }

    /** Arbre stocké relu pour l'admin ; groupe ET vide quand la campagne n'a aucune condition. */
    public static JsonNode readConditions(String stored) {
        try {
            if (stored == null || stored.isBlank()) return MAPPER.readTree("{\"op\":\"AND\",\"children\":[]}");
            return MAPPER.readTree(stored);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Conditions illisibles", e);
        }
    }

    /** Vrai si le compte fait partie de l'audience de l'arbre stocké. */
    public boolean matchesConditions(String storedConditions, UUID userId) {
        try {
            AudienceRule.Group group = AudienceRule.parse(MAPPER.readTree(storedConditions));
            return ruleRepository.matches(AudienceSqlCompiler.compile(group), userId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Conditions illisibles", e);
        }
    }

    // ── Situations ──────────────────────────────────────────────────────────

    /** La situation est-elle vraie pour ce compte, maintenant ? */
    public boolean holds(VideoModalSituation situation, UUID userId) {
        VideoModalSituationKind kind = situation.kind();
        int value = situation.value() != null ? situation.value() : kind.defaultValue() == null ? 0 : kind.defaultValue();
        Instant now = clock.instant();
        return switch (kind) {
            case OFFER_ACCEPTED_UNPAID -> situations.offerAcceptedUnpaid(userId, cutoff(now, Duration.ofHours(value)));
            case OFFER_UNANSWERED -> situations.offerUnanswered(userId, cutoff(now, Duration.ofHours(value)));
            case COUNTER_OFFER_PENDING -> situations.counterOfferPending(userId, cutoff(now, Duration.ofHours(value)));
            case PROFILE_INCOMPLETE -> situations.profileIncomplete(userId);
            case FIRST_DELIVERY_NO_REFERRAL -> situations.firstDeliveryNoReferral(userId);
            // Règle « identité commencée puis abandonnée » de la Diffusion, telle quelle.
            case KYC_ABANDONED -> matchesRule(userId, new AudienceRule.Condition("kyc_abandoned", "IS", BooleanNode.TRUE));
            // Règles « inscription » et « activité » de la Diffusion : inscrit depuis plus de N jours
            // et jamais de trajet publié ni de demande envoyée.
            case DAYS_SINCE_INSTALL_NO_ACTION -> matchesRule(userId,
                    new AudienceRule.Condition("signed_up", "OLDER_THAN_DAYS", IntNode.valueOf(value)),
                    new AudienceRule.Condition("published_trip", "NEVER", null),
                    new AudienceRule.Condition("created_request", "NEVER", null));
            case RETURN_AFTER_ABSENCE -> returnedAfterAbsence(userId, value, now);
        };
    }

    /**
     * Retour après une absence d'au moins {@code days} jours, constaté dans les dernières 24 h.
     * {@code last_seen_at} (règle {@code last_seen}) est réécrite à l'ouverture de l'app, avant que les
     * modals soient demandés : l'absence est donc lue dans les colonnes de coupure de {@code users}.
     */
    private boolean returnedAfterAbsence(UUID userId, int days, Instant now) {
        return situations.lastAbsence(userId).map(a -> {
            Instant started = a.startedAt().toInstant(ZoneOffset.UTC);
            Instant returned = a.returnedAt().toInstant(ZoneOffset.UTC);
            return !returned.isBefore(now.minus(RETURN_WINDOW))
                    && Duration.between(started, returned).compareTo(Duration.ofDays(days)) >= 0;
        }).orElse(false);
    }

    private boolean matchesRule(UUID userId, AudienceRule.Condition... conditions) {
        AudienceRule.Group group = new AudienceRule.Group(AudienceRule.Op.AND, List.of(conditions));
        return ruleRepository.matches(AudienceSqlCompiler.compile(group), userId);
    }

    private static OffsetDateTime cutoff(Instant now, Duration age) {
        return OffsetDateTime.ofInstant(now.minus(age), ZoneOffset.UTC);
    }

    // ── Tirage stable ───────────────────────────────────────────────────────

    /**
     * Tirage stable par personne et par campagne : même couple, même résultat, à chaque appel et
     * sur chaque instance. Hash SHA-256 de {@code userId:campaignId}, ramené à 0..99.
     */
    public static boolean inSample(UUID userId, UUID campaignId, int percent) {
        if (percent >= 100) return true;
        return bucket(userId, campaignId) < percent;
    }

    static int bucket(UUID userId, UUID campaignId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((userId + ":" + campaignId).getBytes(StandardCharsets.UTF_8));
            long head = ((digest[0] & 0xFFL) << 24) | ((digest[1] & 0xFFL) << 16) | ((digest[2] & 0xFFL) << 8) | (digest[3] & 0xFFL);
            return (int) (head % 100);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Estimation d'audience (admin) ───────────────────────────────────────

    /**
     * Nombre de comptes actifs concernés par les conditions (et la plateforme, via leurs appareils
     * enregistrés), puis après tirage. Jamais de donnée personnelle : seulement des comptes. La
     * version de l'app n'est connue d'aucun compte côté serveur : elle ne restreint pas l'estimation.
     */
    public Estimate estimate(String conditions, List<VideoModalPlatform> platforms, int samplePercent) {
        AudienceRule.Group group;
        try {
            group = conditions == null ? new AudienceRule.Group(AudienceRule.Op.AND, List.of())
                    : AudienceRule.parse(MAPPER.readTree(conditions));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Conditions illisibles", e);
        }
        CompiledRule compiled = AudienceSqlCompiler.compile(group);
        if (platforms != null && !platforms.isEmpty()) {
            MapSqlParameterSource params = new MapSqlParameterSource();
            params.addValues(compiled.params().getValues());
            params.addValue("vmPlatforms", platforms.stream().map(p -> p.name().toLowerCase(java.util.Locale.ROOT)).toList());
            compiled = new CompiledRule(compiled.where()
                    + " AND EXISTS (SELECT 1 FROM user_devices d WHERE d.user_id = u.id AND d.platform IN (:vmPlatforms))",
                    params);
        }
        long accounts = ruleRepository.count(compiled);
        return new Estimate(accounts, Math.round(accounts * Math.min(Math.max(samplePercent, 1), 100) / 100.0));
    }

    /** Comptes concernés, puis après tirage (espérance : le tirage réel dépend de chaque identifiant). */
    public record Estimate(long accounts, long afterSample) {}
}
