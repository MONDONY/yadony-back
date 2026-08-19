package com.yadony.api.auth;

import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import com.yadony.api.auth.events.UserFinalizedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.kyc.KycRepository;
import com.yadony.api.kyc.KycVerificationEntity;
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
        // Lot C : donnees re-identifiantes que l'anonymisation laissait intactes.
        u.setProAccount(true);
        u.setProSiret("81234567800012");
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
    @DisplayName("pseuyadonymise le user et soft-delete KYC")
    void pseuyadonymizesUserAndSoftDeletesKyc() {
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
        kyc.setStripeVerificationSessionId("vs_test_123");
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kyc));
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        assertThat(kyc.getStripeVerificationSessionId()).isNull();
        assertThat(kyc.getDeletedAt()).isNotNull();
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
    @DisplayName("supprime les fichiers R2 du user")
    void deletesR2Files() {
        UserEntity user = makeUser();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        verify(storageService).deleteByPrefix("kyc/" + user.getId() + "/");
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
