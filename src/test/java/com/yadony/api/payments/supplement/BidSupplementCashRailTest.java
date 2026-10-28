package com.yadony.api.payments.supplement;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.param.PaymentIntentCreateParams;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.StripeUnavailable;
import com.yadony.api.matching.dto.BidSupplementResponse;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.cash.CommissionSplit;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.WalletCommissionCollector;
import com.yadony.api.payments.cash.dto.FundingAlternativeDto;
import com.yadony.api.payments.cash.exception.CommissionMethodMissingException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import com.yadony.api.payments.supplement.dto.CreateSupplementRequest;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.payments.supplement.BidSupplementPricingTest.setId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rail espèces : commission du supplément prélevée au voyageur à sa demande (solde ou carte
 * commission, 3DS), acceptation en main propre par l'expéditeur. Le rail ne dépend ni du service
 * des bons de parrainage ni de {@code BidRepository} : il ne peut ni consommer un bon déjà consommé
 * par la commission principale, ni toucher {@code bids.commission_*}.
 */
@ExtendWith(MockitoExtension.class)
class BidSupplementCashRailTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    @Mock WalletCommissionCollector collector;
    @Mock WalletService walletService;
    @Mock ActiveCurrencyResolver activeCurrencyResolver;
    @Mock StripeSupplementGateway gateway;
    @Mock BidSupplementCashLedger ledger;
    @Mock BidSupplementCashRefunds refunds;
    @Mock BidSupplementRepository supplements;
    @Mock UserRepository users;
    @Mock StorageService storage;

    private BidSupplementCashRail rail;
    private UserEntity traveler;
    private BidSupplementEntity s;

    @BeforeEach
    void setUp() {
        BidSupplementWindow window = new BidSupplementWindow(new BidSupplementProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        rail = new BidSupplementCashRail(collector, walletService, activeCurrencyResolver, gateway, ledger, refunds,
                supplements, users, storage, window);
        traveler = new UserEntity();
        setId(traveler, UUID.randomUUID());
        traveler.setFirebaseUid("uid-traveler");
        traveler.setStripeCustomerId("cus_t");
        s = new BidSupplementEntity();
        setId(s, UUID.randomUUID());
        s.setBidId(UUID.randomUUID());
        s.setTravelerId(traveler.getId());
        s.setSenderId(UUID.randomUUID());
        s.setRail(BidSupplementRail.CASH);
        s.setStatus(BidSupplementStatus.PROPOSED);
        s.setCurrency("EUR");
        s.setNetAmount(new BigDecimal("20.00"));
        s.setCommissionAmount(new BigDecimal("1.10"));
        s.setGrossAmount(new BigDecimal("21.10"));
        s.setReasonCode(BidSupplementReason.GRID_ITEM);
        s.setPhotoKey("tracking/" + s.getBidId() + "/1.jpg");
        s.setExpiresAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusHours(2));
        lenient().when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        lenient().when(storage.generatePresignedUrl(any(), any())).thenReturn("https://signed");
    }

    private static CreateSupplementRequest request(CommissionSource source, String fundingCurrency) {
        return new CreateSupplementRequest(new BigDecimal("20"), BidSupplementReason.GRID_ITEM, null, null,
                "tracking/x/1.jpg", source, fundingCurrency);
    }

    private static CommissionSplit covered(String commission) {
        BigDecimal c = new BigDecimal(commission);
        return new CommissionSplit("EUR", c, c, BigDecimal.ZERO, "EUR", BigDecimal.ZERO, null,
                new BigDecimal("10.00"), new BigDecimal("10.00"), true, c);
    }

    // ── Bouton : de quoi payer la commission ────────────────────────────────

    @Test
    void canRequest_carteCommissionOuSoldePositifNonGele() {
        assertThat(rail.rail()).isEqualTo(BidSupplementRail.CASH);
        assertThat(rail.canRequest(null, "EUR")).isFalse();

        traveler.setCommissionPaymentMethodId("pm_1");
        assertThat(rail.canRequest(traveler, "EUR")).isTrue();
        verifyNoInteractions(walletService);

        traveler.setCommissionPaymentMethodId(null);
        when(walletService.getAllBalances(traveler.getId())).thenReturn(List.of(wallet("EUR", "0"), wallet("XOF", "500")));
        when(walletService.isFrozen(traveler.getId(), "XOF")).thenReturn(false, true);
        assertThat(rail.canRequest(traveler, "EUR")).isTrue();
        assertThat(rail.canRequest(traveler, "EUR")).as("seul solde positif gelé").isFalse();
    }

    @Test
    void canRequest_niCarteNiSolde_faux() {
        when(walletService.getAllBalances(traveler.getId())).thenReturn(List.of());
        assertThat(rail.canRequest(traveler, "EUR")).isFalse();
    }

    // ── Création : solde ─────────────────────────────────────────────────────

    @Test
    void creation_soldeCouvert_debitDedieEtDemandeProposee() {
        when(activeCurrencyResolver.resolve(traveler.getId())).thenReturn("eur");
        CommissionSplit split = covered("1.10");
        when(collector.plan(traveler.getId(), "EUR", "EUR", null, new BigDecimal("1.10"))).thenReturn(split);

        rail.onCreated(s, request(null, null));

        verify(collector).executeForSupplement(split, traveler.getId(), s.getBidId(), s.getId());
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PROPOSED);
        assertThat(s.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.WALLET);
        assertThat(s.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
        verify(supplements).save(s);
        verify(ledger).audit(eq(s), eq("BID_SUPPLEMENT_COMMISSION_CHARGED_WALLET"), eq(traveler.getId()), anyMap());
        verifyNoInteractions(gateway);
    }

    @Test
    void creation_soldeCouvertAvecComplement_auditDuComplement() {
        CommissionSplit split = new CommissionSplit("EUR", new BigDecimal("1.10"), new BigDecimal("0.50"),
                new BigDecimal("0.60"), "XOF", new BigDecimal("394"), new BigDecimal("656.666667"),
                new BigDecimal("0.50"), new BigDecimal("1000"), true, new BigDecimal("722"));
        when(collector.plan(traveler.getId(), "EUR", "XOF", "XOF", new BigDecimal("1.10"))).thenReturn(split);

        rail.onCreated(s, request(CommissionSource.WALLET_FIRST, "xof"));

        verify(collector).executeForSupplement(split, traveler.getId(), s.getBidId(), s.getId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
        verify(ledger).audit(eq(s), anyString(), any(), audit.capture());
        assertThat(audit.getValue()).containsEntry("activeCurrency", "XOF").containsEntry("remainingActive", "394");
        verifyNoInteractions(activeCurrencyResolver);
    }

    @Test
    void creation_soldeCourt_422AvecAlternativesEtRienDebite() {
        when(activeCurrencyResolver.resolve(traveler.getId())).thenReturn("EUR");
        CommissionSplit split = new CommissionSplit("EUR", new BigDecimal("1.10"), new BigDecimal("0.40"),
                new BigDecimal("0.70"), "EUR", new BigDecimal("0.70"), null, new BigDecimal("0.40"),
                new BigDecimal("0.40"), false, new BigDecimal("1.10"));
        when(collector.plan(traveler.getId(), "EUR", "EUR", null, new BigDecimal("1.10"))).thenReturn(split);
        List<FundingAlternativeDto> alternatives = List.of(new FundingAlternativeDto("XOF", new BigDecimal("5000"),
                new BigDecimal("722")));
        when(collector.alternatives(traveler.getId(), "EUR", "EUR", new BigDecimal("1.10"))).thenReturn(alternatives);

        Throwable thrown = catchThrowable(() -> rail.onCreated(s, request(CommissionSource.WALLET_FIRST, null)));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.getErrorCode()).isEqualTo("supplement-commission-unfunded");
            assertThat(e.getProperties()).containsEntry("alternatives", alternatives)
                    .containsEntry("hasCard", false)
                    .containsEntry("currency", "EUR")
                    .containsEntry("bidCurrency", "EUR")
                    .doesNotContainKey("breakdown");
            assertThat((BigDecimal) e.getProperties().get("shortfall")).isEqualByComparingTo("0.70");
            assertThat((BigDecimal) e.getProperties().get("availableBalance")).isEqualByComparingTo("0.40");
            assertThat((BigDecimal) e.getProperties().get("requiredCommission")).isEqualByComparingTo("1.10");
        });
        verify(collector, never()).executeForSupplement(any(), any(), any(), any());
        assertThat(s.getCommissionStatus()).isNull();
    }

    @Test
    void creation_soldeCourtDevisesDifferentes_detailEtManqueDansLaDeviseDeComplement() {
        traveler.setCommissionPaymentMethodId("pm_1");
        CommissionSplit split = new CommissionSplit("EUR", new BigDecimal("1.10"), new BigDecimal("0.10"),
                new BigDecimal("1.00"), "XOF", new BigDecimal("656"), new BigDecimal("656"),
                new BigDecimal("0.10"), new BigDecimal("100"), false, new BigDecimal("722"));
        when(collector.plan(traveler.getId(), "EUR", "XOF", "XOF", new BigDecimal("1.10"))).thenReturn(split);

        Throwable thrown = catchThrowable(() -> rail.onCreated(s, request(CommissionSource.WALLET_FIRST, "XOF")));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getProperties()).containsKey("breakdown").containsEntry("hasCard", true);
            assertThat((BigDecimal) e.getProperties().get("shortfall")).isEqualByComparingTo("556");
        });
    }

    @Test
    void creation_deviseDeComplementInconnue_422() {
        Throwable thrown = catchThrowable(() -> rail.onCreated(s, request(CommissionSource.WALLET_FIRST, "ZZZ")));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("funding-currency-invalid"));
        verifyNoInteractions(collector);
    }

    @Test
    void creation_commissionNulle_rienAPrelever() {
        s.setCommissionAmount(BigDecimal.ZERO.setScale(2));

        rail.onCreated(s, request(CommissionSource.CARD, null));

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PROPOSED);
        assertThat(s.getCommissionStatus()).isNull();
        verifyNoInteractions(collector, gateway, supplements);
    }

    // ── Création : carte commission ──────────────────────────────────────────

    @Test
    void creation_carte_enAttenteDeCommission_sansAppelStripeSousLeVerrou() {
        traveler.setCommissionPaymentMethodId("pm_1");

        rail.onCreated(s, request(CommissionSource.CARD, null));

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.COMMISSION_PENDING);
        assertThat(s.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.CARD);
        assertThat(s.getCommissionStatus()).isNull();
        verifyNoInteractions(gateway, collector);
    }

    @Test
    void creation_carteSansCarteCommission_422() {
        assertThat(catchThrowable(() -> rail.onCreated(s, request(CommissionSource.CARD, null))))
                .isInstanceOf(CommissionMethodMissingException.class);
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PROPOSED);
    }

    @Test
    void apresCommit_demandeProposee_reponseInchangee() {
        BidSupplementResponse created = view("PROPOSED");
        assertThat(rail.afterCreateCommit(created)).isSameAs(created);
        verifyNoInteractions(gateway);
    }

    @Test
    void apresCommit_carteReussie_commissionEnregistree_paramsSansOffSessionNiBidId() throws Exception {
        pendingCard();
        when(gateway.createPaymentIntent(any(), eq("supplement_commission_" + s.getId() + "_v1")))
                .thenReturn(pi("pi_c", "succeeded"));

        BidSupplementResponse r = rail.afterCreateCommit(view("COMMISSION_PENDING"));

        verify(ledger).commissionCharged(s.getId(), "pi_c", "create");
        assertThat(r.commissionClientSecret()).isNull();
        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        verify(gateway).createPaymentIntent(params.capture(), anyString());
        PaymentIntentCreateParams p = params.getValue();
        assertThat(p.getAmount()).isEqualTo(110L);
        assertThat(p.getCurrency()).isEqualTo("eur");
        assertThat(p.getCustomer()).isEqualTo("cus_t");
        assertThat(p.getPaymentMethod()).isEqualTo("pm_1");
        assertThat(p.getConfirm()).isTrue();
        assertThat(p.getOffSession()).as("voyageur présent : 3DS possible").isNull();
        assertThat(p.getPaymentMethodTypes()).containsExactly("card");
        assertThat(p.getMetadata()).containsEntry("bid_supplement_id", s.getId().toString())
                .containsEntry("commission_purpose", "bid_supplement_commission")
                .containsEntry("scope", "BID_SUPPLEMENT_COMMISSION")
                .doesNotContainKeys("bid_id", "payment_id");
    }

    @Test
    void apresCommit_3ds_clientSecretRenvoye() throws Exception {
        pendingCard();
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("pi_c", "requires_action"));
        BidSupplementEntity withPi = copyPending();
        withPi.setCommissionPaymentIntentId("pi_c");
        withPi.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
        when(ledger.commissionRequiresAction(s.getId(), "pi_c")).thenReturn(withPi);

        BidSupplementResponse r = rail.afterCreateCommit(view("COMMISSION_PENDING"));

        assertThat(r.status()).isEqualTo("COMMISSION_PENDING");
        assertThat(r.commissionClientSecret()).isEqualTo("pi_c_secret");
        assertThat(r.commissionStatus()).isEqualTo("REQUIRES_3DS");
        assertThat(r.netAmount()).as("vue du voyageur").isEqualByComparingTo("20.00");
    }

    @Test
    void apresCommit_traitementEnCours_resteEnAttenteSansSecret() throws Exception {
        pendingCard();
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("pi_c", "processing"));
        when(ledger.commissionRequiresAction(s.getId(), "pi_c")).thenReturn(copyPending());

        BidSupplementResponse r = rail.afterCreateCommit(view("COMMISSION_PENDING"));

        assertThat(r.status()).isEqualTo("COMMISSION_PENDING");
        assertThat(r.commissionClientSecret()).isNull();
    }

    @Test
    void apresCommit_carteRefusee_demandeAnnulee422() throws Exception {
        pendingCard();
        when(gateway.createPaymentIntent(any(), anyString()))
                .thenThrow(new CardException("declined", null, "card_declined", null, null, null, null, null));

        Throwable thrown = catchThrowable(() -> rail.afterCreateCommit(view("COMMISSION_PENDING")));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo("supplement-commission-card-declined");
            assertThat(e.getProperties()).containsEntry("declineCode", "card_declined");
        });
        verify(ledger).commissionFailed(s.getId(), null, "card-declined:card_declined");
    }

    @Test
    void apresCommit_statutInattendu_echec422() throws Exception {
        pendingCard();
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("pi_c", "requires_payment_method"));

        assertThat(catchThrowable(() -> rail.afterCreateCommit(view("COMMISSION_PENDING"))))
                .isInstanceOf(YadonyBusinessException.class);
        verify(ledger).commissionFailed(s.getId(), "pi_c", "status-requires_payment_method");
    }

    @Test
    void apresCommit_erreurReseau_503SansCloreLaDemande_puisRepriseParConfirm() throws Exception {
        pendingCard();
        when(users.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(supplements.findByIdAndBidId(s.getId(), s.getBidId())).thenReturn(Optional.of(s));
        String key = "supplement_commission_" + s.getId() + "_v1";
        when(gateway.createPaymentIntent(any(), eq(key))).thenThrow(new ApiConnectionException("timeout"))
                .thenReturn(pi("pi_c", "requires_action"));
        BidSupplementEntity withPi = copyPending();
        withPi.setCommissionPaymentIntentId("pi_c");
        withPi.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
        when(ledger.commissionRequiresAction(s.getId(), "pi_c")).thenReturn(withPi);

        Throwable thrown = catchThrowable(() -> rail.afterCreateCommit(view("COMMISSION_PENDING")));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StripeUnavailable.CODE));
        verify(ledger, never()).commissionFailed(any(), any(), any());
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.COMMISSION_PENDING);

        // Reprise : la demande en attente sans PaymentIntent relance le débit avec la MÊME clé.
        BidSupplementResponse resumed = rail.confirmCommission(s.getBidId(), s.getId(), "uid-traveler");

        assertThat(resumed.commissionClientSecret()).isEqualTo("pi_c_secret");
        verify(gateway, org.mockito.Mockito.times(2)).createPaymentIntent(any(), eq(key));
        verify(gateway, never()).retrievePaymentIntent(anyString());
    }

    @Test
    void confirmer_enAttenteSansPaymentIntentHorsCarte_vueSansRelance() {
        when(users.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(supplements.findByIdAndBidId(s.getId(), s.getBidId())).thenReturn(Optional.of(s));
        s.setStatus(BidSupplementStatus.COMMISSION_PENDING);

        assertThat(rail.confirmCommission(s.getBidId(), s.getId(), "uid-traveler").status())
                .isEqualTo("COMMISSION_PENDING");
        s.setCommissionChargedVia(CommissionChargedVia.CARD);
        s.setCommissionStatus(CommissionStatus.FAILED);
        rail.confirmCommission(s.getBidId(), s.getId(), "uid-traveler");
        verifyNoInteractions(gateway);
    }

    @Test
    void apresCommit_3dsMaisDemandeCloseEntreTemps_paymentIntentAbandonnee() throws Exception {
        pendingCard();
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("pi_c", "requires_action"));
        BidSupplementEntity closed = copyPending();
        closed.setStatus(BidSupplementStatus.WITHDRAWN);
        closed.setCommissionPaymentIntentId("pi_c");
        when(ledger.commissionRequiresAction(s.getId(), "pi_c")).thenReturn(closed);

        BidSupplementResponse r = rail.afterCreateCommit(view("COMMISSION_PENDING"));

        verify(refunds).settle(s.getId());
        assertThat(r.commissionClientSecret()).as("aucune 3DS à présenter pour une demande retirée").isNull();
    }

    @Test
    void webhookEchec_demandeFermee_paymentIntentAnnuleeAuMieux() throws Exception {
        when(ledger.commissionFailed(s.getId(), "pi_c", "payment_failed:authentication_failed")).thenReturn(true);
        when(ledger.commissionFailed(s.getId(), "pi_d", "payment_failed:x")).thenReturn(true);
        lenient().when(gateway.cancelPaymentIntent("pi_d")).thenThrow(new InvalidRequestException("no", null, "req", null, 400,
                null));

        rail.onCommissionWebhook("payment_intent.payment_failed", s.getId(), "pi_c", "authentication_failed");
        rail.onCommissionWebhook("payment_intent.payment_failed", s.getId(), "pi_d", "x");
        rail.onCommissionWebhook("payment_intent.payment_failed", s.getId(), null, "x");

        verify(gateway).cancelPaymentIntent("pi_c");
        verify(gateway).cancelPaymentIntent("pi_d");
    }

    @Test
    void apresCommit_erreurStripe_502() throws Exception {
        pendingCard();
        when(gateway.createPaymentIntent(any(), anyString()))
                .thenThrow(new InvalidRequestException("bad", null, "req", null, 400, null));

        assertThat(catchThrowable(() -> rail.afterCreateCommit(view("COMMISSION_PENDING"))))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("supplement-commission-charge-failed"));
    }

    @Test
    void apresCommit_carteDetacheeEntreTemps_echec() {
        pendingCard();
        traveler.setCommissionPaymentMethodId(null);

        assertThat(catchThrowable(() -> rail.afterCreateCommit(view("COMMISSION_PENDING"))))
                .isInstanceOf(CommissionMethodMissingException.class);
        verify(ledger).commissionFailed(s.getId(), null, "no-commission-card");
    }

    @Test
    void apresCommit_paymentIntentDejaCreee_rienDeNeuf() {
        pendingCard();
        s.setCommissionPaymentIntentId("pi_old");

        rail.afterCreateCommit(view("COMMISSION_PENDING"));

        verifyNoInteractions(gateway);
    }

    // ── Confirmation après 3DS ───────────────────────────────────────────────

    @Test
    void confirmer_reussie_proposee() throws Exception {
        confirmable();
        when(gateway.retrievePaymentIntent("pi_c")).thenReturn(pi("pi_c", "succeeded"));
        BidSupplementEntity proposed = copyPending();
        proposed.setStatus(BidSupplementStatus.PROPOSED);
        proposed.setCommissionStatus(CommissionStatus.CHARGED);
        when(ledger.find(s.getId())).thenReturn(Optional.of(proposed));

        BidSupplementResponse r = rail.confirmCommission(s.getBidId(), s.getId(), "uid-traveler");

        verify(ledger).commissionCharged(s.getId(), "pi_c", "confirm");
        assertThat(r.status()).isEqualTo("PROPOSED");
        assertThat(r.commissionStatus()).isEqualTo("CHARGED");
    }

    @Test
    void confirmer_encore3ds_secretRendu() throws Exception {
        confirmable();
        when(gateway.retrievePaymentIntent("pi_c")).thenReturn(pi("pi_c", "requires_action"));
        when(ledger.commissionRequiresAction(s.getId(), "pi_c")).thenReturn(s);

        assertThat(rail.confirmCommission(s.getBidId(), s.getId(), "uid-traveler").commissionClientSecret())
                .isEqualTo("pi_c_secret");
    }

    @Test
    void confirmer_3dsEchouee_annulee422() throws Exception {
        confirmable();
        when(gateway.retrievePaymentIntent("pi_c")).thenReturn(pi("pi_c", "canceled"));

        assertThat(catchThrowable(() -> rail.confirmCommission(s.getBidId(), s.getId(), "uid-traveler")))
                .isInstanceOf(YadonyBusinessException.class);
        verify(ledger).commissionFailed(s.getId(), "pi_c", "status-canceled");
    }

    @Test
    void confirmer_erreursStripe() throws Exception {
        confirmable();
        when(gateway.retrievePaymentIntent("pi_c")).thenThrow(new ApiConnectionException("timeout"))
                .thenThrow(new InvalidRequestException("bad", null, "req", null, 400, null));

        assertThat(catchThrowable(() -> rail.confirmCommission(s.getBidId(), s.getId(), "uid-traveler")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StripeUnavailable.CODE));
        assertThat(catchThrowable(() -> rail.confirmCommission(s.getBidId(), s.getId(), "uid-traveler")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("stripe-error"));
    }

    @Test
    void confirmer_dejaProposee_vueSansAppelStripe() {
        when(users.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(supplements.findByIdAndBidId(s.getId(), s.getBidId())).thenReturn(Optional.of(s));

        assertThat(rail.confirmCommission(s.getBidId(), s.getId(), "uid-traveler").status()).isEqualTo("PROPOSED");
        verifyNoInteractions(gateway);
    }

    @Test
    void confirmer_gardes() {
        when(users.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        UserEntity other = new UserEntity();
        setId(other, UUID.randomUUID());
        when(users.findByFirebaseUid("uid-other")).thenReturn(Optional.of(other));
        when(users.findByFirebaseUid("uid-unknown")).thenReturn(Optional.empty());
        when(supplements.findByIdAndBidId(s.getId(), s.getBidId())).thenReturn(Optional.of(s));
        UUID unknown = UUID.randomUUID();
        when(supplements.findByIdAndBidId(unknown, s.getBidId())).thenReturn(Optional.empty());

        assertThat(code(() -> rail.confirmCommission(s.getBidId(), s.getId(), "uid-unknown"))).isEqualTo("user-not-found");
        assertThat(code(() -> rail.confirmCommission(s.getBidId(), unknown, "uid-traveler")))
                .isEqualTo("supplement-not-found");
        assertThat(code(() -> rail.confirmCommission(s.getBidId(), s.getId(), "uid-other"))).isEqualTo("forbidden");
        s.setRail(BidSupplementRail.STRIPE);
        assertThat(code(() -> rail.confirmCommission(s.getBidId(), s.getId(), "uid-traveler")))
                .isEqualTo("supplement-rail-unsupported");
    }

    // ── Webhooks et acceptation ──────────────────────────────────────────────

    @Test
    void webhookCommission_aiguillageParType() {
        rail.onCommissionWebhook("payment_intent.succeeded", s.getId(), "pi_c", "unknown");
        rail.onCommissionWebhook("payment_intent.payment_failed", s.getId(), "pi_c", "authentication_failed");
        rail.onCommissionWebhook("payment_intent.canceled", s.getId(), "pi_c", "unknown");
        rail.onCommissionWebhook("payment_intent.created", s.getId(), "pi_c", "unknown");

        verify(ledger).commissionCharged(s.getId(), "pi_c", "webhook");
        verify(ledger).commissionFailed(s.getId(), "pi_c", "payment_failed:authentication_failed");
        verify(ledger).commissionFailed(s.getId(), "pi_c", "canceled");
    }

    @Test
    void acceptation_payeeEnMainPropre_vueExpediteurSansNet() {
        SupplementAcceptResponse r = rail.accept(s, new UserEntity());

        verify(ledger).acceptCash(s);
        assertThat(r.rail()).isEqualTo("CASH");
        assertThat(r.clientSecret()).isNull();
        assertThat(r.amount()).isEqualByComparingTo("21.10");
        assertThat(r.currency()).isEqualTo("EUR");
        assertThat(r.supplement().netAmount()).isNull();
        assertThat(r.supplement().commissionAmount()).isNull();
        verifyNoInteractions(gateway, collector);
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private void pendingCard() {
        traveler.setCommissionPaymentMethodId("pm_1");
        s.setStatus(BidSupplementStatus.COMMISSION_PENDING);
        s.setCommissionChargedVia(CommissionChargedVia.CARD);
        lenient().when(ledger.find(s.getId())).thenReturn(Optional.of(s));
    }

    private void confirmable() {
        pendingCard();
        s.setCommissionPaymentIntentId("pi_c");
        s.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
        when(users.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(supplements.findByIdAndBidId(s.getId(), s.getBidId())).thenReturn(Optional.of(s));
    }

    private BidSupplementEntity copyPending() {
        BidSupplementEntity c = new BidSupplementEntity();
        setId(c, s.getId());
        c.setBidId(s.getBidId());
        c.setTravelerId(s.getTravelerId());
        c.setSenderId(s.getSenderId());
        c.setRail(BidSupplementRail.CASH);
        c.setStatus(BidSupplementStatus.COMMISSION_PENDING);
        c.setCurrency("EUR");
        c.setNetAmount(s.getNetAmount());
        c.setCommissionAmount(s.getCommissionAmount());
        c.setGrossAmount(s.getGrossAmount());
        c.setReasonCode(s.getReasonCode());
        c.setPhotoKey(s.getPhotoKey());
        c.setExpiresAt(s.getExpiresAt());
        c.setCommissionChargedVia(CommissionChargedVia.CARD);
        return c;
    }

    private BidSupplementResponse view(String status) {
        return new BidSupplementResponse(s.getId(), status, "CASH", "EUR", s.getNetAmount(), s.getCommissionAmount(),
                s.getGrossAmount(), "GRID_ITEM", null, null, null, null, null, null, true);
    }

    private static PaymentIntentView pi(String id, String status) {
        return new PaymentIntentView(id, status, 110L, "eur", id + "_secret", null, List.of("card"));
    }

    private static WalletAccountEntity wallet(String currency, String balance) {
        WalletAccountEntity w = new WalletAccountEntity();
        w.setCurrency(currency);
        w.setBalance(new BigDecimal(balance));
        return w;
    }

    private static String code(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        Throwable t = catchThrowable(call);
        assertThat(t).isInstanceOf(YadonyBusinessException.class);
        return ((YadonyBusinessException) t).getErrorCode();
    }
}
