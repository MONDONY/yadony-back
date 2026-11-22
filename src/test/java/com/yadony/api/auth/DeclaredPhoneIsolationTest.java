package com.yadony.api.auth;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserRecord;
import com.yadony.api.admin.account.AdminAuthService;
import com.yadony.api.auth.dto.RegisterRequest;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.notifications.NotificationEntity;
import com.yadony.api.notifications.NotificationRepository;
import com.yadony.api.notifications.SmsFallbackScheduler;
import com.yadony.api.notifications.SmsService;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.mobilemoney.MobileMoneyAccountService;
import com.yadony.api.payments.mobilemoney.MobileMoneyBidPaymentService;
import com.yadony.api.payments.mobilemoney.MobileMoneyNegotiationPaymentService;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutAccountService;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutAccounts;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.objenesis.ObjenesisStd;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Preuve que le numéro déclaré sans SMS (claim Firebase {@code declared_phone}) ne sert jamais à
 * la connexion ni à l'argent : il peut être montré comme contact et retrouvé par l'admin, rien de
 * plus. Chaque lecteur est exercé avec un compte qui ne porte QUE le claim ({@code phoneNumber}
 * nul), aux deux niveaux ({@code DECLARED}, {@code YADONY_VERIFIED}).
 */
@DisplayName("Numéro déclaré : isolé de la connexion et de l'argent")
class DeclaredPhoneIsolationTest {

    private static final String UID = "uid-declared";
    private static final String DECLARED = "+2250102030405";

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

    /** Contact d'un compte ne portant que le claim : aucun numéro vérifié par SMS. */
    private static FirebaseContactService.Contact declaredOnly() {
        return new FirebaseContactService.Contact(null, "awa@example.com", DECLARED);
    }

    private static UserEntity userWith(DeclaredPhoneLevel level) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(UID);
        u.setDeclaredPhoneHash("hash");
        u.setDeclaredPhoneLevel(level);
        if (level == DeclaredPhoneLevel.YADONY_VERIFIED) {
            u.setDeclaredPhoneVerifiedAt(LocalDateTime.now());
        }
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        return u;
    }

    private static FirebaseContactService contactServiceFor(FirebaseAuth firebaseAuth) throws Exception {
        UserRecord record = mock(UserRecord.class);
        when(record.getUid()).thenReturn(UID);
        when(record.getEmail()).thenReturn("awa@example.com");
        when(record.getPhoneNumber()).thenReturn(null);
        when(record.getCustomClaims()).thenReturn(Map.of("declared_phone", DECLARED));
        when(firebaseAuth.getUser(UID)).thenReturn(record);
        return new FirebaseContactService(firebaseAuth);
    }

    // ── Firebase : le numéro déclaré ne devient jamais phoneNumber ni clé de recherche ──────────

    @Test
    @DisplayName("contact : phoneNumber reste nul, declaredPhone porte le claim")
    void contact_phoneNumberStaysNull() throws Exception {
        FirebaseContactService service = contactServiceFor(mock(FirebaseAuth.class));

        FirebaseContactService.Contact contact = service.getContact(UID);

        assertThat(contact.phoneNumber()).isNull();
        assertThat(contact.declaredPhone()).isEqualTo(DECLARED);
    }

    @Test
    @DisplayName("findUidByPhone / findPhoneOwner / findPhoneHolder : le numéro déclaré ne désigne personne")
    void findUidByPhone_doesNotMatchDeclared() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        FirebaseContactService service = contactServiceFor(firebaseAuth);
        // Firebase n'indexe pas les claims : getUserByPhoneNumber ne connaît pas ce numéro.
        FirebaseAuthException notFound = mock(FirebaseAuthException.class);
        when(notFound.getAuthErrorCode()).thenReturn(AuthErrorCode.USER_NOT_FOUND);
        when(firebaseAuth.getUserByPhoneNumber(DECLARED)).thenThrow(notFound);

        assertThat(service.findUidByPhone(DECLARED)).isEmpty();
        assertThat(service.findPhoneOwner(DECLARED).outcome())
                .isEqualTo(FirebaseContactService.PhoneOwnerLookup.Outcome.NOT_FOUND);
        assertThat(service.findPhoneHolder(DECLARED)).isEmpty();
        // La recherche passe par l'index téléphone de Firebase, jamais par une lecture ou un
        // balayage des claims : un lookup fondé sur declared_phone ferait échouer ces deux lignes.
        verify(firebaseAuth, never()).getUserByEmail(anyString());
        verify(firebaseAuth, never()).getUser(anyString());
        verify(firebaseAuth, never()).listUsers(any());
    }

    // ── Connexion ───────────────────────────────────────────────────────────────────────────────

    private AuthService newAuthService(UserRepository userRepository, FirebaseContactService firebaseContact) {
        UsernameGenerator usernameGenerator = mock(UsernameGenerator.class);
        lenientUsername(usernameGenerator);
        return new AuthService(userRepository, mock(AuditService.class), mock(UserService.class),
                mock(AccountFinalizationService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(ConnectedDevicesService.class), mock(StorageService.class), mock(AdminAuthService.class),
                firebaseContact, usernameGenerator, TestMessages.resolver(),
                new DeclaredPhoneResolver(new DeclaredPhoneHasher("")));
    }

    private static void lenientUsername(UsernameGenerator generator) {
        Mockito.lenient().when(generator.generate()).thenReturn("user1785153600");
    }

    private static FirebaseToken phoneTokenWithoutPhoneClaim() {
        FirebaseToken token = mock(FirebaseToken.class);
        // Le jeton d'un compte « déclaré » n'a pas de phone_number : Firebase ne le pose que pour
        // un numéro vérifié. Le claim déclaré peut très bien circuler dans le jeton, il n'y change rien.
        when(token.getClaims()).thenReturn(Map.of(
                "firebase", Map.of("sign_in_provider", "phone"),
                "declared_phone", DECLARED));
        return token;
    }

    @Test
    @DisplayName("connexion par téléphone : refusée (phone-required) sans numéro vérifié par SMS")
    void phoneLogin_rejected() {
        UserRepository userRepository = mock(UserRepository.class);
        FirebaseContactService firebaseContact = mock(FirebaseContactService.class);
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.empty());
        AuthService authService = newAuthService(userRepository, firebaseContact);

        assertThatThrownBy(() -> authService.register(UID, phoneTokenWithoutPhoneClaim(),
                new RegisterRequest(DECLARED, null, java.util.Set.of("SENDER"))))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("phone-required");
                });
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("connexion par SMS OTP (jeton custom) : le numéro déclaré ne tient pas lieu de numéro vérifié")
    void smsOtpLogin_rejected() {
        UserRepository userRepository = mock(UserRepository.class);
        FirebaseContactService firebaseContact = mock(FirebaseContactService.class);
        when(firebaseContact.getContact(UID)).thenReturn(declaredOnly());
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.empty());
        AuthService authService = newAuthService(userRepository, firebaseContact);
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getClaims()).thenReturn(Map.of(
                "firebase", Map.of("sign_in_provider", "custom"),
                "otp_channel", "sms"));

        assertThatThrownBy(() -> authService.register(UID, token, new RegisterRequest(null, null, java.util.Set.of("SENDER"))))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("phone-required"));
        verify(userRepository, never()).save(any());
    }

    // ── Argent : mobile money ───────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(DeclaredPhoneLevel.class)
    @DisplayName("mobile money (compte de versement) : le numéro déclaré n'est ni identité ni numéro de versement")
    void mobileMoneyPayer_ignoresDeclared(DeclaredPhoneLevel level) {
        UserRepository userRepository = mock(UserRepository.class);
        FirebaseContactService firebaseContact = mock(FirebaseContactService.class);
        PawapayClient client = mock(PawapayClient.class);
        PawapayProviderResolver resolver = new PawapayProviderResolver(client);
        MobileMoneyPayoutAccountService payoutAccounts = mock(MobileMoneyPayoutAccountService.class);
        PawapayProperties props = new PawapayProperties(true, "https://x", "t", false, 30, "https://r",
                "yadony://bids/%s/mobile-money/awaiting", "yadony://negotiations/%s/mobile-money/awaiting",
                "yadony://payments/wallet", new PawapayProperties.BalanceMin(BigDecimal.ZERO, BigDecimal.ZERO));
        UserEntity user = userWith(level);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(firebaseContact.getContact(UID)).thenReturn(declaredOnly());
        MobileMoneyAccountService service = new MobileMoneyAccountService(userRepository, firebaseContact,
                resolver, payoutAccounts, props, TestMessages.resolver());

        // Comme pour un compte sans aucun numéro : 422, rien n'est créé, pawaPay n'est jamais appelé.
        assertThatThrownBy(() -> service.activate(user.getId(), null))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("mobile-money-phone-required"));
        verifyNoInteractions(client, payoutAccounts);
    }

    @ParameterizedTest
    @EnumSource(DeclaredPhoneLevel.class)
    @DisplayName("mobile money (paiement d'un colis) : le payeur n'est jamais le numéro déclaré")
    void bidPayer_ignoresDeclared(DeclaredPhoneLevel level) {
        assertPayerResolutionIgnoresDeclared(MobileMoneyBidPaymentService.class, level, true);
    }

    @ParameterizedTest
    @EnumSource(DeclaredPhoneLevel.class)
    @DisplayName("mobile money (paiement d'une négociation) : le payeur n'est jamais le numéro déclaré")
    void negotiationPayer_ignoresDeclared(DeclaredPhoneLevel level) {
        assertPayerResolutionIgnoresDeclared(MobileMoneyNegotiationPaymentService.class, level, false);
    }

    /**
     * Ces deux services ont une quinzaine de collaborateurs : on les crée sans constructeur et on
     * ne câble que ce que lit {@code resolvePayerMsisdn} (le plus petit morceau qui lit le numéro).
     */
    private void assertPayerResolutionIgnoresDeclared(Class<?> type, DeclaredPhoneLevel level, boolean bid) {
        UserEntity sender = userWith(level);
        UserRepository userRepository = mock(UserRepository.class);
        FirebaseContactService firebaseContact = mock(FirebaseContactService.class);
        MobileMoneyPayoutAccounts payoutAccounts = mock(MobileMoneyPayoutAccounts.class);
        when(userRepository.findById(sender.getId())).thenReturn(Optional.of(sender));
        when(firebaseContact.getContact(UID)).thenReturn(declaredOnly());
        when(payoutAccounts.payerMsisdnIn(sender.getId(), "XOF")).thenReturn(Optional.empty());
        Object service = new ObjenesisStd().newInstance(type);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "firebaseContact", firebaseContact);
        ReflectionTestUtils.setField(service, "payoutAccounts", payoutAccounts);

        Throwable thrown;
        if (bid) {
            com.yadony.api.matching.BidEntity bidEntity = mock(com.yadony.api.matching.BidEntity.class);
            when(bidEntity.getSenderId()).thenReturn(sender.getId());
            when(bidEntity.getMobileMoneyPhone()).thenReturn(null);
            thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> ReflectionTestUtils.invokeMethod(service, "resolvePayerMsisdn", bidEntity, null, "XOF"));
        } else {
            thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> ReflectionTestUtils.invokeMethod(service, "resolvePayerMsisdn", sender.getId(), null, "XOF"));
        }

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("mobile-money-phone-required"));
    }

    // ── Argent : SMS de repli ───────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(DeclaredPhoneLevel.class)
    @DisplayName("repli SMS : aucun SMS vers un compte qui n'a qu'un numéro déclaré")
    void smsFallback_skipsDeclared(DeclaredPhoneLevel level) {
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        SmsService smsService = mock(SmsService.class);
        FirebaseContactService firebaseContact = mock(FirebaseContactService.class);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.criticalSmsFallbackEnabled()).thenReturn(true);
        UserEntity user = userWith(level);
        NotificationEntity notification = new NotificationEntity(user.getId(), "DELIVERY_CONFIRMED",
                "Livraison confirmée", "Votre colis est arrivé à destination",
                Map.of("type", "DELIVERY_CONFIRMED"), true);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(notification));
        when(userRepository.findAllById(any())).thenReturn(List.of(user));
        when(firebaseContact.getContacts(any())).thenReturn(Map.of(UID, declaredOnly()));

        new SmsFallbackScheduler(notificationRepository, userRepository, smsService, firebaseContact, settings)
                .processPendingFallbacks();

        verify(smsService, never()).send(anyString(), anyString());
        verify(smsService, never()).sendOrThrow(anyString(), anyString());
        // Traitée comme « pas de numéro » : marquée, pas rejouée.
        assertThat(notification.getSmsSentAt()).isNotNull();
    }

    // ── Rattachement : seul un numéro validé par Yadony, concordant et non contesté, est de confiance ──

    @Test
    @DisplayName("trustedDeclaredPhone : DECLARED -> vide ; YADONY_VERIFIED -> le numéro ; numéro SMS présent -> vide")
    void trustedDeclaredPhone_declaredEmpty_yadonyReturnsNumber() {
        DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("cle-de-test");
        FirebaseContactService firebaseContact = mock(FirebaseContactService.class);
        UserRepository userRepository = mock(UserRepository.class);
        PhoneLookupService lookup = new PhoneLookupService(firebaseContact, userRepository, hasher,
                new DeclaredPhoneResolver(hasher));
        when(firebaseContact.findPhoneOwner(DECLARED)).thenReturn(FirebaseContactService.PhoneOwnerLookup.notFound());
        when(firebaseContact.getContacts(any())).thenAnswer(inv -> {
            java.util.Collection<String> uids = inv.getArgument(0);
            Map<String, FirebaseContactService.Contact> out = new java.util.HashMap<>();
            uids.forEach(uid -> out.put(uid, new FirebaseContactService.Contact(null, null, DECLARED)));
            return out;
        });
        FirebaseContactService.Contact claimOnly = new FirebaseContactService.Contact(null, null, DECLARED);

        UserEntity declared = userWith(DeclaredPhoneLevel.DECLARED);
        declared.setDeclaredPhoneHash(hasher.hash(DECLARED));
        assertThat(lookup.trustedDeclaredPhone(declared, claimOnly)).isEmpty();

        UserEntity yadony = userWith(DeclaredPhoneLevel.YADONY_VERIFIED);
        yadony.setDeclaredPhoneHash(hasher.hash(DECLARED));
        when(userRepository.findTop3ByDeclaredPhoneHashAndDeclaredPhoneLevel(
                hasher.hash(DECLARED), DeclaredPhoneLevel.YADONY_VERIFIED)).thenReturn(List.of(yadony));
        assertThat(lookup.trustedDeclaredPhone(yadony, claimOnly)).contains(DECLARED);

        // Un compte qui a un numéro vérifié par SMS : le numéro déclaré ne compte plus.
        assertThat(lookup.trustedDeclaredPhone(yadony,
                new FirebaseContactService.Contact("+2250700000000", null, DECLARED))).isEmpty();
    }

    // ── Garde source : jamais de declaredPhone dans payments ni notifications ──────────────────

    @Test
    @DisplayName("garde source : aucune classe de payments ou notifications ne lit le numéro déclaré")
    void paymentsAndNotifications_neverReadDeclaredPhone() throws IOException {
        Path root = Paths.get("src/main/java/com/yadony/api");
        int scanned = 0;
        for (String pkg : List.of("payments", "notifications")) {
            Path dir = root.resolve(pkg);
            assertThat(dir).as("paquet %s présent dans src/main", pkg).isDirectory();
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    assertThat(source)
                            .as("%s ne doit pas lire le numéro déclaré (argent et SMS exclus)", file)
                            .doesNotContain("declaredPhone")
                            .doesNotContain("declared_phone")
                            .doesNotContain("DeclaredPhone");
                    scanned++;
                }
            }
        }
        // Un garde qui ne lirait aucun fichier passerait toujours : on s'assure qu'il a bien lu les sources.
        assertThat(scanned).isGreaterThan(20);
    }
}
