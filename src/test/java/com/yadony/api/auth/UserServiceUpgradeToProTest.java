package com.yadony.api.auth;

import com.yadony.api.auth.dto.UpgradeToProRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.messaging.FirestoreService;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.payments.wallet.WalletRefundRequestService;
import com.yadony.api.payments.wallet.WalletSelfRefundService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserService.upgradeToPro() — tests unitaires.
 *
 * <p>Depuis le lot Stripe billing (tâche 5), cette méthode n'accorde plus le statut
 * PRO : elle ne fait plus que mettre à jour le profil professionnel (raison sociale,
 * SIRET). Le drapeau {@code isProAccount} est désormais piloté exclusivement par
 * {@code billing/ProAccessSynchronizer}, en réaction à un abonnement Stripe payant.
 *
 * <p>Cette suite remplace l'ancienne version qui vérifiait que l'appel accordait
 * automatiquement le statut PRO ({@code upgradeToPro_validRequest_setsProAccount},
 * {@code upgradeToPro_nullFields_setsProAccountTrue}) — ce comportement a disparu,
 * les tests sont donc réécrits pour affirmer l'absence d'octroi ({@link
 * #doesNotGrantProStatus}, {@link #nullFieldsAccepted}).
 *
 * <p>Un test de l'ancienne suite n'a pas de successeur : {@code
 * upgradeToPro_alreadyPro_updatesAndEmitsDifferentAuditAction} vérifiait que
 * l'action d'audit différait selon que le compte était déjà PRO
 * ("USER_UPGRADED_TO_PRO" vs "USER_PRO_PROFILE_UPDATED"). Cette distinction n'a
 * plus de sens : la méthode n'émettant plus jamais "USER_UPGRADED_TO_PRO" (elle
 * n'accorde plus le statut), une seule action d'audit ("USER_PRO_PROFILE_UPDATED")
 * est désormais possible, quel que soit l'état initial — vérifié ci-dessous dans
 * {@link #doesNotGrantProStatus} et {@link #keepsExistingProStatus}.
 *
 * <p>Les autres tests de l'ancienne suite (validation SIRET, champs nuls/vides,
 * compte avec Stripe Connect existant) restent pertinents — leur logique est
 * inchangée par cette tâche — et sont conservés ci-dessous, adaptés au nouveau
 * constructeur de {@link UserService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService.upgradeToPro — ne donne plus le statut PRO")
class UserServiceUpgradeToProTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock UserRepository userRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock WalletAccountRepository walletAccountRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AccountFinalizationService accountFinalizationService;
    @Mock FirestoreService firestoreService;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock WalletRefundRequestService walletRefundRequestService;
    @Mock WalletSelfRefundService walletSelfRefundService;

    private UserService service() {
        return new UserService(userRepository, paymentRepository, walletAccountRepository,
                auditService, eventPublisher, accountFinalizationService, firestoreService,
                notificationDispatcher, walletRefundRequestService, walletSelfRefundService);
    }

    private UserEntity user(boolean pro) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        u.setProAccount(pro);
        return u;
    }

    @Test
    @DisplayName("un compte standard renseigne son profil sans devenir PRO")
    void doesNotGrantProStatus() {
        UserEntity u = user(false);
        when(userRepository.save(u)).thenReturn(u);

        service().upgradeToPro(u, new UpgradeToProRequest("Yadony SARL", "12345678901234"));

        assertThat(u.isProAccount())
                .as("le statut PRO s'obtient désormais par abonnement payant")
                .isFalse();
        assertThat(u.getProCompanyName()).isEqualTo("Yadony SARL");
        assertThat(u.getProSiret()).isEqualTo("12345678901234");
        verify(eventPublisher, never()).publishEvent(any(UserProStatusChangedEvent.class));
        verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_PRO_PROFILE_UPDATED"), eq(USER_ID), any());
    }

    @Test
    @DisplayName("un compte déjà PRO garde son statut en mettant à jour son profil")
    void keepsExistingProStatus() {
        UserEntity u = user(true);
        when(userRepository.save(u)).thenReturn(u);

        service().upgradeToPro(u, new UpgradeToProRequest("Yadony SAS", null));

        assertThat(u.isProAccount())
                .as("un abonné payant ne doit pas perdre son accès en corrigeant sa raison sociale")
                .isTrue();
        assertThat(u.getProCompanyName()).isEqualTo("Yadony SAS");
        assertThat(u.getProSiret()).isNull();
        verify(eventPublisher, never()).publishEvent(any(UserProStatusChangedEvent.class));
        verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_PRO_PROFILE_UPDATED"), eq(USER_ID), any());
    }

    @Test
    @DisplayName("champs nuls acceptés : profil vidé sans erreur, toujours sans statut PRO")
    void nullFieldsAccepted() {
        UserEntity u = user(false);
        when(userRepository.save(u)).thenReturn(u);

        service().upgradeToPro(u, new UpgradeToProRequest(null, null));

        assertThat(u.isProAccount()).isFalse();
        assertThat(u.getProCompanyName()).isNull();
        assertThat(u.getProSiret()).isNull();
    }

    @Test
    @DisplayName("SIRET vide traité comme absent, aucune erreur de validation")
    void blankSiretNoValidationError() {
        UserEntity u = user(false);
        when(userRepository.save(u)).thenReturn(u);

        service().upgradeToPro(u, new UpgradeToProRequest("Mon Entreprise", "   "));

        assertThat(u.isProAccount()).isFalse();
        assertThat(u.getProCompanyName()).isEqualTo("Mon Entreprise");
    }

    @Test
    @DisplayName("un compte avec un Stripe Connect existant peut aussi mettre à jour son profil pro")
    void stripeAccountExists_stillUpdatesProfile() {
        UserEntity u = user(false);
        u.setStripeAccountId("acct_existing_123");
        when(userRepository.save(u)).thenReturn(u);

        service().upgradeToPro(u, new UpgradeToProRequest("Yadony SARL", "12345678901234"));

        assertThat(u.isProAccount()).isFalse();
        assertThat(u.getProCompanyName()).isEqualTo("Yadony SARL");
        verify(userRepository).save(u);
    }

    @Test
    @DisplayName("un SIRET mal formé reste refusé")
    void invalidSiretStillRejected() {
        assertThatThrownBy(() ->
                service().upgradeToPro(user(false), new UpgradeToProRequest("X", "123")))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("SIRET à 13 chiffres → HTTP 422 Unprocessable Entity")
    void siret13Digits_throws422() {
        assertThatThrownBy(() ->
                service().upgradeToPro(user(false), new UpgradeToProRequest("Yadony SARL", "1234567890123")))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("invalid-siret");
                });
    }

    @Test
    @DisplayName("SIRET à 15 chiffres → HTTP 422 Unprocessable Entity")
    void siret15Digits_throws422() {
        assertThatThrownBy(() ->
                service().upgradeToPro(user(false), new UpgradeToProRequest("Yadony SARL", "123456789012345")))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("invalid-siret");
                });
    }

    @Test
    @DisplayName("SIRET avec des lettres → HTTP 422 Unprocessable Entity")
    void siretWithLetters_throws422() {
        assertThatThrownBy(() ->
                service().upgradeToPro(user(false), new UpgradeToProRequest("Yadony SARL", "1234567890ABCD")))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("invalid-siret");
                });
    }
}
