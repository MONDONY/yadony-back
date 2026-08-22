package com.yadony.api.auth;

import com.yadony.api.favorites.FavoriteEntity;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.favorites.FavoriteTargetType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Purge des lignes invitées abandonnées.
 *
 * <p>Ces tests tournent sur une base réelle (H2) et non sur des mocks de repository : le
 * critère de purge vit entièrement en SQL, si bien qu'un test à base de mocks ne prouverait
 * rien de ce qui compte ici. Or ce qui compte, c'est exactement le contenu du critère : le
 * code testé <b>supprime des lignes users</b>, et une erreur de critère détruirait des comptes
 * réels toutes les nuits, en silence.
 *
 * <p>Les lignes invitées sont fabriquées par {@link GuestUserProvisioner} lui-même, jamais à la
 * main : c'est la seule façon de garantir que le critère décrit bien ce que le provisionneur
 * écrit réellement. Si quelqu'un lui fait renseigner demain un champ que le critère exclut, ces
 * tests deviennent rouges au lieu de laisser la purge devenir silencieusement inopérante.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class GuestUserCleanupSchedulerTest {

    private static final int RETENTION_DAYS = 30;

    @Autowired UserRepository userRepository;
    @Autowired FavoriteRepository favoriteRepository;
    @PersistenceContext EntityManager entityManager;

    private GuestUserProvisioner provisioner;
    private GuestUserCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        provisioner = new GuestUserProvisioner(userRepository, new UsernameGenerator(userRepository));
        scheduler = new GuestUserCleanupScheduler(userRepository, RETENTION_DAYS);
    }

    // ------------------------------------------------------------------
    // Les quatre contrats du brief
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ligne invitée sans rôle, sans favori, ancienne : supprimée")
    void deletesAbandonedGuestRow() {
        UUID guestId = provisionGuest("guest-abandoned");
        backdate(guestId, RETENTION_DAYS + 10);

        scheduler.purgeAbandonedGuestRows();

        assertThat(countUserRows(guestId)).isZero();
    }

    @Test
    @DisplayName("ligne invitée récente : conservée")
    void keepsRecentGuestRow() {
        UUID guestId = provisionGuest("guest-recent");
        backdate(guestId, RETENTION_DAYS - 1);

        scheduler.purgeAbandonedGuestRows();

        assertThat(countUserRows(guestId)).isEqualTo(1);
    }

    @Test
    @DisplayName("ligne invitée avec un favori : conservée, favori compris")
    void keepsGuestRowWithFavorite() {
        UUID guestId = provisionGuest("guest-with-favorite");
        backdate(guestId, RETENTION_DAYS + 10);
        UUID targetId = UUID.randomUUID();
        favoriteRepository.saveAndFlush(new FavoriteEntity(guestId, FavoriteTargetType.TRIP, targetId));

        scheduler.purgeAbandonedGuestRows();

        assertThat(countUserRows(guestId)).isEqualTo(1);
        assertThat(favoriteRepository.findTargetIds(guestId, FavoriteTargetType.TRIP)).containsExactly(targetId);
    }

    @Test
    @DisplayName("compte réel (rôles non vides) : JAMAIS supprimé, même ancien et vide")
    void neverDeletesRealAccount() {
        // Volontairement dépouillé de tout sauf de ses rôles : ni favori, ni prénom, ni
        // Stripe, ni KYC. C'est le pire cas pour le critère, celui où seul « aucun rôle »
        // sépare encore ce compte d'une ligne invitée.
        UUID senderAndTraveler = realAccount("real-both", Role.SENDER, Role.TRAVELER);
        UUID senderOnly = realAccount("real-sender", Role.SENDER);
        UUID travelerOnly = realAccount("real-traveler", Role.TRAVELER);
        UUID admin = realAccount("real-admin", Role.ADMIN);
        backdate(senderAndTraveler, 400);
        backdate(senderOnly, 400);
        backdate(travelerOnly, 400);
        backdate(admin, 400);

        scheduler.purgeAbandonedGuestRows();

        assertThat(countUserRows(senderAndTraveler)).isEqualTo(1);
        assertThat(countUserRows(senderOnly)).isEqualTo(1);
        assertThat(countUserRows(travelerOnly)).isEqualTo(1);
        assertThat(countUserRows(admin)).isEqualTo(1);
        // ... et la purge n'a retiré aucun rôle au passage. GuestUserProvisioner
        // #reactivateIfSoftDeleted et GuestClaimService reposent tous deux sur l'invariant
        // « une ligne à rôles est un compte réel » : une purge qui dépouillerait une ligne
        // de ses rôles avant de la supprimer romprait cet invariant à chaque nuit.
        assertThat(countRoleRows(senderAndTraveler)).isEqualTo(2);
        assertThat(countRoleRows(senderOnly)).isEqualTo(1);
        assertThat(countRoleRows(admin)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Lignes déjà soft-deletées : l'état nominal après une réclamation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ligne invitée déjà soft-deletée par une réclamation : supprimée elle aussi")
    void deletesClaimedGuestRowEvenWhenAlreadySoftDeleted() {
        // GuestClaimService soft-delete la ligne invitée à CHAQUE réclamation réussie : c'est
        // le cas courant, pas le cas rare. Un critère qui ne verrait pas les lignes supprimées
        // (toute requête JPQL, à cause du @Where(deleted_at IS NULL) de UserEntity) les
        // laisserait s'accumuler indéfiniment, c'est-à-dire raterait sa cible principale.
        UUID guestId = provisionGuest("guest-claimed");
        UserEntity guest = userRepository.findById(guestId).orElseThrow();
        guest.softDelete();
        userRepository.saveAndFlush(guest);
        backdate(guestId, RETENTION_DAYS + 1);

        scheduler.purgeAbandonedGuestRows();

        assertThat(countUserRows(guestId)).isZero();
    }

    @Test
    @DisplayName("compte réel supprimé RGPD (soft-deleted, rôles intacts) : JAMAIS supprimé")
    void neverDeletesGdprFinalizedAccount() {
        // AccountFinalizationService#finalize soft-delete un VRAI compte sans jamais vider ses
        // rôles. Voir ces lignes est nécessaire (cas ci-dessus), les épargner l'est tout autant.
        UUID accountId = realAccount("real-gdpr", Role.SENDER, Role.TRAVELER);
        UserEntity account = userRepository.findById(accountId).orElseThrow();
        account.setFirstName("Utilisateur");
        account.setLastName("supprimé");
        account.setStatus(UserStatus.BANNED);
        account.softDelete();
        userRepository.saveAndFlush(account);
        backdate(accountId, 400);

        scheduler.purgeAbandonedGuestRows();

        assertThat(countUserRows(accountId)).isEqualTo(1);
        assertThat(countRoleRows(accountId)).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Défense en profondeur : une ligne sans rôle qui porte une marque de compte réel
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ligne sans rôle mais portant une marque de compte réel : conservée")
    void keepsRoleLessRowCarryingAnyMarkOfARealAccount() {
        UUID named = roleLessRow("mark-named", u -> u.setFirstName("Awa"));
        UUID surnamed = roleLessRow("mark-surnamed", u -> u.setLastName("Diallo"));
        UUID kyc = roleLessRow("mark-kyc", u -> u.setKycStatus(KycStatus.VERIFIED));
        UUID suspended = roleLessRow("mark-suspended", u -> u.setStatus(UserStatus.SUSPENDED));
        UUID pendingDeletion = roleLessRow("mark-pending-deletion", u -> {
            u.setStatus(UserStatus.PENDING_DELETION);
            u.setDeletionRequestedAt(java.time.Instant.now());
        });
        UUID gdprRequested = roleLessRow("mark-gdpr-requested",
                u -> u.setDeletionRequestedAt(java.time.Instant.now()));
        UUID connect = roleLessRow("mark-connect", u -> u.setStripeAccountId("acct_test"));
        UUID customer = roleLessRow("mark-customer", u -> u.setStripeCustomerId("cus_test"));
        UUID pro = roleLessRow("mark-pro", u -> u.setProAccount(true));
        for (UUID id : new UUID[] {named, surnamed, kyc, suspended, pendingDeletion, gdprRequested,
                connect, customer, pro}) {
            backdate(id, 400);
        }

        scheduler.purgeAbandonedGuestRows();

        assertThat(countUserRows(named)).isEqualTo(1);
        assertThat(countUserRows(surnamed)).isEqualTo(1);
        assertThat(countUserRows(kyc)).isEqualTo(1);
        assertThat(countUserRows(suspended)).isEqualTo(1);
        assertThat(countUserRows(pendingDeletion)).isEqualTo(1);
        assertThat(countUserRows(gdprRequested)).isEqualTo(1);
        assertThat(countUserRows(connect)).isEqualTo(1);
        assertThat(countUserRows(customer)).isEqualTo(1);
        assertThat(countUserRows(pro)).isEqualTo(1);
    }

    @Test
    @DisplayName("favori soft-deleté : compte encore comme un favori, la ligne est conservée")
    void keepsGuestRowWhoseOnlyFavoriteIsSoftDeleted() {
        // Le critère « sans favori » ignore délibérément le deleted_at des favoris. En base
        // PostgreSQL, favorites.user_id porte une clé étrangère vers users(id) sans cascade
        // (V152) : une ligne de favori soft-deletée suffirait à faire échouer la suppression
        // physique de la ligne users, et donc tout le lot avec elle.
        UUID guestId = provisionGuest("guest-soft-deleted-favorite");
        FavoriteEntity favorite = favoriteRepository.saveAndFlush(
                new FavoriteEntity(guestId, FavoriteTargetType.TRIP, UUID.randomUUID()));
        favorite.softDelete();
        favoriteRepository.saveAndFlush(favorite);
        backdate(guestId, RETENTION_DAYS + 10);

        scheduler.purgeAbandonedGuestRows();

        assertThat(countUserRows(guestId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Idempotence et garde de configuration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("idempotent : deux passages de suite ne changent rien de plus")
    void isIdempotent() {
        UUID abandoned = provisionGuest("guest-idempotent-abandoned");
        UUID kept = provisionGuest("guest-idempotent-kept");
        backdate(abandoned, RETENTION_DAYS + 10);
        backdate(kept, 1);

        scheduler.purgeAbandonedGuestRows();
        scheduler.purgeAbandonedGuestRows();

        assertThat(countUserRows(abandoned)).isZero();
        assertThat(countUserRows(kept)).isEqualTo(1);
    }

    @Test
    @DisplayName("seuil de rétention mal configuré (0 jour) : la purge ne supprime RIEN")
    void refusesToRunWithNonPositiveRetention() {
        // Un seuil à zéro ou négatif supprimerait les lignes invitées à peine créées, y compris
        // celles d'un visiteur en pleine navigation. Face à une configuration absurde, une
        // tâche destructrice ne fait rien : elle ne devine pas ce que l'exploitant voulait dire.
        UUID guestId = provisionGuest("guest-misconfigured");
        backdate(guestId, 400);

        new GuestUserCleanupScheduler(userRepository, 0).purgeAbandonedGuestRows();
        new GuestUserCleanupScheduler(userRepository, -1).purgeAbandonedGuestRows();

        assertThat(countUserRows(guestId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Fabrique une ligne invitée par le chemin de production, pas à la main. */
    private UUID provisionGuest(String firebaseUid) {
        UUID id = provisioner.resolveOrProvision(firebaseUid);
        entityManager.flush();
        return id;
    }

    private UUID realAccount(String firebaseUid, Role... roles) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        user.setUsername("u-" + UUID.randomUUID().toString().substring(0, 20));
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.NOT_STARTED);
        user.setRoles(new HashSet<>(Set.of(roles)));
        return userRepository.saveAndFlush(user).getId();
    }

    private UUID roleLessRow(String firebaseUid, java.util.function.Consumer<UserEntity> customizer) {
        UUID id = provisionGuest(firebaseUid);
        UserEntity row = userRepository.findById(id).orElseThrow();
        customizer.accept(row);
        userRepository.saveAndFlush(row);
        return id;
    }

    /** Vieillit une ligne en base : {@code created_at} est posé par @PrePersist, jamais réglable. */
    private void backdate(UUID userId, int days) {
        entityManager.flush();
        LocalDateTime when = LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
        entityManager.createNativeQuery("UPDATE users SET created_at = :when WHERE id = :id")
                .setParameter("when", when)
                .setParameter("id", userId)
                .executeUpdate();
        // Le contexte de persistance garde une copie non vieillie : sans ce clear, un flush
        // ultérieur réécrirait created_at et le test mesurerait autre chose que ce qu'il croit.
        entityManager.clear();
    }

    /** Compte les lignes réellement présentes, hors @Where(deleted_at IS NULL) et hors cache L1. */
    private long countUserRows(UUID userId) {
        entityManager.flush();
        entityManager.clear();
        return ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM users WHERE id = :id")
                .setParameter("id", userId)
                .getSingleResult()).longValue();
    }

    private long countRoleRows(UUID userId) {
        return ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM user_roles WHERE user_id = :id")
                .setParameter("id", userId)
                .getSingleResult()).longValue();
    }
}
