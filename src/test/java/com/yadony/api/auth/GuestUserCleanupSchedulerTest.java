package com.yadony.api.auth;

import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.favorites.FavoriteEntity;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.favorites.FavoriteTargetType;
import com.yadony.api.kyc.KycVerificationEntity;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import io.sentry.Sentry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 *
 * <p><b>Une limite dont il faut avoir conscience.</b> H2 fabrique son schéma à partir des
 * entités JPA, où la clé étrangère {@code user_roles → users} n'a pas de cascade ; PostgreSQL,
 * lui, la déclare {@code ON DELETE CASCADE} (V1). Supprimer par erreur une ligne à rôles
 * échoue donc bruyamment ici, et réussirait <b>silencieusement</b> en production. Aucun test de
 * ce dépôt ne peut reproduire ce que ferait un critère faux là-bas : c'est le critère lui-même,
 * et lui seul, qui protège.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class GuestUserCleanupSchedulerTest {

    private static final int RETENTION_DAYS = 30;

    @Autowired UserRepository userRepository;
    @Autowired FavoriteRepository favoriteRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager entityManager;

    private GuestUserProvisioner provisioner;
    private GuestUserCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        provisioner = new GuestUserProvisioner(userRepository, new UsernameGenerator(userRepository));
        scheduler = new GuestUserCleanupScheduler(
                userRepository, new AuditService(auditLogRepository), transactionManager, RETENTION_DAYS);
        // Suppression native : un deleteAll() JPA relirait les lignes existantes, et relire
        // une entrée d'audit échoue sur H2 (cf. countAuditRows).
        entityManager.createNativeQuery("DELETE FROM audit_log").executeUpdate();
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
        // Précaution structurelle, pas cas réel : FavoriteEntity#softDelete() n'est appelé
        // nulle part dans src/main (le retrait d'un favori est un hard delete). Elle protège
        // la clé étrangère PostgreSQL favorites.user_id → users(id), sans cascade (V152) :
        // le jour où un favori serait soft-deleté, sa seule présence ferait échouer la
        // suppression de la ligne users, et donc tout le lot de la nuit avec elle.
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
    // Traces d'activité dans les tables liées : les deux modes de défaillance
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ligne sans rôle ayant publié un trajet : conservée (FK sans cascade)")
    void keepsRoleLessRowThatOwnsAnAnnouncement() {
        // Mode de défaillance 1 : announcements.traveler_id référence users(id) SANS cascade
        // (V3). Sans cette condition, le DELETE du lot échouerait en entier — aucune purge
        // cette nuit-là ni les suivantes, et l'échec serait avalé sans bruit.
        UUID ownerId = provisionGuest("activity-announcement");
        backdate(ownerId, 400);
        newAnnouncement(ownerId);

        scheduler.purgeAbandonedGuestRows();

        assertThat(countUserRows(ownerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("ligne sans rôle ayant un appareil ou un blocage : conservée (FK avec cascade)")
    void keepsRoleLessRowWithCascadingChildren() {
        // Mode de défaillance 2 : user_devices (V96) et user_blocks (V98) sont en ON DELETE
        // CASCADE. Sans cette condition, la suppression réussirait et emporterait ces enfants
        // SILENCIEUSEMENT — le pire des deux modes, puisque rien ne le signalerait.
        UUID withDevice = provisionGuest("activity-device");
        UUID blocker = provisionGuest("activity-blocker");
        UUID blocked = provisionGuest("activity-blocked");
        backdate(withDevice, 400);
        backdate(blocker, 400);
        backdate(blocked, 400);
        entityManager.persist(newDevice(withDevice));
        entityManager.persist(newBlock(blocker, blocked));
        entityManager.flush();

        scheduler.purgeAbandonedGuestRows();

        assertThat(countUserRows(withDevice)).isEqualTo(1);
        assertThat(countUserRows(blocker)).isEqualTo(1);
        assertThat(countUserRows(blocked)).isEqualTo(1);
    }

    @Test
    @DisplayName("ligne sans rôle ayant une vérification KYC : conservée (FK avec cascade)")
    void keepsRoleLessRowWithKycVerification() {
        // kyc_schema.kyc_verifications référence users(id) en ON DELETE CASCADE (V2:19). C'est
        // la famille dangereuse : la suppression réussirait et emporterait SILENCIEUSEMENT une
        // vérification d'identité, sa session Stripe Identity et ses champs chiffrés.
        // La ligne garde volontairement kyc_status = NOT_STARTED : ce test doit prouver la
        // condition sur la table liée, pas celle sur la colonne de statut.
        UUID ownerId = provisionGuest("activity-kyc");
        backdate(ownerId, 400);
        KycVerificationEntity kyc = new KycVerificationEntity();
        kyc.setUserId(ownerId);
        kyc.setStripeVerificationSessionId("vs_test_" + UUID.randomUUID());
        entityManager.persist(kyc);
        entityManager.flush();

        scheduler.purgeAbandonedGuestRows();

        assertThat(countUserRows(ownerId)).isEqualTo(1);
        assertThat(countKycRows(ownerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("le critère couvre les treize tables liées inventoriées")
    void predicateCoversEveryInventoriedRelatedTable() {
        // Cette énumération est le seul rempart contre les deux modes de défaillance ci-dessus,
        // et rien dans le code ne signalerait qu'une ligne en a disparu. Ce test le signale.
        // Les sept clés étrangères ON DELETE CASCADE vers users (user_roles, kyc_verifications,
        // user_notification_preferences, user_devices, user_blocks x2, user_business_preferences)
        // sont TOUTES ici : c'est la seule famille qui détruit sans bruit.
        assertThat(UserRepository.ABANDONED_GUEST_ROW_PREDICATE)
                .contains("FROM user_roles r WHERE r.user_id = users.id")
                .contains("FROM favorites f WHERE f.user_id = users.id")
                .contains("a.traveler_id = users.id")
                .contains("a.reserved_sender_id = users.id")
                .contains("FROM bids b WHERE b.sender_id = users.id")
                .contains("FROM package_requests pr WHERE pr.sender_id = users.id")
                .contains("c.sender_id = users.id")
                .contains("c.traveler_id = users.id")
                .contains("d.sender_id = users.id")
                .contains("d.traveler_id = users.id")
                .contains("FROM wallet_accounts wa WHERE wa.user_id = users.id")
                .contains("FROM notifications n WHERE n.user_id = users.id")
                .contains("FROM corridor_alerts ca WHERE ca.owner_id = users.id")
                .contains("FROM kyc_schema.kyc_verifications kv WHERE kv.user_id = users.id")
                .contains("FROM user_devices ud WHERE ud.user_id = users.id")
                .contains("FROM user_notification_preferences np WHERE np.user_id = users.id")
                .contains("FROM user_business_preferences bp WHERE bp.user_id = users.id")
                .contains("ub.blocker_id = users.id")
                .contains("ub.blocked_id = users.id");
    }

    // ------------------------------------------------------------------
    // Trace immuable de ce qui a été détruit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("une entrée audit_log nomme exactement les lignes détruites")
    void writesAnAuditEntryNamingEveryDeletedRow() {
        // La suppression étant physique, l'entrée d'audit est la SEULE trace de ce qui a
        // disparu : sans elle, une erreur de critère serait irréversible ET indiagnosticable.
        UUID firstDeleted = provisionGuest("audit-deleted-1");
        UUID secondDeleted = provisionGuest("audit-deleted-2");
        UUID kept = provisionGuest("audit-kept");
        backdate(firstDeleted, 400);
        backdate(secondDeleted, 400);
        backdate(kept, 1);

        scheduler.purgeAbandonedGuestRows();

        assertThat(countAuditRows()).isEqualTo(1);
        List<AuditLogEntity> entries = auditLogRepository.findAll();
        AuditLogEntity entry = entries.get(0);
        assertThat(entry.getAction()).isEqualTo(GuestUserCleanupScheduler.AUDIT_ACTION);
        assertThat(entry.getPayload()).containsEntry("deletedCount", 2);
        assertThat(entry.getPayload()).containsEntry("retentionDays", RETENTION_DAYS);
        // Les clés survivent à AuditService.redact() : une valeur masquée rendrait l'entrée
        // inutilisable pour l'enquête qui justifie son existence.
        assertThat(entry.getPayload().get("cutoff")).isNotEqualTo("[redacted]");
        List<String> auditedIds = ((List<?>) entry.getPayload().get("deletedUserIds"))
                .stream().map(String::valueOf).toList();
        assertThat(auditedIds)
                .containsExactlyInAnyOrder(firstDeleted.toString(), secondDeleted.toString())
                .doesNotContain(kept.toString());
    }

    @Test
    @DisplayName("aucune ligne supprimée : aucune entrée audit_log (table immuable)")
    void writesNoAuditEntryWhenNothingWasDeleted() {
        UUID recent = provisionGuest("audit-none");
        backdate(recent, 1);

        scheduler.purgeAbandonedGuestRows();

        assertThat(countAuditRows()).isZero();
    }

    // ------------------------------------------------------------------
    // Idempotence, garde de configuration, échec bruyant
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
        // Le second passage n'a rien trouvé : il n'a donc rien ajouté à la table immuable.
        assertThat(countAuditRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("seuil de rétention mal configuré (0 jour) : la purge ne supprime RIEN")
    void refusesToRunWithNonPositiveRetention() {
        // Un seuil à zéro ou négatif supprimerait les lignes invitées à peine créées, y compris
        // celles d'un visiteur en pleine navigation. Face à une configuration absurde, une
        // tâche destructrice ne fait rien : elle ne devine pas ce que l'exploitant voulait dire.
        UUID guestId = provisionGuest("guest-misconfigured");
        backdate(guestId, 400);
        AuditService auditService = new AuditService(auditLogRepository);

        new GuestUserCleanupScheduler(userRepository, auditService, transactionManager, 0)
                .purgeAbandonedGuestRows();
        new GuestUserCleanupScheduler(userRepository, auditService, transactionManager, -1)
                .purgeAbandonedGuestRows();

        assertThat(countUserRows(guestId)).isEqualTo(1);
        assertThat(countAuditRows()).isZero();
    }

    @Test
    @DisplayName("échec de la purge : journalisé et remonté dans Sentry, jamais avalé")
    void reportsFailureLoudlyInsteadOfDyingInSilence() {
        // Une clé étrangère non anticipée fait échouer le DELETE en entier. Sans ce filet, la
        // purge cesserait de tourner sans que personne ne l'apprenne : ni cette nuit-là, ni
        // les suivantes. Repository et gestionnaire de transaction sont ici des mocks, pour ne
        // pas empoisonner la transaction ambiante du test avec un rollback-only.
        UserRepository failing = mock(UserRepository.class);
        AuditService auditService = mock(AuditService.class);
        RuntimeException boom = new IllegalStateException("clé étrangère non anticipée");
        when(failing.findAbandonedGuestRowIds(any())).thenThrow(boom);
        GuestUserCleanupScheduler failingScheduler = new GuestUserCleanupScheduler(
                failing, auditService, mock(PlatformTransactionManager.class), RETENTION_DAYS);

        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            assertThatCode(failingScheduler::purgeAbandonedGuestRows).doesNotThrowAnyException();
            sentry.verify(() -> Sentry.captureException(boom), times(1));
        }
        // Rien n'a été détruit, donc rien n'est écrit dans la table immuable.
        verify(auditService, never()).log(any(), any(), any(), any(), any());
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

    private void newAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDepartureDate(LocalDate.now().plusDays(5));
        a.setTransportMode(com.yadony.api.matching.TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport Bamako-Sénou");
        a.setDeliveryLat(new BigDecimal("12.533579"));
        a.setDeliveryLng(new BigDecimal("-7.948969"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        announcementRepository.saveAndFlush(a);
    }

    private UserDeviceEntity newDevice(UUID userId) {
        UserDeviceEntity device = new UserDeviceEntity();
        device.setUserId(userId);
        device.setDeviceId("device-" + UUID.randomUUID());
        device.setDeviceName("Pixel de test");
        device.setPlatform("android");
        device.setLastSeenAt(OffsetDateTime.now(ZoneOffset.UTC));
        device.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return device;
    }

    private UserBlockEntity newBlock(UUID blockerId, UUID blockedId) {
        UserBlockEntity block = new UserBlockEntity();
        block.setBlockerId(blockerId);
        block.setBlockedId(blockedId);
        block.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return block;
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

    /**
     * Compte les entrées d'audit sans les désérialiser : sur H2, relire la colonne déclarée
     * {@code jsonb} échoue (« Could not deserialize string to java type »). Artefact du schéma
     * de test, sans rapport avec la purge — en PostgreSQL la colonne est un vrai {@code jsonb}.
     */
    private long countAuditRows() {
        entityManager.flush();
        return ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM audit_log")
                .getSingleResult()).longValue();
    }

    /** Compte les vérifications KYC en base, hors @Where(deleted_at IS NULL). */
    private long countKycRows(UUID userId) {
        entityManager.flush();
        return ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM kyc_schema.kyc_verifications WHERE user_id = :id")
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
