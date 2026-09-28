package com.yadony.api.admin;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    @Mock UserService userService;
    @Mock UserRepository userRepository;
    @Mock com.yadony.api.auth.FirebaseContactService firebaseContact;
    @Mock UserDeletionImpactService deletionImpactService;
    @Mock AdminUserDeletionService deletionService;
    @Mock com.yadony.api.billing.ProSubscriptionService proSubscriptionService;
    @Mock com.yadony.api.billing.ProSubscriptionRepository proSubscriptionRepository;
    @Mock com.yadony.api.payments.hold.PayoutHoldService payoutHoldService;

    // ── Délégation du contrôleur ────────────────────────────────────────────

    @Test
    void setCommissionRate_delegatesToService_andReturnsDetail() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        // Coordonnées servies par Firebase, plus par la base
        when(firebaseContact.getContact(any())).thenReturn(
                com.yadony.api.auth.FirebaseContactService.Contact.EMPTY);
        UUID userId = UUID.randomUUID();
        BigDecimal rate = new BigDecimal("0.08");
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        when(userService.setCommissionRateOverride(userId, rate, ADMIN_ID)).thenReturn(user);

        var resp = controller.setCommissionRate(userId, new CommissionRateOverrideRequest(rate), adminAuth());

        assertThat(resp).isNotNull();
        verify(userService).setCommissionRateOverride(userId, rate, ADMIN_ID);
    }

    @Test
    void setCommissionRate_nullRate_delegatesNull_forGlobalReset() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        // Coordonnées servies par Firebase, plus par la base
        when(firebaseContact.getContact(any())).thenReturn(
                com.yadony.api.auth.FirebaseContactService.Contact.EMPTY);
        UUID userId = UUID.randomUUID();
        when(userService.setCommissionRateOverride(userId, null, ADMIN_ID))
                .thenReturn(new com.yadony.api.auth.UserEntity());

        controller.setCommissionRate(userId, new CommissionRateOverrideRequest(null), adminAuth());

        verify(userService).setCommissionRateOverride(userId, null, ADMIN_ID);
    }

    // ── Suspension / levée : l'admin est l'acteur de l'audit ─────────────────

    @Test
    void unsuspend_propagatesAdminId() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        when(firebaseContact.getContact(any())).thenReturn(
                com.yadony.api.auth.FirebaseContactService.Contact.EMPTY);
        UUID userId = UUID.randomUUID();
        when(userService.unsuspendUser(userId, ADMIN_ID)).thenReturn(new com.yadony.api.auth.UserEntity());

        controller.unsuspendUser(userId, adminAuth());

        verify(userService).unsuspendUser(userId, ADMIN_ID);
    }

    @Test
    void suspendPublishing_propagatesAdminId() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        UUID userId = UUID.randomUUID();

        ResponseEntity<Void> resp = controller.suspendPublishing(userId, "retour non rendu", adminAuth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(userService).suspendPublishing(userId, "retour non rendu", ADMIN_ID);
    }

    @Test
    void liftPublishingSuspension_propagatesAdminId() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        UUID userId = UUID.randomUUID();

        ResponseEntity<Void> resp = controller.liftPublishingSuspension(userId, adminAuth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(userService).liftPublishingSuspension(userId, ADMIN_ID);
    }

    // ── Lot B : coupure de messagerie ────────────────────────────────────────

    private static final UUID ADMIN_ID = UUID.randomUUID();

    /**
     * L'identifiant de l'administrateur doit atteindre le service : c'est lui qui part dans
     * {@code audit_log} comme acteur. Une trace qui designe la CIBLE ne pourra jamais etre
     * corrigee, la table etant immuable.
     */
    private static org.springframework.security.core.Authentication adminAuth() {
        var principal = new com.yadony.api.admin.account.AdminPrincipal(
                ADMIN_ID, "admin@yadony.test",
                com.yadony.api.admin.account.AdminRole.ADMIN, false, "uid-admin");
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, java.util.List.of());
    }

    @Test
    void muteMessaging_delegatesToService_andReturnsDetail() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        when(firebaseContact.getContact(any())).thenReturn(
                com.yadony.api.auth.FirebaseContactService.Contact.EMPTY);
        UUID userId = UUID.randomUUID();
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        when(userService.muteMessaging(userId, 24, "harcèlement", ADMIN_ID)).thenReturn(user);

        var resp = controller.muteMessaging(userId,
                new com.yadony.api.admin.dto.MuteMessagingRequest(24, "harcèlement"), adminAuth());

        assertThat(resp).isNotNull();
        verify(userService).muteMessaging(userId, 24, "harcèlement", ADMIN_ID);
    }

    @Test
    void muteMessaging_nullDuration_delegatesNull_forIndefiniteMute() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        when(firebaseContact.getContact(any())).thenReturn(
                com.yadony.api.auth.FirebaseContactService.Contact.EMPTY);
        UUID userId = UUID.randomUUID();
        when(userService.muteMessaging(userId, null, "fraude", ADMIN_ID))
                .thenReturn(new com.yadony.api.auth.UserEntity());

        controller.muteMessaging(userId,
                new com.yadony.api.admin.dto.MuteMessagingRequest(null, "fraude"), adminAuth());

        verify(userService).muteMessaging(userId, null, "fraude", ADMIN_ID);
    }

    @Test
    void unmuteMessaging_delegatesToService_andReturnsDetail() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        when(firebaseContact.getContact(any())).thenReturn(
                com.yadony.api.auth.FirebaseContactService.Contact.EMPTY);
        UUID userId = UUID.randomUUID();
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        when(userService.unmuteMessaging(userId, ADMIN_ID)).thenReturn(user);

        var resp = controller.unmuteMessaging(userId, adminAuth());

        assertThat(resp).isNotNull();
        verify(userService).unmuteMessaging(userId, ADMIN_ID);
    }

    @Test
    void muteMessagingRequest_blankReason_isRejected() {
        assertThat(validator().validate(new com.yadony.api.admin.dto.MuteMessagingRequest(24, "  ")))
                .isNotEmpty();
    }

    @Test
    void muteMessagingRequest_validReason_hasNoViolations() {
        assertThat(validator().validate(new com.yadony.api.admin.dto.MuteMessagingRequest(24, "harcèlement")))
                .isEmpty();
    }

    // ── Recherche : téléphone et email ne sont plus en base ─────────────────

    @Test
    void listUsers_queryOnEmail_resolvesFirebaseUid_andMapsContacts() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        user.setFirebaseUid("uid-awa");

        when(firebaseContact.findUidByEmail("awa@example.com")).thenReturn(java.util.Optional.of("uid-awa"));
        when(userRepository.findAdminFiltered(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(user)));
        when(firebaseContact.getContacts(java.util.List.of("uid-awa"))).thenReturn(
                java.util.Map.of("uid-awa", new com.yadony.api.auth.FirebaseContactService.Contact(
                        "+221701234567", "awa@example.com")));

        var page = controller.listUsers(null, null, null, null, null, "awa@example.com", 0, 20);

        // L'UID résolu est passé à la requête, qui l'apparie exactement
        verify(userRepository).findAdminFiltered(
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("%awa@example.com%"),
                org.mockito.ArgumentMatchers.eq("uid-awa"),
                org.mockito.ArgumentMatchers.eq("awa@example.com"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), any());
        assertThat(page.getContent()).singleElement()
                .extracting(com.yadony.api.admin.dto.AdminUserListItemResponse::phoneNumber)
                .isEqualTo("+221701234567");
    }

    @Test
    void listUsers_queryOnPhone_usesPhoneLookupOnly() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        when(firebaseContact.findUidByPhone("+221701234567")).thenReturn(java.util.Optional.of("uid-awa"));
        when(userRepository.findAdminFiltered(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        controller.listUsers(null, null, null, null, null, "+221701234567", 0, 20);

        // Un terme en E.164 ne déclenche que le lookup téléphone : le lookup email
        // était un aller-retour réseau voué à échouer.
        verify(firebaseContact).findUidByPhone("+221701234567");
        verify(firebaseContact, org.mockito.Mockito.never()).findUidByEmail(any());
    }

    @Test
    void listUsers_queryOnName_hitsNoFirebaseLookup() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        when(userRepository.findAdminFiltered(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        controller.listUsers(null, null, null, null, null, "Dupont", 0, 20);

        // Cas dominant d'une liste admin : aucun appel Firebase ne doit partir.
        verify(firebaseContact, org.mockito.Mockito.never()).findUidByEmail(any());
        verify(firebaseContact, org.mockito.Mockito.never()).findUidByPhone(any());
    }

    @Test
    void listUsers_queryShapedLikeUuid_passesTypedIdAndRawTerm() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        UUID id = UUID.fromString("3f2b8c1e-9a4d-4e6f-8b21-7c5d0e9a1b34");
        when(userRepository.findAdminFiltered(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        controller.listUsers(null, null, null, null, null, "  " + id + "  ", 0, 20);

        verify(userRepository).findAdminFiltered(
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("%" + id + "%"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(id.toString()),
                org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.isNull(), any());
        verify(firebaseContact, org.mockito.Mockito.never()).findUidByEmail(any());
        verify(firebaseContact, org.mockito.Mockito.never()).findUidByPhone(any());
    }

    @Test
    void listUsers_queryNotStrictUuid_passesNoTypedId() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        when(userRepository.findAdminFiltered(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        // UUID.fromString accepterait « 1-1-1-1-1 » : seule la forme canonique compte.
        controller.listUsers(null, null, null, null, null, "1-1-1-1-1", 0, 20);
        controller.listUsers(null, null, null, null, null, "FbUidAwa123XYZ", 0, 20);

        verify(userRepository).findAdminFiltered(
                any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("1-1-1-1-1"),
                org.mockito.ArgumentMatchers.isNull(), any(), any());
        verify(userRepository).findAdminFiltered(
                any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("FbUidAwa123XYZ"),
                org.mockito.ArgumentMatchers.isNull(), any(), any());
    }

    @Test
    void listUsers_withoutQuery_doesNotHitFirebaseLookups() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        when(userRepository.findAdminFiltered(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        controller.listUsers(null, null, null, null, null, "   ", 0, 20);

        verify(firebaseContact, org.mockito.Mockito.never()).findUidByEmail(any());
        verify(firebaseContact, org.mockito.Mockito.never()).findUidByPhone(any());
    }

    // ── Bean validation du DTO (@DecimalMin / @DecimalMax) ───────────────────

    private static Validator validator() {
        try (ValidatorFactory f = Validation.buildDefaultValidatorFactory()) {
            return f.getValidator();
        }
    }

    @Test
    void request_validRate_hasNoViolations() {
        assertThat(validator().validate(new CommissionRateOverrideRequest(new BigDecimal("0.08"))))
                .isEmpty();
    }

    @Test
    void request_nullRate_hasNoViolations() {
        // null = retour au taux global, accepté par le DTO.
        assertThat(validator().validate(new CommissionRateOverrideRequest(null))).isEmpty();
    }

    @Test
    void request_negativeRate_isRejected() {
        assertThat(validator().validate(new CommissionRateOverrideRequest(new BigDecimal("-0.01"))))
                .isNotEmpty();
    }

    @Test
    void request_rateOneOrAbove_isRejected() {
        assertThat(validator().validate(new CommissionRateOverrideRequest(new BigDecimal("1.5"))))
                .isNotEmpty();
    }

    // ── Octroi / révocation PRO admin (lot 3, vague finale) ──────────────────

    @Test
    void grantPro_userNotFound_throws404_beforeCallingService() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.grantPro(userId,
                new com.yadony.api.admin.dto.ProGrantRequest("Partenariat"), adminAuth()))
                .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((com.yadony.api.common.YadonyBusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        // Mutation refusée avant tout appel au service : pas de ligne fantôme.
        verify(proSubscriptionService, never())
                .grantByAdmin(any(), any(), any());
    }

    @Test
    void grantPro_userExists_delegatesToService_andReturnsDetail() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        UUID userId = UUID.randomUUID();
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(firebaseContact.getContact(any())).thenReturn(
                com.yadony.api.auth.FirebaseContactService.Contact.EMPTY);

        var resp = controller.grantPro(userId,
                new com.yadony.api.admin.dto.ProGrantRequest("Partenariat"), adminAuth());

        assertThat(resp).isNotNull();
        verify(proSubscriptionService).grantByAdmin(userId, ADMIN_ID, "Partenariat");
    }

    @Test
    void revokePro_delegatesToRevokeAdminGrant_andReturnsDetail() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        UUID userId = UUID.randomUUID();
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(firebaseContact.getContact(any())).thenReturn(
                com.yadony.api.auth.FirebaseContactService.Contact.EMPTY);

        var resp = controller.revokePro(userId, adminAuth());

        assertThat(resp).isNotNull();
        verify(proSubscriptionService).revokeAdminGrant(userId, ADMIN_ID);
    }

    @Test
    void getUser_exposeLeGelDesVersements() {
        AdminUserController controller = new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService, deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
        UUID userId = UUID.randomUUID();
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(firebaseContact.getContact(any())).thenReturn(com.yadony.api.auth.FirebaseContactService.Contact.EMPTY);
        java.time.LocalDateTime since = java.time.LocalDateTime.now().minusDays(1);
        when(payoutHoldService.summaryOf(userId)).thenReturn(new com.yadony.api.payments.hold.PayoutHoldSummary(
                since, java.util.List.of(com.yadony.api.payments.hold.PayoutHoldReason.KYC_REVOKED), 2L));

        var detail = controller.getUser(userId);

        assertThat(detail.payoutsHeldSince()).isEqualTo(since);
        assertThat(detail.payoutsHeldReason()).isEqualTo("KYC_REVOKED");
        assertThat(detail.heldPaymentsCount()).isEqualTo(2L);
    }

    // ── Annulation d'une suppression de compte ───────────────────────────────

    private AdminUserController newController() {
        return new AdminUserController(userService, userRepository, firebaseContact, deletionImpactService,
                deletionService, proSubscriptionService, proSubscriptionRepository, payoutHoldService);
    }

    @Test
    void cancelDeletion_delegatesWithAdminAndTrimmedReason_andReturnsDetail() {
        when(firebaseContact.getContact(any())).thenReturn(
                com.yadony.api.auth.FirebaseContactService.Contact.EMPTY);
        UUID userId = UUID.randomUUID();
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        user.setStatus(com.yadony.api.auth.UserStatus.ACTIVE);
        user.setKycStatus(com.yadony.api.auth.KycStatus.NOT_STARTED);
        when(userService.cancelDeletionByAdmin(userId, ADMIN_ID, "Demande faite par erreur")).thenReturn(user);

        var detail = newController().cancelDeletion(userId,
                new com.yadony.api.admin.dto.RestoreRequest("  Demande faite par erreur "), adminAuth());

        assertThat(detail.status()).isEqualTo("ACTIVE");
        assertThat(detail.deletionRequestedAt()).isNull();
        assertThat(detail.deletionScheduledFor()).isNull();
        verify(userService).cancelDeletionByAdmin(userId, ADMIN_ID, "Demande faite par erreur");
    }

    @Test
    void getUser_pendingDeletion_exposesRequestAndScheduledFinalization() {
        when(firebaseContact.getContact(any())).thenReturn(
                com.yadony.api.auth.FirebaseContactService.Contact.EMPTY);
        UUID userId = UUID.randomUUID();
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        user.setStatus(com.yadony.api.auth.UserStatus.PENDING_DELETION);
        user.setKycStatus(com.yadony.api.auth.KycStatus.NOT_STARTED);
        java.time.Instant requested = java.time.Instant.parse("2026-09-10T08:00:00Z");
        user.setDeletionRequestedAt(requested);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var detail = newController().getUser(userId);

        assertThat(detail.deletionRequestedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 10, 8, 0));
        // Le scheduler finalise les demandes de plus de 30 jours.
        assertThat(detail.deletionScheduledFor()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 10, 8, 0));
    }

    @Test
    void listUsers_itemsExposeDeletionDates() {
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        user.setStatus(com.yadony.api.auth.UserStatus.PENDING_DELETION);
        user.setKycStatus(com.yadony.api.auth.KycStatus.NOT_STARTED);
        user.setDeletionRequestedAt(java.time.Instant.parse("2026-09-10T08:00:00Z"));
        user.setFirebaseUid("uid-pending");
        when(userRepository.findAdminFiltered(org.mockito.ArgumentMatchers.eq("PENDING_DELETION"), any(), any(),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(user)));
        when(firebaseContact.getContacts(any())).thenReturn(java.util.Map.of());

        var page = newController().listUsers(com.yadony.api.auth.UserStatus.PENDING_DELETION,
                null, null, null, null, null, 0, 20);

        var item = page.getContent().get(0);
        assertThat(item.status()).isEqualTo("PENDING_DELETION");
        assertThat(item.deletionRequestedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 10, 8, 0));
        assertThat(item.deletionScheduledFor()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 10, 8, 0));
    }
}
