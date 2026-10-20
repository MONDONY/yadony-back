package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.Msisdn;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyAccountResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersResponse;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

/**
 * Ancien contrat {@code /payments/mobile-money/account} servi par la table des comptes de versement
 * (tâche 4) : le vrai {@link MobileMoneyPayoutAccountService} et le vrai
 * {@link MobileMoneyPayoutAccounts} tournent sur un dépôt simulé en mémoire, pour que chaque
 * activation ou désactivation se lise sur le résumé recalculé ({@code syncSummary}) exactement comme
 * en base.
 */
@ExtendWith(MockitoExtension.class)
class MobileMoneyAccountServiceTest {

    @Mock UserRepository userRepository;
    @Mock FirebaseContactService firebaseContact;
    @Mock PawapayClient client;
    @Mock AuditService audit;
    @Mock MobileMoneyPayoutAccountRepository accountRepository;

    private final UUID userId = UUID.randomUUID();
    private final List<MobileMoneyPayoutAccountEntity> store = new ArrayList<>();
    private UserEntity user;
    private MobileMoneyAccountService service;

    private static final PawapayProviderConfig.Limits OK =
            new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1000000"), "PROVIDER_AUTH", "OPERATIONAL");

    private static final PawapayProviderConfig ORANGE = new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK);
    private static final PawapayProviderConfig WAVE = new PawapayProviderConfig("WAVE_SEN", "SEN", "XOF", OK, OK);
    private static final PawapayProviderConfig FREE = new PawapayProviderConfig("FREE_SEN", "SEN", "XOF", OK, OK);

    private static Map<String, PawapayProviderConfig> configuration(PawapayProviderConfig... confs) {
        Map<String, PawapayProviderConfig> m = new LinkedHashMap<>();
        for (PawapayProviderConfig c : confs) {
            m.put(c.provider(), c);
        }
        return m;
    }

    private void senegalNumberPredicts(String provider) {
        when(client.predictProvider("221771234567"))
                .thenReturn(Optional.of(new PawapayProviderPrediction("SEN", provider, "221771234567")));
        when(client.activeConfiguration()).thenReturn(configuration(ORANGE, WAVE, FREE));
    }

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setFirebaseUid("uid-1");
        service = newService(true);
        // lenient : les cas rejetés avant tout accès (pawaPay coupé, numéro invalide, PUT retiré)
        // ne consomment pas ces stubs — même motif que les autres classes de test du projet.
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Dépôt en mémoire : l'ordre d'insertion tient lieu d'ordre de création.
        lenient().when(accountRepository.save(any())).thenAnswer(inv -> {
            MobileMoneyPayoutAccountEntity a = inv.getArgument(0);
            if (a.getId() == null) {
                ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
                store.add(a);
            }
            return a;
        });
        lenient().when(accountRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId))
                .thenAnswer(inv -> store.stream().filter(a -> a.getDeletedAt() == null).toList());
        lenient().when(accountRepository.findByUserIdAndProviderAndDeletedAtIsNull(eq(userId), anyString()))
                .thenAnswer(inv -> store.stream()
                        .filter(a -> a.getDeletedAt() == null && a.getProvider().equals(inv.getArgument(1))).findFirst());
    }

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

    private MobileMoneyAccountService newService(boolean enabled) {
        PawapayProviderResolver resolver = new PawapayProviderResolver(client);
        MobileMoneyPayoutAccountService payoutAccounts = new MobileMoneyPayoutAccountService(userRepository,
                accountRepository, new MobileMoneyPayoutAccounts(accountRepository, TestMessages.resolver()), resolver, audit, props(enabled),
                TestMessages.resolver());
        return new MobileMoneyAccountService(userRepository, firebaseContact, resolver, payoutAccounts, props(enabled),
                TestMessages.resolver());
    }

    private static PawapayProperties props(boolean enabled) {
        return new PawapayProperties(enabled, "https://x", "t", false, 30, "https://r", "yadony://bids/%s/mobile-money/awaiting",
                "yadony://negotiations/%s/mobile-money/awaiting", "yadony://payments/wallet",
                new PawapayProperties.BalanceMin(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private MobileMoneyPayoutAccountEntity liveAccount(String provider, String msisdn) {
        MobileMoneyPayoutAccountEntity a = new MobileMoneyPayoutAccountEntity(userId, provider, "SN", "XOF", msisdn,
                Msisdn.mask(msisdn), Instant.parse("2026-03-04T10:11:12Z"));
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        store.add(a);
        return a;
    }

    private List<MobileMoneyPayoutAccountEntity> live() {
        return store.stream().filter(a -> a.getDeletedAt() == null).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditPayload(String action) {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("USER"), eq(userId), eq(action), eq(userId), payload.capture());
        return payload.getValue();
    }

    private void assertNothingWritten() {
        verify(accountRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        verify(audit, never()).log(anyString(), any(), anyString(), any(), anyMap());
    }

    // ── activation avec réseaux (ancien contrat : un seul compte, celui du code de repli) ──

    @Test
    void activate_withProviders_createsOnlyTheDetectedCodesAccount_andAnswersWithIt() {
        senegalNumberPredicts("ORANGE_SEN");
        MobileMoneyAccountResponse r = service.activate(userId, "+221 77 123 45 67",
                List.of("free_sen", "WAVE_SEN", " ORANGE_SEN ", "WAVE_SEN"));

        assertThat(live()).extracting(MobileMoneyPayoutAccountEntity::getProvider).containsExactly("ORANGE_SEN");
        assertThat(user.getMobileMoneyProviderList()).containsExactly("ORANGE_SEN");
        assertThat(user.getMobileMoneyProvider()).isEqualTo("ORANGE_SEN");
        assertThat(user.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.ACTIVE);
        assertThat(r.status()).isEqualTo("ACTIVE");
        assertThat(r.provider()).isEqualTo("ORANGE_SEN");
        assertThat(r.msisdnMasked()).isEqualTo("+221 •••• 67");
        assertThat(r.providers()).extracting(MobileMoneyAccountResponse.ProviderView::label).containsExactly("Orange Money");
        Map<String, Object> payload = auditPayload("MM_PAYOUT_ACCOUNT_ADDED");
        assertThat(payload).containsEntry("provider", "ORANGE_SEN").containsEntry("source", "provided");
        assertThat(payload.values()).allSatisfy(v -> assertThat(String.valueOf(v)).doesNotContain("771234567"));
    }

    /**
     * Sans le détecté parmi les cochés, le repli est le premier coché (ordre du catalogue) ; sur un
     * numéro Orange, ce code d'un autre opérateur est refusé par la validation partagée : jamais un
     * compte Wave sur un numéro Orange (Review Focus 3), et rien n'est écrit.
     */
    @Test
    void activate_withProvidersWithoutTheDetectedOne_targetsTheFirstChecked_andIsRefusedAsWrongNetwork() {
        senegalNumberPredicts("ORANGE_SEN");
        YadonyBusinessException ex = catchThrowableOfType(
                () -> service.activate(userId, "+221 77 123 45 67", List.of("FREE_SEN", "WAVE_SEN")),
                YadonyBusinessException.class);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-account-wrong-network");
        assertThat(store).isEmpty();
        assertNothingWritten();
    }

    /**
     * Prédit fermé au versement : le catalogue n'a pas de détecté, le repli est le premier coché dans
     * l'ordre du catalogue (Orange), refusé comme tout autre opérateur que le prédit.
     */
    @Test
    void activate_withProviders_whenThePredictedIsClosed_targetsTheFirstChecked_andIsRefusedAsWrongNetwork() {
        when(client.predictProvider("221771234567"))
                .thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "WAVE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(configuration(ORANGE,
                new PawapayProviderConfig("WAVE_SEN", "SEN", "XOF", OK, null), FREE));
        YadonyBusinessException ex = catchThrowableOfType(
                () -> service.activate(userId, "+221 77 123 45 67", List.of("FREE_SEN", "ORANGE_SEN")),
                YadonyBusinessException.class);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-account-wrong-network");
        assertThat(store).isEmpty();
    }

    @Test
    void activate_withProviders_onTheCodeOfALiveAccount_replacesItsNumber() {
        MobileMoneyPayoutAccountEntity existing = liveAccount("ORANGE_SEN", "221770000001");
        senegalNumberPredicts("ORANGE_SEN");

        MobileMoneyAccountResponse r = service.activate(userId, "+221 77 123 45 67", List.of("ORANGE_SEN", "WAVE_SEN"));

        assertThat(live()).containsExactly(existing);
        assertThat(existing.getMsisdn()).isEqualTo("221771234567");
        assertThat(r.msisdnMasked()).isEqualTo("+221 •••• 67");
        assertThat(auditPayload("MM_PAYOUT_ACCOUNT_NUMBER_CHANGED")).containsEntry("oldMsisdnMasked", "+221 •••• 01")
                .containsEntry("source", "provided");
    }

    @Test
    void activate_replacingALockedAccount_is409Locked_andTouchesNothing() {
        MobileMoneyPayoutAccountEntity existing = liveAccount("ORANGE_SEN", "221770000001");
        senegalNumberPredicts("ORANGE_SEN");
        when(accountRepository.countParcelsAwaitingPayout(userId, List.of("ORANGE_SEN"))).thenReturn(1);

        YadonyBusinessException ex = catchThrowableOfType(
                () -> service.activate(userId, "+221 77 123 45 67", null), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-account-locked");
        assertThat(existing.getMsisdn()).isEqualTo("221770000001");
        assertNothingWritten();
    }

    @Test
    void activate_withAProviderOutsideTheCatalogue_is422_andWritesNothing() {
        when(client.predictProvider("221771234567"))
                .thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(configuration(ORANGE, WAVE));
        assertThatThrownBy(() -> service.activate(userId, "+221 77 123 45 67", List.of("ORANGE_SEN", "MTN_CIV")))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-unsupported");
                    assertThat(((YadonyBusinessException) e).getMessage()).contains("MTN MoMo");
                });
        assertNothingWritten();
        assertThat(user.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.NOT_CONFIGURED);
    }

    @Test
    void activate_withAProviderOutsideTheCatalogue_en_isTranslated() {
        TestMessages.requestWithAcceptLanguage("en");
        when(client.predictProvider("221771234567"))
                .thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(configuration(ORANGE, WAVE));
        assertThatThrownBy(() -> service.activate(userId, "+221 77 123 45 67", List.of("ORANGE_SEN", "MTN_CIV")))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-unsupported");
                    assertThat(((YadonyBusinessException) e).getMessage()).isEqualTo("The MTN MoMo network is not available for this number.");
                });
    }

    /**
     * Revue finale, point 3 (Minor 2) : un code mal formé (retour à la ligne) ne doit jamais
     * être échoué dans le {@code detail} du 422, même partiellement. Rejeté avant tout appel pawaPay.
     */
    @Test
    void activate_withAMalformedProviderCode_is422_withoutEchoingIt() {
        assertThatThrownBy(() -> service.activate(userId, "+221 77 123 45 67", List.of("ORANGE_SEN", "bad\ncode")))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-unsupported");
                    assertThat(((YadonyBusinessException) e).getMessage()).doesNotContain("bad");
                });
        verify(client, never()).predictProvider(any());
        assertNothingWritten();
    }

    @Test
    void activate_withAMalformedProviderCode_en_isTranslated() {
        TestMessages.requestWithAcceptLanguage("en");
        YadonyBusinessException ex = catchThrowableOfType(
                () -> service.activate(userId, "+221 77 123 45 67", List.of("ORANGE_SEN", "bad\ncode")),
                YadonyBusinessException.class);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-account-unsupported");
        assertThat(ex.getMessage()).isEqualTo("Invalid network code.");
    }

    @Test
    void activate_withProviders_whenTheCatalogueIsRefused_is422() {
        when(client.predictProvider("221771234567"))
                .thenReturn(Optional.of(new PawapayProviderPrediction("ZZZ", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "ZZZ", "XOF", OK, OK)));
        YadonyBusinessException ex = catchThrowableOfType(
                () -> service.activate(userId, "+221 77 123 45 67", List.of("ORANGE_SEN")), YadonyBusinessException.class);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-account-unsupported");
        assertThat(ex.getMessage()).isEqualTo("Pays non reconnu pour ce numéro.");
        assertNothingWritten();
    }

    /** Ancien contrat (app sans liste) : un compte de l'opérateur prédit. */
    @Test
    void activate_withoutProviders_createsTheAccountOfThePredictedOperator() {
        senegalNumberPredicts("ORANGE_SEN");
        MobileMoneyAccountResponse r = service.activate(userId, "+221 77 123 45 67", null);
        assertThat(live()).extracting(MobileMoneyPayoutAccountEntity::getProvider).containsExactly("ORANGE_SEN");
        assertThat(user.getMobileMoneyProviderList()).containsExactly("ORANGE_SEN");
        assertThat(user.getMobileMoneyProvider()).isEqualTo("ORANGE_SEN");
        assertThat(r.providers()).extracting(MobileMoneyAccountResponse.ProviderView::code).containsExactly("ORANGE_SEN");
    }

    /**
     * Un deuxième compte d'un autre code s'ajoute sans toucher au premier ; la réponse résume le
     * compte visé, le résumé utilisateur porte les deux codes.
     */
    @Test
    void activate_withAnotherOperatorsNumber_addsASecondAccount_andAnswersWithTheNewOne() {
        liveAccount("WAVE_SEN", "221760000002");
        senegalNumberPredicts("ORANGE_SEN");

        MobileMoneyAccountResponse r = service.activate(userId, "+221 77 123 45 67", null);

        assertThat(live()).extracting(MobileMoneyPayoutAccountEntity::getProvider).containsExactly("WAVE_SEN", "ORANGE_SEN");
        assertThat(user.getMobileMoneyProviderList()).containsExactly("WAVE_SEN", "ORANGE_SEN");
        assertThat(r.provider()).isEqualTo("ORANGE_SEN");
        assertThat(r.msisdnMasked()).isEqualTo("+221 •••• 67");
        assertThat(r.providers()).extracting(MobileMoneyAccountResponse.ProviderView::code).containsExactly("ORANGE_SEN");
    }

    /** Compte activé avant V255 : liste vide en base, la réponse expose quand même son unique réseau. */
    @Test
    void get_legacyAccountWithoutList_exposesItsSingleProvider() {
        user.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        user.setMobileMoneyProvider("ORANGE_SEN");
        MobileMoneyAccountResponse r = service.get(userId);
        assertThat(r.providers()).extracting(MobileMoneyAccountResponse.ProviderView::label).containsExactly("Orange Money");
    }

    @Test
    void activate_snapshotsFirebasePhone_predictsProvider_andAudits() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK)));

        MobileMoneyAccountResponse r = service.activate(userId, null);

        assertThat(user.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.ACTIVE);
        assertThat(user.getMobileMoneyMsisdn()).isEqualTo("221771234567");
        assertThat(user.getMobileMoneyMsisdnMasked()).isEqualTo("+221 •••• 67");
        assertThat(user.getMobileMoneyProvider()).isEqualTo("ORANGE_SEN");
        assertThat(user.getMobileMoneyCountry()).isEqualTo("SN");
        assertThat(user.getMobileMoneyCurrency()).isEqualTo("XOF");
        assertThat(user.getMobileMoneyVerifiedAt()).isNotNull();
        assertThat(r.status()).isEqualTo("ACTIVE");
        assertThat(r.providerLabel()).isEqualTo("Orange Money");
        assertThat(r.country()).isEqualTo("SN");
        assertThat(r.currency()).isEqualTo("XOF");
        assertThat(r.verifiedAt()).isNotNull();
        assertThat(auditPayload("MM_PAYOUT_ACCOUNT_ADDED")).containsEntry("source", "firebase");
    }

    /**
     * Quand pawaPay répond une prédiction SANS numéro normalisé ({@code phoneNumber() == null}), le
     * service doit retomber sur le numéro Firebase déjà vérifié par OTP, jamais persister un MSISDN
     * nul ou vide.
     */
    @Test
    void activate_pawapayPredictionOmitsPhoneNumber_fallsBackToFirebasePhone() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", null)));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK)));

        service.activate(userId, null);

        assertThat(user.getMobileMoneyMsisdn()).isEqualTo("221771234567");
        assertThat(live().get(0).getMsisdn()).isEqualTo("221771234567");
    }

    @Test
    void activate_withoutFirebasePhone_is422() {
        when(firebaseContact.getContact("uid-1")).thenReturn(FirebaseContactService.Contact.EMPTY);
        assertThatThrownBy(() -> service.activate(userId, null)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-phone-required");
    }

    /**
     * {@code Contact.EMPTY} porte un {@code phoneNumber() == null}, donc le test ci-dessus ne
     * couvre que la moitié gauche de {@code phone == null || phone.isBlank()}. Un numéro non
     * nul mais uniquement composé d'espaces doit être rejeté de la même façon.
     */
    @Test
    void activate_withBlankFirebasePhone_is422() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("   ", null));
        assertThatThrownBy(() -> service.activate(userId, null)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-phone-required");
    }

    /**
     * Un numéro fourni est accepté et devient la source, normalisé ({@code Msisdn.normalize})
     * avant d'être transmis à pawaPay, sa provenance tracée dans l'audit sous
     * {@code source: "provided"}. Le compte Firebase n'est même pas interrogé.
     */
    @Test
    void activate_providedPhone_usedWhenNoFirebasePhone() {
        when(client.predictProvider("221773456789"))
                .thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221773456789")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK)));

        MobileMoneyAccountResponse r = service.activate(userId, "+221 77 345 67 89");

        assertThat(user.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.ACTIVE);
        assertThat(user.getMobileMoneyMsisdn()).isEqualTo("221773456789");
        assertThat(r.status()).isEqualTo("ACTIVE");
        assertThat(auditPayload("MM_PAYOUT_ACCOUNT_ADDED")).containsEntry("source", "provided");
        verify(firebaseContact, never()).getContact(any());
    }

    /**
     * Le numéro fourni est TOUJOURS prioritaire, même quand le compte Firebase a déjà un téléphone
     * (différent ici, pour le prouver) : Firebase n'est pas consulté du tout.
     */
    @Test
    void activate_providedPhone_takesPriorityOverFirebasePhone() {
        lenient().when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("221770000001"))
                .thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221770000001")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK)));

        MobileMoneyAccountResponse r = service.activate(userId, "+221 77 000 00 01");

        assertThat(user.getMobileMoneyMsisdn()).isEqualTo("221770000001");
        assertThat(r.status()).isEqualTo("ACTIVE");
        assertThat(auditPayload("MM_PAYOUT_ACCOUNT_ADDED")).containsEntry("source", "provided");
        verify(firebaseContact, never()).getContact(any());
    }

    /**
     * Numéro fourni vide ou uniquement composé d'espaces : traité comme absent, retombe sur le
     * téléphone Firebase déjà vérifié par OTP, provenance {@code firebase} dans l'audit.
     */
    @Test
    void activate_blankProvidedPhone_fallsBackToFirebasePhone() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("221771234567"))
                .thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK)));

        MobileMoneyAccountResponse r = service.activate(userId, "   ");

        assertThat(user.getMobileMoneyMsisdn()).isEqualTo("221771234567");
        assertThat(r.status()).isEqualTo("ACTIVE");
        assertThat(auditPayload("MM_PAYOUT_ACCOUNT_ADDED")).containsEntry("source", "firebase");
    }

    /**
     * Un numéro fourni mais inexploitable par {@code Msisdn.normalize} (trop court) : 422
     * {@code mobile-money-invalid-phone}, sans appel pawaPay, sans écriture, sans consulter Firebase.
     */
    @Test
    void activate_invalidProvidedPhone_is422InvalidPhone() {
        YadonyBusinessException ex = catchThrowableOfType(() -> service.activate(userId, "123"), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-invalid-phone");
        assertNothingWritten();
        verify(firebaseContact, never()).getContact(any());
        verify(client, never()).predictProvider(any());
    }

    /**
     * Ni téléphone Firebase ni numéro fourni : 422 {@code mobile-money-phone-required}, avec le
     * détail propre au versement.
     */
    @Test
    void activate_withoutFirebasePhoneAndWithoutProvidedPhone_is422WithDetail() {
        when(firebaseContact.getContact("uid-1")).thenReturn(FirebaseContactService.Contact.EMPTY);

        YadonyBusinessException ex = catchThrowableOfType(() -> service.activate(userId, null), YadonyBusinessException.class);

        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-phone-required");
        assertThat(ex.getMessage()).isEqualTo("Indiquez le numéro mobile money qui recevra vos versements.");
    }

    @Test
    void activate_unknownProvider_is422() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+33612345678", null));
        when(client.predictProvider("33612345678")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activate(userId, null)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-unsupported");
    }

    @Test
    void activate_unknownProvider_en_isTranslated() {
        TestMessages.requestWithAcceptLanguage("en");
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+33612345678", null));
        when(client.predictProvider("33612345678")).thenReturn(Optional.empty());
        YadonyBusinessException ex = catchThrowableOfType(() -> service.activate(userId, null), YadonyBusinessException.class);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-account-unsupported");
        assertThat(ex.getMessage()).isEqualTo("No mobile money operator recognized for your number.");
    }

    @Test
    void activate_providerWithoutPayout_is422() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "WAVE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("WAVE_SEN", new PawapayProviderConfig("WAVE_SEN", "SEN", "XOF", OK, null)));
        assertThatThrownBy(() -> service.activate(userId, null)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-unsupported");
    }

    @Test
    void activate_providerWithoutPayout_en_isTranslated() {
        TestMessages.requestWithAcceptLanguage("en");
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "WAVE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("WAVE_SEN", new PawapayProviderConfig("WAVE_SEN", "SEN", "XOF", OK, null)));
        YadonyBusinessException ex = catchThrowableOfType(() -> service.activate(userId, null), YadonyBusinessException.class);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-account-unsupported");
        assertThat(ex.getMessage()).isEqualTo("Wave does not support payouts yet.");
    }

    /**
     * Distinct de {@code activate_providerWithoutPayout_is422} : pawaPay reconnaît l'opérateur mais
     * celui-ci n'apparaît PAS du tout dans la configuration active yadony. Même 422.
     */
    @Test
    void activate_providerNotInActiveConfiguration_is422() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of());

        assertThatThrownBy(() -> service.activate(userId, null)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-unsupported");
    }

    /**
     * L'activation d'un compte de versement ne compare pas la devise du numéro à la devise active du
     * portefeuille : le compte prend la devise de l'opérateur prédit.
     */
    @Test
    void activate_ignoresWalletCurrency_usesOperatorCurrency() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK)));

        MobileMoneyAccountResponse r = service.activate(userId, null);

        assertThat(user.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.ACTIVE);
        assertThat(user.getMobileMoneyCurrency()).isEqualTo("XOF");
        assertThat(r.status()).isEqualTo("ACTIVE");
    }

    @Test
    void activate_pawapayUnavailable_is502() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("221771234567")).thenThrow(new RestClientException("pawaPay indisponible"));

        YadonyBusinessException ex = catchThrowableOfType(() -> service.activate(userId, null), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
    }

    /** Même défaut que ci-dessus, sur le DEUXIÈME appel HTTP pawaPay ({@code activeConfiguration}). */
    @Test
    void activate_activeConfigurationUnavailable_is502() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenThrow(new RestClientException("pawaPay indisponible"));

        YadonyBusinessException ex = catchThrowableOfType(() -> service.activate(userId, null), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
    }

    /** Le chemin avec réseaux interroge aussi le catalogue : même 502 quand pawaPay tombe. */
    @Test
    void activate_withProviders_pawapayUnavailable_is502() {
        when(client.predictProvider("221771234567")).thenThrow(new RestClientException("pawaPay indisponible"));

        YadonyBusinessException ex = catchThrowableOfType(
                () -> service.activate(userId, "+221 77 123 45 67", List.of("ORANGE_SEN")), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertNothingWritten();
    }

    // pawaPay a répondu, mais avec un numéro prédit inexploitable : même 502, pas un 422.
    @Test
    void activate_pawapayPredictsUnusablePhoneNumber_is502() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "123")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK)));

        YadonyBusinessException ex = catchThrowableOfType(() -> service.activate(userId, null), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
    }

    /**
     * Revue finale, point 5 : un alpha-3 hors table ISO du JDK ("ZZZ") ne doit jamais donner un
     * compte au pays nul : 422, rien n'est écrit.
     */
    @Test
    void activate_countryNotRecognized_is422() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("ZZZ", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "ZZZ", "XOF", OK, OK)));

        assertThatThrownBy(() -> service.activate(userId, null)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-unsupported");
        assertNothingWritten();
    }

    @Test
    void activate_countryNotRecognized_en_isTranslated() {
        TestMessages.requestWithAcceptLanguage("en");
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("ZZZ", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "ZZZ", "XOF", OK, OK)));

        YadonyBusinessException ex = catchThrowableOfType(() -> service.activate(userId, null), YadonyBusinessException.class);

        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-account-unsupported");
        assertThat(ex.getMessage()).isEqualTo("Country not recognized for this number.");
    }

    @Test
    void activate_whenDisabledGlobally_is422() {
        assertThatThrownBy(() -> newService(false).activate(userId, null)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-disabled");
        verify(firebaseContact, never()).getContact(any());
    }

    @Test
    void activate_userNotFound_is404() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        YadonyBusinessException ex = catchThrowableOfType(() -> service.activate(userId, null), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("user-not-found");
    }

    // ── désactivation : retire tous les comptes ─────────────────────────────

    @Test
    void disable_removesEveryLiveAccount_answersDisabled_andAuditsEachRemoval() {
        user.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        MobileMoneyPayoutAccountEntity orange = liveAccount("ORANGE_SEN", "221771234567");
        MobileMoneyPayoutAccountEntity wave = liveAccount("WAVE_SEN", "221760000002");

        MobileMoneyAccountResponse r = newService(false).disable(userId);

        assertThat(orange.getDeletedAt()).isNotNull();
        assertThat(wave.getDeletedAt()).isNotNull();
        assertThat(user.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.DISABLED);
        assertThat(user.getMobileMoneyCurrencyList()).isEmpty();
        assertThat(user.canReceiveMobileMoney("XOF")).isFalse();
        assertThat(r.status()).isEqualTo("DISABLED");
        verify(audit, org.mockito.Mockito.times(2))
                .log(eq("USER"), eq(userId), eq("MM_PAYOUT_ACCOUNT_REMOVED"), eq(userId), anyMap());
    }

    @Test
    void disable_whileAParcelAwaitsItsPayout_is409Locked_andRemovesNothing() {
        user.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        MobileMoneyPayoutAccountEntity orange = liveAccount("ORANGE_SEN", "221771234567");
        MobileMoneyPayoutAccountEntity wave = liveAccount("WAVE_SEN", "221760000002");
        when(accountRepository.countParcelsAwaitingPayout(userId, List.of("ORANGE_SEN"))).thenReturn(0);
        when(accountRepository.countParcelsAwaitingPayout(userId, List.of("WAVE_SEN"))).thenReturn(1);

        YadonyBusinessException ex = catchThrowableOfType(() -> service.disable(userId), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-account-locked");
        assertThat(orange.getDeletedAt()).isNull();
        assertThat(wave.getDeletedAt()).isNull();
        assertThat(user.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.ACTIVE);
        assertNothingWritten();
    }

    @Test
    void disable_userNotFound_is404() {
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.empty());

        YadonyBusinessException ex = catchThrowableOfType(() -> service.disable(userId), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("user-not-found");
    }

    @Test
    void get_notConfigured_isAStateNotAnError() {
        assertThat(service.get(userId).status()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void get_userNotFound_is404() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        YadonyBusinessException ex = catchThrowableOfType(() -> service.get(userId), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("user-not-found");
    }

    // ── catalogue du voyageur ───────────────────────────────────────────────

    @Test
    void providers_withAProvidedNumber_listsThePayoutCatalogue_detectedFlagged() {
        when(client.predictProvider("221771234567"))
                .thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "WAVE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(configuration(ORANGE, WAVE, FREE));
        MobileMoneyProvidersResponse r = service.providers(userId, "+221 77 123 45 67");
        assertThat(r.country()).isEqualTo("SN");
        assertThat(r.currency()).isEqualTo("XOF");
        assertThat(r.msisdnMasked()).isEqualTo("+221 •••• 67");
        assertThat(r.detected()).isEqualTo("WAVE_SEN");
        assertThat(r.providers()).extracting(MobileMoneyProvidersResponse.ProviderOption::code)
                .containsExactly("WAVE_SEN", "ORANGE_SEN", "FREE_SEN");
        assertThat(r.providers().get(0).detected()).isTrue();
        assertThat(r.providers().get(1).detected()).isFalse();
        assertThat(r.providers().get(1).label()).isEqualTo("Orange Money");
        verify(firebaseContact, never()).getContact(any());
    }

    @Test
    void providers_withoutNumber_usesTheStoredPayoutNumber() {
        user.setMobileMoneyMsisdn("221771234567");
        when(client.predictProvider("221771234567"))
                .thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(configuration(ORANGE, WAVE));
        assertThat(service.providers(userId, null).detected()).isEqualTo("ORANGE_SEN");
        verify(firebaseContact, never()).getContact(any());
    }

    @Test
    void providers_withoutAnyNumber_is422PhoneRequired() {
        when(firebaseContact.getContact("uid-1")).thenReturn(FirebaseContactService.Contact.EMPTY);
        assertThatThrownBy(() -> service.providers(userId, null)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-phone-required");
    }

    @Test
    void providers_unknownNumber_is422Unsupported() {
        when(client.predictProvider("33612345678")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.providers(userId, "+33 6 12 34 56 78")).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-unsupported");
    }

    /** Aucune devise active n'est fournie : le catalogue retient la devise de l'opérateur prédit. */
    @Test
    void providers_ignoresWalletCurrency_usesOperatorCurrency() {
        when(client.predictProvider("221771234567"))
                .thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(configuration(ORANGE, WAVE, FREE));

        MobileMoneyProvidersResponse r = service.providers(userId, "+221 77 123 45 67");

        assertThat(r.currency()).isEqualTo("XOF");
    }

    @Test
    void providers_whenDisabledGlobally_is422() {
        assertThatThrownBy(() -> newService(false).providers(userId, "+221 77 123 45 67")).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-disabled");
    }

    // ── PUT /account/providers retiré : l'app doit être mise à jour ─────────

    @Test
    void updateProviders_is422UpdateApp_andTouchesNothing() {
        YadonyBusinessException ex = catchThrowableOfType(() -> service.updateProviders(), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-update-app");
        assertThat(ex.getMessage()).isEqualTo("Mettez à jour l'application pour gérer vos comptes de versement.");
        assertNothingWritten();
        verify(client, never()).predictProvider(any());
    }

    @Test
    void updateProviders_en_isTranslated() {
        TestMessages.requestWithAcceptLanguage("en");
        YadonyBusinessException ex = catchThrowableOfType(() -> service.updateProviders(), YadonyBusinessException.class);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-update-app");
        assertThat(ex.getMessage()).isEqualTo("Update the app to manage your payout accounts.");
    }
}
