package com.yadony.api.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByFirebaseUid(String firebaseUid);
    Optional<UserEntity> findByStripeAccountId(String stripeAccountId);
    Optional<UserEntity> findByStripeCustomerId(String stripeCustomerId);
    Optional<UserEntity> findByCommissionPaymentMethodId(String commissionPaymentMethodId);

    boolean existsByFirebaseUid(String firebaseUid);

    /**
     * Le username est-il déjà pris, y compris par un compte supprimé ?
     *
     * <p>Requête native délibérée : une méthode dérivée subirait le {@code @Where(deleted_at IS
     * NULL)} de {@link UserEntity} et déclarerait libre un username encore porté par une ligne
     * soft-deleted, que l'index unique {@code ux_users_username} refuserait ensuite.
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM users WHERE username = :username)", nativeQuery = true)
    boolean existsByUsername(@Param("username") String username);

    /**
     * Finds a user by Firebase UID regardless of soft-delete status.
     * Used to detect and reactivate accounts that were previously deleted.
     */
    @Query(value = "SELECT * FROM users WHERE firebase_uid = :firebaseUid LIMIT 1", nativeQuery = true)
    Optional<UserEntity> findByFirebaseUidIncludingDeleted(@Param("firebaseUid") String firebaseUid);

    /**
     * Trouve un utilisateur par id, y compris soft-deleted.
     *
     * <p>Requête native délibérée, même motif que {@link #findByFirebaseUidIncludingDeleted} :
     * {@code com.yadony.api.messaging.UserFinalizedMessagingListener} tourne en
     * {@code AFTER_COMMIT}, c'est-à-dire après que {@code AccountFinalizationService#finalize} a
     * déjà persisté {@code deleted_at}. Une
     * méthode dérivée subirait le {@code @Where(deleted_at IS NULL)} de {@link UserEntity} et ne
     * trouverait jamais la ligne, alors que {@code firebase_uid} lui-même n'est jamais effacé par
     * la finalisation.
     */
    @Query(value = "SELECT * FROM users WHERE id = :id LIMIT 1", nativeQuery = true)
    Optional<UserEntity> findByIdIncludingDeleted(@Param("id") UUID id);

    /**
     * Reactivates a soft-deleted account via native UPDATE, bypassing the @Where filter.
     * Required because em.merge() on a soft-deleted entity would trigger an internal
     * SELECT filtered by @Where(deleted_at IS NULL), find nothing, and attempt an INSERT
     * that violates the unique constraint on firebase_uid.
     */
    // clearAutomatically=true: evicts the stale L1 cache entry after the native UPDATE so that
    // the subsequent findByFirebaseUid loads a fresh entity — without this, Hibernate flushes
    // the cached (deleted) state at commit and overwrites our reactivation.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE users SET deleted_at = NULL, status = :status, updated_at = NOW() WHERE firebase_uid = :firebaseUid", nativeQuery = true)
    void reactivateByFirebaseUid(@Param("firebaseUid") String firebaseUid, @Param("status") String status);

    /**
     * Loads a UserEntity with a pessimistic write lock to prevent concurrent
     * Stripe Connect account creation (race condition guard in createConnectAccount).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Loads a UserEntity by Firebase UID with a pessimistic write lock.
     * Used in traveler role activation to guard against concurrent upgrades.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.firebaseUid = :uid")
    Optional<UserEntity> findByFirebaseUidForUpdate(@Param("uid") String uid);

    /**
     * Finds users with a given status whose deletion grace period has expired.
     * Used to identify accounts ready for final permanent deletion.
     */
    List<UserEntity> findByStatusAndDeletionRequestedAtBefore(UserStatus status, Instant cutoff);

    /**
     * Lot C — file des demandes de suppression RGPD, les plus anciennes d'abord.
     *
     * <p>Requête dérivée délibérée : elle hérite du {@code @Where(deleted_at IS NULL)} de
     * {@link UserEntity}, ce qui exclut d'office les comptes déjà finalisés — la file ne
     * montre donc que les demandes encore à traiter, sans filtre supplémentaire.
     */
    Page<UserEntity> findByDeletionRequestedAtIsNotNullOrderByDeletionRequestedAtAsc(Pageable pageable);

    /**
     * Ce qui distingue une ligne invitée abandonnée de tout le reste de la table.
     *
     * <p>Constante partagée et non SQL recopié : ce prédicat décide de la <b>suppression
     * physique</b> de lignes {@code users}. Deux copies pourraient diverger, et une seule
     * divergence détruirait des comptes réels.
     *
     * <p>Chaque condition, et pourquoi elle est là :
     * <ul>
     *   <li><b>aucun rôle</b> — la condition centrale. {@link GuestUserProvisioner} est le seul
     *       code qui crée une ligne sans rôle ; toute inscription réelle reçoit SENDER+TRAVELER
     *       ({@code AuthService#createUser}), et rien ne retire jamais un rôle (la
     *       désactivation volontaire du rôle voyageur a été supprimée, cf. migration V193 qui
     *       répare les comptes qu'elle avait dépouillés). Une ligne à rôles est donc un compte
     *       réel, sans exception connue ;</li>
     *   <li><b>aucun favori</b> — un favori est la seule donnée qu'un invité puisse produire
     *       (son périmètre se limite à chercher, consulter et mettre en favori). Une ligne qui
     *       en porte encore un a du contenu à conserver. Le {@code deleted_at} des favoris est
     *       délibérément ignoré : en PostgreSQL {@code favorites.user_id} référence
     *       {@code users(id)} sans cascade (V152), et un favori soft-deleté suffirait à faire
     *       échouer la suppression, donc tout le lot avec elle ;</li>
     *   <li><b>plus ancienne que le seuil</b> — laisse au visiteur le temps de revenir ;</li>
     *   <li><b>ni suspendue, ni bannie, ni en attente de suppression, ni KYC entamé, ni prénom,
     *       ni nom, ni compte Stripe, ni client Stripe, ni compte PRO</b> — défense en
     *       profondeur. Une ligne invitée ne porte rien de tout cela : le provisionneur
     *       n'écrit que {@code firebase_uid}, {@code username}, {@code status=ACTIVE} et
     *       {@code kyc_status=NOT_STARTED}. Ces conditions n'épargnent donc aucune ligne
     *       réellement invitée, et couvrent le cas où « aucun rôle » cesserait un jour d'être
     *       le discriminant qu'il est aujourd'hui. Un compte supprimé par
     *       {@code AccountFinalizationService} est ainsi protégé quatre fois plutôt qu'une
     *       (rôles intacts, statut BANNED, prénom et nom de pseudonymisation).</li>
     * </ul>
     *
     * <p><b>Aucune condition sur {@code deleted_at}</b>, et c'est voulu :
     * {@code GuestClaimService} soft-delete la ligne invitée à chaque réclamation réussie, si
     * bien que les lignes déjà supprimées sont la population principale à purger. Un critère
     * qui ne les verrait pas raterait sa cible. C'est aussi pourquoi ces requêtes sont natives :
     * toute requête JPQL subirait le {@code @Where(deleted_at IS NULL)} de {@link UserEntity}.
     */
    String ABANDONED_GUEST_ROW_PREDICATE = """
            NOT EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = users.id)
            AND NOT EXISTS (SELECT 1 FROM favorites f WHERE f.user_id = users.id)
            AND users.created_at < :cutoff
            AND users.status = 'ACTIVE'
            AND users.kyc_status = 'NOT_STARTED'
            AND users.deletion_requested_at IS NULL
            AND users.first_name IS NULL
            AND users.last_name IS NULL
            AND users.stripe_account_id IS NULL
            AND users.stripe_customer_id IS NULL
            AND users.is_pro_account = FALSE
            """;

    /**
     * Supprime physiquement les lignes invitées abandonnées. Voir
     * {@link #ABANDONED_GUEST_ROW_PREDICATE} pour le critère et sa justification.
     *
     * <p><b>Suppression physique et non soft delete</b>, par exception assumée à la règle du
     * projet — la même exception que celle déjà retenue pour les favoris (V172). Un soft delete
     * ne supprimerait rien : la moitié de la population visée est <i>déjà</i> soft-deletée par
     * les réclamations, et repasser {@code deleted_at} sur une ligne qui le porte déjà laisserait
     * la table grossir indéfiniment, ce qui est précisément le problème que cette purge existe
     * pour résoudre. La règle du soft delete protège des données métier et un historique ; une
     * ligne invitée abandonnée n'a ni l'un ni l'autre : ni rôle, ni favori, ni profil, et ni
     * téléphone ni email (ils vivent dans Firebase).
     *
     * <p>Idempotent par construction : les lignes supprimées ne ressortent plus au passage
     * suivant.
     *
     * @return le nombre de lignes supprimées
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM users WHERE " + ABANDONED_GUEST_ROW_PREDICATE, nativeQuery = true)
    int deleteAbandonedGuestRows(@Param("cutoff") java.time.LocalDateTime cutoff);

    @Query("SELECT u FROM UserEntity u WHERE u.commissionPaymentMethodId IS NOT NULL AND " +
           "(u.commissionCardExpYear < :year OR " +
           "(u.commissionCardExpYear = :year AND u.commissionCardExpMonth <= :month))")
    List<UserEntity> findUsersWithCardExpiringBefore(@Param("year") int year, @Param("month") int month);

    /**
     * Recherche admin. Le terme libre {@code queryLike} ne balaie plus que les noms :
     * téléphone et email ont quitté la base pour Firebase. Une recherche sur l'un des
     * deux est résolue en amont en UID Firebase, passé ici via {@code queryFirebaseUid}
     * et apparié exactement.
     */
    @Query(value = """
            SELECT u.* FROM users u
            WHERE u.deleted_at IS NULL
            AND (CAST(:status AS VARCHAR) IS NULL OR u.status = :status)
            AND (CAST(:kycStatus AS VARCHAR) IS NULL OR u.kyc_status = :kycStatus)
            AND (:pro IS NULL OR u.is_pro_account = :pro)
            AND (CAST(:city AS VARCHAR) IS NULL OR u.city ILIKE :city)
            AND (CAST(:queryLike AS VARCHAR) IS NULL
                 OR u.first_name ILIKE :queryLike
                 OR u.last_name  ILIKE :queryLike
                 OR (CAST(:queryFirebaseUid AS VARCHAR) IS NOT NULL
                     AND u.firebase_uid = :queryFirebaseUid))
            AND (CAST(:role AS VARCHAR) IS NULL OR EXISTS (
                 SELECT 1 FROM user_roles r WHERE r.user_id = u.id AND r.role = :role))
            ORDER BY u.created_at DESC
            """,
           countQuery = """
            SELECT COUNT(*) FROM users u
            WHERE u.deleted_at IS NULL
            AND (CAST(:status AS VARCHAR) IS NULL OR u.status = :status)
            AND (CAST(:kycStatus AS VARCHAR) IS NULL OR u.kyc_status = :kycStatus)
            AND (:pro IS NULL OR u.is_pro_account = :pro)
            AND (CAST(:city AS VARCHAR) IS NULL OR u.city ILIKE :city)
            AND (CAST(:queryLike AS VARCHAR) IS NULL
                 OR u.first_name ILIKE :queryLike
                 OR u.last_name  ILIKE :queryLike
                 OR (CAST(:queryFirebaseUid AS VARCHAR) IS NOT NULL
                     AND u.firebase_uid = :queryFirebaseUid))
            AND (CAST(:role AS VARCHAR) IS NULL OR EXISTS (
                 SELECT 1 FROM user_roles r WHERE r.user_id = u.id AND r.role = :role))
            """,
           nativeQuery = true)
    Page<UserEntity> findAdminFiltered(
            @Param("status") String status,
            @Param("kycStatus") String kycStatus,
            @Param("pro") Boolean pro,
            @Param("city") String city,
            @Param("queryLike") String queryLike,
            @Param("queryFirebaseUid") String queryFirebaseUid,
            @Param("role") String role,
            Pageable pageable);

    java.util.List<UserEntity> findAllByCreatedAtBetweenOrderByCreatedAtAsc(
            java.time.LocalDateTime from, java.time.LocalDateTime to);

    /**
     * Onboardings Connect commencés puis abandonnés, éligibles à une relance.
     *
     * Deux relances au total, pas une de plus : la première passé
     * {@code firstDueBefore} (compte créé il y a assez longtemps, jamais
     * relancé), la seconde passé {@code secondDueBefore} et seulement si la
     * relance précédente est antérieure à ce seuil. Une fois la seconde
     * envoyée, {@code stripeOnboardingLastReminderAt} dépasse le seuil et la
     * ligne ne ressort plus — le scheduler peut donc tourner en boucle sans
     * jamais renvoyer deux fois la même relance.
     */
    @Query("SELECT u FROM UserEntity u "
            + "WHERE u.stripeAccountStatus = com.yadony.api.auth.StripeAccountStatus.PENDING_ONBOARDING "
            + "AND u.stripeAccountId IS NOT NULL "
            + "AND u.stripeAccountCreatedAt IS NOT NULL "
            + "AND ( (u.stripeOnboardingLastReminderAt IS NULL "
            + "         AND u.stripeAccountCreatedAt <= :firstDueBefore) "
            + "   OR (u.stripeOnboardingLastReminderAt IS NOT NULL "
            + "         AND u.stripeAccountCreatedAt <= :secondDueBefore "
            + "         AND u.stripeOnboardingLastReminderAt <= :secondDueBefore) )")
    List<UserEntity> findStaleConnectOnboardings(
            @Param("firstDueBefore") Instant firstDueBefore,
            @Param("secondDueBefore") Instant secondDueBefore);
}
