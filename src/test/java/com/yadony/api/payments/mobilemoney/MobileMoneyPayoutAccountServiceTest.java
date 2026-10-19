package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountsResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountsResponse.AccountView;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayoutAccountsResponse.ProviderView;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MobileMoneyPayoutAccountServiceTest {

    @Mock UserRepository userRepository;
    @Mock MobileMoneyPayoutAccountRepository repository;
    @Mock MobileMoneyPayoutAccounts accounts;
    @Mock PawapayClient client;
    @Mock AuditService audit;

    private final UUID userId = UUID.randomUUID();
    private UserEntity user;
    private MobileMoneyPayoutAccountService service;

    private static final PawapayProviderConfig.Limits OK =
            new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1000000"), "PROVIDER_AUTH", "OPERATIONAL");
    private static final PawapayProviderConfig.Limits CLOSED =
            new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1000000"), "PROVIDER_AUTH", "CLOSED");

    private static final PawapayProviderConfig ORANGE_CIV = new PawapayProviderConfig("ORANGE_CIV", "CIV", "XOF", OK, OK);
    private static final PawapayProviderConfig MTN_CIV = new PawapayProviderConfig("MTN_MOMO_CIV", "CIV", "XOF", OK, OK);
    private static final PawapayProviderConfig ORANGE_SEN = new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK);

    /** Numéros de test : jamais en clair dans un audit, une réponse ou un message. */
    private static final String CIV_ORANGE_NUMBER = "2250701234567";
    private static final String CIV_MTN_NUMBER = "2250501234588";
    private static final String CIV_NEW_ORANGE_NUMBER = "2250709999999";
    private static final String SEN_ORANGE_NUMBER = "221771234567";

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        service = newService(true);
        lenient().when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repository.save(any())).thenAnswer(inv -> {
            MobileMoneyPayoutAccountEntity a = inv.getArgument(0);
            if (a.getId() == null) {
                ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            }
            return a;
        });
    }

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

    private MobileMoneyPayoutAccountService newService(boolean enabled) {
        return new MobileMoneyPayoutAccountService(userRepository, repository, accounts,
                new PawapayProviderResolver(client), audit, props(enabled), TestMessages.resolver());
    }

    private static PawapayProperties props(boolean enabled) {
        return new PawapayProperties(enabled, "https://x", "t", false, 30, "https://r", "yadony://bids/%s/mobile-money/awaiting",
                "yadony://negotiations/%s/mobile-money/awaiting", "yadony://payments/wallet",
                new PawapayProperties.BalanceMin(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private static Map<String, PawapayProviderConfig> configuration(PawapayProviderConfig... confs) {
        Map<String, PawapayProviderConfig> m = new LinkedHashMap<>();
        for (PawapayProviderConfig c : confs) {
            m.put(c.provider(), c);
        }
        return m;
    }

    private void predicts(String number, String country, String provider, PawapayProviderConfig... confs) {
        when(client.predictProvider(number)).thenReturn(Optional.of(new PawapayProviderPrediction(country, provider, number)));
        when(client.activeConfiguration()).thenReturn(configuration(confs.length == 0
                ? new PawapayProviderConfig[] {ORANGE_CIV, MTN_CIV, ORANGE_SEN} : confs));
    }

    private MobileMoneyPayoutAccountEntity existing(String provider, String country, String msisdn) {
        MobileMoneyPayoutAccountEntity a = new MobileMoneyPayoutAccountEntity(userId, provider, country, "XOF", msisdn,
                com.yadony.api.common.Msisdn.mask(msisdn), Instant.parse("2026-03-04T10:11:12Z"));
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        return a;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditPayload(String action) {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("USER"), eq(userId), eq(action), eq(userId), payload.capture());
        return payload.getValue();
    }

    private static void assertNoClearNumber(Map<String, Object> payload, String... numbers) {
        for (Object value : payload.values()) {
            for (String n : numbers) {
                assertThat(String.valueOf(value)).doesNotContain(n);
            }
        }
    }

    private static YadonyBusinessException thrown(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        return catchThrowableOfType(YadonyBusinessException.class, call);
    }

    private void assertNothingWritten() {
        verify(repository, never()).save(any());
        verify(userRepository, never()).save(any());
        verify(accounts, never()).syncSummary(any());
        verify(audit, never()).log(anyString(), any(), anyString(), any(), anyMap());
    }

    // ── Ajout ───────────────────────────────────────────────────────────────

    @Test
    void add_createsTheAccount_syncsTheSummary_andAuditsWithoutTheClearNumber() {
        predicts(CIV_ORANGE_NUMBER, "CIV", "ORANGE_CIV");
        when(repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, "ORANGE_CIV")).thenReturn(Optional.empty());

        AccountView view = service.add(userId, "+225 07 01 23 45 67", " orange_civ ");

        ArgumentCaptor<MobileMoneyPayoutAccountEntity> saved = ArgumentCaptor.forClass(MobileMoneyPayoutAccountEntity.class);
        verify(repository).save(saved.capture());
        MobileMoneyPayoutAccountEntity a = saved.getValue();
        assertThat(a.getUserId()).isEqualTo(userId);
        assertThat(a.getProvider()).isEqualTo("ORANGE_CIV");
        assertThat(a.getCountry()).isEqualTo("CI");
        assertThat(a.getCurrency()).isEqualTo("XOF");
        assertThat(a.getMsisdn()).isEqualTo(CIV_ORANGE_NUMBER);
        assertThat(a.getMsisdnMasked()).isEqualTo("+225 •••• 67");
        assertThat(a.getVerifiedAt()).isNotNull();
        assertThat(a.isMigrated()).isFalse();

        InOrder order = inOrder(userRepository, repository, accounts);
        order.verify(userRepository).findByIdForUpdate(userId);
        order.verify(repository).save(a);
        order.verify(accounts).syncSummary(user);
        order.verify(userRepository).save(user);

        Map<String, Object> payload = auditPayload("MM_PAYOUT_ACCOUNT_ADDED");
        assertThat(payload).containsEntry("provider", "ORANGE_CIV").containsEntry("country", "CI")
                .containsEntry("currency", "XOF").containsEntry("msisdnMasked", "+225 •••• 67")
                .containsEntry("accountId", a.getId()).containsEntry("source", "provided");
        assertNoClearNumber(payload, CIV_ORANGE_NUMBER, "0701234567");

        assertThat(view.id()).isEqualTo(a.getId());
        assertThat(view.provider()).isEqualTo("ORANGE_CIV");
        assertThat(view.providerLabel()).isEqualTo("Orange Money");
        assertThat(view.brand()).isEqualTo("ORANGE");
        assertThat(view.country()).isEqualTo("CI");
        assertThat(view.currency()).isEqualTo("XOF");
        assertThat(view.msisdnMasked()).isEqualTo("+225 •••• 67");
        assertThat(view.verifiedAt()).isEqualTo(a.getVerifiedAt());
        assertThat(view.pendingPayouts()).isZero();
        assertThat(view.toString()).doesNotContain(CIV_ORANGE_NUMBER);
    }

    @Test
    void add_aSecondCodeInAnotherCountry_isAccepted() {
        predicts(SEN_ORANGE_NUMBER, "SEN", "ORANGE_SEN");
        when(repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, "ORANGE_SEN")).thenReturn(Optional.empty());

        AccountView view = service.add(userId, "+221 77 123 45 67", "ORANGE_SEN");

        assertThat(view.provider()).isEqualTo("ORANGE_SEN");
        assertThat(view.country()).isEqualTo("SN");
        verify(repository, never()).findByUserIdAndProviderAndDeletedAtIsNull(userId, "ORANGE_CIV");
        verify(accounts).syncSummary(user);
    }

    @Test
    void add_whenAnAccountOfTheSameCodeIsLive_is409_andCallsNeitherPawapayNorWrites() {
        when(repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, "ORANGE_CIV"))
                .thenReturn(Optional.of(existing("ORANGE_CIV", "CI", CIV_ORANGE_NUMBER)));

        YadonyBusinessException e = thrown(() -> service.add(userId, "+225 07 01 23 45 67", "ORANGE_CIV"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("mobile-money-account-exists");
        assertThat(e.getMessage()).contains("Orange Money").doesNotContain(CIV_ORANGE_NUMBER);
        verifyNoInteractions(client);
        assertNothingWritten();
    }

    /** Review Focus 3 : jamais un compte Orange sur un numéro MTN. */
    @Test
    void add_withANumberOfAnotherOperator_is422WrongNetwork_namingTheDetectedOne() {
        predicts(CIV_MTN_NUMBER, "CIV", "MTN_MOMO_CIV");
        when(repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, "ORANGE_CIV")).thenReturn(Optional.empty());

        YadonyBusinessException e = thrown(() -> service.add(userId, CIV_MTN_NUMBER, "ORANGE_CIV"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo("mobile-money-account-wrong-network");
        assertThat(e.getMessage()).isEqualTo("Ce numéro est un numéro MTN MoMo.");
        assertNothingWritten();
    }

    @Test
    void add_wrongNetwork_isTranslated() {
        TestMessages.requestWithAcceptLanguage("en");
        predicts(CIV_MTN_NUMBER, "CIV", "MTN_MOMO_CIV");
        when(repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, "ORANGE_CIV")).thenReturn(Optional.empty());

        YadonyBusinessException e = thrown(() -> service.add(userId, CIV_MTN_NUMBER, "ORANGE_CIV"));

        assertThat(e.getMessage()).isEqualTo("This number belongs to MTN MoMo.");
    }

    @Test
    void add_whenPawapayDetectsNoUsableOperator_acceptsTheChosenCodeFromTheCatalogue() {
        // Opérateur prédit fermé au versement : detected nul, Orange reste au catalogue du numéro.
        PawapayProviderConfig mtnClosed = new PawapayProviderConfig("MTN_MOMO_CIV", "CIV", "XOF", OK, CLOSED);
        predicts(CIV_MTN_NUMBER, "CIV", "MTN_MOMO_CIV", ORANGE_CIV, mtnClosed);
        when(repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, "ORANGE_CIV")).thenReturn(Optional.empty());

        AccountView view = service.add(userId, CIV_MTN_NUMBER, "ORANGE_CIV");

        assertThat(view.provider()).isEqualTo("ORANGE_CIV");
        verify(repository).save(any());
    }

    @Test
    void add_withACodeOutsideTheNumbersPayoutCatalogue_is422Unsupported() {
        predicts(CIV_ORANGE_NUMBER, "CIV", "ORANGE_CIV");
        when(repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, "ORANGE_SEN")).thenReturn(Optional.empty());

        YadonyBusinessException e = thrown(() -> service.add(userId, CIV_ORANGE_NUMBER, "ORANGE_SEN"));

        assertThat(e.getErrorCode()).isEqualTo("mobile-money-account-unsupported");
        assertThat(e.getMessage()).isEqualTo("Réseau Orange Money indisponible pour ce numéro.");
        assertNothingWritten();
    }

    @Test
    void add_whenTheCatalogueIsRefused_is422Unsupported() {
        when(client.predictProvider(CIV_ORANGE_NUMBER)).thenReturn(Optional.empty());
        when(repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, "ORANGE_CIV")).thenReturn(Optional.empty());

        YadonyBusinessException e = thrown(() -> service.add(userId, CIV_ORANGE_NUMBER, "ORANGE_CIV"));

        assertThat(e.getErrorCode()).isEqualTo("mobile-money-account-unsupported");
        assertThat(e.getMessage()).isEqualTo("Aucun opérateur mobile money reconnu pour votre numéro.");
        assertNothingWritten();
    }

    @Test
    void add_withAMalformedCode_is422Unsupported_withoutEchoingIt() {
        YadonyBusinessException e = thrown(() -> service.add(userId, CIV_ORANGE_NUMBER, "ORANGE\nCIV"));

        assertThat(e.getErrorCode()).isEqualTo("mobile-money-account-unsupported");
        assertThat(e.getMessage()).isEqualTo("Code de réseau invalide.");
        verifyNoInteractions(client);
        assertNothingWritten();
    }

    @Test
    void add_withoutCode_is422Unsupported() {
        YadonyBusinessException e = thrown(() -> service.add(userId, CIV_ORANGE_NUMBER, "  "));

        assertThat(e.getErrorCode()).isEqualTo("mobile-money-account-unsupported");
        assertThat(e.getMessage()).isEqualTo("Choisissez au moins un réseau.");
        verifyNoInteractions(client);
    }

    @Test
    void add_withAnInvalidPhone_is422InvalidPhone() {
        YadonyBusinessException e = thrown(() -> service.add(userId, "12", "ORANGE_CIV"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo("mobile-money-invalid-phone");
        assertThat(e.getMessage()).isEqualTo("Numéro de téléphone invalide pour le versement mobile money.");
        verifyNoInteractions(client);
        assertNothingWritten();
    }

    @Test
    void writes_whenPawapayIsDisabled_areRefused() {
        MobileMoneyPayoutAccountService off = newService(false);
        UUID accountId = UUID.randomUUID();

        assertThat(thrown(() -> off.add(userId, CIV_ORANGE_NUMBER, "ORANGE_CIV")).getErrorCode()).isEqualTo("mobile-money-disabled");
        assertThat(thrown(() -> off.changeNumber(userId, accountId, CIV_ORANGE_NUMBER)).getErrorCode()).isEqualTo("mobile-money-disabled");
        assertThat(thrown(() -> off.upsert(userId, CIV_ORANGE_NUMBER, "ORANGE_CIV", "provided")).getErrorCode()).isEqualTo("mobile-money-disabled");
        verify(userRepository, never()).findByIdForUpdate(any());
        verifyNoInteractions(client);
    }

    // ── Changement de numéro ────────────────────────────────────────────────

    @Test
    void changeNumber_replacesTheNumber_andAuditsBothMaskedForms() {
        MobileMoneyPayoutAccountEntity account = existing("ORANGE_CIV", "CI", CIV_ORANGE_NUMBER);
        when(repository.findByIdAndUserIdAndDeletedAtIsNull(account.getId(), userId)).thenReturn(Optional.of(account));
        when(accounts.pendingPayouts(userId, "ORANGE_CIV")).thenReturn(0);
        predicts(CIV_NEW_ORANGE_NUMBER, "CIV", "ORANGE_CIV");

        AccountView view = service.changeNumber(userId, account.getId(), "+225 07 09 99 99 99");

        assertThat(account.getMsisdn()).isEqualTo(CIV_NEW_ORANGE_NUMBER);
        assertThat(account.getMsisdnMasked()).isEqualTo("+225 •••• 99");
        assertThat(account.getVerifiedAt()).isAfter(Instant.parse("2026-03-04T10:11:12Z"));
        assertThat(view.msisdnMasked()).isEqualTo("+225 •••• 99");
        InOrder order = inOrder(userRepository, repository, accounts);
        order.verify(userRepository).findByIdForUpdate(userId);
        order.verify(repository).save(account);
        order.verify(accounts).syncSummary(user);
        order.verify(userRepository).save(user);
        Map<String, Object> payload = auditPayload("MM_PAYOUT_ACCOUNT_NUMBER_CHANGED");
        assertThat(payload).containsEntry("accountId", account.getId()).containsEntry("provider", "ORANGE_CIV")
                .containsEntry("oldMsisdnMasked", "+225 •••• 67").containsEntry("msisdnMasked", "+225 •••• 99");
        assertNoClearNumber(payload, CIV_ORANGE_NUMBER, CIV_NEW_ORANGE_NUMBER);
    }

    @Test
    void changeNumber_toANumberOfAnotherOperator_is422WrongNetwork() {
        MobileMoneyPayoutAccountEntity account = existing("ORANGE_CIV", "CI", CIV_ORANGE_NUMBER);
        when(repository.findByIdAndUserIdAndDeletedAtIsNull(account.getId(), userId)).thenReturn(Optional.of(account));
        predicts(CIV_MTN_NUMBER, "CIV", "MTN_MOMO_CIV");

        YadonyBusinessException e = thrown(() -> service.changeNumber(userId, account.getId(), CIV_MTN_NUMBER));

        assertThat(e.getErrorCode()).isEqualTo("mobile-money-account-wrong-network");
        assertThat(account.getMsisdn()).isEqualTo(CIV_ORANGE_NUMBER);
        assertNothingWritten();
    }

    @Test
    void changeNumber_whileAParcelAwaitsItsPayout_is409Locked_andTouchesNothing() {
        MobileMoneyPayoutAccountEntity account = existing("ORANGE_CIV", "CI", CIV_ORANGE_NUMBER);
        when(repository.findByIdAndUserIdAndDeletedAtIsNull(account.getId(), userId)).thenReturn(Optional.of(account));
        when(accounts.pendingPayouts(userId, "ORANGE_CIV")).thenReturn(2);

        YadonyBusinessException e = thrown(() -> service.changeNumber(userId, account.getId(), CIV_NEW_ORANGE_NUMBER));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("mobile-money-account-locked");
        assertThat(e.getProperties()).containsEntry("pendingPayouts", 2);
        assertThat(e.getMessage()).startsWith("2 colis attendent encore leur versement sur ce compte.");
        assertThat(account.getMsisdn()).isEqualTo(CIV_ORANGE_NUMBER);
        verifyNoInteractions(client);
        assertNothingWritten();
    }

    @Test
    void changeNumber_ofAnUnknownOrForeignAccount_is404() {
        UUID foreign = UUID.randomUUID();
        when(repository.findByIdAndUserIdAndDeletedAtIsNull(foreign, userId)).thenReturn(Optional.empty());

        YadonyBusinessException e = thrown(() -> service.changeNumber(userId, foreign, CIV_NEW_ORANGE_NUMBER));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.getErrorCode()).isEqualTo("mobile-money-account-not-found");
        assertThat(e.getMessage()).isEqualTo("Compte de versement introuvable.");
        verifyNoInteractions(client);
        assertNothingWritten();
    }

    // ── Retrait ─────────────────────────────────────────────────────────────

    @Test
    void remove_softDeletes_resyncsTheSummary_andAudits() {
        MobileMoneyPayoutAccountEntity account = existing("ORANGE_CIV", "CI", CIV_ORANGE_NUMBER);
        when(repository.findByIdAndUserIdAndDeletedAtIsNull(account.getId(), userId)).thenReturn(Optional.of(account));
        when(accounts.pendingPayouts(userId, "ORANGE_CIV")).thenReturn(0);

        service.remove(userId, account.getId());

        assertThat(account.getDeletedAt()).isNotNull();
        InOrder order = inOrder(userRepository, repository, accounts);
        order.verify(userRepository).findByIdForUpdate(userId);
        order.verify(repository).save(account);
        order.verify(accounts).syncSummary(user);
        order.verify(userRepository).save(user);
        Map<String, Object> payload = auditPayload("MM_PAYOUT_ACCOUNT_REMOVED");
        assertThat(payload).containsEntry("accountId", account.getId()).containsEntry("provider", "ORANGE_CIV")
                .containsEntry("country", "CI").containsEntry("currency", "XOF")
                .containsEntry("msisdnMasked", "+225 •••• 67");
        assertNoClearNumber(payload, CIV_ORANGE_NUMBER);
        verifyNoInteractions(client);
    }

    @Test
    void remove_isAllowedEvenWhenPawapayIsDisabled() {
        MobileMoneyPayoutAccountEntity account = existing("ORANGE_CIV", "CI", CIV_ORANGE_NUMBER);
        when(repository.findByIdAndUserIdAndDeletedAtIsNull(account.getId(), userId)).thenReturn(Optional.of(account));

        newService(false).remove(userId, account.getId());

        assertThat(account.getDeletedAt()).isNotNull();
    }

    @Test
    void remove_whileAParcelAwaitsItsPayout_is409Locked_singular() {
        MobileMoneyPayoutAccountEntity account = existing("ORANGE_CIV", "CI", CIV_ORANGE_NUMBER);
        when(repository.findByIdAndUserIdAndDeletedAtIsNull(account.getId(), userId)).thenReturn(Optional.of(account));
        when(accounts.pendingPayouts(userId, "ORANGE_CIV")).thenReturn(1);

        YadonyBusinessException e = thrown(() -> service.remove(userId, account.getId()));

        assertThat(e.getErrorCode()).isEqualTo("mobile-money-account-locked");
        assertThat(e.getProperties()).containsEntry("pendingPayouts", 1);
        assertThat(e.getMessage()).startsWith("1 colis attend encore son versement sur ce compte.");
        assertThat(account.getDeletedAt()).isNull();
        assertNothingWritten();
    }

    @Test
    void remove_ofAForeignAccount_is404() {
        UUID foreign = UUID.randomUUID();
        when(repository.findByIdAndUserIdAndDeletedAtIsNull(foreign, userId)).thenReturn(Optional.empty());

        assertThat(thrown(() -> service.remove(userId, foreign)).getErrorCode()).isEqualTo("mobile-money-account-not-found");
        assertNothingWritten();
    }

    // ── Upsert (ancien contrat, tâche 4) ────────────────────────────────────

    @Test
    void upsert_withoutLiveAccountOfThatCode_creates_andAuditsTheSource() {
        predicts(CIV_ORANGE_NUMBER, "CIV", "ORANGE_CIV");
        when(repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, "ORANGE_CIV")).thenReturn(Optional.empty());

        AccountView view = service.upsert(userId, "+2250701234567", "ORANGE_CIV", "firebase");

        assertThat(view.provider()).isEqualTo("ORANGE_CIV");
        verify(accounts).syncSummary(user);
        assertThat(auditPayload("MM_PAYOUT_ACCOUNT_ADDED")).containsEntry("source", "firebase");
    }

    @Test
    void upsert_withALiveAccountOfThatCode_replacesItsNumber_neverA409() {
        MobileMoneyPayoutAccountEntity account = existing("ORANGE_CIV", "CI", CIV_ORANGE_NUMBER);
        when(repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, "ORANGE_CIV")).thenReturn(Optional.of(account));
        when(accounts.pendingPayouts(userId, "ORANGE_CIV")).thenReturn(0);
        predicts(CIV_NEW_ORANGE_NUMBER, "CIV", "ORANGE_CIV");

        AccountView view = service.upsert(userId, CIV_NEW_ORANGE_NUMBER, "ORANGE_CIV", "provided");

        assertThat(view.id()).isEqualTo(account.getId());
        assertThat(account.getMsisdn()).isEqualTo(CIV_NEW_ORANGE_NUMBER);
        Map<String, Object> payload = auditPayload("MM_PAYOUT_ACCOUNT_NUMBER_CHANGED");
        assertThat(payload).containsEntry("source", "provided").containsEntry("oldMsisdnMasked", "+225 •••• 67");
        assertNoClearNumber(payload, CIV_ORANGE_NUMBER, CIV_NEW_ORANGE_NUMBER);
    }

    @Test
    void upsert_replacingALockedAccount_is409Locked() {
        MobileMoneyPayoutAccountEntity account = existing("ORANGE_CIV", "CI", CIV_ORANGE_NUMBER);
        when(repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, "ORANGE_CIV")).thenReturn(Optional.of(account));
        when(accounts.pendingPayouts(userId, "ORANGE_CIV")).thenReturn(2);
        predicts(CIV_NEW_ORANGE_NUMBER, "CIV", "ORANGE_CIV");

        YadonyBusinessException e = thrown(() -> service.upsert(userId, CIV_NEW_ORANGE_NUMBER, "ORANGE_CIV", "provided"));

        assertThat(e.getErrorCode()).isEqualTo("mobile-money-account-locked");
        assertThat(e.getProperties()).containsEntry("pendingPayouts", 2);
        assertThat(account.getMsisdn()).isEqualTo(CIV_ORANGE_NUMBER);
        assertNothingWritten();
    }

    @Test
    void upsert_withoutCode_usesTheDetectedOperator() {
        predicts(CIV_MTN_NUMBER, "CIV", "MTN_MOMO_CIV");
        when(repository.findByUserIdAndProviderAndDeletedAtIsNull(userId, "MTN_MOMO_CIV")).thenReturn(Optional.empty());

        AccountView view = service.upsert(userId, CIV_MTN_NUMBER, null, "firebase");

        assertThat(view.provider()).isEqualTo("MTN_MOMO_CIV");
        assertThat(view.providerLabel()).isEqualTo("MTN MoMo");
    }

    @Test
    void upsert_withoutCodeAndNoUsableDetectedOperator_is422Unsupported() {
        PawapayProviderConfig mtnClosed = new PawapayProviderConfig("MTN_MOMO_CIV", "CIV", "XOF", OK, CLOSED);
        predicts(CIV_MTN_NUMBER, "CIV", "MTN_MOMO_CIV", ORANGE_CIV, mtnClosed);

        YadonyBusinessException e = thrown(() -> service.upsert(userId, CIV_MTN_NUMBER, null, "firebase"));

        assertThat(e.getErrorCode()).isEqualTo("mobile-money-account-unsupported");
        assertNothingWritten();
    }

    // ── Liste ───────────────────────────────────────────────────────────────

    @Test
    void list_returnsEveryLiveAccount_withItsLock_andSuggestsLegacyCodesWithoutAccount() {
        MobileMoneyPayoutAccountEntity civ = existing("ORANGE_CIV", "CI", CIV_ORANGE_NUMBER);
        civ.setMigrated(true);
        civ.setLegacyAcceptedProviders("ORANGE_CIV, mtn_momo_civ,MOOV_CIV,ORANGE_SEN,bad code");
        MobileMoneyPayoutAccountEntity sen = existing("ORANGE_SEN", "SN", SEN_ORANGE_NUMBER);
        when(accounts.active(userId)).thenReturn(List.of(civ, sen));
        when(accounts.pendingPayouts(userId, "ORANGE_CIV")).thenReturn(1);
        when(accounts.pendingPayouts(userId, "ORANGE_SEN")).thenReturn(0);

        MobileMoneyPayoutAccountsResponse r = service.list(userId);

        assertThat(r.accounts()).extracting(AccountView::provider).containsExactly("ORANGE_CIV", "ORANGE_SEN");
        assertThat(r.accounts()).extracting(AccountView::pendingPayouts).containsExactly(1, 0);
        assertThat(r.accounts()).extracting(AccountView::msisdnMasked).containsExactly("+225 •••• 67", "+221 •••• 67");
        assertThat(r.suggestedProviders()).containsExactly(new ProviderView("MTN_MOMO_CIV", "MTN MoMo"),
                new ProviderView("MOOV_CIV", "Moov Money"));
        assertThat(r.toString()).doesNotContain(CIV_ORANGE_NUMBER).doesNotContain(SEN_ORANGE_NUMBER);
        verifyNoInteractions(client);
    }

    @Test
    void list_withoutMigratedAccount_suggestsNothing() {
        MobileMoneyPayoutAccountEntity civ = existing("ORANGE_CIV", "CI", CIV_ORANGE_NUMBER);
        civ.setLegacyAcceptedProviders("MTN_MOMO_CIV");
        when(accounts.active(userId)).thenReturn(List.of(civ));

        assertThat(service.list(userId).suggestedProviders()).isEmpty();
        when(accounts.active(userId)).thenReturn(List.of());
        assertThat(service.list(userId).accounts()).isEmpty();
    }
}
