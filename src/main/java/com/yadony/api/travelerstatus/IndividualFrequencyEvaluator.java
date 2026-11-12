package com.yadony.api.travelerstatus;

import com.yadony.api.gppro.GpProApplicationStatus;
import com.yadony.api.gppro.GpProTravelFrequency;
import com.yadony.api.matching.AnnouncementStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Repère les particuliers qui publient comme des professionnels (lot 4). Lecture seule : ne modifie ni
 * {@code users}, ni {@code announcements}, ni les réservations. On n'en bloque JAMAIS aucun : on alerte l'équipe.
 *
 * <p>Un utilisateur est retenu s'il n'a pas le badge GP Pro, s'est déclaré particulier
 * ({@code traveler_status = INDIVIDUAL}), et :
 * <ul>
 *   <li>il a publié strictement plus de trajets que le seuil (réglable dans l'admin, 4 par défaut) dans le MOIS CIVIL
 *       en cours, du 1er au dernier jour, fuseau {@value #ZONE_ID} (voir {@link #monthOf} et
 *       {@link IndividualFrequencyFlagRepository#countPublishedTripsAboveThreshold}). Tous les trajets publiés
 *       comptent, avec ou sans réservation ;</li>
 *   <li>ou une demande GP Pro « chaque semaine » a été refusée ou annulée dans les {@link #WINDOW_DAYS} derniers
 *       jours (date de la décision ou de l'annulation).</li>
 * </ul>
 * <b>Définition d'« un trajet du mois »</b> : la même que la limite mensuelle de trajets
 * ({@code PublishingEligibilityService#usedThisMonth} / {@code AnnouncementRepository#countPublishedInPeriod}) :
 * un trajet compte pour le mois où il a été publié ({@code published_at}) ; un brouillon jamais publié ne compte pas ;
 * supprimer un trajet publié ne le retire pas du compte ; chaque étape d'un voyage multi-étapes compte pour un trajet.
 * Écarts voulus : les trajets annulés ou retirés par la modération ne comptent pas ({@link #EXCLUDED_TRIPS}), et le
 * mois est celui de {@value #ZONE_ID} (le quota, lui, coupe le mois à minuit UTC : au plus deux heures d'écart).
 * <b>Ancienne règle</b> (remplacée) : trajets avec au moins une réservation acceptée sur 30 jours glissants, comptés
 * par dates de départ distinctes.
 *
 * <p>N'est jamais retenu non plus celui qui a une demande GP Pro en cours ({@link #OPEN_APPLICATIONS}) : l'équipe
 * l'examine déjà dans la file GP Pro.
 * Un professionnel, un GP Pro validé ou un utilisateur sans statut déclaré n'est jamais retenu, ni un compte
 * suspendu, banni, en cours de suppression ({@code status != ACTIVE} ou {@code deletion_requested_at} posé) ou
 * supprimé (soft delete). Un signalement ouvert d'un tel compte est donc résolu au passage suivant.
 */
@Component
public class IndividualFrequencyEvaluator {

    public static final int WINDOW_DAYS = 30;

    /** Fuseau qui borne le mois civil. */
    public static final String ZONE_ID = "Europe/Paris";
    static final ZoneId ZONE = ZoneId.of(ZONE_ID);

    /** Trajet annulé ou retiré par la modération : il n'a pas eu lieu, il ne compte pas. */
    static final Set<AnnouncementStatus> EXCLUDED_TRIPS =
            EnumSet.of(AnnouncementStatus.CANCELLED, AnnouncementStatus.REMOVED_BY_ADMIN);

    /** Demande GP Pro en cours d'examen : la personne n'est pas signalée. */
    static final Set<GpProApplicationStatus> OPEN_APPLICATIONS =
            EnumSet.of(GpProApplicationStatus.AWAITING_KYC, GpProApplicationStatus.PENDING);

    /** Demande GP Pro close sans badge. */
    static final Set<GpProApplicationStatus> CLOSED_WITHOUT_BADGE =
            EnumSet.of(GpProApplicationStatus.REJECTED, GpProApplicationStatus.CANCELLED);

    /** Ce qui a été constaté pour un utilisateur retenu ; {@code tripsCount} : trajets publiés dans le mois civil. */
    public record Detection(int tripsCount, boolean weeklyApplication) {
    }

    private final IndividualFrequencyFlagRepository repository;

    public IndividualFrequencyEvaluator(IndividualFrequencyFlagRepository repository) {
        this.repository = repository;
    }

    /** Mois civil ({@value #ZONE_ID}) contenant {@code instantUtc}, un instant exprimé en UTC. */
    public static YearMonth monthOf(LocalDateTime instantUtc) {
        return YearMonth.from(instantUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZONE));
    }

    /**
     * @param now instant du contrôle, en UTC
     * @param threshold seuil (au moins 1) : retenu au-delà, strictement
     * @return utilisateurs retenus, avec leur nombre de trajets publiés dans le mois civil en cours
     */
    @Transactional(readOnly = true)
    public Map<UUID, Detection> detect(LocalDateTime now, int threshold) {
        LocalDateTime since = now.minusDays(WINDOW_DAYS);
        YearMonth month = monthOf(now);
        LocalDateTime from = startUtc(month);
        LocalDateTime to = startUtc(month.plusMonths(1));
        int safeThreshold = Math.max(threshold, 1);
        List<String> excluded = names(EXCLUDED_TRIPS);
        Map<UUID, Detection> result = new HashMap<>();
        for (Object[] row : repository.countPublishedTripsAboveThreshold(from, to, excluded,
                names(OPEN_APPLICATIONS), safeThreshold)) {
            result.put(toUuid(row[0]), new Detection(toInt(row[1]), false));
        }
        List<UUID> weekly = repository.findIndividualsWithApplication(GpProTravelFrequency.WEEKLY,
                CLOSED_WITHOUT_BADGE, OPEN_APPLICATIONS, since);
        if (weekly.isEmpty()) {
            return result;
        }
        List<UUID> belowThreshold = weekly.stream().filter(id -> !result.containsKey(id)).toList();
        Map<UUID, Integer> counts = new HashMap<>();
        if (!belowThreshold.isEmpty()) {
            for (Object[] row : repository.countPublishedTrips(belowThreshold, from, to, excluded)) {
                counts.put(toUuid(row[0]), toInt(row[1]));
            }
        }
        for (UUID id : weekly) {
            Detection above = result.get(id);
            result.put(id, new Detection(above != null ? above.tripsCount() : counts.getOrDefault(id, 0), true));
        }
        return result;
    }

    /** Début du mois civil ({@value #ZONE_ID}) en UTC, comme {@code announcements.published_at}. */
    private static LocalDateTime startUtc(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static List<String> names(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).toList();
    }

    private static UUID toUuid(Object value) {
        // Une requête native rend un UUID sous PostgreSQL, 16 octets sous H2 (tests).
        if (value instanceof UUID id) {
            return id;
        }
        if (value instanceof byte[] bytes && bytes.length == 16) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        return UUID.fromString(value.toString());
    }

    private static int toInt(Object value) {
        long n = ((Number) value).longValue();
        return (int) Math.min(n, Integer.MAX_VALUE);
    }
}
