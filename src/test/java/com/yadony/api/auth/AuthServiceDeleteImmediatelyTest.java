package com.yadony.api.auth;

import com.yadony.api.auth.dto.DeleteImmediatelyRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService.deleteImmediately")
class AuthServiceDeleteImmediatelyTest {

    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private UserService userService;
    @Mock private AccountFinalizationService accountFinalizationService;

    @InjectMocks private AuthService authService;

    private static final String FIREBASE_UID = "uid-test-001";
    private static final UUID USER_ID = UUID.randomUUID();

    private UserEntity makeUser(UserStatus status) {
        UserEntity u = new UserEntity();
        setId(u, USER_ID);
        u.setFirebaseUid(FIREBASE_UID);
        u.setStatus(status);
        return u;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("escrow actif → 422")
    void activeEscrow_throws422() {
        when(userRepository.findByFirebaseUid(FIREBASE_UID))
                .thenReturn(Optional.of(makeUser(UserStatus.ACTIVE)));
        when(userService.hasActiveEscrow(USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> authService.deleteImmediately(
                FIREBASE_UID, new DeleteImmediatelyRequest(true)))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("solde wallet positif → ne bloque plus, ticket ouvert, finalisation appelée quand même (Apple 5.1.1(v))")
    void positiveWalletBalance_doesNotBlock_opensTicketAndFinalizes() {
        UserEntity user = makeUser(UserStatus.ACTIVE);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

        authService.deleteImmediately(FIREBASE_UID, new DeleteImmediatelyRequest(true));

        // La règle elle-même vit dans UserService#openWalletRefundTicketIfNeeded (point unique,
        // partagé avec requestDeletion) et y est testée sur le vrai repository ; ici on vérifie
        // uniquement que deleteImmediately l'applique puis poursuit la finalisation.
        verify(userService).openWalletRefundTicketIfNeeded(USER_ID);
        verify(accountFinalizationService).finalize(eq(user), eq(FinalizationReason.HARD_IMMEDIATE));
    }

    @Test
    @DisplayName("wallet à solde zéro → pas bloqué, finalisation appelée")
    void zeroWalletBalance_doesNotBlock() {
        UserEntity user = makeUser(UserStatus.ACTIVE);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

        authService.deleteImmediately(FIREBASE_UID, new DeleteImmediatelyRequest(true));

        verify(userService).openWalletRefundTicketIfNeeded(USER_ID);
        verify(accountFinalizationService).finalize(eq(user), eq(FinalizationReason.HARD_IMMEDIATE));
    }

    @Test
    @DisplayName("user déjà BANNED → 409")
    void alreadyBanned_throws409() {
        // No escrow stub needed: BANNED check runs before escrow check
        when(userRepository.findByFirebaseUid(FIREBASE_UID))
                .thenReturn(Optional.of(makeUser(UserStatus.BANNED)));

        assertThatThrownBy(() -> authService.deleteImmediately(
                FIREBASE_UID, new DeleteImmediatelyRequest(true)))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("succès → AccountFinalizationService.finalize appelé avec HARD_IMMEDIATE")
    void success_callsFinalizationService() {
        UserEntity user = makeUser(UserStatus.ACTIVE);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

        authService.deleteImmediately(FIREBASE_UID, new DeleteImmediatelyRequest(true));

        verify(accountFinalizationService).finalize(eq(user), eq(FinalizationReason.HARD_IMMEDIATE));
        verify(auditService).log(eq("USER"), eq(USER_ID),
                eq("ACCOUNT_DELETE_IMMEDIATELY_REQUESTED"), eq(USER_ID), any());
    }
}
