package com.yadony.api.smsotp;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.notifications.InvalidSmsRecipientException;
import com.yadony.api.notifications.SmsService;
import com.yadony.api.notifications.TwilioVerifyService;
import com.yadony.api.notifications.TwilioVerifyService.VerifyUnavailableException;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SmsOtpService — tests unitaires")
class SmsOtpServiceTest {

    @Mock private SmsOtpRepository smsOtpRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SmsService smsService;
    // Instance réelle et non un mock : ces seuils sont la règle métier vérifiée
    // ici. Un mock renverrait 0 partout et ferait passer les tests contre des
    // valeurs choisies par le test au lieu de celles de la production.
    @org.mockito.Spy private SmsOtpProperties properties = new SmsOtpProperties();
    @Mock private FirebaseAuth firebaseAuth;
    @Mock private UserRepository userRepository;
    @Mock private FirebaseContactService firebaseContact;
    @Mock private AuditService auditService;
    @Mock private Environment environment;
    // Mock muet par défaut : handles() rend faux, donc tous les tests historiques restent
    // sur la route SMS classique. Seul le groupe « Twilio Verify » l'active.
    @Mock private TwilioVerifyService twilioVerify;
    // Instance réelle et non un mock : messagesResolver.forRequest().get("sms.otp", ...)
    // doit lire le vrai RequestContextHolder posé par TestMessages, pas un mock muet.
    private final MessagesResolver messagesResolver = TestMessages.resolver();

    private static final String PHONE = "+221701234567";

    private SmsOtpService newService() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"test"});
        return new SmsOtpService(smsOtpRepository, passwordEncoder, smsService, properties,
                firebaseAuth, userRepository, firebaseContact, auditService, environment, messagesResolver,
                twilioVerify);
    }

    @AfterEach
    void clearRequestLanguage() {
        TestMessages.clearRequest();
    }

    @Nested
    @DisplayName("sendOtp")
    class SendOtp {

        @Test
        @DisplayName("succès — sauvegarde token et envoie SMS, texte français exact, sans configuration")
        void success() {
            SmsOtpService service = newService();
            when(smsOtpRepository.countByPhoneSince(eq(PHONE), any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(smsService.isEnabled()).thenReturn(true);

            var result = service.sendOtp(PHONE);

            assertThat(result).isNotNull();
            verify(smsOtpRepository).save(argThat(e ->
                    PHONE.equals(e.getPhoneNumber()) && "$2a$10$hashed".equals(e.getCodeHash())));
            verify(smsService).send(eq(PHONE), argThat(msg ->
                    msg.matches("Ton code Yadony est : \\d{6}\\. Valable 10 minutes\\.")));
        }

        @Test
        @DisplayName("succès — Accept-Language: en envoie le texte anglais")
        void success_english() {
            TestMessages.requestWithAcceptLanguage("en");
            SmsOtpService service = newService();
            when(smsOtpRepository.countByPhoneSince(eq(PHONE), any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(smsService.isEnabled()).thenReturn(true);

            service.sendOtp(PHONE);

            verify(smsService).send(eq(PHONE), argThat(msg ->
                    msg.matches("Your Yadony code is: \\d{6}\\. Valid for 10 minutes\\.")));
        }

        @Test
        @DisplayName("numéro refusé par le transporteur → 422 invalid-phone-number, pas de « code envoyé » fantôme")
        void invalidRecipient_throws422() {
            // Sentry YADONY-BACK-STAGING-2 : Twilio refusait le numéro (21211), SmsService
            // l'avalait en log.error et l'utilisateur voyait « code envoyé » sans jamais rien
            // recevoir. L'exception métier fait aussi rollback de la ligne OTP.
            SmsOtpService service = newService();
            when(smsOtpRepository.countByPhoneSince(eq(PHONE), any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(smsService.isEnabled()).thenReturn(true);
            doThrow(new InvalidSmsRecipientException(21211)).when(smsService).send(eq(PHONE), anyString());

            assertThatThrownBy(() -> service.sendOtp(PHONE))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        assertThat(((YadonyBusinessException) e).getStatus())
                                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(((YadonyBusinessException) e).getErrorCode())
                                .isEqualTo("invalid-phone-number");
                    });
        }

        @Test
        @DisplayName("le 5e renvoi passe encore — le bouton de l'app se rouvre toutes les 60 s")
        void fourPreviousSendsStillAllowANewOne() {
            // Régression : le budget était de 3, alors que l'écran de saisie du code
            // (partagé avec le canal email) rouvre « Renvoyer » chaque minute.
            SmsOtpService service = newService();
            when(smsOtpRepository.countByPhoneSince(eq(PHONE), any())).thenReturn(4L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(smsService.isEnabled()).thenReturn(true);

            assertThat(service.sendOtp(PHONE)).isNotNull();
            verify(smsService).send(eq(PHONE), anyString());
        }

        @Test
        @DisplayName("429 — 5 envois ou plus dans la fenêtre de 5 min")
        void rateLimitExceeded() {
            SmsOtpService service = newService();
            when(smsOtpRepository.countByPhoneSince(eq(PHONE), any())).thenReturn(5L);

            assertThatThrownBy(() -> service.sendOtp(PHONE))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            verify(smsOtpRepository, never()).save(any());
            verify(smsService, never()).send(any(), any());
        }

        @Test
        @DisplayName("staging n'est pas un poste de dev : SMS coupés → 503, jamais de code relayé en log")
        void stagingIsNotDev() {
            // L'ancienne condition « pas prod » classait staging comme dev : le code OTP à six
            // chiffres partait en clair dans Loki et Sentry à côté du numéro de vrais testeurs.
            Environment stagingEnvironment = mock(Environment.class);
            when(stagingEnvironment.getActiveProfiles()).thenReturn(new String[] {"staging"});
            SmsOtpService service = new SmsOtpService(smsOtpRepository, passwordEncoder, smsService,
                    properties, firebaseAuth, userRepository, firebaseContact, auditService, stagingEnvironment, messagesResolver,
                    twilioVerify);
            when(smsService.isEnabled()).thenReturn(false);

            assertThatThrownBy(() -> service.sendOtp(PHONE))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("sms-otp-disabled"));
            verify(smsOtpRepository, never()).save(any());
        }

        @Test
        @DisplayName("503 — SMS désactivé en prod (flag pas encore activé alors que l'écran reste accessible)")
        void smsDisabledInProd() {
            Environment prodEnvironment = mock(Environment.class);
            when(prodEnvironment.getActiveProfiles()).thenReturn(new String[] {"prod"});
            SmsOtpService service = new SmsOtpService(smsOtpRepository, passwordEncoder, smsService,
                    properties, firebaseAuth, userRepository, firebaseContact, auditService, prodEnvironment,
                    messagesResolver, twilioVerify);
            when(smsService.isEnabled()).thenReturn(false);

            assertThatThrownBy(() -> service.sendOtp(PHONE))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

            verify(smsOtpRepository, never()).countByPhoneSince(any(), any());
            verify(smsOtpRepository, never()).save(any());
            verify(smsService, never()).send(any(), any());
        }

        @Test
        @DisplayName("succès — SMS désactivé en dev/test (repli log, pas de blocage)")
        void smsDisabledInDevStillSucceeds() {
            SmsOtpService service = newService();
            when(smsOtpRepository.countByPhoneSince(eq(PHONE), any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(smsService.isEnabled()).thenReturn(false);

            var result = service.sendOtp(PHONE);

            assertThat(result).isNotNull();
            verify(smsService).send(eq(PHONE), anyString());
        }
    }

    @Nested
    @DisplayName("verifyOtp")
    class VerifyOtp {

        private SmsOtpEntity validToken() {
            SmsOtpEntity t = new SmsOtpEntity();
            t.setPhoneNumber(PHONE);
            t.setCodeHash("$2a$10$hash");
            t.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
            t.setAttempts(0);
            return t;
        }

        private void givenValidOtp() {
            when(smsOtpRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(PHONE))
                    .thenReturn(Optional.of(validToken()));
            when(passwordEncoder.matches("123456", "$2a$10$hash")).thenReturn(true);
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("succès — numéro déjà rattaché à un UID Firebase existant (SDK natif ou passage précédent)")
        void success_existingFirebaseUser_reusesUid() throws Exception {
            SmsOtpService service = newService();
            givenValidOtp();
            UserRecord existing = mock(UserRecord.class);
            when(existing.getUid()).thenReturn("native-phone-uid");
            when(firebaseAuth.getUserByPhoneNumber(PHONE)).thenReturn(existing);
            when(firebaseAuth.createCustomToken("native-phone-uid", Map.of("otp_channel", "sms")))
                    .thenReturn("firebase-custom-token");

            String result = service.verifyOtp(PHONE, "123456");

            assertThat(result).isEqualTo("firebase-custom-token");
            verify(firebaseAuth, never()).createUser(any());
        }

        @Test
        @DisplayName("succès — numéro tout neuf, crée un UID Firebase avant de minter le token")
        void success_newNumber_createsFirebaseUser() throws Exception {
            SmsOtpService service = newService();
            givenValidOtp();
            FirebaseAuthException notFound = mock(FirebaseAuthException.class);
            when(notFound.getAuthErrorCode()).thenReturn(AuthErrorCode.USER_NOT_FOUND);
            when(firebaseAuth.getUserByPhoneNumber(PHONE)).thenThrow(notFound);
            UserRecord created = mock(UserRecord.class);
            when(created.getUid()).thenReturn("new-uid");
            when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(created);
            when(firebaseAuth.createCustomToken("new-uid", Map.of("otp_channel", "sms")))
                    .thenReturn("firebase-token-new-user");

            String result = service.verifyOtp(PHONE, "123456");

            assertThat(result).isEqualTo("firebase-token-new-user");
        }

        @Test
        @DisplayName("succès — course concurrente : createUser échoue (déjà créé entre-temps), relit l'UID gagnant")
        void success_raceOnCreate_reReadsWinningUid() throws Exception {
            SmsOtpService service = newService();
            givenValidOtp();
            FirebaseAuthException notFound = mock(FirebaseAuthException.class);
            when(notFound.getAuthErrorCode()).thenReturn(AuthErrorCode.USER_NOT_FOUND);
            FirebaseAuthException alreadyExists = mock(FirebaseAuthException.class);
            when(alreadyExists.getAuthErrorCode()).thenReturn(AuthErrorCode.PHONE_NUMBER_ALREADY_EXISTS);
            UserRecord winning = mock(UserRecord.class);
            when(winning.getUid()).thenReturn("winning-uid");

            when(firebaseAuth.getUserByPhoneNumber(PHONE))
                    .thenThrow(notFound)
                    .thenReturn(winning);
            when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenThrow(alreadyExists);
            when(firebaseAuth.createCustomToken("winning-uid", Map.of("otp_channel", "sms")))
                    .thenReturn("firebase-token-race");

            String result = service.verifyOtp(PHONE, "123456");

            assertThat(result).isEqualTo("firebase-token-race");
            verify(firebaseAuth, times(2)).getUserByPhoneNumber(PHONE);
        }

        @Test
        @DisplayName("500 — getUserByPhoneNumber échoue avec une erreur autre que USER_NOT_FOUND")
        void unexpectedErrorOnLookup_throws500() throws Exception {
            SmsOtpService service = newService();
            givenValidOtp();
            FirebaseAuthException unexpected = mock(FirebaseAuthException.class);
            when(unexpected.getAuthErrorCode()).thenReturn(AuthErrorCode.CERTIFICATE_FETCH_FAILED);
            when(firebaseAuth.getUserByPhoneNumber(PHONE)).thenThrow(unexpected);

            assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("500 — createUser échoue avec une erreur autre que PHONE_NUMBER_ALREADY_EXISTS")
        void unexpectedErrorOnCreate_throws500() throws Exception {
            SmsOtpService service = newService();
            givenValidOtp();
            FirebaseAuthException notFound = mock(FirebaseAuthException.class);
            when(notFound.getAuthErrorCode()).thenReturn(AuthErrorCode.USER_NOT_FOUND);
            when(firebaseAuth.getUserByPhoneNumber(PHONE)).thenThrow(notFound);
            FirebaseAuthException unexpected = mock(FirebaseAuthException.class);
            when(unexpected.getAuthErrorCode()).thenReturn(AuthErrorCode.UID_ALREADY_EXISTS);
            when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenThrow(unexpected);

            assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("400 — aucun token non utilisé")
        void noTokenFound() {
            SmsOtpService service = newService();
            when(smsOtpRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(PHONE))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("429 — le budget d'essais survit au renvoi d'un nouveau code")
        void attemptsBudgetSurvivesResend() {
            SmsOtpService service = newService();
            when(smsOtpRepository.sumAttemptsByPhoneSince(eq(PHONE), any())).thenReturn(5L);

            assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            verify(smsOtpRepository, never())
                    .findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(any());
        }

        @Test
        @DisplayName("429 — trop de tentatives échouées")
        void tooManyAttempts() {
            SmsOtpService service = newService();
            SmsOtpEntity token = validToken();
            token.setAttempts(5);
            when(smsOtpRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(PHONE))
                    .thenReturn(Optional.of(token));

            assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("400 — token expiré")
        void tokenExpired() {
            SmsOtpService service = newService();
            SmsOtpEntity token = validToken();
            token.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
            when(smsOtpRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(PHONE))
                    .thenReturn(Optional.of(token));

            assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("400 — code BCrypt invalide, incrémente attempts")
        void invalidCode() {
            SmsOtpService service = newService();
            SmsOtpEntity token = validToken();
            when(smsOtpRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(PHONE))
                    .thenReturn(Optional.of(token));
            when(passwordEncoder.matches("000000", "$2a$10$hash")).thenReturn(false);
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThatThrownBy(() -> service.verifyOtp(PHONE, "000000"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            assertThat(token.getAttempts()).isEqualTo(1);
            verify(smsOtpRepository).save(argThat(e -> e.getAttempts() == 1));
        }

        @Test
        @DisplayName("succès — retourne null si firebaseAuth non disponible (mode test)")
        void firebaseAuth_null_returnsNull() {
            when(environment.getActiveProfiles()).thenReturn(new String[] {"test"});
            SmsOtpService serviceWithoutFirebase = new SmsOtpService(
                    smsOtpRepository, passwordEncoder, smsService, properties, null,
                    userRepository, firebaseContact, auditService, environment, null, twilioVerify);
            givenValidOtp();

            String result = serviceWithoutFirebase.verifyOtp(PHONE, "123456");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("500 — FirebaseAuthException lors de createCustomToken")
        void firebaseAuthException_onCreateCustomToken() throws Exception {
            SmsOtpService service = newService();
            givenValidOtp();
            UserRecord existing = mock(UserRecord.class);
            when(existing.getUid()).thenReturn("uid");
            when(firebaseAuth.getUserByPhoneNumber(PHONE)).thenReturn(existing);
            doThrow(mock(FirebaseAuthException.class))
                    .when(firebaseAuth).createCustomToken("uid", Map.of("otp_channel", "sms"));

            assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("attachPhoneToAccount")
    class AttachPhone {

        private static final String UID = "uid-inscrit-par-email";

        private SmsOtpEntity validToken() {
            SmsOtpEntity t = new SmsOtpEntity();
            t.setPhoneNumber(PHONE);
            t.setCodeHash("$2a$10$hash");
            t.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
            t.setAttempts(0);
            return t;
        }

        private void givenValidOtp() {
            when(smsOtpRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(PHONE))
                    .thenReturn(Optional.of(validToken()));
            when(passwordEncoder.matches("123456", "$2a$10$hash")).thenReturn(true);
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        private UserEntity account() {
            UserEntity u = new UserEntity();
            u.setFirebaseUid(UID);
            org.springframework.test.util.ReflectionTestUtils.setField(u, "id", java.util.UUID.randomUUID());
            return u;
        }

        @Test
        @DisplayName("compte sans numéro + code valide → écrit le numéro dans Firebase")
        void attach_success() {
            SmsOtpService service = newService();
            when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(account()));
            givenValidOtp();
            when(firebaseContact.getContact(UID)).thenReturn(FirebaseContactService.Contact.EMPTY);
            when(firebaseContact.isPhoneTakenByAnother(PHONE, UID)).thenReturn(false);

            service.attachPhoneToAccount(UID, PHONE, "123456");

            verify(firebaseContact).updatePhone(UID, PHONE);
            verify(auditService).log(eq("USER"), any(), eq("USER_PHONE_ATTACHED"), any(), any());
        }

        @Test
        @DisplayName("code faux → aucune écriture : la preuve de possession est exigée")
        void attach_wrongCode_writesNothing() {
            SmsOtpService service = newService();
            when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(account()));
            when(smsOtpRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(PHONE))
                    .thenReturn(Optional.of(validToken()));
            when(passwordEncoder.matches("000000", "$2a$10$hash")).thenReturn(false);
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThatThrownBy(() -> service.attachPhoneToAccount(UID, PHONE, "000000"))
                    .isInstanceOf(YadonyBusinessException.class);

            verify(firebaseContact, never()).updatePhone(anyString(), anyString());
        }

        @Test
        @DisplayName("compte portant déjà un numéro → 409, pas de remplacement")
        void attach_phoneAlreadySet_conflict() {
            SmsOtpService service = newService();
            when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(account()));
            givenValidOtp();
            when(firebaseContact.getContact(UID)).thenReturn(
                    new FirebaseContactService.Contact("+221700000000", null));

            assertThatThrownBy(() -> service.attachPhoneToAccount(UID, PHONE, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("phone-already-set");
                    });
            verify(firebaseContact, never()).updatePhone(anyString(), anyString());
        }

        @Test
        @DisplayName("numéro déjà rattaché à un autre compte → 409")
        void attach_phoneTakenByAnother_conflict() {
            SmsOtpService service = newService();
            when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(account()));
            givenValidOtp();
            when(firebaseContact.getContact(UID)).thenReturn(FirebaseContactService.Contact.EMPTY);
            when(firebaseContact.isPhoneTakenByAnother(PHONE, UID)).thenReturn(true);

            assertThatThrownBy(() -> service.attachPhoneToAccount(UID, PHONE, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("phone-already-exists"));
            verify(firebaseContact, never()).updatePhone(anyString(), anyString());
        }

        @Test
        @DisplayName("compte inconnu → 404")
        void attach_unknownAccount_404() {
            SmsOtpService service = newService();
            when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.attachPhoneToAccount(UID, PHONE, "123456"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("longueur du SMS OTP")
    class SmsLength {

        /**
         * Alphabet GSM 03.38 de base (simplifié aux caractères latins usuels) : tant que
         * le texte n'en sort pas, un SMS reste encodé en 7 bits et tient dans 160
         * caractères (contre 70 en UCS-2 dès qu'un seul caractère en sort, un accent par
         * exemple). Les deux textes ne doivent pas changer de classe d'encodage par
         * rapport à l'ancien texte français (déjà sans accent).
         */
        private static final String GSM7_BASIC =
                "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ ÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡"
                        + "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñü à";

        private boolean isGsm7(String text) {
            return text.chars().allMatch(c -> GSM7_BASIC.indexOf(c) >= 0);
        }

        @Test
        @DisplayName("français — texte inchangé, GSM-7, un seul segment (<=160)")
        void french_staysGsm7SingleSegment() {
            String text = TestMessages.fr().get("sms.otp", "123456");

            assertThat(text).isEqualTo("Ton code Yadony est : 123456. Valable 10 minutes.");
            assertThat(isGsm7(text)).as("le texte français doit rester encodable en GSM-7").isTrue();
            assertThat(text.length()).as("longueur du SMS français").isLessThanOrEqualTo(160);
        }

        @Test
        @DisplayName("anglais — GSM-7, un seul segment (<=160), même classe que le français")
        void english_sameLengthClassAsFrench() {
            String fr = TestMessages.fr().get("sms.otp", "123456");
            String en = TestMessages.en().get("sms.otp", "123456");

            assertThat(en).isEqualTo("Your Yadony code is: 123456. Valid for 10 minutes.");
            assertThat(isGsm7(en)).as("le texte anglais doit rester encodable en GSM-7").isTrue();
            assertThat(en.length()).as("longueur du SMS anglais").isLessThanOrEqualTo(160);
            // Même classe d'encodage (GSM-7) et même classe de segmentation (1 segment
            // <=160 caractères GSM-7) que le français : ni l'un ni l'autre ne bascule
            // en UCS-2 (limite 70) ni en SMS multipart (limite 153/segment).
            assertThat(isGsm7(fr)).isEqualTo(isGsm7(en));
            assertThat(fr.length() <= 160).isEqualTo(en.length() <= 160);
        }
    }

    /**
     * Numéros +1 : les opérateurs américains bloquent les SMS de notre numéro local non
     * enregistré A2P 10DLC (un testeur de Houston ne recevait jamais son code, 27/09). Twilio
     * Verify génère, envoie et contrôle le code ; yadony garde la ligne pour ses budgets.
     */
    @Nested
    @DisplayName("Twilio Verify (+1)")
    class ViaTwilioVerify {

        private static final String US_PHONE = "+17135550123";

        private SmsOtpEntity usToken() {
            SmsOtpEntity t = new SmsOtpEntity();
            t.setPhoneNumber(US_PHONE);
            t.setCodeHash("$2a$10$random");
            t.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
            t.setAttempts(0);
            return t;
        }

        @Test
        @DisplayName("envoi — passe par Verify dans la langue de la requête, jamais par un SMS classique")
        void send_usesVerify() {
            TestMessages.requestWithAcceptLanguage("en");
            SmsOtpService service = newService();
            when(smsService.isEnabled()).thenReturn(true);
            when(twilioVerify.handles(US_PHONE)).thenReturn(true);
            when(smsOtpRepository.countByPhoneSince(eq(US_PHONE), any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$random");
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThat(service.sendOtp(US_PHONE)).isNotNull();

            verify(twilioVerify).start(eq(US_PHONE), anyString(), any());
            verify(smsService, never()).send(any(), any());
            // La ligne est gardée : elle porte le budget anti-spam et le budget de tentatives.
            verify(smsOtpRepository).save(argThat(e -> US_PHONE.equals(e.getPhoneNumber())));
        }

        @Test
        @DisplayName("envoi — la ligne ne porte pas un code à 6 chiffres devinable")
        void send_storesNoGuessableCode() {
            SmsOtpService service = newService();
            when(smsService.isEnabled()).thenReturn(true);
            when(twilioVerify.handles(US_PHONE)).thenReturn(true);
            when(smsOtpRepository.countByPhoneSince(eq(US_PHONE), any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$random");
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            service.sendOtp(US_PHONE);

            verify(passwordEncoder).encode(argThat(raw -> !raw.toString().matches("\\d{6}")));
        }

        @Test
        @DisplayName("envoi — numéro refusé par Verify → 422 invalid-phone-number")
        void send_invalidRecipient() {
            SmsOtpService service = newService();
            when(smsService.isEnabled()).thenReturn(true);
            when(twilioVerify.handles(US_PHONE)).thenReturn(true);
            when(smsOtpRepository.countByPhoneSince(eq(US_PHONE), any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$random");
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            doThrow(new InvalidSmsRecipientException(60200)).when(twilioVerify).start(eq(US_PHONE), anyString(), any());

            assertThatThrownBy(() -> service.sendOtp(US_PHONE))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                    .isEqualTo("invalid-phone-number");
        }

        @Test
        @DisplayName("envoi — Verify en panne → 503 sms-otp-unavailable, pas de « code envoyé » fantôme")
        void send_verifyDown() {
            SmsOtpService service = newService();
            when(smsService.isEnabled()).thenReturn(true);
            when(twilioVerify.handles(US_PHONE)).thenReturn(true);
            when(smsOtpRepository.countByPhoneSince(eq(US_PHONE), any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$random");
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            doThrow(new VerifyUnavailableException()).when(twilioVerify).start(eq(US_PHONE), anyString(), any());

            assertThatThrownBy(() -> service.sendOtp(US_PHONE))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        assertThat(((YadonyBusinessException) e).getStatus())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(((YadonyBusinessException) e).getErrorCode())
                                .isEqualTo("sms-otp-unavailable");
                    });
        }

        @Test
        @DisplayName("SMS coupés (dev) → reste en local même pour un +1, pour que le code soit relayé")
        void smsDisabled_staysLocal() {
            SmsOtpService service = newService();
            when(smsService.isEnabled()).thenReturn(false);
            when(smsOtpRepository.countByPhoneSince(eq(US_PHONE), any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            service.sendOtp(US_PHONE);

            verify(twilioVerify, never()).start(any(), any(), any());
            verify(smsService).send(eq(US_PHONE), anyString());
        }

        @Test
        @DisplayName("vérification — code approuvé par Twilio → consommé, sans BCrypt")
        void check_approved() {
            SmsOtpService service = newService();
            SmsOtpEntity token = usToken();
            when(smsService.isEnabled()).thenReturn(true);
            when(twilioVerify.handles(US_PHONE)).thenReturn(true);
            when(smsOtpRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(US_PHONE))
                    .thenReturn(Optional.of(token));
            when(twilioVerify.check(US_PHONE, "482913")).thenReturn(true);
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            service.consumeOtp(US_PHONE, "482913");

            assertThat(token.getUsedAt()).isNotNull();
            verify(passwordEncoder, never()).matches(any(), any());
        }

        @Test
        @DisplayName("vérification — code refusé par Twilio → 400 et une tentative décomptée")
        void check_rejected_countsAttempt() {
            SmsOtpService service = newService();
            SmsOtpEntity token = usToken();
            when(smsService.isEnabled()).thenReturn(true);
            when(twilioVerify.handles(US_PHONE)).thenReturn(true);
            when(smsOtpRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(US_PHONE))
                    .thenReturn(Optional.of(token));
            when(twilioVerify.check(US_PHONE, "000000")).thenReturn(false);
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThatThrownBy(() -> service.consumeOtp(US_PHONE, "000000"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                    .isEqualTo("phone-otp-invalid");
            assertThat(token.getAttempts()).isEqualTo(1);
            assertThat(token.getUsedAt()).isNull();
        }

        @Test
        @DisplayName("vérification — Verify en panne → 503, la tentative n'est pas décomptée")
        void check_verifyDown() {
            SmsOtpService service = newService();
            SmsOtpEntity token = usToken();
            when(smsService.isEnabled()).thenReturn(true);
            when(twilioVerify.handles(US_PHONE)).thenReturn(true);
            when(smsOtpRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(US_PHONE))
                    .thenReturn(Optional.of(token));
            when(twilioVerify.check(US_PHONE, "482913")).thenThrow(new VerifyUnavailableException());

            assertThatThrownBy(() -> service.consumeOtp(US_PHONE, "482913"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(token.getAttempts()).isZero();
        }
    }

    /**
     * API SMS Retriever : Android ne remet le SMS à l'app que s'il contient son empreinte.
     * Le code se remplit alors sans que l'utilisateur ouvre Messages, où iOS et Android
     * rangent les codes d'expéditeurs inconnus hors de la liste principale.
     */
    @Nested
    @DisplayName("empreinte Android (SMS Retriever)")
    class AndroidAppHash {

        private static final String DEBUG_HASH = "QR5XSgGkFEN";
        private static final String PLAY_HASH = "Ab+/Cd12Ef3";

        private void givenSendable() {
            when(smsOtpRepository.countByPhoneSince(eq(PHONE), any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(smsService.isEnabled()).thenReturn(true);
        }

        @Test
        @DisplayName("les empreintes configurées suivent le texte, sur une ligne à part")
        void appendsConfiguredHashes() {
            properties.setAndroidAppHashes(java.util.List.of(PLAY_HASH, DEBUG_HASH));
            SmsOtpService service = newService();
            givenSendable();

            service.sendOtp(PHONE);

            verify(smsService).send(eq(PHONE), argThat(msg -> msg.matches(
                    "Ton code Yadony est : \\d{6}\\. Valable 10 minutes\\.\n"
                            + java.util.regex.Pattern.quote(PLAY_HASH + " " + DEBUG_HASH))));
        }

        @Test
        @DisplayName("sans empreinte configurée, le SMS reste inchangé")
        void noHash_unchangedText() {
            SmsOtpService service = newService();
            givenSendable();

            service.sendOtp(PHONE);

            verify(smsService).send(eq(PHONE), argThat(msg ->
                    msg.matches("Ton code Yadony est : \\d{6}\\. Valable 10 minutes\\.")));
        }

        @Test
        @DisplayName("une valeur mal copiée est écartée, les bonnes restent")
        void invalidHashesAreDropped() {
            properties.setAndroidAppHashes(java.util.List.of(" " + DEBUG_HASH + " ", "trop-court", "", DEBUG_HASH));

            assertThat(properties.getAndroidAppHashes()).containsExactly(DEBUG_HASH);
            assertThat(properties.getRejectedAndroidAppHashes()).containsExactly("trop-court");
        }

        @Test
        @DisplayName("SMS Retriever exige au plus 140 octets : tenu avec deux empreintes, dans les deux langues")
        void fitsRetrieverLimitWithTwoHashes() {
            java.util.List<String> hashes = java.util.List.of(PLAY_HASH, DEBUG_HASH);
            for (String text : java.util.List.of(
                    TestMessages.fr().get("sms.otp", "123456"),
                    TestMessages.en().get("sms.otp", "123456"))) {
                String sms = SmsOtpService.withAppHashes(text, hashes);
                assertThat(sms.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                        .as(sms).isLessThanOrEqualTo(140);
            }
        }

        @Test
        @DisplayName("Twilio Verify (+1) reçoit la première empreinte en AppHash")
        void verifyRouteGetsFirstHash() {
            properties.setAndroidAppHashes(java.util.List.of(PLAY_HASH, DEBUG_HASH));
            SmsOtpService service = newService();
            String usPhone = "+17135550123";
            when(smsService.isEnabled()).thenReturn(true);
            when(twilioVerify.handles(usPhone)).thenReturn(true);
            when(smsOtpRepository.countByPhoneSince(eq(usPhone), any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$random");
            when(smsOtpRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            service.sendOtp(usPhone);

            verify(twilioVerify).start(eq(usPhone), anyString(), eq(PLAY_HASH));
        }
    }
}
