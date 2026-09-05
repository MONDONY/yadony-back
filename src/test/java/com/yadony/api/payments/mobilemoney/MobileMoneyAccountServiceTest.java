package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
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
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyAccountResponse;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class MobileMoneyAccountServiceTest {

    @Mock UserRepository userRepository;
    @Mock FirebaseContactService firebaseContact;
    @Mock PawapayClient client;
    @Mock ActiveCurrencyResolver currencyResolver;
    @Mock AuditService audit;

    private final UUID userId = UUID.randomUUID();
    private UserEntity user;
    private MobileMoneyAccountService service;

    private static final PawapayProviderConfig.Limits OK =
            new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1000000"), "NONE", "PROVIDER_AUTH", "OPERATIONAL");

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setFirebaseUid("uid-1");
        service = new MobileMoneyAccountService(userRepository, firebaseContact, client, currencyResolver, audit, props(true));
        // lenient : get_notConfigured_isAStateNotAnError (findById, pas findByIdForUpdate) et
        // activate_whenDisabledGlobally_is422 (rejeté avant tout accès repository) ne consomment
        // jamais ce stub — cf. les ~60 autres classes de test du projet qui suivent le même motif.
        lenient().when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
    }

    private static PawapayProperties props(boolean enabled) {
        return new PawapayProperties(enabled, "https://x", "t", false, 30, "https://r", "yadony://bids/%s/mobile-money/awaiting",
                new PawapayProperties.BalanceMin(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    void activate_snapshotsFirebasePhone_predictsProvider_andAudits() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("+221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK, OK)));
        when(currencyResolver.resolve(userId)).thenReturn("XOF");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MobileMoneyAccountResponse r = service.activate(userId);

        assertThat(user.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.ACTIVE);
        assertThat(user.getMobileMoneyMsisdn()).isEqualTo("221771234567");
        assertThat(user.getMobileMoneyMsisdnMasked()).isEqualTo("+221 •••• 67");
        assertThat(user.getMobileMoneyProvider()).isEqualTo("ORANGE_SEN");
        assertThat(user.getMobileMoneyCountry()).isEqualTo("SN");
        assertThat(user.getMobileMoneyCurrency()).isEqualTo("XOF");
        assertThat(user.getMobileMoneyVerifiedAt()).isNotNull();
        assertThat(r.status()).isEqualTo("ACTIVE");
        assertThat(r.providerLabel()).isEqualTo("Orange Money");
        verify(audit).log(eq("USER"), eq(userId), eq("MM_ACCOUNT_ACTIVATED"), eq(userId), any());
    }

    /**
     * Couvre la moitié droite du ternaire ligne 117 : quand pawaPay répond une prédiction SANS
     * numéro normalisé ({@code phoneNumber() == null}, contrairement au test ci-dessus), le
     * service doit retomber sur le numéro Firebase déjà vérifié par OTP, jamais persister un
     * MSISDN nul ou vide.
     */
    @Test
    void activate_pawapayPredictionOmitsPhoneNumber_fallsBackToFirebasePhone() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("+221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", null)));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK, OK)));
        when(currencyResolver.resolve(userId)).thenReturn("XOF");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.activate(userId);

        assertThat(user.getMobileMoneyMsisdn()).isEqualTo("221771234567");
    }

    @Test
    void activate_withoutFirebasePhone_is422() {
        when(firebaseContact.getContact("uid-1")).thenReturn(FirebaseContactService.Contact.EMPTY);
        assertThatThrownBy(() -> service.activate(userId)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-phone-required");
    }

    /**
     * {@code Contact.EMPTY} porte un {@code phoneNumber() == null}, donc le test ci-dessus ne
     * couvre que la moitié gauche de {@code phone == null || phone.isBlank()}. Un numéro non
     * nul mais uniquement composé d'espaces (dégradation Firebase différente d'un uid inconnu)
     * doit être rejeté de la même façon, pas silencieusement accepté.
     */
    @Test
    void activate_withBlankFirebasePhone_is422() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("   ", null));
        assertThatThrownBy(() -> service.activate(userId)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-phone-required");
    }

    @Test
    void activate_unknownProvider_is422() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+33612345678", null));
        when(client.predictProvider("+33612345678")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activate(userId)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-unsupported");
    }

    @Test
    void activate_providerWithoutPayout_is422() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("+221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "WAVE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("WAVE_SEN", new PawapayProviderConfig("WAVE_SEN", "SEN", "XOF", OK, null, null)));
        assertThatThrownBy(() -> service.activate(userId)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-unsupported");
    }

    /**
     * Distinct de {@code activate_providerWithoutPayout_is422} : ici pawaPay reconnaît
     * l'opérateur (prédiction non vide) mais celui-ci n'apparaît PAS du tout dans la
     * configuration active yadony ({@code configuration.get(...)} rend {@code null}) — un
     * opérateur que pawaPay connaît mais que yadony n'a pas encore activé, pas un opérateur
     * activé mais sans capacité payout. Même 422 côté utilisateur, mais une branche distincte
     * de la garde ligne 105.
     */
    @Test
    void activate_providerNotInActiveConfiguration_is422() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("+221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of());

        assertThatThrownBy(() -> service.activate(userId)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-unsupported");
    }

    @Test
    void activate_currencyMismatch_is422() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("+221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK, OK)));
        when(currencyResolver.resolve(userId)).thenReturn("EUR");
        assertThatThrownBy(() -> service.activate(userId)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-unsupported");
    }

    @Test
    void activate_pawapayUnavailable_is502() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("+221771234567")).thenThrow(new RestClientException("pawaPay indisponible"));

        YadonyBusinessException ex = catchThrowableOfType(() -> service.activate(userId), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
    }

    /**
     * Même défaut historique que {@code activate_pawapayUnavailable_is502}, mais sur le
     * DEUXIÈME appel HTTP pawaPay ({@code activeConfiguration}, pas {@code predictProvider}) —
     * un site d'appel distinct, avec son propre bloc {@code catch}, que rien ne garantit
     * couvert par le test du premier appel.
     */
    @Test
    void activate_activeConfigurationUnavailable_is502() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("+221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenThrow(new RestClientException("pawaPay indisponible"));

        YadonyBusinessException ex = catchThrowableOfType(() -> service.activate(userId), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
    }

    // Même famille que la panne réseau ci-dessus : pawaPay a répondu, mais avec un numéro
    // prédit inexploitable (Msisdn.normalize lève IllegalArgumentException). Pas un cas à
    // part métier (l'utilisateur n'y peut rien) : même 502, pas un 422.
    @Test
    void activate_pawapayPredictsUnusablePhoneNumber_is502() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("+221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "123")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK, OK)));
        when(currencyResolver.resolve(userId)).thenReturn("XOF");

        YadonyBusinessException ex = catchThrowableOfType(() -> service.activate(userId), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
    }

    /**
     * Revue finale, point 5 (Important) : {@code PawapayCountries.toAlpha2} rend {@code null}
     * pour un alpha-3 non couvert par la table ISO du JDK ("ZZZ" n'est le code d'aucun pays
     * réel) — même fixture que {@code MobileMoneyBidPaymentServiceTest#initiateDeposit_unmappableCountry_is422}
     * (Ronde 1, point 9), dont la garde n'avait pas été remontée ici (tâche 11, écrite avant la
     * tâche 13). Sans garde, {@code user.mobileMoneyCountry} était persisté à {@code null} en
     * silence : l'échec n'apparaissait qu'au versement (pawapay_operations.country NOT NULL),
     * en 500 générique et sans alerte.
     */
    @Test
    void activate_countryNotRecognized_is422() {
        when(firebaseContact.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(client.predictProvider("+221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("ZZZ", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "ZZZ", "XOF", OK, OK, OK)));
        when(currencyResolver.resolve(userId)).thenReturn("XOF");

        assertThatThrownBy(() -> service.activate(userId)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-unsupported");
        verify(userRepository, never()).save(any());
    }

    @Test
    void activate_whenDisabledGlobally_is422() {
        MobileMoneyAccountService off = new MobileMoneyAccountService(userRepository, firebaseContact, client, currencyResolver, audit, props(false));
        assertThatThrownBy(() -> off.activate(userId)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-disabled");
    }

    /**
     * {@code notFound()} est un helper partagé par {@code get}/{@code activate}/{@code disable}
     * (ligne 188) : sans ce test (et ses deux pendants sur {@code get}/{@code disable}), il
     * n'était exercé par aucune des ~15 autres méthodes de test, qui passent toutes par un
     * utilisateur trouvé. Verrou pessimiste {@code findByIdForUpdate} qui ne trouve rien →
     * 404 propre, jamais une NPE sur un {@code user} nul en aval.
     */
    @Test
    void activate_userNotFound_is404() {
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.empty());

        YadonyBusinessException ex = catchThrowableOfType(() -> service.activate(userId), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("user-not-found");
    }

    @Test
    void disable_keepsTheNumber_andAudits() {
        user.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        user.setMobileMoneyMsisdn("221771234567");
        user.setMobileMoneyMsisdnMasked("+221 •••• 67");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MobileMoneyAccountResponse r = service.disable(userId);

        assertThat(user.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.DISABLED);
        assertThat(user.getMobileMoneyMsisdn()).isEqualTo("221771234567");
        assertThat(r.status()).isEqualTo("DISABLED");
        assertThat(r.msisdnMasked()).isEqualTo("+221 •••• 67");
        verify(audit).log(eq("USER"), eq(userId), eq("MM_ACCOUNT_DISABLED"), eq(userId), any());
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
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        assertThat(service.get(userId).status()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void get_userNotFound_is404() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        YadonyBusinessException ex = catchThrowableOfType(() -> service.get(userId), YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("user-not-found");
    }
}
