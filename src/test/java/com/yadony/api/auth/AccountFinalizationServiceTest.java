package com.yadony.api.auth;

import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import com.yadony.api.auth.events.IdentitySessionsErasureRequestedEvent;
import com.yadony.api.auth.events.UserFinalizedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.kyc.KycRepository;
import com.yadony.api.kyc.KycVerificationEntity;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountFinalizationService")
class AccountFinalizationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private KycRepository kycRepository;
    @Mock private StorageService storageService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private FirebaseContactService firebaseContact;
    @Mock private com.yadony.api.common.stripe.AdminAlertService adminAlertService;
    @Mock private TaxInfoRetentionPort taxInfoRetention;

    @InjectMocks private AccountFinalizationService service;

    private UserEntity makeUser() {
        UserEntity u = new UserEntity();
        setId(u, UUID.randomUUID());
        u.setFirebaseUid("uid-test");
        u.setFirstName("Jean");
        u.setLastName("Dupont");
        u.setStatus(UserStatus.PENDING_DELETION);
        u.setBirthDate(java.time.LocalDate.of(1990, 1, 1));
        u.setCity("Paris");
        u.setResidenceStreet("12 rue de la Paix");
        u.setResidenceLine2("Appt 4");
        u.setResidencePostalCode("75002");
        // Lot C : donnees re-identifiantes que l'anonymisation laissait intactes.
        u.setProAccount(true);
        u.setProSiret("81234567800012");
        // Statut légal du voyageur (lot 2) : raison sociale et numéro d'immatriculation re-identifient.
        u.setTravelerStatus(com.yadony.api.travelerstatus.TravelerStatus.PROFESSIONAL);
        u.setTravelerStatusDeclaredAt(java.time.Instant.now());
        u.setTravelerStatusTermsVersion("2026-11-10");
        u.setBusinessName("Acme Express");
        u.setBusinessRegistrationNumber("SIREN 123 456 789");
        u.setBusinessCountry("FR");
        u.setKycStatus(KycStatus.VERIFIED);
        u.setDeletionRequestedAt(java.time.Instant.now());
        return u;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try { Field f = c.getDeclaredField("id"); f.setAccessible(true); f.set(entity, id); return; }
                catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    @DisplayName("pseudonymise le user et soft-delete KYC")
    void pseudonymizesUserAndSoftDeletesKyc() {
        UserEntity user = makeUser();
        UUID userId = user.getId();
        KycVerificationEntity kyc = new KycVerificationEntity();
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kyc));
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        assertThat(user.getStatus()).isEqualTo(UserStatus.BANNED);
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getFirstName()).isEqualTo("Utilisateur");
        assertThat(user.getLastName()).isEqualTo("supprimé");
        assertThat(user.getFcmToken()).isNull();
        assertThat(kyc.getDeletedAt()).isNotNull();
        assertThat(user.getBirthDate()).isNull();
        assertThat(user.getCity()).isNull();
        assertThat(user.getResidenceStreet()).isNull();
        assertThat(user.getResidenceLine2()).isNull();
        assertThat(user.getResidencePostalCode()).isNull();
    }

    @Test
    @DisplayName("écrit la clé d'audit pseudonymized, jamais pseuyadonymized")
    void writesPseudonymizedAuditKey() {
        UserEntity user = makeUser();
        UUID userId = user.getId();
        com.google.firebase.auth.FirebaseAuth mockAuth =
                mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(
                eq("USER"), eq(userId), eq("USER_GDPR_DELETION"), eq(userId), payload.capture());

        assertThat(payload.getValue()).containsKey("pseudonymized");
        assertThat(payload.getValue()).doesNotContainKey("pseuyadonymized");
    }

    @Test
    @DisplayName("anonymise proSiret — un SIRET reste un identifiant ré-identifiant")
    void anonymizesProSiret() {
        UserEntity user = makeUser();
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        assertThat(user.getProSiret()).isNull();
    }

    @Test
    @DisplayName("efface le statut légal du voyageur : statut, date, version, raison sociale, numéro et pays")
    void clearsTravelerStatusColumns() {
        UserEntity user = makeUser();
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        assertThat(user.getTravelerStatus()).isNull();
        assertThat(user.getTravelerStatusDeclaredAt()).isNull();
        assertThat(user.getTravelerStatusTermsVersion()).isNull();
        assertThat(user.getBusinessName()).isNull();
        assertThat(user.getBusinessRegistrationNumber()).isNull();
        assertThat(user.getBusinessCountry()).isNull();
    }

    @Test
    @DisplayName("efface les informations fiscales DAC7 : NIF, pays d'émission, TVA et leur suivi")
    void clearsDac7TaxInfo() {
        UserEntity user = makeUser();
        user.setTaxIdentificationNumber("3023217600053");
        user.setTaxCountry("FR");
        user.setVatNumber("FR40303265045");
        user.setTaxInfoProvidedAt(java.time.Instant.now());
        user.setTaxInfoRequestedAt(java.time.Instant.now());
        user.setTaxReminderCount(2);
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        assertThat(user.getTaxIdentificationNumber()).isNull();
        assertThat(user.getTaxCountry()).isNull();
        assertThat(user.getVatNumber()).isNull();
        assertThat(user.getTaxInfoProvidedAt()).isNull();
        assertThat(user.getTaxInfoRequestedAt()).isNull();
        assertThat(user.getTaxReminderCount()).isZero();
    }

    private UserEntity userWithTaxInfo() {
        UserEntity user = makeUser();
        user.setTaxIdentificationNumber("3023217600053");
        user.setTaxCountry("FR");
        user.setVatNumber("FR40303265045");
        user.setTaxInfoProvidedAt(java.time.Instant.parse("2026-03-01T10:00:00Z"));
        user.setTaxInfoRequestedAt(java.time.Instant.now());
        user.setTaxReminderCount(2);
        return user;
    }

    private void finalizeWithFirebase(UserEntity user) {
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);
        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }
    }

    @Test
    @DisplayName("DAC7 : NIF, pays d'émission et TVA gardés, résidence et statut figés, si encore à déclarer")
    void retainsDac7TaxInfoWhenStillDeclarable() {
        UserEntity user = userWithTaxInfo();
        // La règle est évaluée AVANT l'effacement : le pays de l'entreprise d'un professionnel compte encore.
        org.mockito.Mockito.when(taxInfoRetention.retention(org.mockito.ArgumentMatchers.argThat(
                        u -> "FR".equals(u.getBusinessCountry()))))
                .thenReturn(java.util.Optional.of(new TaxInfoRetentionPort.Retention("FR",
                        com.yadony.api.travelerstatus.TravelerStatus.PROFESSIONAL)));

        finalizeWithFirebase(user);

        assertThat(user.getTaxIdentificationNumber()).isEqualTo("3023217600053");
        assertThat(user.getTaxCountry()).isEqualTo("FR");
        assertThat(user.getVatNumber()).isEqualTo("FR40303265045");
        assertThat(user.getTaxInfoProvidedAt()).isEqualTo(java.time.Instant.parse("2026-03-01T10:00:00Z"));
        assertThat(user.getTaxResidenceCountry()).isEqualTo("FR");
        assertThat(user.getTaxSellerStatus()).isEqualTo(com.yadony.api.travelerstatus.TravelerStatus.PROFESSIONAL);
        // Plus aucun rappel pour un compte supprimé ; le reste de l'identité est bien effacé.
        assertThat(user.getTaxInfoRequestedAt()).isNull();
        assertThat(user.getTaxReminderCount()).isZero();
        assertThat(user.getBirthDate()).isNull();
        assertThat(user.getResidenceStreet()).isNull();
        assertThat(user.getBusinessCountry()).isNull();
        assertThat(user.getTravelerStatus()).isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("USER"), eq(user.getId()), eq("USER_GDPR_DELETION"), eq(user.getId()),
                payload.capture());
        assertThat(payload.getValue()).containsEntry("taxInfoRetained", true);
        assertThat(payload.getValue().toString()).doesNotContain("3023217600053").doesNotContain("FR4030");
    }

    @Test
    @DisplayName("DAC7 : décision en échec, la suppression aboutit quand même et garde le NIF par prudence")
    void dac7DecisionFailure_finalizationStillSucceedsAndRetains() {
        UserEntity user = userWithTaxInfo();
        org.mockito.Mockito.when(taxInfoRetention.retention(any()))
                .thenThrow(new IllegalStateException("statement timeout"));

        finalizeWithFirebase(user);

        assertThat(user.getStatus()).isEqualTo(UserStatus.BANNED);
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getFirstName()).isEqualTo("Utilisateur");
        assertThat(user.getTaxIdentificationNumber()).isEqualTo("3023217600053");
        // Repli : la règle DAC7 (pays de l'entreprise pour un professionnel) est figée.
        assertThat(user.getTaxResidenceCountry()).isEqualTo("FR");
        verify(auditService).log(eq("USER"), eq(user.getId()), eq("USER_GDPR_DELETION"), eq(user.getId()),
                org.mockito.ArgumentMatchers.argThat(m -> Boolean.TRUE.equals(m.get("taxInfoRetained"))));
    }

    @Test
    @DisplayName("DAC7 : rien à garder (ou DAC7 éteint) : tout est effacé comme avant le lot")
    void dac7NothingToRetain_erasesEverything() {
        UserEntity user = userWithTaxInfo();
        user.setTaxResidenceCountry("FR");
        org.mockito.Mockito.when(taxInfoRetention.retention(any())).thenReturn(java.util.Optional.empty());

        finalizeWithFirebase(user);

        assertThat(user.getTaxIdentificationNumber()).isNull();
        assertThat(user.getTaxCountry()).isNull();
        assertThat(user.getVatNumber()).isNull();
        assertThat(user.getTaxInfoProvidedAt()).isNull();
        assertThat(user.getTaxResidenceCountry()).isNull();
        assertThat(user.getTaxSellerStatus()).isNull();
    }

    @Test
    @DisplayName("remet kycStatus à NOT_STARTED et efface deletionRequestedAt")
    void resetsKycStatusAndClearsDeletionRequest() {
        UserEntity user = makeUser();
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        assertThat(user.getKycStatus()).isEqualTo(KycStatus.NOT_STARTED);
        assertThat(user.getDeletionRequestedAt()).isNull();
    }

    @Test
    @DisplayName("efface le pointeur de session Stripe — il mène aux pièces d'identité")
    void clearsStripeVerificationSessionPointer() {
        UserEntity user = makeUser();
        UUID userId = user.getId();
        KycVerificationEntity kyc = new KycVerificationEntity();
        kyc.setVerificationSessionId("vs_test_123");
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kyc));
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        assertThat(kyc.getVerificationSessionId()).isNull();
        assertThat(kyc.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("capture la session Didit avant de la vider et la publie pour effacement chez Didit")
    void capturesDiditSessionBeforeClearingIt() {
        UserEntity user = makeUser();
        UUID userId = user.getId();
        KycVerificationEntity kyc = new KycVerificationEntity();
        kyc.setProvider(VerificationProviderKind.DIDIT);
        kyc.setVerificationSessionId("11111111-2222-3333-4444-555555555555");
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kyc));

        service.finalize(user, FinalizationReason.HARD_IMMEDIATE);

        assertThat(kyc.getVerificationSessionId()).isNull();
        ArgumentCaptor<IdentitySessionsErasureRequestedEvent> captor =
                ArgumentCaptor.forClass(IdentitySessionsErasureRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(userId);
        assertThat(captor.getValue().diditSessionIds())
                .containsExactly("11111111-2222-3333-4444-555555555555");
    }

    @Test
    @DisplayName("une session Stripe Identity n'est pas envoyée à l'effacement Didit")
    void doesNotSendStripeSessionToDidit() {
        UserEntity user = makeUser();
        UUID userId = user.getId();
        KycVerificationEntity kyc = new KycVerificationEntity();
        kyc.setProvider(VerificationProviderKind.STRIPE);
        kyc.setVerificationSessionId("vs_test_123");
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kyc));

        service.finalize(user, FinalizationReason.HARD_IMMEDIATE);

        ArgumentCaptor<IdentitySessionsErasureRequestedEvent> captor =
                ArgumentCaptor.forClass(IdentitySessionsErasureRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().diditSessionIds()).isEmpty();
    }

    @Test
    @DisplayName("sans ligne KYC, l'effacement Didit est quand même demandé (sessions refusées)")
    void publishesErasureEvenWithoutKycRow() {
        UserEntity user = makeUser();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());

        service.finalize(user, FinalizationReason.SOFT_GRACE_EXPIRED);

        ArgumentCaptor<IdentitySessionsErasureRequestedEvent> captor =
                ArgumentCaptor.forClass(IdentitySessionsErasureRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(user.getId());
        assertThat(captor.getValue().diditSessionIds()).isEmpty();
    }

    @Test
    @DisplayName("ADMIN_INITIATED est propagée telle quelle dans l'event")
    void propagatesAdminInitiatedReason() {
        UserEntity user = makeUser();
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.ADMIN_INITIATED);
        }

        ArgumentCaptor<UserFinalizedEvent> captor = ArgumentCaptor.forClass(UserFinalizedEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .anySatisfy(e -> assertThat(e.getReason()).isEqualTo(FinalizationReason.ADMIN_INITIATED));
    }

    @Test
    @DisplayName("supprime le compte Firebase — seul porteur du téléphone et de l'email")
    void deletesFirebaseAccountAndEvictsContactCache() {
        UserEntity user = makeUser();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());

        service.finalize(user, FinalizationReason.HARD_IMMEDIATE);

        // Suppression et purge du cache sont une seule opération portée par le service
        // qui détient le cache : l'appelant ne peut plus oublier la seconde.
        verify(firebaseContact).deleteAccount("uid-test");
    }

    @Test
    @DisplayName("suppression Firebase ratée → alerte admin FIREBASE_USER_DELETE_FAILED, sans PII")
    void firebaseDeletionFailure_raisesAdminAlert() {
        UserEntity user = makeUser();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());
        when(firebaseContact.deleteAccount("uid-test")).thenReturn(FirebaseContactService.DeletionOutcome.FAILED);

        service.finalize(user, FinalizationReason.SOFT_GRACE_EXPIRED);

        verify(adminAlertService).raise(eq("FIREBASE_USER_DELETE_FAILED"), anyString(), argThat(ctx ->
                user.getId().equals(ctx.get("userId")) && "uid-test".equals(ctx.get("firebaseUid"))
                        && ctx.keySet().stream().noneMatch(k -> k.contains("phone") || k.contains("email"))));
    }

    @Test
    @DisplayName("suppression Firebase réussie ou compte déjà parti → aucune alerte")
    void firebaseDeletionOk_noAlert() {
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());
        when(firebaseContact.deleteAccount("uid-test"))
                .thenReturn(FirebaseContactService.DeletionOutcome.DELETED)
                .thenReturn(FirebaseContactService.DeletionOutcome.ALREADY_GONE);

        service.finalize(makeUser(), FinalizationReason.HARD_IMMEDIATE);
        service.finalize(makeUser(), FinalizationReason.HARD_IMMEDIATE);

        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("supprime les fichiers R2 du user")
    void deletesR2Files() {
        UserEntity user = makeUser();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        verify(storageService).deleteByPrefix("support/" + user.getId() + "/");
    }

    private void runFinalize(UserEntity user) {
        FirebaseAuth mockAuth = mock(FirebaseAuth.class);
        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }
    }

    @Test
    @DisplayName("purge support/ et l'avatar, jamais kyc/, bids/, package_requests/ ni reports/")
    void finalize_purgesOnlySupportAndAvatar() {
        UserEntity user = makeUser();
        user.setAvatarUrl("users/uid-test/avatar.jpg");
        UUID userId = user.getId();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());

        runFinalize(user);

        verify(storageService).deleteByPrefix("support/" + userId + "/");
        verify(storageService).deleteByPrefix("users/uid-test/");
        verify(storageService, never()).deleteByPrefix("kyc/" + userId + "/");
        verify(storageService, never()).deleteByPrefix("bids/" + userId + "/");
        verify(storageService, never()).deleteByPrefix("package_requests/" + userId + "/");
        verify(storageService, never()).deleteByPrefix("reports/" + userId + "/");
        assertThat(user.getAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("uid null ou blanc : pas de purge users/")
    void finalize_noUsersPurgeWhenUidNullOrBlank() {
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());
        UserEntity nullUid = makeUser();
        nullUid.setFirebaseUid(null);
        runFinalize(nullUid);
        UserEntity blankUid = makeUser();
        blankUid.setFirebaseUid("  ");
        runFinalize(blankUid);

        verify(storageService, never()).deleteByPrefix(startsWith("users/"));
    }

    @Test
    @DisplayName("la suppression R2 n'a lieu qu'après le commit")
    void finalize_deletesOnlyAfterCommit() {
        UserEntity user = makeUser();
        UUID userId = user.getId();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            runFinalize(user);
            verify(storageService, never()).deleteByPrefix(anyString());

            for (org.springframework.transaction.support.TransactionSynchronization sync :
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(storageService).deleteByPrefix("support/" + userId + "/");
            verify(storageService).deleteByPrefix("users/uid-test/");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("une erreur R2 n'empêche ni les autres préfixes ni la finalisation")
    void finalize_r2FailureDoesNotBreakFinalization() {
        UserEntity user = makeUser();
        UUID userId = user.getId();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("R2 down")).when(storageService).deleteByPrefix("support/" + userId + "/");

        runFinalize(user);

        verify(storageService).deleteByPrefix("users/uid-test/");
        verify(auditService).log(eq("USER"), eq(userId), eq("USER_GDPR_DELETION"), eq(userId), anyMap());
    }

    @Test
    @DisplayName("publie UserFinalizedEvent avec la bonne reason")
    void publishesUserFinalizedEvent() {
        UserEntity user = makeUser();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.SOFT_GRACE_EXPIRED);
        }

        ArgumentCaptor<UserFinalizedEvent> captor = ArgumentCaptor.forClass(UserFinalizedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo(FinalizationReason.SOFT_GRACE_EXPIRED);
    }

    @Test
    @DisplayName("publie AccountDeletionRequestedEvent pour annuler les annonces/bids du user (HARD_IMMEDIATE et SOFT_GRACE_EXPIRED ne passent jamais par requestDeletion())")
    void publishesAccountDeletionRequestedEventForBothReasons() {
        UserEntity user = makeUser();
        UUID userId = user.getId();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        ArgumentCaptor<AccountDeletionRequestedEvent> captor = ArgumentCaptor.forClass(AccountDeletionRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("crée une entrée audit log USER_GDPR_DELETION")
    void createsAuditLog() {
        UserEntity user = makeUser();
        UUID userId = user.getId();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        verify(auditService).log(eq("USER"), eq(userId), eq("USER_GDPR_DELETION"), eq(userId), any());
    }
}
