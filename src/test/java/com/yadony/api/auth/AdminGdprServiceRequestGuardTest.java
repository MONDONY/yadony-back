package com.yadony.api.auth;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L'execution RGPD est IRREVERSIBLE : pseudonymisation du nom, effacement du SIRET et du
 * statut KYC, purge des pieces d'identite, suppression du compte Firebase — l'utilisateur ne
 * peut plus se connecter.
 *
 * <p>Elle ne verifiait pourtant PAS qu'une demande existait. La file
 * {@code GET /gdpr-requests} ne liste que les comptes portant un {@code deletionRequestedAt},
 * mais rien ne reliait techniquement cette file a l'execution : l'API acceptait n'importe
 * quel identifiant.
 *
 * <p>Le garde n'est pas absolu, car une demande RGPD arrive legitimement par courrier ou par
 * mail, hors application. Il exige alors un aveu EXPLICITE, trace dans l'audit — la
 * difference entre un clic et une decision.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminGdprServiceRequestGuardTest — pas d'execution sans demande")
class AdminGdprServiceRequestGuardTest {

    @Mock UserRepository userRepository;
    @Mock UserService userService;
    @Mock AccountFinalizationService accountFinalizationService;
    @Mock AuditService auditService;

    AdminGdprService service;

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminGdprService(userRepository, userService, accountFinalizationService, auditService);
    }

    private UserEntity user(Instant deletionRequestedAt) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirebaseUid("uid-guard");
        u.setDeletionRequestedAt(deletionRequestedAt);
        lenient().when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        lenient().when(userService.hasActiveEscrow(u.getId())).thenReturn(false);
        return u;
    }

    @Test
    @DisplayName("Aucune demande et aucun aveu → 422, et RIEN n'est detruit")
    void withoutRequestAndWithoutAcknowledgement_throws422AndFinalizesNothing() {
        UserEntity target = user(null);

        assertThatThrownBy(() -> service.executeDeletion(target.getId(), ADMIN_ID, "motif", false))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("gdpr-no-request"));

        // Le point critique : le refus doit intervenir AVANT toute destruction.
        verify(accountFinalizationService, never()).finalize(any(), any());
        verify(auditService, never()).log(any(), any(), eq("USER_GDPR_EXECUTED"), any(), any());
    }

    @Test
    @DisplayName("Demande presente → execution normale, sans aveu necessaire")
    void withPendingRequest_executesNormally() {
        UserEntity target = user(Instant.parse("2026-07-01T00:00:00Z"));

        service.executeDeletion(target.getId(), ADMIN_ID, "demande confirmee", false);

        verify(accountFinalizationService).finalize(target, FinalizationReason.ADMIN_INITIATED);
    }

    @Test
    @DisplayName("Aucune demande mais aveu explicite → execution, et l'audit le consigne")
    void withoutRequestButAcknowledged_executesAndRecordsIt() {
        UserEntity target = user(null);

        service.executeDeletion(target.getId(), ADMIN_ID, "demande recue par courrier", true);

        verify(accountFinalizationService).finalize(target, FinalizationReason.ADMIN_INITIATED);
        // La trace doit dire qu'il n'y avait PAS de demande en base : sans cela, on ne
        // pourrait plus distinguer a posteriori une execution reguliere d'une execution
        // decidee hors application — audit_log etant immuable.
        verify(auditService).log(eq("USER"), eq(target.getId()), eq("USER_GDPR_EXECUTED"),
                eq(ADMIN_ID), argThatRecordsNoRequest());
    }

    private static Map<String, Object> argThatRecordsNoRequest() {
        return org.mockito.ArgumentMatchers.argThat(payload ->
                "true".equals(String.valueOf(payload.get("withoutUserRequest"))));
    }
}
