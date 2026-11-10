package com.yadony.api.kyc.reminder;

import com.yadony.api.auth.UserEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Sélection des comptes à relancer pour la vérification d'identité (SQL natif sur users + kyc_schema). */
public interface KycReminderRepository extends Repository<UserEntity, UUID> {

    String FROM_JOIN = " FROM users u JOIN kyc_schema.kyc_verifications k ON k.user_id = u.id";

    /** Conditions communes, sans la branche « refusé » ni les délais. */
    String COMMON = " WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' AND u.deletion_requested_at IS NULL"
            + " AND u.kyc_status <> 'VERIFIED' AND u.kyc_reminders_opted_out_at IS NULL"
            + " AND u.kyc_reminder_anchor_at IS NOT NULL AND u.kyc_reminder_count < 3"
            + " AND k.deleted_at IS NULL AND k.verification_session_id IS NOT NULL"
            + " AND k.decided_by_admin_id IS NULL AND k.decision_kind IS NULL";

    String PENDING = "(k.status = 'PENDING' AND k.submitted_at IS NULL)";
    String PENDING_OR_REJECTED = "(" + PENDING
            + " OR (k.status = 'REJECTED' AND UPPER(k.rejection_code) IN (:retryableCodes)))";

    String DUE = " AND ((u.kyc_reminder_count = 0 AND u.kyc_reminder_anchor_at <= :firstDue)"
            + " OR (u.kyc_reminder_count = 1 AND u.kyc_reminder_anchor_at <= :secondDue AND u.kyc_reminder_last_at <= :secondGap)"
            + " OR (u.kyc_reminder_count = 2 AND u.kyc_reminder_anchor_at <= :thirdDue AND u.kyc_reminder_last_at <= :thirdGap))"
            // Borne d'âge : le stock ancré à la migration, dont l'interrupteur est allumé bien plus tard, ne
            // doit pas relancer des parcours vieux de plusieurs mois.
            + " AND u.kyc_reminder_anchor_at >= :oldestAnchor";

    String ORDER = " ORDER BY u.kyc_reminder_anchor_at ASC LIMIT 500";

    @Query(value = "SELECT CAST(u.id AS VARCHAR) AS id" + FROM_JOIN + COMMON + " AND " + PENDING_OR_REJECTED + DUE + ORDER,
           nativeQuery = true)
    List<CandidateRow> findCandidateRows(@Param("firstDue") Instant firstDue, @Param("secondDue") Instant secondDue,
                                         @Param("thirdDue") Instant thirdDue, @Param("secondGap") Instant secondGap,
                                         @Param("thirdGap") Instant thirdGap, @Param("oldestAnchor") Instant oldestAnchor,
                                         @Param("retryableCodes") Collection<String> retryableCodes);

    /** Variante sans refus : évite un {@code IN ()} invalide quand la liste des codes est vide. */
    @Query(value = "SELECT CAST(u.id AS VARCHAR) AS id" + FROM_JOIN + COMMON + " AND " + PENDING + DUE + ORDER,
           nativeQuery = true)
    List<CandidateRow> findPendingOnlyCandidateRows(@Param("firstDue") Instant firstDue,
                                                    @Param("secondDue") Instant secondDue,
                                                    @Param("thirdDue") Instant thirdDue,
                                                    @Param("secondGap") Instant secondGap,
                                                    @Param("thirdGap") Instant thirdGap,
                                                    @Param("oldestAnchor") Instant oldestAnchor);

    @Query(value = "SELECT COUNT(*)" + FROM_JOIN + COMMON + " AND u.id = :userId AND " + PENDING_OR_REJECTED + DUE,
           nativeQuery = true)
    int countStillCandidate(@Param("userId") UUID userId, @Param("firstDue") Instant firstDue,
                            @Param("secondDue") Instant secondDue, @Param("thirdDue") Instant thirdDue,
                            @Param("secondGap") Instant secondGap, @Param("thirdGap") Instant thirdGap,
                            @Param("oldestAnchor") Instant oldestAnchor,
                            @Param("retryableCodes") Collection<String> retryableCodes);

    @Query(value = "SELECT COUNT(*)" + FROM_JOIN + COMMON + " AND u.id = :userId AND " + PENDING + DUE,
           nativeQuery = true)
    int countStillCandidatePendingOnly(@Param("userId") UUID userId, @Param("firstDue") Instant firstDue,
                                       @Param("secondDue") Instant secondDue, @Param("thirdDue") Instant thirdDue,
                                       @Param("secondGap") Instant secondGap, @Param("thirdGap") Instant thirdGap,
                                       @Param("oldestAnchor") Instant oldestAnchor);

    @Query(value = "SELECT CASE WHEN k.status = 'REJECTED' THEN 'rejected' ELSE 'incomplete' END"
            + " FROM kyc_schema.kyc_verifications k WHERE k.user_id = :userId AND k.deleted_at IS NULL",
           nativeQuery = true)
    String reminderCase(@Param("userId") UUID userId);

    /**
     * Échéances de la sélection, calculées une fois par le planificateur et rejouées au re-contrôle :
     * un compte dont l'ancre a été remise à maintenant (nouvelle session) n'est plus dû.
     */
    record Due(Instant first, Instant second, Instant third, Instant secondGap, Instant thirdGap,
               Instant oldestAnchor) {}

    /** Au plus 500 ids, ancre la plus ancienne d'abord. Codes attendus en majuscules. */
    default List<UUID> findCandidates(Due d, Collection<String> retryableCodes) {
        List<CandidateRow> rows = retryableCodes == null || retryableCodes.isEmpty()
                ? findPendingOnlyCandidateRows(d.first(), d.second(), d.third(), d.secondGap(), d.thirdGap(),
                        d.oldestAnchor())
                : findCandidateRows(d.first(), d.second(), d.third(), d.secondGap(), d.thirdGap(), d.oldestAnchor(),
                        retryableCodes);
        return rows.stream().map(r -> UUID.fromString(r.getId())).toList();
    }

    /** 1 si le compte remplit encore les conditions, délais et borne d'âge compris, sinon 0. */
    default int isStillCandidate(UUID userId, Due d, Collection<String> retryableCodes) {
        return retryableCodes == null || retryableCodes.isEmpty()
                ? countStillCandidatePendingOnly(userId, d.first(), d.second(), d.third(), d.secondGap(),
                        d.thirdGap(), d.oldestAnchor())
                : countStillCandidate(userId, d.first(), d.second(), d.third(), d.secondGap(), d.thirdGap(),
                        d.oldestAnchor(), retryableCodes);
    }

    interface CandidateRow {
        String getId();
    }
}
