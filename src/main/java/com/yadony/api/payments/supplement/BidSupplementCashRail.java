package com.yadony.api.payments.supplement;

import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
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
import com.yadony.api.payments.cash.dto.CommissionShortfallDto;
import com.yadony.api.payments.cash.exception.CommissionMethodMissingException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import com.yadony.api.payments.supplement.dto.CreateSupplementRequest;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import com.yadony.api.payments.wallet.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Rail espèces du supplément (plan §4.4, décision du propriétaire n° 1) : le complément se remet
 * en main propre, l'app ne prélève que la COMMISSION du supplément, AU VOYAGEUR, au moment de sa
 * demande (il est dans l'app, une 3DS est possible). Même choix que l'acceptation espèces
 * ({@code POST /bids/{id}/accept-with-commission}) :
 * <ul>
 *   <li>{@code WALLET_FIRST} (défaut) : portefeuille de la devise du colis d'abord, complément sur la
 *       devise active ou {@code fundingCurrency}, tout ou rien, dans la transaction de la création.
 *       Solde court : 422 {@code supplement-commission-unfunded} avec les portefeuilles qui
 *       couvriraient ({@code alternatives}), et rien n'est créé ;</li>
 *   <li>{@code CARD} : carte commission du voyageur, débitée APRÈS le commit de la création (jamais
 *       d'appel Stripe sous le verrou du colis). La demande attend en {@code COMMISSION_PENDING}
 *       (l'expéditeur ne la voit pas) ; une 3DS renvoie {@code commissionClientSecret}, puis
 *       {@code POST …/commission/confirm}.</li>
 * </ul>
 * La commission est rendue si la demande n'aboutit pas ({@link BidSupplementCashRefunds}). Ni bon
 * de parrainage, ni code promo, ni {@code bids.commission_*} : la commission principale du colis
 * reste intacte.
 */
@Component
public class BidSupplementCashRail implements BidSupplementRailHandler {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementCashRail.class);

    /** Métadonnée de routage de la PaymentIntent de commission ({@code CashCommissionWebhookHandler} attend {@code cash_bid}). */
    public static final String COMMISSION_PURPOSE = "bid_supplement_commission";

    static final String SCOPE = "BID_SUPPLEMENT_COMMISSION";

    private final WalletCommissionCollector collector;
    private final WalletService walletService;
    private final ActiveCurrencyResolver activeCurrencyResolver;
    private final StripeSupplementGateway gateway;
    private final BidSupplementCashLedger ledger;
    private final BidSupplementRepository supplements;
    private final UserRepository users;
    private final StorageService storage;
    private final BidSupplementWindow window;

    public BidSupplementCashRail(WalletCommissionCollector collector, WalletService walletService,
                                 ActiveCurrencyResolver activeCurrencyResolver, StripeSupplementGateway gateway,
                                 BidSupplementCashLedger ledger, BidSupplementRepository supplements,
                                 UserRepository users, StorageService storage, BidSupplementWindow window) {
        this.collector = collector;
        this.walletService = walletService;
        this.activeCurrencyResolver = activeCurrencyResolver;
        this.gateway = gateway;
        this.ledger = ledger;
        this.supplements = supplements;
        this.users = users;
        this.storage = storage;
        this.window = window;
    }

    @Override
    public BidSupplementRail rail() {
        return BidSupplementRail.CASH;
    }

    /**
     * De quoi payer une commission : une carte commission enregistrée, ou un portefeuille non
     * gelé au solde positif. Le montant exact n'est connu qu'à la demande, qui fait le contrôle.
     */
    @Override
    public boolean canRequest(UserEntity traveler, String currency) {
        if (traveler == null) {
            return false;
        }
        if (traveler.getCommissionPaymentMethodId() != null) {
            return true;
        }
        return walletService.getAllBalances(traveler.getId()).stream()
                .anyMatch(w -> w.getBalance() != null && w.getBalance().signum() > 0
                        && !walletService.isFrozen(traveler.getId(), w.getCurrency()));
    }

    @Override
    public void onCreated(BidSupplementEntity s, CreateSupplementRequest request) {
        if (s.getCommissionAmount() == null || s.getCommissionAmount().signum() == 0) {
            // Taux nul : rien à prélever, la demande part telle quelle.
            return;
        }
        if (request.commissionSourceOrDefault() == CommissionSource.CARD) {
            UserEntity traveler = users.findById(s.getTravelerId()).orElseThrow();
            if (traveler.getCommissionPaymentMethodId() == null) {
                throw new CommissionMethodMissingException();
            }
            s.setStatus(BidSupplementStatus.COMMISSION_PENDING);
            s.setCommissionChargedVia(CommissionChargedVia.CARD);
            supplements.save(s);
            return;
        }
        chargeWallet(s, request.fundingCurrency());
    }

    /**
     * Solde : portefeuille du colis puis complément, sous verrou ({@code plan} en
     * {@code MANDATORY}), dans la transaction de la création. Tout ou rien : un solde court lève
     * le 422 et annule la création.
     */
    private void chargeWallet(BidSupplementEntity s, String fundingCurrency) {
        String bidCurrency = normalize(s.getCurrency());
        String complement = complementCurrency(s.getTravelerId(), fundingCurrency);
        BigDecimal commission = s.getCommissionAmount();
        CommissionSplit split = collector.plan(s.getTravelerId(), bidCurrency, complement, commission);
        if (!split.covered()) {
            throw unfunded(s.getTravelerId(), split, bidCurrency, complement, commission);
        }
        collector.executeForSupplement(split, s.getTravelerId(), s.getBidId(), s.getId());
        s.setCommissionChargedVia(CommissionChargedVia.WALLET);
        s.setCommissionStatus(CommissionStatus.CHARGED);
        supplements.save(s);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("fromBidWallet", split.fromBidWallet().toPlainString());
        if (split.remainingBid().signum() > 0) {
            extra.put("remainingActive", split.remainingActive().toPlainString());
            extra.put("activeCurrency", split.activeCurrency());
        }
        ledger.audit(s, "BID_SUPPLEMENT_COMMISSION_CHARGED_WALLET", s.getTravelerId(), extra);
    }

    /**
     * 422 {@code supplement-commission-unfunded} : mêmes champs que l'{@code INSUFFICIENT_WALLET}
     * de l'acceptation espèces ({@code availableBalance}, {@code requiredCommission},
     * {@code currency} dans la devise de complément, {@code breakdown} si les devises diffèrent,
     * {@code bidCurrency}, {@code alternatives}, {@code hasCard}), plus {@code shortfall} : ce qui
     * manque dans {@code currency}.
     */
    private YadonyBusinessException unfunded(UUID travelerId, CommissionSplit split, String bidCurrency,
                                             String complement, BigDecimal commission) {
        boolean hasCard = users.findById(travelerId).map(u -> u.getCommissionPaymentMethodId() != null).orElse(false);
        BigDecimal missing = split.sameCurrency()
                ? commission.subtract(split.activeBalance())
                : split.remainingActive().subtract(split.activeBalance());
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("availableBalance", split.activeBalance());
        props.put("requiredCommission", split.commissionInActive());
        props.put("shortfall", missing.max(BigDecimal.ZERO));
        props.put("hasCard", hasCard);
        props.put("currency", complement);
        CommissionShortfallDto breakdown = CommissionShortfallDto.from(split);
        if (breakdown != null) {
            props.put("breakdown", breakdown);
        }
        props.put("bidCurrency", bidCurrency);
        props.put("alternatives", collector.alternatives(travelerId, bidCurrency, complement, commission));
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "supplement-commission-unfunded",
                "Supplement Commission Unfunded",
                "Votre solde ne couvre pas la commission de ce supplément : rechargez-le ou payez par carte", props);
    }

    /** Devise de complément : celle choisie (code supporté, sinon 422), la devise active sinon. */
    private String complementCurrency(UUID travelerId, String fundingCurrency) {
        if (fundingCurrency == null || fundingCurrency.isBlank()) {
            return normalize(activeCurrencyResolver.resolve(travelerId));
        }
        String code = normalize(fundingCurrency);
        if (SupportedCurrency.fromCode(code) == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "funding-currency-invalid",
                    "Invalid Funding Currency", "Devise de prélèvement inconnue");
        }
        return code;
    }

    /** Après le commit de la création : la carte commission est débitée (hors verrou du colis). */
    @Override
    public BidSupplementResponse afterCreateCommit(BidSupplementResponse created) {
        if (!BidSupplementStatus.COMMISSION_PENDING.name().equals(created.status())) {
            return created;
        }
        return chargeCard(created.id());
    }

    /**
     * Débit de la carte commission du voyageur, présent dans l'app : confirmation immédiate SANS
     * {@code off_session}, pour qu'une 3DS revienne en {@code requires_action} (et non en refus
     * {@code authentication_required}). Clé {@code supplement_commission_<id>_v1} : une seule
     * tentative par demande, une carte refusée clôt la demande.
     */
    BidSupplementResponse chargeCard(UUID supplementId) {
        BidSupplementEntity s = ledger.find(supplementId).orElseThrow(BidSupplementCashRail::notFound);
        if (s.getStatus() != BidSupplementStatus.COMMISSION_PENDING
                || s.getCommissionChargedVia() != CommissionChargedVia.CARD || s.getCommissionStatus() != null
                || s.getCommissionPaymentIntentId() != null) {
            return view(s);
        }
        UserEntity traveler = users.findById(s.getTravelerId()).orElseThrow();
        if (traveler.getCommissionPaymentMethodId() == null) {
            // Carte détachée entre la création et le débit.
            ledger.commissionFailed(supplementId, null, "no-commission-card");
            throw new CommissionMethodMissingException();
        }
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(s.getCurrency());
        CurrencyAmount amount = CurrencyAmount.of(s.getCommissionAmount(), currency);
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amount.minor())
                .setCurrency(currency.code())
                .setCustomer(traveler.getStripeCustomerId())
                .setPaymentMethod(traveler.getCommissionPaymentMethodId())
                .addPaymentMethodType("card")
                .setConfirm(true)
                .setDescription("Commission supplément " + s.getId())
                // Ni bid_id ni payment_id : aucun traitement du colis ne doit la prendre pour la sienne.
                .putMetadata("bid_supplement_id", s.getId().toString())
                .putMetadata("commission_purpose", COMMISSION_PURPOSE)
                .putMetadata("scope", SCOPE)
                .putMetadata("supplement_bid_id", s.getBidId().toString())
                .build();
        PaymentIntentView pi;
        try {
            pi = gateway.createPaymentIntent(params, commissionKey(s.getId()));
        } catch (CardException e) {
            ledger.commissionFailed(supplementId, null, "card-declined:" + e.getCode());
            throw cardDeclined(e.getCode());
        } catch (StripeException e) {
            // Issue inconnue : la demande reste en attente ; le webhook (ou le filet à 30 min) tranche.
            log.error("Commission carte du supplément {} : erreur Stripe", supplementId, e);
            if (StripeUnavailable.isNetworkFailure(e)) {
                throw StripeUnavailable.exception();
            }
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "supplement-commission-charge-failed",
                    "Commission Charge Failed", "Le débit de la commission n'a pas pu aboutir. Veuillez réessayer.");
        }
        return applyPaymentIntent(supplementId, pi, "create");
    }

    /**
     * Après la 3DS ({@code POST …/commission/confirm}) : relit la PaymentIntent de commission et
     * enregistre son issue. Idempotent ; vue du voyageur.
     */
    public BidSupplementResponse confirmCommission(UUID bidId, UUID supplementId, String firebaseUid) {
        UserEntity traveler = users.findByFirebaseUid(firebaseUid).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        BidSupplementEntity s = supplements.findByIdAndBidId(supplementId, bidId)
                .orElseThrow(BidSupplementCashRail::notFound);
        if (!traveler.getId().equals(s.getTravelerId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas partie à ce colis");
        }
        if (s.getRail() != BidSupplementRail.CASH) {
            throw BidSupplementRail.unsupported();
        }
        if (s.getStatus() != BidSupplementStatus.COMMISSION_PENDING || s.getCommissionPaymentIntentId() == null) {
            return view(s);
        }
        PaymentIntentView pi;
        try {
            pi = gateway.retrievePaymentIntent(s.getCommissionPaymentIntentId());
        } catch (StripeException e) {
            if (StripeUnavailable.isNetworkFailure(e)) {
                throw StripeUnavailable.exception();
            }
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "stripe-error", "Stripe Error",
                    "Impossible de vérifier le paiement auprès de Stripe");
        }
        return applyPaymentIntent(supplementId, pi, "confirm");
    }

    /** Issue d'une PaymentIntent de commission (création, confirmation). */
    private BidSupplementResponse applyPaymentIntent(UUID supplementId, PaymentIntentView pi, String source) {
        String status = pi.status() == null ? "" : pi.status();
        switch (status) {
            case "succeeded" -> {
                ledger.commissionCharged(supplementId, pi.id(), source);
                return view(reload(supplementId));
            }
            case "requires_action", "requires_confirmation" -> {
                BidSupplementEntity s = ledger.commissionRequiresAction(supplementId, pi.id());
                BidSupplementResponse view = view(s);
                return s.getStatus() == BidSupplementStatus.COMMISSION_PENDING
                        ? view.withCommissionClientSecret(pi.clientSecret()) : view;
            }
            case "processing" -> {
                // Rare pour une carte : le webhook tranchera. La demande reste en attente.
                return view(ledger.commissionRequiresAction(supplementId, pi.id()));
            }
            default -> {
                // requires_payment_method (refus ou 3DS échouée), canceled : rien n'a été prélevé.
                ledger.commissionFailed(supplementId, pi.id(), "status-" + status);
                throw cardDeclined(null);
            }
        }
    }

    /** Webhook d'une PaymentIntent de commission (routé par {@link BidSupplementStripeWebhookRouter}). */
    public void onCommissionWebhook(String type, UUID supplementId, String paymentIntentId, String failureCode) {
        switch (type) {
            case "payment_intent.succeeded" -> ledger.commissionCharged(supplementId, paymentIntentId, "webhook");
            case "payment_intent.payment_failed" -> ledger.commissionFailed(supplementId, paymentIntentId,
                    "payment_failed:" + failureCode);
            case "payment_intent.canceled" -> ledger.commissionFailed(supplementId, paymentIntentId, "canceled");
            default -> log.debug("Webhook {} de la commission du supplément {} : rien à faire", type, supplementId);
        }
    }

    /**
     * Acceptation par l'expéditeur : il s'engage à remettre le complément en main propre.
     * {@code PAID} tout de suite, aucun versement ; le voyageur est prévenu.
     */
    @Override
    public SupplementAcceptResponse accept(BidSupplementEntity supplement, UserEntity sender) {
        ledger.acceptCash(supplement);
        return new SupplementAcceptResponse(BidSupplementViews.of(supplement, true, storage, window.now()),
                BidSupplementRail.CASH.name(), null, null, supplement.getGrossAmount(), supplement.getCurrency());
    }

    static String commissionKey(UUID supplementId) {
        return "supplement_commission_" + supplementId + "_v1";
    }

    private BidSupplementEntity reload(UUID supplementId) {
        return ledger.find(supplementId).orElseThrow(BidSupplementCashRail::notFound);
    }

    private BidSupplementResponse view(BidSupplementEntity s) {
        return BidSupplementViews.of(s, false, storage, window.now());
    }

    private static YadonyBusinessException cardDeclined(String declineCode) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (declineCode != null) {
            props.put("declineCode", declineCode);
        }
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "supplement-commission-card-declined",
                "Commission Card Declined",
                "Votre carte commission a été refusée : rien n'a été prélevé et la demande est annulée", props);
    }

    private static YadonyBusinessException notFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "supplement-not-found", "Supplement Not Found",
                "Demande de supplément introuvable");
    }

    private static String normalize(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    }
}
