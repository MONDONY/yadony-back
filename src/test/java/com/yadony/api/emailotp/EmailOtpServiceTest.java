package com.yadony.api.emailotp;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailOtpService — tests unitaires")
class EmailOtpServiceTest {

    @Mock private EmailOtpRepository emailOtpRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ResendEmailService resendEmailService;
    @Mock private FirebaseAuth firebaseAuth;
    @Mock private UserRepository userRepository;
    @Mock private com.yadony.api.auth.FirebaseContactService firebaseContact;
    @Mock private com.yadony.api.common.AuditService auditService;

    // Instance réelle, pas un mock : ces seuils sont la règle métier testée ici.
    // Les mocker reviendrait à vérifier des valeurs choisies par le test plutôt
    // que celles qu'appliquera la production.
    @org.mockito.Spy private EmailOtpProperties properties = new EmailOtpProperties();

    @InjectMocks private EmailOtpService emailOtpService;

    private static final String EMAIL = "test@example.com";

    // Ces fabriques stubbent leurs propres mocks : les appeler à l'intérieur d'un
    // when(...) imbriquerait deux stubbings et Mockito lèverait
    // UnfinishedStubbingException. Toujours les affecter à une variable d'abord.

    /** UserRecord minimal : seul l'UID est lu par le service. */
    private static com.google.firebase.auth.UserRecord userRecord(String uid) {
        com.google.firebase.auth.UserRecord record =
                mock(com.google.firebase.auth.UserRecord.class);
        doReturn(uid).when(record).getUid();
        return record;
    }

    /** Firebase signale une adresse inconnue par ce code d'erreur, pas par un null. */
    private static com.google.firebase.auth.FirebaseAuthException userNotFound() {
        com.google.firebase.auth.FirebaseAuthException e =
                mock(com.google.firebase.auth.FirebaseAuthException.class);
        doReturn(com.google.firebase.auth.AuthErrorCode.USER_NOT_FOUND)
                .when(e).getAuthErrorCode();
        return e;
    }

    @Nested
    @DisplayName("sendOtp")
    class SendOtp {

        @Test
        @DisplayName("succès — sauvegarde token et envoie email")
        void success() {
            when(emailOtpRepository.countByEmailSince(eq(EMAIL), any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
            when(emailOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            var result = emailOtpService.sendOtp(EMAIL);

            assertThat(result).isNotNull();
            verify(emailOtpRepository).save(argThat(e ->
                    EMAIL.equals(e.getEmail()) && "$2a$10$hashed".equals(e.getCodeHash())));
            verify(resendEmailService).sendOtp(eq(EMAIL), argThat(code ->
                    code.matches("\\d{6}")));
        }

        @Test
        @DisplayName("le 5e renvoi passe encore — le bouton de l'app se rouvre toutes les 60 s")
        void fourPreviousSendsStillAllowANewOne() {
            // Régression : le budget était de 3, alors que l'écran rouvre « Renvoyer
            // le code » chaque minute. L'utilisateur se voyait refuser un renvoi que
            // l'interface venait de lui proposer.
            when(emailOtpRepository.countByEmailSince(eq(EMAIL), any())).thenReturn(4L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
            when(emailOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThat(emailOtpService.sendOtp(EMAIL)).isNotNull();
            verify(resendEmailService).sendOtp(eq(EMAIL), anyString());
        }

        @Test
        @DisplayName("429 — 5 envois ou plus dans la fenêtre de 5 min")
        void rateLimitExceeded() {
            when(emailOtpRepository.countByEmailSince(eq(EMAIL), any())).thenReturn(5L);

            assertThatThrownBy(() -> emailOtpService.sendOtp(EMAIL))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            verify(emailOtpRepository, never()).save(any());
            verify(resendEmailService, never()).sendOtp(any(), any());
        }
    }

    @Nested
    @DisplayName("attachEmailToAccount")
    class AttachEmail {

        private static final String UID = "uid-inscrit-par-sms";

        private EmailOtpEntity validToken() {
            EmailOtpEntity t = new EmailOtpEntity();
            t.setEmail(EMAIL);
            t.setCodeHash("$2a$10$hash");
            t.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
            t.setAttempts(0);
            return t;
        }

        private void givenValidOtp() {
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.of(validToken()));
            when(passwordEncoder.matches("123456", "$2a$10$hash")).thenReturn(true);
            when(emailOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        private UserEntity account() {
            UserEntity u = new UserEntity();
            u.setFirebaseUid(UID);
            org.springframework.test.util.ReflectionTestUtils.setField(u, "id", java.util.UUID.randomUUID());
            return u;
        }

        @Test
        @DisplayName("compte sans email + code valide → écrit l'adresse dans Firebase")
        void attach_success() {
            when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(account()));
            givenValidOtp();
            when(firebaseContact.getContact(UID))
                    .thenReturn(com.yadony.api.auth.FirebaseContactService.Contact.EMPTY);
            when(firebaseContact.isEmailTakenByAnother(EMAIL, UID)).thenReturn(false);

            emailOtpService.attachEmailToAccount(UID, EMAIL, "123456");

            verify(firebaseContact).updateEmail(UID, EMAIL);
            verify(auditService).log(eq("USER"), any(), eq("USER_EMAIL_ATTACHED"), any(), any());
        }

        @Test
        @DisplayName("code faux → aucune écriture : la preuve de possession est exigée")
        void attach_wrongCode_writesNothing() {
            when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(account()));
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.of(validToken()));
            when(passwordEncoder.matches("000000", "$2a$10$hash")).thenReturn(false);
            when(emailOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThatThrownBy(() -> emailOtpService.attachEmailToAccount(UID, EMAIL, "000000"))
                    .isInstanceOf(YadonyBusinessException.class);

            // Le cœur de la protection : sans code valide, rien n'atteint Firebase
            verify(firebaseContact, never()).updateEmail(anyString(), anyString());
        }

        @Test
        @DisplayName("compte portant déjà un email → 409, pas de remplacement")
        void attach_emailAlreadySet_conflict() {
            when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(account()));
            givenValidOtp();
            when(firebaseContact.getContact(UID)).thenReturn(
                    new com.yadony.api.auth.FirebaseContactService.Contact(null, "deja@yadony.app"));

            assertThatThrownBy(() -> emailOtpService.attachEmailToAccount(UID, EMAIL, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("email-already-set");
                    });
            verify(firebaseContact, never()).updateEmail(anyString(), anyString());
        }

        @Test
        @DisplayName("adresse déjà rattachée à un autre compte → 409")
        void attach_emailTakenByAnother_conflict() {
            when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(account()));
            givenValidOtp();
            when(firebaseContact.getContact(UID))
                    .thenReturn(com.yadony.api.auth.FirebaseContactService.Contact.EMPTY);
            when(firebaseContact.isEmailTakenByAnother(EMAIL, UID)).thenReturn(true);

            assertThatThrownBy(() -> emailOtpService.attachEmailToAccount(UID, EMAIL, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("email-already-exists"));
            verify(firebaseContact, never()).updateEmail(anyString(), anyString());
        }

        @Test
        @DisplayName("compte inconnu → 404")
        void attach_unknownAccount_404() {
            when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> emailOtpService.attachEmailToAccount(UID, EMAIL, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("verifyOtp")
    class VerifyOtp {

        private EmailOtpEntity validToken() {
            EmailOtpEntity t = new EmailOtpEntity();
            t.setEmail(EMAIL);
            t.setCodeHash("$2a$10$hash");
            t.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
            t.setAttempts(0);
            return t;
        }

        @Test
        @DisplayName("succès — adresse inconnue de Firebase, le compte est créé")
        void success() throws Exception {
            EmailOtpEntity token = validToken();
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.of(token));
            when(passwordEncoder.matches("123456", "$2a$10$hash")).thenReturn(true);
            var absente = userNotFound();
            var nouveau = userRecord("uid-tout-neuf");
            when(firebaseAuth.getUserByEmail(EMAIL)).thenThrow(absente);
            when(firebaseAuth.createUser(any())).thenReturn(nouveau);
            when(firebaseAuth.createCustomToken(eq("uid-tout-neuf"), any()))
                    .thenReturn("firebase-custom-token");
            when(emailOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            String result = emailOtpService.verifyOtp(EMAIL, "123456");

            assertThat(result).isEqualTo("firebase-custom-token");
            assertThat(token.getUsedAt()).isNotNull();
        }

        @Test
        @DisplayName("le compte Firebase qui porte l'adresse est réutilisé, même sans ligne locale")
        void success_existingUser_usesExistingFirebaseUid() throws Exception {
            // RÉGRESSION : l'UID n'était réutilisé que si un compte local y correspondait.
            // Sinon le service prenait l'adresse elle-même comme UID, fabriquant une
            // seconde identité Firebase pour la même personne ; l'inscription échouait
            // ensuite sur EMAIL_ALREADY_EXISTS, après consommation du code OTP.
            EmailOtpEntity token = validToken();
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.of(token));
            when(passwordEncoder.matches("123456", "$2a$10$hash")).thenReturn(true);
            var compteGoogle = userRecord("uid-cree-par-google");
            when(firebaseAuth.getUserByEmail(EMAIL)).thenReturn(compteGoogle);
            when(firebaseAuth.createCustomToken(eq("uid-cree-par-google"), any()))
                    .thenReturn("firebase-token-with-existing-uid");
            when(emailOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            String result = emailOtpService.verifyOtp(EMAIL, "123456");

            assertThat(result).isEqualTo("firebase-token-with-existing-uid");
            verify(firebaseAuth, never()).createUser(any());
        }

        @Test
        @DisplayName("400 — aucun token non utilisé")
        void noTokenFound() {
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> emailOtpService.verifyOtp(EMAIL, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("429 — le budget d'essais survit au renvoi d'un nouveau code")
        void attemptsBudgetSurvivesResend() {
            // 5 échecs cumulés sur d'anciens tokens : même un token tout neuf
            // (attempts=0) doit être refusé, sinon chaque renvoi offre 5 essais frais.
            when(emailOtpRepository.sumAttemptsByEmailSince(eq(EMAIL), any()))
                    .thenReturn(5L);

            assertThatThrownBy(() -> emailOtpService.verifyOtp(EMAIL, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            verify(emailOtpRepository, never())
                    .findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(any());
        }

        @Test
        @DisplayName("429 — trop de tentatives échouées")
        void tooManyAttempts() {
            EmailOtpEntity token = validToken();
            token.setAttempts(5);
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.of(token));

            assertThatThrownBy(() -> emailOtpService.verifyOtp(EMAIL, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("400 — token expiré")
        void tokenExpired() {
            EmailOtpEntity token = validToken();
            token.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.of(token));

            assertThatThrownBy(() -> emailOtpService.verifyOtp(EMAIL, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("400 — code BCrypt invalide, incrémente attempts")
        void invalidCode() {
            EmailOtpEntity token = validToken();
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.of(token));
            when(passwordEncoder.matches("000000", "$2a$10$hash")).thenReturn(false);
            when(emailOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThatThrownBy(() -> emailOtpService.verifyOtp(EMAIL, "000000"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            assertThat(token.getAttempts()).isEqualTo(1);
            verify(emailOtpRepository).save(argThat(e -> e.getAttempts() == 1));
        }

        @Test
        @DisplayName("400 — OTP déjà consommé (usedAt != null, filtré par la query)")
        void tokenAlreadyUsed() {
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> emailOtpService.verifyOtp(EMAIL, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("succès — retourne null si firebaseAuth non disponible (mode test)")
        void firebaseAuth_null_returnsNull() {
            EmailOtpService serviceWithoutFirebase = new EmailOtpService(
                    properties, emailOtpRepository, passwordEncoder, resendEmailService, null,
                    userRepository, firebaseContact, auditService);
            EmailOtpEntity token = validToken();
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.of(token));
            when(passwordEncoder.matches("123456", "$2a$10$hash")).thenReturn(true);
            when(emailOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            String result = serviceWithoutFirebase.verifyOtp(EMAIL, "123456");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("500 — FirebaseAuthException lors de createCustomToken")
        void firebaseAuthException() throws Exception {
            EmailOtpEntity token = validToken();
            when(emailOtpRepository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(Optional.of(token));
            when(passwordEncoder.matches("123456", "$2a$10$hash")).thenReturn(true);
            when(emailOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            var existant = userRecord("uid-existant");
            var panne = mock(com.google.firebase.auth.FirebaseAuthException.class);
            when(firebaseAuth.getUserByEmail(EMAIL)).thenReturn(existant);
            when(firebaseAuth.createCustomToken(eq("uid-existant"), any())).thenThrow(panne);

            assertThatThrownBy(() -> emailOtpService.verifyOtp(EMAIL, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
