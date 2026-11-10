package com.yadony.api.travelerstatus;

import com.yadony.api.gppro.GpProApplicationStatus;
import com.yadony.api.gppro.GpProTravelFrequency;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Repère les particuliers qui voyagent comme des professionnels (lot 4). Lecture seule : ne modifie ni
 * {@code users}, ni {@code announcements}, ni les réservations.
 *
 * <p>Un utilisateur est retenu s'il n'a pas le badge GP Pro, s'est déclaré particulier
 * ({@code traveler_status = INDIVIDUAL}), et :
 * <ul>
 *   <li>ses voyages (dates de départ distinctes, voir {@link IndividualFrequencyFlagRepository#countTripsAboveThreshold})
 *       parmi les trajets ayant au moins une réservation {@link #COUNTED_BIDS} créée dans les {@link #WINDOW_DAYS}
 *       derniers jours (UTC, borne incluse) sont strictement plus nombreux que le seuil ;</li>
 *   <li>ou une demande GP Pro « chaque semaine » a été refusée ou annulée dans la même fenêtre (date de la décision
 *       ou de l'annulation).</li>
 * </ul>
 * N'est jamais retenu non plus celui qui a une demande GP Pro en cours ({@link #OPEN_APPLICATIONS}) : l'équipe
 * l'examine déjà dans la file GP Pro.
 * Un professionnel, un GP Pro validé ou un utilisateur sans statut déclaré n'est jamais retenu, ni un compte
 * suspendu, banni, en cours de suppression ({@code status != ACTIVE} ou {@code deletion_requested_at} posé) ou
 * supprimé (soft delete). Un signalement ouvert d'un tel compte est donc résolu au passage suivant.
 */
@Component
public class IndividualFrequencyEvaluator {

    public static final int WINDOW_DAYS = 30;

    /**
     * Réservation acceptée ou plus avancée, colis réellement pris en charge ou à prendre. Exclus : pas encore
     * acceptée (en attente, en attente de paiement, séquestre avant acceptation), refusée, annulée, expirée,
     * voyageur absent ({@code NO_SHOW}, remboursé), colis refusé à la remise ({@code PARCEL_REFUSED}) et fils de
     * négociation (jamais une réservation).
     */
    static final Set<BidStatus> COUNTED_BIDS = EnumSet.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER,
            BidStatus.IN_TRANSIT, BidStatus.ARRIVED, BidStatus.COMPLETED);

    /** Trajet annulé ou retiré par la modération : il n'a pas eu lieu, ses réservations ne comptent pas. */
    static final Set<AnnouncementStatus> EXCLUDED_TRIPS =
            EnumSet.of(AnnouncementStatus.CANCELLED, AnnouncementStatus.REMOVED_BY_ADMIN);

    /** Demande GP Pro en cours d'examen : la personne n'est pas signalée. */
    static final Set<GpProApplicationStatus> OPEN_APPLICATIONS =
            EnumSet.of(GpProApplicationStatus.AWAITING_KYC, GpProApplicationStatus.PENDING);

    /** Demande GP Pro close sans badge. */
    static final Set<GpProApplicationStatus> CLOSED_WITHOUT_BADGE =
            EnumSet.of(GpProApplicationStatus.REJECTED, GpProApplicationStatus.CANCELLED);

    /** Ce qui a été constaté pour un utilisateur retenu ; {@code tripsCount} compte des voyages (dates de départ). */
    public record Detection(int tripsCount, boolean weeklyApplication) {
    }

    private final IndividualFrequencyFlagRepository repository;

    public IndividualFrequencyEvaluator(IndividualFrequencyFlagRepository repository) {
        this.repository = repository;
    }

    /**
     * @param now instant du contrôle, en UTC
     * @param threshold seuil (au moins 1) : retenu au-delà, strictement
     * @return utilisateurs retenus, avec leur compte de trajets sur la fenêtre
     */
    @Transactional(readOnly = true)
    public Map<UUID, Detection> detect(LocalDateTime now, int threshold) {
        LocalDateTime since = now.minusDays(WINDOW_DAYS);
        int safeThreshold = Math.max(threshold, 1);
        Map<UUID, Detection> result = new HashMap<>();
        for (Object[] row : repository.countTripsAboveThreshold(COUNTED_BIDS, EXCLUDED_TRIPS,
                OPEN_APPLICATIONS, since, safeThreshold)) {
            result.put((UUID) row[0], new Detection(toInt(row[1]), false));
        }
        List<UUID> weekly = repository.findIndividualsWithApplication(GpProTravelFrequency.WEEKLY,
                CLOSED_WITHOUT_BADGE, OPEN_APPLICATIONS, since);
        if (weekly.isEmpty()) {
            return result;
        }
        List<UUID> belowThreshold = weekly.stream().filter(id -> !result.containsKey(id)).toList();
        Map<UUID, Integer> counts = new HashMap<>();
        if (!belowThreshold.isEmpty()) {
            for (Object[] row : repository.countTrips(belowThreshold, COUNTED_BIDS, EXCLUDED_TRIPS, since)) {
                counts.put((UUID) row[0], toInt(row[1]));
            }
        }
        for (UUID id : weekly) {
            Detection above = result.get(id);
            result.put(id, new Detection(above != null ? above.tripsCount() : counts.getOrDefault(id, 0), true));
        }
        return result;
    }

    private static int toInt(Object value) {
        long n = ((Number) value).longValue();
        return (int) Math.min(n, Integer.MAX_VALUE);
    }
}
