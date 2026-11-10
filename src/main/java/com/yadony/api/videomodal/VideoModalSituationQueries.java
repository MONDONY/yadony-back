package com.yadony.api.videomodal;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Lectures des situations de modal qui n'existent pas dans le catalogue de la Diffusion. Une
 * requête par situation, bornée à un compte, en SQL natif sur les tables des autres fonctionnalités
 * (offres, paiements, parrainage, profil) : lecture seule, jamais d'écriture, comme le moteur de
 * règles. Les bornes de temps sont calculées en Java et passées en paramètres (aucun {@code now()}) :
 * l'exactitude « N heures pile » se teste avec une horloge fixe.
 *
 * <p>Deux circuits d'offres coexistent et sont couverts tous les deux : les demandes sur un trajet
 * ({@code bids}, fil de prix {@code bid_negotiation_messages}) et les demandes de colis où les
 * voyageurs proposent ({@code package_requests}, {@code negotiation_threads}).
 */
@Repository
public class VideoModalSituationQueries {

    private final NamedParameterJdbcTemplate jdbc;

    public VideoModalSituationQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Expéditeur dont une offre a été acceptée et n'est pas payée depuis {@code cutoff}.
     * Demande sur trajet : {@code AWAITING_PAYMENT} avec un accord de prix (message ACCEPT, rail
     * carte) ou un paiement pawaPay créé à l'acceptation du voyageur (mobile money) ; une demande
     * carte en {@code AWAITING_PAYMENT} sans accord n'a été acceptée par personne et ne compte pas.
     * Demande de colis : fil en attente de paiement ou de dépôt, depuis sa dernière activité.
     */
    public boolean offerAcceptedUnpaid(UUID userId, OffsetDateTime cutoff) {
        return flag("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM bids b
                    WHERE b.sender_id = :uid AND b.deleted_at IS NULL AND b.status = 'AWAITING_PAYMENT'
                      AND COALESCE(
                            (SELECT MAX(m.created_at) FROM bid_negotiation_messages m
                              WHERE m.bid_id = b.id AND m.kind = 'ACCEPT'),
                            (SELECT MAX(p.created_at) FROM payments p
                              WHERE p.bid_id = b.id AND p.rail = 'PAWAPAY' AND p.deleted_at IS NULL)
                          ) <= :cutoff
                ) OR EXISTS (
                    SELECT 1 FROM negotiation_threads t
                    JOIN package_requests r ON r.id = t.package_request_id
                    WHERE r.sender_id = :uid AND r.deleted_at IS NULL AND t.deleted_at IS NULL
                      AND t.status IN ('AWAITING_PAYMENT', 'AWAITING_DEPOSIT')
                      AND t.last_activity_at <= :cutoff
                ) THEN 1 ELSE 0 END
                """, userId, cutoff);
    }

    /**
     * Voyageur qui a reçu une demande sans y répondre depuis {@code cutoff} : demande en attente de
     * sa décision (même horloge que le délai de réponse du back, {@code COALESCE(pending_since,
     * created_at)}), ou première proposition de prix d'un fil encore ouvert.
     */
    public boolean offerUnanswered(UUID userId, OffsetDateTime cutoff) {
        return flag("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM bids b JOIN announcements a ON a.id = b.announcement_id
                    WHERE a.traveler_id = :uid AND a.deleted_at IS NULL AND b.deleted_at IS NULL
                      AND b.status IN ('PENDING', 'PAYMENT_ESCROWED')
                      AND COALESCE(b.pending_since, b.created_at) <= :cutoff
                ) OR EXISTS (
                    SELECT 1 FROM bids b
                    JOIN announcements a ON a.id = b.announcement_id
                    JOIN bid_negotiation_messages m ON m.bid_id = b.id
                    WHERE a.traveler_id = :uid AND a.deleted_at IS NULL AND b.deleted_at IS NULL
                      AND b.status = 'NEGOTIATING' AND m.kind = 'PROPOSAL' AND m.created_at <= :cutoff
                      AND m.created_at = (SELECT MAX(m2.created_at) FROM bid_negotiation_messages m2
                                           WHERE m2.bid_id = b.id)
                ) THEN 1 ELSE 0 END
                """, userId, cutoff);
    }

    /**
     * Contre-offre adressée à la personne (dernier message d'un fil ouvert, de type COUNTER, écrit
     * par l'autre partie) restée sans réponse depuis {@code cutoff}.
     */
    public boolean counterOfferPending(UUID userId, OffsetDateTime cutoff) {
        return flag("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM bids b
                    JOIN announcements a ON a.id = b.announcement_id
                    JOIN bid_negotiation_messages m ON m.bid_id = b.id
                    WHERE b.deleted_at IS NULL AND a.deleted_at IS NULL AND b.status = 'NEGOTIATING'
                      AND (b.sender_id = :uid OR a.traveler_id = :uid)
                      AND m.kind = 'COUNTER' AND m.author_id <> :uid AND m.created_at <= :cutoff
                      AND m.created_at = (SELECT MAX(m2.created_at) FROM bid_negotiation_messages m2
                                           WHERE m2.bid_id = b.id)
                ) OR EXISTS (
                    SELECT 1 FROM negotiation_threads t
                    JOIN package_requests r ON r.id = t.package_request_id
                    JOIN negotiation_messages m ON m.thread_id = t.id
                    WHERE t.deleted_at IS NULL AND r.deleted_at IS NULL AND t.status = 'OPEN'
                      AND (t.traveler_id = :uid OR r.sender_id = :uid)
                      AND m.kind = 'COUNTER' AND m.from_user_id <> :uid AND m.created_at <= :cutoff
                      AND m.created_at = (SELECT MAX(m2.created_at) FROM negotiation_messages m2
                                           WHERE m2.thread_id = t.id)
                ) THEN 1 ELSE 0 END
                """, userId, cutoff);
    }

    /** Photo, ville ou « À propos » manquant (vide ou espaces compris). */
    public boolean profileIncomplete(UUID userId) {
        return flag("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM users u WHERE u.id = :uid AND u.deleted_at IS NULL
                      AND (u.avatar_url IS NULL OR TRIM(u.avatar_url) = ''
                           OR u.city IS NULL OR TRIM(u.city) = ''
                           OR u.bio IS NULL OR TRIM(u.bio) = '')
                ) THEN 1 ELSE 0 END
                """, userId, null);
    }

    /**
     * Au moins une livraison réussie (colis livré côté expéditeur ou côté voyageur, compteurs du
     * compte) et aucune invitation de parrainage envoyée.
     */
    public boolean firstDeliveryNoReferral(UUID userId) {
        return flag("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM users u WHERE u.id = :uid AND u.deleted_at IS NULL
                      AND (u.total_shipments >= 1 OR u.total_deliveries >= 1)
                      AND NOT EXISTS (SELECT 1 FROM referral_invitations i
                                       WHERE i.referrer_user_id = u.id AND i.deleted_at IS NULL)
                ) THEN 1 ELSE 0 END
                """, userId, null);
    }

    /** Dernière pause vue pour la personne : début de l'absence et retour (UTC), s'ils existent. */
    public Optional<Absence> lastAbsence(UUID userId) {
        return jdbc.query("SELECT absence_started_at, returned_at FROM users WHERE id = :uid AND deleted_at IS NULL",
                new MapSqlParameterSource("uid", userId), (rs, i) -> {
                    LocalDateTime started = rs.getObject("absence_started_at", LocalDateTime.class);
                    LocalDateTime returned = rs.getObject("returned_at", LocalDateTime.class);
                    return started == null || returned == null ? null : new Absence(started, returned);
                }).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /** Pause entre deux visites : dernière visite avant la coupure et heure du retour (UTC). */
    public record Absence(LocalDateTime startedAt, LocalDateTime returnedAt) {}

    private boolean flag(String sql, UUID userId, OffsetDateTime cutoff) {
        MapSqlParameterSource params = new MapSqlParameterSource("uid", userId);
        if (cutoff != null) params.addValue("cutoff", cutoff);
        Integer value = jdbc.queryForObject(sql, params, Integer.class);
        return value != null && value == 1;
    }
}
