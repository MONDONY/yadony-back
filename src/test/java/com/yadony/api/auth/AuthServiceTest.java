package com.yadony.api.auth;

import com.yadony.api.admin.account.AdminAuthService;
import com.yadony.api.auth.dto.DeletionEligibilityResponse;
import com.yadony.api.auth.dto.RegisterRequest;
import com.yadony.api.auth.dto.UpdateProfileRequest;
import com.yadony.api.auth.dto.UserResponse;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.payments.PaymentRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — tests unitaires")
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private UserService userService;
    @Mock private PaymentRepository paymentRepository;
    @Mock private AccountFinalizationService accountFinalizationService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ConnectedDevicesService connectedDevicesService;
    @Mock private StorageService storageService;
    @Mock private AdminAuthService adminAuthService;
    @Mock private FirebaseContactService firebaseContact;
    @Mock private UsernameGenerator usernameGenerator;

    @InjectMocks private AuthService authService;

    /**
     * Téléphone et email ne sont plus en base : toute lecture passe par Firebase.
     * Ces stubs par défaut donnent le contact « nominal », les tests qui portent sur
     * une autre adresse le réécrivent.
     */
    @BeforeEach
    void stubFirebaseContact() {
        lenient().when(firebaseContact.getContact(anyString()))
                .thenReturn(new FirebaseContactService.Contact(PHONE, null));
        lenient().when(firebaseContact.findUidByEmail(anyString())).thenReturn(Optional.empty());
        lenient().when(firebaseContact.findUidByPhone(anyString())).thenReturn(Optional.empty());
        lenient().when(usernameGenerator.generate()).thenReturn(GENERATED_USERNAME);
    }

    private static final String GENERATED_USERNAME = "user1785153600";

    private static final String FIREBASE_UID = "uid-test-123";
    private static final String PHONE = "+33612345678";

    // ─── Helper ────────────────────────────────────────────────────────────────

    private UserEntity buildUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(FIREBASE_UID);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.getRoles().add(Role.SENDER);
        setId(u, UUID.randomUUID());
        return u;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Session Firebase encore anonyme : aucun parcours d'inscription légitime n'en présente. */
    private com.google.firebase.auth.FirebaseToken anonymousToken() {
        com.google.firebase.auth.FirebaseToken token = mock(com.google.firebase.auth.FirebaseToken.class);
        lenient().when(token.getClaims()).thenReturn(java.util.Map.of(
                "firebase", java.util.Map.of("sign_in_provider", "anonymous")));
        return token;
    }

    private com.google.firebase.auth.FirebaseToken mockPhoneToken() {
        com.google.firebase.auth.FirebaseToken token = mock(com.google.firebase.auth.FirebaseToken.class);
        lenient().when(token.getClaims()).thenReturn(java.util.Map.of(
                "firebase", java.util.Map.of("sign_in_provider", "phone"),
                "phone_number", PHONE));
        return token;
    }

    // ─── register ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("register()")
    class RegisterTests {

        @Test
        @DisplayName("utilisateur déjà inscrit → retourne le profil existant")
        void register_existingUser_returnsExisting() {
            UserEntity existing = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(existing));

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER"));
            UserResponse result = authService.register(FIREBASE_UID, mockPhoneToken(), req);

            assertThat(result.phoneNumber()).isEqualTo(PHONE);
            verify(userRepository, never()).save(any());
        }

        /**
         * Ronde 1 de la Task 5, constat 2. {@code linkWithCredential} conserve l'UID :
         * quand un visiteur s'inscrit, {@code register} retombe sur SA ligne, déjà active
         * et créée sans aucun rôle par {@code GuestUserProvisioner}. La renvoyer telle
         * quelle laissait le compte à autorités vides — aucun endpoint à rôle accessible,
         * et la purge de la Task 7 l'aurait visé comme une ligne invitée abandonnée.
         */
        @Test
        @DisplayName("ligne invitée sans rôle + token promu → promue en SENDER+TRAVELER")
        void register_guestRow_promotesToSenderAndTraveler() {
            UserEntity guestRow = new UserEntity();
            guestRow.setFirebaseUid(FIREBASE_UID);
            guestRow.setStatus(UserStatus.ACTIVE);
            guestRow.setKycStatus(KycStatus.NOT_STARTED);
            setId(guestRow, UUID.randomUUID());
            assertThat(guestRow.getRoles()).isEmpty();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(guestRow));
            when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER"));
            authService.register(FIREBASE_UID, mockPhoneToken(), req);

            assertThat(guestRow.getRoles()).containsExactlyInAnyOrder(Role.SENDER, Role.TRAVELER);
            verify(userRepository).save(guestRow);
            // L'inscription d'un invité EST une inscription : le code de parrainage et les
            // métriques dépendent de cet event, qui n'était jamais publié pour lui.
            verify(eventPublisher).publishEvent(any(com.yadony.api.auth.events.UserRegisteredEvent.class));
        }

        /**
         * Le garde-fou du correctif ci-dessus. {@code /auth/**} est en {@code permitAll} :
         * un invité peut atteindre {@code /auth/register}. Accorder des rôles sur la foi d'un
         * jeton ENCORE anonyme donnerait un compte complet à une simple session visiteur.
         *
         * <p>Ronde 2 : le refus est désormais posé en tête de {@code register}, il vaut donc
         * pour les trois branches et pas seulement pour celle-ci.
         */
        @Test
        @DisplayName("ligne invitée sans rôle + token encore anonyme → refus, jamais promue")
        void register_guestRow_anonymousToken_neverPromotes() {
            UserEntity guestRow = new UserEntity();
            guestRow.setFirebaseUid(FIREBASE_UID);
            guestRow.setStatus(UserStatus.ACTIVE);
            guestRow.setKycStatus(KycStatus.NOT_STARTED);
            setId(guestRow, UUID.randomUUID());
            lenient().when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(guestRow));

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER"));
            assertThatThrownBy(() -> authService.register(FIREBASE_UID, anonymousToken(), req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("invalid-provider"));

            assertThat(guestRow.getRoles()).isEmpty();
            verify(userRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        /**
         * Ronde 2. La branche RÉACTIVATION accordait {@code SENDER+TRAVELER} sans le moindre
         * contrôle de provider : le {@code switch} de {@code createUser}, qui rejette
         * {@code anonymous}, n'est jamais atteint depuis cette branche.
         *
         * <p>Ce n'était pas exploitable avant la Task 5, faute de lignes invitées
         * soft-deletées. Ça l'est depuis que {@code GuestClaimService} en produit à chaque
         * réclamation réussie (et la purge de la Task 7 en produira aussi) : visiteur pose un
         * favori, réclame ses données (ligne soft-deletée), puis appelle
         * {@code /auth/register} avec le même jeton encore anonyme et obtient un compte
         * complet sur un UID anonyme, sans téléphone ni email.
         */
        @Test
        @DisplayName("branche réactivation + token encore anonyme → refus, ligne jamais réactivée")
        void register_reactivation_anonymousToken_isRejected() {
            UserEntity softDeleted = buildUser();
            lenient().when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            lenient().when(userRepository.findByFirebaseUidIncludingDeleted(FIREBASE_UID))
                    .thenReturn(Optional.of(softDeleted));

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER"));
            assertThatThrownBy(() -> authService.register(FIREBASE_UID, anonymousToken(), req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("invalid-provider"));

            // La ligne reste supprimée : reactivateByFirebaseUid est le seul mécanisme qui
            // efface deleted_at, et il n'est jamais invoqué.
            verify(userRepository, never()).reactivateByFirebaseUid(any(), any());
            verify(userRepository, never()).save(any());
        }

        /**
         * Non-régression du refus ci-dessus : une réactivation légitime, avec un jeton
         * téléphone, doit continuer de fonctionner exactement comme avant.
         */
        @Test
        @DisplayName("branche réactivation + token téléphone → réactivation inchangée")
        void register_reactivation_nonAnonymousToken_stillWorks() {
            UserEntity softDeleted = buildUser();
            when(userRepository.findByFirebaseUidIncludingDeleted(FIREBASE_UID))
                    .thenReturn(Optional.of(softDeleted));

            UserEntity reactivated = new UserEntity();
            reactivated.setFirebaseUid(FIREBASE_UID);
            reactivated.setStatus(UserStatus.ACTIVE);
            setId(reactivated, UUID.randomUUID());
            when(userRepository.findByFirebaseUid(FIREBASE_UID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(reactivated));
            when(userRepository.save(any())).thenReturn(reactivated);

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER"));
            authService.register(FIREBASE_UID, mockPhoneToken(), req);

            verify(userRepository).reactivateByFirebaseUid(FIREBASE_UID, UserStatus.ACTIVE.name());
            assertThat(reactivated.getRoles()).containsExactlyInAnyOrder(Role.SENDER, Role.TRAVELER);
            verify(auditService).log(eq("USER"), any(), eq("USER_REACTIVATED"), any(), any());
        }

        @Test
        @DisplayName("nouvel utilisateur valide → crée l'utilisateur en base")
        void register_newUser_createsUser() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("TRAVELER"));
            UserResponse result = authService.register(FIREBASE_UID, mockPhoneToken(), req);

            assertThat(result.phoneNumber()).isEqualTo(PHONE);
            assertThat(result.kycStatus()).isEqualTo("NOT_STARTED");
            assertThat(result.status()).isEqualTo("ACTIVE");
            verify(userRepository).save(any(UserEntity.class));
            verify(auditService).log(eq("USER"), any(), eq("USER_CREATED"), any(), any());
        }

        @Test
        @DisplayName("rôle ADMIN dans la requête → ignoré, compte créé avec SENDER+TRAVELER")
        void register_adminRole_ignored_createsSenderAndTraveler() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("ADMIN"));
            authService.register(FIREBASE_UID, mockPhoneToken(), req);

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRoles()).containsExactlyInAnyOrder(Role.SENDER, Role.TRAVELER);
        }

        @Test
        @DisplayName("inscription par téléphone → aucune coordonnée écrite en base, unicité déléguée à Firebase")
        void register_phone_storesNoContactLocally() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER"));
            UserResponse result = authService.register(FIREBASE_UID, mockPhoneToken(), req);

            // Le numéro rendu vient de Firebase, pas d'une colonne Yadony
            assertThat(result.phoneNumber()).isEqualTo(PHONE);
            verify(firebaseContact).getContact(FIREBASE_UID);
            // Aucune écriture de coordonnée : le compte Firebase porte déjà le numéro
            verify(firebaseContact, never()).updatePhone(anyString(), anyString());
        }

        @ParameterizedTest
        @ValueSource(strings = {"INVALID", "SUPERUSER", "ROOT"})
        @DisplayName("rôles non reconnus dans la requête → ignorés, compte créé avec SENDER+TRAVELER")
        void register_unknownRoles_ignored_createsSenderAndTraveler(String role) {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of(role));
            authService.register(FIREBASE_UID, mockPhoneToken(), req);

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRoles()).containsExactlyInAnyOrder(Role.SENDER, Role.TRAVELER);
        }

        @Test
        @DisplayName("SENDER+TRAVELER dans la requête → peu importe, le compte reçoit toujours les deux rôles")
        void register_dualRoles_alwaysAssignsBoth() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER", "TRAVELER"));
            authService.register(FIREBASE_UID, mockPhoneToken(), req);

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRoles())
                    .containsExactlyInAnyOrder(Role.SENDER, Role.TRAVELER);
        }
    }

    // ─── getProfile ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getProfile()")
    class GetProfileTests {

        @Test
        @DisplayName("utilisateur existant → retourne le profil complet")
        void getProfile_existingUser_returnsUserResponse() {
            UserEntity user = buildUser();
            when(firebaseContact.getContact(FIREBASE_UID))
                    .thenReturn(new FirebaseContactService.Contact(PHONE, "test@yadony.app"));
            user.setFirstName("Amadou");
            user.setLastName("Diallo");
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            UserResponse result = authService.getProfile(FIREBASE_UID);

            assertThat(result.phoneNumber()).isEqualTo(PHONE);
            assertThat(result.email()).isEqualTo("test@yadony.app");
            assertThat(result.firstName()).isEqualTo("Amadou");
            assertThat(result.lastName()).isEqualTo("Diallo");
        }

        @Test
        @DisplayName("utilisateur inconnu → 404 NOT_FOUND")
        void getProfile_unknownUser_throwsNotFound() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.getProfile(FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.getErrorCode()).isEqualTo("user-not-found");
                    });
        }
    }

    // ─── updateProfile ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateProfile()")
    class UpdateProfileTests {

        @Test
        @DisplayName("mise à jour tous les champs → profil modifié")
        void updateProfile_allFields_updatesUser() {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any(UserEntity.class))).thenReturn(user);

            UpdateProfileRequest req = new UpdateProfileRequest(
                    "Amadou", "Diallo",
                    LocalDate.of(1990, 5, 15), "Paris", null, null, null, null
            );

            UserResponse result = authService.updateProfile(FIREBASE_UID, req);

            assertThat(user.getFirstName()).isEqualTo("Amadou");
            assertThat(user.getLastName()).isEqualTo("Diallo");
            // L'email n'est pas modifiable par cet endpoint : jamais d'écriture Firebase
            verify(firebaseContact, never()).updateEmail(anyString(), anyString());
            assertThat(user.getBirthDate()).isEqualTo(LocalDate.of(1990, 5, 15));
            assertThat(user.getCity()).isEqualTo("Paris");
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("email absent du contrat — un client qui l'enverrait quand même ne peut pas le changer")
        void updateProfile_emailIsNotEditable() {
            // L'email identifie le compte Firebase : le rendre modifiable depuis une
            // requête de profil permettrait de détourner l'adresse d'un compte.
            UserEntity user = buildUser();
            when(firebaseContact.getContact(FIREBASE_UID))
                    .thenReturn(new FirebaseContactService.Contact(PHONE, "titulaire@yadony.app"));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            UserResponse res = authService.updateProfile(FIREBASE_UID,
                    new UpdateProfileRequest("Amadou", null, null, null, null, null, null, null));

            // Le reste du profil passe, l'adresse reste celle du compte Firebase
            assertThat(res.firstName()).isEqualTo("Amadou");
            assertThat(res.email()).isEqualTo("titulaire@yadony.app");
            verify(firebaseContact, never()).updateEmail(anyString(), anyString());
        }

        @Test
        @DisplayName("champs vides → valeurs nulles en base")
        void updateProfile_emptyStrings_setsNullValues() {
            UserEntity user = buildUser();
            user.setFirstName("Ancien Prénom");
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            UpdateProfileRequest req = new UpdateProfileRequest("  ", null, null, "  ", null, null, null, null);
            authService.updateProfile(FIREBASE_UID, req);

            assertThat(user.getFirstName()).isNull();
            assertThat(user.getCity()).isNull();
        }

        @Test
        @DisplayName("champs null → valeurs non modifiées")
        void updateProfile_nullFields_keepsExistingValues() {
            UserEntity user = buildUser();
            user.setFirstName("Original");
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            UpdateProfileRequest req = new UpdateProfileRequest(null, null, null, null, null, null, null, null);
            authService.updateProfile(FIREBASE_UID, req);

            assertThat(user.getFirstName()).isEqualTo("Original");
        }

        @Test
        @DisplayName("ajout numéro de téléphone → sauvegardé en base")
        void updateProfile_addPhoneNumber_saved() {
            UserEntity user = buildUser(); // phone = PHONE = "+33612345678"
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            authService.updateProfile(FIREBASE_UID,
                    new UpdateProfileRequest(null, null, null, null, "+33699000001", null, null, null));

            verify(firebaseContact).updatePhone(FIREBASE_UID, "+33699000001");
        }

        @Test
        @DisplayName("numéro déjà pris → 409 CONFLICT")
        void updateProfile_phoneAlreadyTaken_throws409() {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(firebaseContact.isPhoneTakenByAnother("+33699999999", FIREBASE_UID))
                    .thenReturn(true);

            assertThatThrownBy(() -> authService.updateProfile(FIREBASE_UID,
                    new UpdateProfileRequest(null, null, null, null, "+33699999999", null, null, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("utilisateur inconnu → 404 NOT_FOUND")
        void updateProfile_unknownUser_throwsNotFound() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.updateProfile(FIREBASE_UID,
                    new UpdateProfileRequest("A", null, null, null, null, null, null, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("bio/languages/transportMode → persistés et retournés")
        void updateProfile_persistsBioLanguagesTransport() {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            var req = new UpdateProfileRequest(null, null, null, null, null,
                    "Voyageur sérieux", Set.of("FR", "WO"), "AVION");

            UserResponse res = authService.updateProfile(FIREBASE_UID, req);

            assertThat(res.bio()).isEqualTo("Voyageur sérieux");
            assertThat(res.languages()).containsExactlyInAnyOrder("FR", "WO");
            assertThat(res.transportMode()).isEqualTo("AVION");
        }
    }

    // ─── analytics consent ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAnalyticsConsent()")
    class GetAnalyticsConsentTests {

        @Test
        @DisplayName("utilisateur a répondu → granted/consentAt/version retournés")
        void getAnalyticsConsent_answered_returnsValues() {
            UserEntity user = buildUser();
            java.time.Instant at = java.time.Instant.parse("2026-06-03T04:55:08.960Z");
            user.setAnalyticsConsent(true);
            user.setAnalyticsConsentAt(at);
            user.setAnalyticsConsentVersion("1.0");
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            com.yadony.api.auth.dto.AnalyticsConsentResponse resp =
                    authService.getAnalyticsConsent(FIREBASE_UID);

            assertThat(resp.granted()).isTrue();
            assertThat(resp.consentAt()).isEqualTo("2026-06-03T04:55:08.960Z");
            assertThat(resp.policyVersion()).isEqualTo("1.0");
        }

        @Test
        @DisplayName("utilisateur n'a jamais répondu → tout null")
        void getAnalyticsConsent_neverAnswered_returnsNulls() {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            com.yadony.api.auth.dto.AnalyticsConsentResponse resp =
                    authService.getAnalyticsConsent(FIREBASE_UID);

            assertThat(resp.granted()).isNull();
            assertThat(resp.consentAt()).isNull();
            assertThat(resp.policyVersion()).isNull();
        }

        @Test
        @DisplayName("utilisateur inconnu → 404 NOT_FOUND")
        void getAnalyticsConsent_unknownUser_throwsNotFound() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.getAnalyticsConsent(FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.getErrorCode()).isEqualTo("user-not-found");
                    });
        }
    }

    @Nested
    @DisplayName("updateAnalyticsConsent()")
    class UpdateAnalyticsConsentTests {

        @Test
        @DisplayName("met à jour les colonnes + écrit une entrée audit_log avec payload non-null")
        void updateAnalyticsConsent_setsColumns_andLogsAudit() {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            authService.updateAnalyticsConsent(FIREBASE_UID, true, "1.0", "manual");

            assertThat(user.getAnalyticsConsent()).isTrue();
            assertThat(user.getAnalyticsConsentAt()).isNotNull();
            assertThat(user.getAnalyticsConsentVersion()).isEqualTo("1.0");
            assertThat(user.getAnalyticsConsentSource()).isEqualTo("manual");
            verify(userRepository).save(user);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(auditService).log(eq("USER"), eq(user.getId()),
                    eq("ANALYTICS_CONSENT_UPDATED"), eq(user.getId()), payloadCaptor.capture());
            java.util.Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).isNotNull();
            assertThat(payload.get("granted")).isEqualTo(true);
            assertThat(payload.get("policyVersion")).isEqualTo("1.0");
            assertThat(payload.get("source")).isEqualTo("manual");
        }

        @Test
        @DisplayName("policyVersion et source null → payload audit utilise des valeurs non-null")
        void updateAnalyticsConsent_nullOptionals_payloadNonNull() {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            authService.updateAnalyticsConsent(FIREBASE_UID, false, null, null);

            assertThat(user.getAnalyticsConsent()).isFalse();
            assertThat(user.getAnalyticsConsentVersion()).isNull();
            assertThat(user.getAnalyticsConsentSource()).isNull();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(auditService).log(eq("USER"), eq(user.getId()),
                    eq("ANALYTICS_CONSENT_UPDATED"), eq(user.getId()), payloadCaptor.capture());
            java.util.Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload.get("granted")).isEqualTo(false);
            assertThat(payload.get("policyVersion")).isNotNull();
            assertThat(payload.get("source")).isNotNull();
        }

        @Test
        @DisplayName("utilisateur inconnu → 404 NOT_FOUND, aucun audit")
        void updateAnalyticsConsent_unknownUser_throwsNotFound() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.updateAnalyticsConsent(FIREBASE_UID, true, "1.0", "manual"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
            verify(auditService, never()).log(any(), any(), any(), any(), any());
        }
    }

    // ─── updateAvatar ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateAvatar()")
    class UpdateAvatarTests {

        @Test
        @DisplayName("fichier valide → upload + clé S3 persistée dans avatarUrl, presigned URL retournée")
        void updateAvatar_uploadsAndPersistsKey() throws Exception {
            String key = "users/" + FIREBASE_UID + "/123_a.jpg";
            String presignedUrl = "https://r2.example.com/presigned/users/" + FIREBASE_UID + "/123_a.jpg?X-Amz-Signature=abc";
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            MultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1, 2, 3});
            when(storageService.uploadFile(eq(file), startsWith("users/" + FIREBASE_UID + "/")))
                    .thenReturn(key);
            when(storageService.avatarUrl(key)).thenReturn(presignedUrl);

            UserResponse res = authService.updateAvatar(FIREBASE_UID, file);

            // The raw key is persisted in the entity
            assertThat(user.getAvatarUrl()).isEqualTo(key);
            // The response exposes a presigned URL, not the raw key
            assertThat(res.avatarUrl()).isEqualTo(presignedUrl);
        }

        @Test
        @DisplayName("utilisateur inconnu → 404 NOT_FOUND")
        void updateAvatar_unknownUser_throwsNotFound() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            MultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1, 2, 3});

            assertThatThrownBy(() -> authService.updateAvatar(FIREBASE_UID, file))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("fichier vide → 400 BAD_REQUEST")
        void updateAvatar_emptyFile_throwsBadRequest() throws Exception {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            MultipartFile emptyFile = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[0]);

            assertThatThrownBy(() -> authService.updateAvatar(FIREBASE_UID, emptyFile))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("fichier > 10 Mo → 413 PAYLOAD_TOO_LARGE")
        void updateAvatar_fileTooLarge_throwsPayloadTooLarge() throws Exception {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            MultipartFile bigFile = new MockMultipartFile("file", "big.jpg", "image/jpeg",
                    new byte[10 * 1024 * 1024 + 1]);

            assertThatThrownBy(() -> authService.updateAvatar(FIREBASE_UID, bigFile))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        }
    }

    // ─── deleteAccount ─────────────────────────────────────────────────────────
    // Full GDPR logic tested in UserServiceTest; AuthService delegates to UserService.

    @Nested
    @DisplayName("deleteAccount()")
    class DeleteAccountTests {

        @Test
        @DisplayName("délègue à UserService.deleteAccount()")
        void deleteAccount_delegatesToUserService() {
            doNothing().when(userService).deleteAccount(FIREBASE_UID);

            authService.deleteAccount(FIREBASE_UID);

            verify(userService).deleteAccount(FIREBASE_UID);
        }
    }

    // ─── checkDeletionEligibility ───────────────────────────────────────────────
    // Full logic tested in UserServiceTest; AuthService delegates to UserService.

    @Nested
    @DisplayName("checkDeletionEligibility()")
    class CheckDeletionEligibilityTests {

        @Test
        @DisplayName("délègue à UserService.checkDeletionEligibility()")
        void checkDeletionEligibility_delegatesToUserService() {
            var expected = new DeletionEligibilityResponse(true, null, false);
            when(userService.checkDeletionEligibility(FIREBASE_UID)).thenReturn(expected);

            assertThat(authService.checkDeletionEligibility(FIREBASE_UID)).isEqualTo(expected);
        }
    }

    // ─── Réglages de confidentialité ───────────────────────────────────────────

    @Nested
    @DisplayName("privacySettings()")
    class PrivacySettingsTests {

        @Test
        @DisplayName("le masquage du numéro est enregistré et journalisé")
        void updatePrivacySettings_hidePhone_persistsAndAudits() {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            authService.updatePrivacySettings(FIREBASE_UID, true, true);

            assertThat(user.isHidePhoneNumber()).isTrue();
            verify(userRepository).save(user);
            verify(auditService).log(eq("USER"), eq(user.getId()), eq("PHONE_VISIBILITY_UPDATED"),
                    eq(user.getId()), argThat(p -> Boolean.TRUE.equals(p.get("hidePhoneNumber"))));
        }

        /**
         * Une app antérieure à ce champ n'envoie que {@code contactKycOnly} : le null
         * qui en résulte doit laisser la préférence intacte. La remettre à false
         * rendrait le numéro à nouveau visible sans que l'utilisateur l'ait demandé,
         * simplement parce qu'il n'a pas mis son app à jour.
         */
        @Test
        @DisplayName("hidePhoneNumber null laisse la préférence inchangée, sans entrée d'audit")
        void updatePrivacySettings_nullHidePhone_keepsPreference() {
            UserEntity user = buildUser();
            user.setHidePhoneNumber(true);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            authService.updatePrivacySettings(FIREBASE_UID, false, null);

            assertThat(user.isHidePhoneNumber()).isTrue();
            assertThat(user.isContactKycOnly()).isFalse();
            verify(auditService, never()).log(any(), any(), eq("PHONE_VISIBILITY_UPDATED"), any(), any());
        }

        @Test
        @DisplayName("valeur identique à l'existante : pas de doublon dans audit_log")
        void updatePrivacySettings_unchangedHidePhone_doesNotAudit() {
            UserEntity user = buildUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            authService.updatePrivacySettings(FIREBASE_UID, true, false);

            assertThat(user.isHidePhoneNumber()).isFalse();
            verify(auditService, never()).log(any(), any(), eq("PHONE_VISIBILITY_UPDATED"), any(), any());
        }

        @Test
        @DisplayName("getPrivacySettings renvoie les deux préférences")
        void getPrivacySettings_returnsBothFlags() {
            UserEntity user = buildUser();
            user.setContactKycOnly(false);
            user.setHidePhoneNumber(true);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            var resp = authService.getPrivacySettings(FIREBASE_UID);

            assertThat(resp.contactKycOnly()).isFalse();
            assertThat(resp.hidePhoneNumber()).isTrue();
        }
    }

    // ─── toResponse ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toResponse()")
    class ToResponseTests {

        @Test
        @DisplayName("entité complète → UserResponse correctement mappé")
        void toResponse_fullEntity_mapsAllFields() {
            UserEntity user = buildUser();
            when(firebaseContact.getContact(FIREBASE_UID))
                    .thenReturn(new FirebaseContactService.Contact(PHONE, "test@example.com"));
            user.setFirstName("Fatou");
            user.setLastName("Sow");
            user.setBirthDate(LocalDate.of(1985, 3, 10));
            user.setCity("Lyon");
            user.setKycStatus(KycStatus.VERIFIED);
            user.setStatus(UserStatus.ACTIVE);
            // Le défaut "FR" en dur a été retiré de UserEntity (V225, le pays est
            // désormais une donnée saisie) : cette fixture "entité complète" doit
            // le renseigner explicitement pour continuer à couvrir le mapping.
            user.setCountry("FR");

            UserResponse resp = authService.toResponse(user);

            assertThat(resp.email()).isEqualTo("test@example.com");
            assertThat(resp.firstName()).isEqualTo("Fatou");
            assertThat(resp.lastName()).isEqualTo("Sow");
            assertThat(resp.birthDate()).isEqualTo(LocalDate.of(1985, 3, 10));
            assertThat(resp.city()).isEqualTo("Lyon");
            assertThat(resp.kycStatus()).isEqualTo("VERIFIED");
            assertThat(resp.status()).isEqualTo("ACTIVE");
            assertThat(resp.roles()).contains("SENDER");
            // PRO fields — new in PR-1 review fix
            assertThat(resp.isProAccount()).isFalse();
            assertThat(resp.stripeAccountStatus()).isNotNull();
            assertThat(resp.country()).isEqualTo("FR");
        }
    }

    // ─── register — routing par provider Firebase ──────────────────────────────

    @Nested
    @DisplayName("register — routing par provider Firebase")
    class RegisterWithProvider {

        private com.google.firebase.auth.FirebaseToken mockToken(String signInProvider, String email) {
            return mockToken(signInProvider, email, null);
        }

        /** {@code phone} alimente le claim {@code phone_number}, seule source du numéro. */
        private com.google.firebase.auth.FirebaseToken mockToken(String signInProvider, String email, String phone) {
            com.google.firebase.auth.FirebaseToken token = mock(com.google.firebase.auth.FirebaseToken.class);
            java.util.Map<String, Object> claims = new java.util.HashMap<>();
            claims.put("firebase", java.util.Map.of("sign_in_provider", signInProvider));
            if (phone != null) claims.put("phone_number", phone);
            when(token.getClaims()).thenReturn(claims);
            if (email != null) when(token.getEmail()).thenReturn(email);
            return token;
        }

        @Test
        @DisplayName("provider phone — claim phone_number absent du token → 422")
        void phone_phoneNumberRequired() {
            com.google.firebase.auth.FirebaseToken token = mockToken("phone", null);
            RegisterRequest req = new RegisterRequest(null, null, Set.of("SENDER"));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.register(FIREBASE_UID, token, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("provider phone — succès")
        void phone_success() {
            com.google.firebase.auth.FirebaseToken token = mockToken("phone", null, PHONE);
            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER"));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(i -> {
                UserEntity u = i.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            UserResponse result = authService.register(FIREBASE_UID, token, req);

            assertThat(result).isNotNull();
            // Le numéro rendu est celui de Firebase, aucune colonne Yadony ne le porte
            assertThat(result.phoneNumber()).isEqualTo(PHONE);
            verify(userRepository).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("provider google.com — email depuis token Firebase")
        void google_emailFromToken() {
            com.google.firebase.auth.FirebaseToken token = mockToken("google.com", "google@gmail.com");
            RegisterRequest req = new RegisterRequest(null, null, Set.of("SENDER"));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(firebaseContact.getContact(FIREBASE_UID))
                    .thenReturn(new FirebaseContactService.Contact(null, "google@gmail.com"));
            when(userRepository.save(any())).thenAnswer(i -> {
                UserEntity u = i.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            UserResponse result = authService.register(FIREBASE_UID, token, req);

            // L'email est celui du compte Firebase, il n'est pas recopié en base
            assertThat(result.email()).isEqualTo("google@gmail.com");
            verify(userRepository).save(any(UserEntity.class));
            verify(firebaseContact, never()).updateEmail(anyString(), anyString());
        }

        @Test
        @DisplayName("provider custom (email OTP) — email depuis body")
        void custom_emailFromBody() {
            com.google.firebase.auth.FirebaseToken token = mockToken("custom", null);
            RegisterRequest req = new RegisterRequest(null, "otp@example.com", Set.of("SENDER"));
            when(userRepository.findByFirebaseUid("otp@example.com")).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(i -> {
                UserEntity u = i.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            UserResponse result = authService.register("otp@example.com", token, req);

            assertThat(result).isNotNull();
            verify(userRepository).save(any(UserEntity.class));
            // Un compte custom ne porte pas d'email côté Firebase : on l'y écrit,
            // pour que Firebase reste la seule source de vérité.
            verify(firebaseContact).updateEmail("otp@example.com", "otp@example.com");
        }

        @Test
        @DisplayName("provider google.com — email déjà rattaché à un autre compte → renvoie ce compte")
        void google_existingAccountWithSameEmail_returnsIt() {
            com.google.firebase.auth.FirebaseToken token = mockToken("google.com", "deja@gmail.com");
            RegisterRequest req = new RegisterRequest(null, null, Set.of("SENDER"));
            UserEntity existing = buildUser();
            existing.setFirebaseUid("uid-historique");
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
            when(firebaseContact.findUidByEmail("deja@gmail.com")).thenReturn(Optional.of("uid-historique"));
            when(userRepository.findByFirebaseUid("uid-historique")).thenReturn(Optional.of(existing));

            UserResponse result = authService.register(FIREBASE_UID, token, req);

            assertThat(result.id()).isEqualTo(existing.getId());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("provider inconnu → 422")
        void unknownProvider_422() {
            com.google.firebase.auth.FirebaseToken token = mockToken("password", null);
            RegisterRequest req = new RegisterRequest(null, null, Set.of("SENDER"));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.register(FIREBASE_UID, token, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("provider custom — email body ≠ UID token → 422 email-mismatch")
        void custom_emailMismatch_rejected() {
            // L'UID du custom token est "real@firebase.com" mais le body envoie un autre email
            com.google.firebase.auth.FirebaseToken token = mockToken("custom", null);
            RegisterRequest req = new RegisterRequest(null, "spoofed@evil.com", Set.of("SENDER"));
            when(userRepository.findByFirebaseUid("real@firebase.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.register("real@firebase.com", token, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("email-mismatch");
                    });
        }

        @Test
        @DisplayName("provider google.com — email null dans le token → 422 email-required")
        void google_nullEmailInToken_throws() {
            com.google.firebase.auth.FirebaseToken token = mockToken("google.com", null);
            // getEmail() non stubbé → retourne null par défaut
            RegisterRequest req = new RegisterRequest(null, null, Set.of("SENDER"));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.register(FIREBASE_UID, token, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("email-required");
                    });
        }

        /**
         * Token custom avec le developer claim {@code otp_channel=sms} posé par
         * SmsOtpService.verifyOtp() — top-level, PAS nested sous {@code firebase}
         * (contrairement à {@code sign_in_provider}), exactement comme Firebase le
         * propage depuis {@code createCustomToken(uid, claims)}.
         */
        private com.google.firebase.auth.FirebaseToken mockSmsOtpCustomToken(String uid) {
            com.google.firebase.auth.FirebaseToken token = mock(com.google.firebase.auth.FirebaseToken.class);
            java.util.Map<String, Object> claims = new java.util.HashMap<>();
            claims.put("firebase", java.util.Map.of("sign_in_provider", "custom"));
            claims.put("otp_channel", "sms");
            lenient().when(token.getClaims()).thenReturn(claims);
            return token;
        }

        @Test
        @DisplayName("provider custom (SMS OTP) — uid déjà résolu par SmsOtpService, numéro lu sur Firebase")
        void custom_smsOtp_success() {
            String uid = "native-phone-uid";
            com.google.firebase.auth.FirebaseToken token = mockSmsOtpCustomToken(uid);
            // request.email() est naturellement null pour ce flow (le client envoie phoneNumber)
            RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("SENDER"));
            when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.empty());
            // @BeforeEach stub par défaut : firebaseContact.getContact(*) → Contact(PHONE, null)
            when(userRepository.save(any())).thenAnswer(i -> {
                UserEntity u = i.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            UserResponse result = authService.register(uid, token, req);

            assertThat(result).isNotNull();
            verify(userRepository).save(any(UserEntity.class));
            // Régression : le branchement SMS ne doit jamais écrire d'email (chemin
            // réservé au custom-token email, où l'UID == l'adresse).
            verify(firebaseContact, never()).updateEmail(anyString(), anyString());
        }

        @Test
        @DisplayName("provider custom (SMS OTP) — aucun numéro sur le UserRecord Firebase → 422 phone-required")
        void custom_smsOtp_missingPhone_rejected() {
            String uid = "native-phone-uid";
            com.google.firebase.auth.FirebaseToken token = mockSmsOtpCustomToken(uid);
            RegisterRequest req = new RegisterRequest(null, null, Set.of("SENDER"));
            when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.empty());
            when(firebaseContact.getContact(uid)).thenReturn(FirebaseContactService.Contact.EMPTY);

            assertThatThrownBy(() -> authService.register(uid, token, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("phone-required");
                    });
            verify(userRepository, never()).save(any());
        }
    }

    // ─── SENDER-par-défaut ─────────────────────────────────────────────────────

    @Test
    @DisplayName("createUser assigne toujours SENDER+TRAVELER, quels que soient request.roles=[TRAVELER,SENDER]")
    void createUser_alwaysAssignsSenderAndTraveler_ignoringRequestRoles() {
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
        when(userRepository.findByFirebaseUidIncludingDeleted(FIREBASE_UID)).thenReturn(Optional.empty());

        UserEntity saved = new UserEntity();
        saved.setFirebaseUid(FIREBASE_UID);
        saved.setStatus(UserStatus.ACTIVE);
        saved.setKycStatus(KycStatus.NOT_STARTED);
        saved.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER, Role.TRAVELER)));
        setId(saved, UUID.randomUUID());
        when(userRepository.save(any())).thenReturn(saved);

        RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("TRAVELER", "SENDER"));
        UserResponse result = authService.register(FIREBASE_UID, mockPhoneToken(), req);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRoles()).containsExactlyInAnyOrder(Role.SENDER, Role.TRAVELER);
    }

    @Test
    @DisplayName("register reactivation assigne toujours SENDER+TRAVELER, quels que soient request.roles")
    void register_reactivation_assignsSenderAndTravelerRoles() {
        UserEntity deleted = buildUser();
        deleted.getRoles().add(Role.TRAVELER);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
        when(userRepository.findByFirebaseUidIncludingDeleted(FIREBASE_UID)).thenReturn(Optional.of(deleted));

        UserEntity reactivated = new UserEntity();
        reactivated.setFirebaseUid(FIREBASE_UID);
        reactivated.setStatus(UserStatus.ACTIVE);
        reactivated.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER)));
        setId(reactivated, UUID.randomUUID());
        when(userRepository.findByFirebaseUid(FIREBASE_UID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(reactivated));
        when(userRepository.save(any())).thenReturn(reactivated);

        RegisterRequest req = new RegisterRequest(PHONE, null, Set.of("TRAVELER"));
        authService.register(FIREBASE_UID, mockPhoneToken(), req);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRoles()).containsExactlyInAnyOrder(Role.SENDER, Role.TRAVELER);
    }

    @Test
    @DisplayName("createUser assigne SENDER+TRAVELER à l'inscription — le voyageur est universel")
    void createUser_assignsSenderAndTravelerRoles() {
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());
        when(userRepository.findByFirebaseUidIncludingDeleted(FIREBASE_UID)).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            setId(u, UUID.randomUUID());
            return u;
        });

        RegisterRequest registerRequest = new RegisterRequest(PHONE, null, Set.of("SENDER"));
        UserResponse resp = authService.register(FIREBASE_UID, mockPhoneToken(), registerRequest);

        assertThat(resp.roles()).containsExactlyInAnyOrder("SENDER", "TRAVELER");
    }
}
