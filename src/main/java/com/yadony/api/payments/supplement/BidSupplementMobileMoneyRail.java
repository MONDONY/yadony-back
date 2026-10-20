package com.yadony.api.payments.supplement;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.Msisdn;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.TransientLockRetry;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.dto.BidSupplementResponse;
import com.yadony.api.payments.PaymentBusy;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutAccounts;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayerProvidersResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPaymentStatusResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersResponse;
import com.yadony.api.payments.pawapay.PawapayErrors;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Rail mobile money (pawaPay) du supplément (plan §4.3), calqué sur le paiement mobile money du
 * colis ({@code MobileMoneyBidPaymentService}) : l'acceptation passe la demande en
 * {@code PAYMENT_PENDING}, l'app ouvre l'écran d'attente qui appelle {@link #initiate} (push PIN ou
 * redirection Wave) puis relit {@link #status}. Le dépôt porte le purpose {@code BID_SUPPLEMENT},
 * {@code bid_supplement_id} et l'expéditeur en {@code user_id}, jamais {@code payment_id} : au plus
 * un dépôt vivant ou abouti par supplément (index {@code uq_pawapay_ops_live_per_supplement}).
 *
 * <p>Aucun appel pawaPay sous verrou : la demande est contrôlée et verrouillée dans une
 * transaction courte ({@link BidSupplementMobileMoneyLedger#prepareDeposit}), puis le numéro et
 * l'opérateur sont résolus et le dépôt soumis hors transaction. Deux initiations concurrentes sont
 * départagées par l'index : la perdante relit le dépôt de la gagnante.
 */
@Component
public class BidSupplementMobileMoneyRail implements BidSupplementRailHandler {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementMobileMoneyRail.class);

    private final BidSupplementMobileMoneyLedger ledger;
    private final BidSupplementRepository supplements;
    private final BidRepository bids;
    private final AnnouncementRepository announcements;
    private final UserRepository users;
    private final PawapayOperationService operations;
    private final PawapaySubmissionService submission;
    private final PawapayProviderResolver providers;
    private final PawapayProperties pawapay;
    private final StorageService storage;
    private final BidSupplementWindow window;
    private final MobileMoneyPayoutAccounts payoutAccounts;

    public BidSupplementMobileMoneyRail(BidSupplementMobileMoneyLedger ledger, BidSupplementRepository supplements,
                                        BidRepository bids, AnnouncementRepository announcements, UserRepository users,
                                        PawapayOperationService operations, PawapaySubmissionService submission,
                                        PawapayProviderResolver providers, PawapayProperties pawapay,
                                        StorageService storage, BidSupplementWindow window,
                                        MobileMoneyPayoutAccounts payoutAccounts) {
        this.ledger = ledger;
        this.supplements = supplements;
        this.bids = bids;
        this.announcements = announcements;
        this.users = users;
        this.operations = operations;
        this.submission = submission;
        this.providers = providers;
        this.pawapay = pawapay;
        this.storage = storage;
        this.window = window;
        this.payoutAccounts = payoutAccounts;
    }

    @Override
    public BidSupplementRail rail() {
        return BidSupplementRail.PAWAPAY;
    }

    /** {@code PAYMENT_PENDING} ; l'app ouvre l'écran d'attente, qui déclenche le dépôt. */
    @Override
    public SupplementAcceptResponse accept(BidSupplementEntity supplement, UserEntity sender) {
        ledger.startPayment(supplement);
        BidSupplementResponse view = BidSupplementViews.of(supplement, true, storage, window);
        return new SupplementAcceptResponse(view, BidSupplementRail.PAWAPAY.name(), null, null,
                supplement.getGrossAmount(), supplement.getCurrency());
    }

    /**
     * Un dépôt ne s'annule pas : une demande échue n'est close qu'une fois son dépôt tranché.
     * L'attente est bornée par l'échéance de dépôt pawaPay ({@code deposit-deadline-minutes}) : au-delà,
     * la demande expire et un dépôt qui aboutirait encore serait un paiement tardif, donc rendu.
     */
    @Override
    public boolean mayExpire(BidSupplementEntity supplement) {
        Optional<PawapayOperationEntity> deposit = operations.findLiveForSupplement(supplement.getId(),
                PawapayOperationKind.DEPOSIT);
        if (deposit.isEmpty()) {
            return true;
        }
        PawapayOperationEntity op = deposit.get();
        if (op.getStatus() == PawapayOperationStatus.COMPLETED) {
            // Abouti mais pas encore appliqué (écouteur en route) : on le laisse appliquer.
            return false;
        }
        Duration deadline = Duration.ofMinutes(Math.max(1, pawapay.depositDeadlineMinutes()));
        return op.getCreatedAt() != null && op.getCreatedAt().plus(deadline).isBefore(window.now());
    }

    // ── Initiation du dépôt ──────────────────────────────────────────────────

    /**
     * {@code POST /bids/{bidId}/supplements/{id}/mobile-money/initiate} : push PIN (ou redirection
     * Wave) du brut sur le numéro de l'expéditeur. Idempotent : un dépôt vivant ou abouti est relu,
     * jamais doublé. L'interrupteur pawaPay et l'échéance ne bloquent qu'un NOUVEAU dépôt.
     */
    public MobileMoneyPaymentStatusResponse initiate(UUID bidId, UUID supplementId, String firebaseUid,
                                                     String phoneOverride, String provider) {
        UserEntity sender = user(firebaseUid);
        BidSupplementMobileMoneyLedger.Claim claim = TransientLockRetry.run("supplement mm initiate " + supplementId,
                () -> ledger.prepareDeposit(bidId, supplementId, sender.getId()), conflict -> PaymentBusy.exception());
        if (BidSupplementStatus.PAID_STATES.contains(claim.status())) {
            return status(supplementId);
        }
        if (operations.findLiveForSupplement(supplementId, PawapayOperationKind.DEPOSIT).isPresent()) {
            return status(supplementId);
        }
        if (!pawapay.enabled()) {
            throw PawapayErrors.disabled();
        }
        BidSupplementMobileMoneyLedger.requireNotExpired(claim.expiresAt(), window.now());

        BidEntity bid = bids.findById(bidId).orElseThrow(BidSupplementMobileMoneyLedger::notFound);
        UserEntity traveler = traveler(bid);
        String msisdn = payerMsisdn(sender, bid, phoneOverride, claim.currency());
        PawapayProviderResolver.Resolved resolved;
        try {
            resolved = providers.resolve(msisdn, PawapayOperationKind.DEPOSIT, claim.currency(), provider,
                    "l'initiation du dépôt du supplément " + supplementId);
        } catch (PawapayProviderResolver.UnsupportedNumberException e) {
            throw payerUnsupported(payerReason(e, claim.currency()));
        }
        // Même couplage que le colis : l'expéditeur paie sur un opérateur (code exact) où le voyageur
        // a un compte de versement dans la devise du supplément.
        payoutAccounts.payerRefusal(traveler.getId(), resolved.provider(), resolved.providerLabel(),
                resolved.countryAlpha2(), claim.currency()).ifPresent(detail -> {
            throw payerUnsupported(detail);
        });
        PawapayProviderConfig.Limits limits = resolved.config().deposit();
        if (limits != null && (limits.minAmount() != null && claim.gross().compareTo(limits.minAmount()) < 0
                || limits.maxAmount() != null && claim.gross().compareTo(limits.maxAmount()) > 0)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-amount-out-of-range",
                    "Mobile Money Amount Out Of Range", "Montant hors des limites de " + resolved.providerLabel() + ".");
        }
        String successfulUrl = null;
        String failedUrl = null;
        if (resolved.config().isRedirectDeposit()) {
            String base = returnUrl(bidId, supplementId);
            successfulUrl = base + "&outcome=success";
            failedUrl = base + "&outcome=failed";
        }

        PawapayOperationEntity op;
        try {
            op = submission.submitSupplementDeposit(supplementId, sender.getId(), resolved.msisdn(),
                    resolved.provider(), resolved.countryAlpha2(), claim.gross(), claim.currency(),
                    clientReference(supplementId), successfulUrl, failedUrl);
        } catch (YadonyBusinessException e) {
            if ("mobile-money-operation-in-progress".equals(e.getErrorCode())) {
                // Course entre deux initiations : l'index a tranché, on relit le dépôt gagnant.
                return status(supplementId);
            }
            throw e;
        }
        ledger.auditOnly(supplementId, "BID_SUPPLEMENT_MM_DEPOSIT_SUBMITTED", Map.of(
                "operationId", op.getId().toString(), "provider", op.getProvider(),
                "msisdnMasked", op.getMsisdnMasked(), "operationStatus", op.getStatus().name()));
        if (op.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-deposit-rejected",
                    "Deposit Rejected", "Paiement refusé par l'opérateur : "
                    + (op.getFailureMessage() != null ? op.getFailureMessage() : op.getFailureCode()));
        }
        log.info("Dépôt mobile money {} soumis pour le supplément {}", op.getId(), supplementId);
        return status(supplementId);
    }

    /** Page de rebond Wave (HTTPS) : {@code PawapayReturnController} renvoie vers l'écran d'attente du supplément. */
    String returnUrl(UUID bidId, UUID supplementId) {
        return pawapay.returnBaseUrl() + "/api/v1/pawapay/return/" + bidId + "?supplement=" + supplementId;
    }

    static String clientReference(UUID supplementId) {
        return "supp-" + supplementId;
    }

    /**
     * Numéro payeur : saisi sur l'écran de paiement, sinon le compte mobile money vérifié de
     * l'expéditeur dans la devise, sinon le numéro qui a payé le colis.
     */
    private static String payerMsisdn(UserEntity sender, BidEntity bid, String phoneOverride, String currency) {
        if (phoneOverride != null && !phoneOverride.isBlank()) {
            try {
                return Msisdn.normalize(phoneOverride);
            } catch (IllegalArgumentException e) {
                throw payerUnsupported("Numéro de téléphone invalide.");
            }
        }
        String account = sender.mobileMoneyPayerMsisdn(currency);
        if (account != null) {
            return account;
        }
        if (bid.getMobileMoneyPhone() != null && !bid.getMobileMoneyPhone().isBlank()) {
            return bid.getMobileMoneyPhone();
        }
        throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-phone-required",
                "Phone Required", "Indiquez le numéro mobile money qui paiera.");
    }

    // ── Catalogue du payeur ──────────────────────────────────────────────────

    /**
     * Réseaux avec lesquels l'expéditeur peut payer le supplément : catalogue DEPOSIT de son numéro
     * dans la devise du colis, restreint aux marques acceptées par le voyageur. Le catalogue du colis
     * ({@code POST /bids/{id}/mobile-money/providers}) exige un paiement principal encore en attente :
     * il répond 409 une fois le colis payé, d'où ce catalogue dédié.
     */
    @Transactional(readOnly = true)
    public MobileMoneyPayerProvidersResponse providersForPayer(UUID bidId, UUID supplementId, String firebaseUid,
                                                               String phoneOverride) {
        UserEntity sender = user(firebaseUid);
        BidSupplementEntity s = supplements.findByIdAndBidId(supplementId, bidId)
                .orElseThrow(BidSupplementMobileMoneyLedger::notFound);
        if (!sender.getId().equals(s.getSenderId())) {
            throw BidSupplementMobileMoneyLedger.forbidden();
        }
        if (s.getRail() != BidSupplementRail.PAWAPAY) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "supplement-rail-mismatch",
                    "Supplement Rail Mismatch", "Ce supplément ne se paie pas en mobile money");
        }
        if (s.getStatus() != BidSupplementStatus.PROPOSED && s.getStatus() != BidSupplementStatus.PAYMENT_PENDING) {
            throw BidSupplementMobileMoneyLedger.notPending();
        }
        if (!pawapay.enabled()) {
            throw PawapayErrors.disabled();
        }
        BidEntity bid = bids.findById(bidId).orElseThrow(BidSupplementMobileMoneyLedger::notFound);
        UserEntity traveler = traveler(bid);
        String msisdn = payerMsisdn(sender, bid, phoneOverride, s.getCurrency());
        PawapayProviderResolver.Catalogue catalogue;
        try {
            catalogue = providers.catalogue(msisdn, PawapayOperationKind.DEPOSIT, s.getCurrency(),
                    "le catalogue payeur du supplément " + supplementId);
        } catch (PawapayProviderResolver.UnsupportedNumberException e) {
            throw payerUnsupported(payerReason(e, s.getCurrency()));
        }
        List<MobileMoneyProvidersResponse.ProviderOption> options = catalogue.options().stream()
                .filter(o -> payoutAccounts.accepts(traveler.getId(), o.provider(), s.getCurrency()))
                .map(o -> new MobileMoneyProvidersResponse.ProviderOption(o.provider(),
                        PawapayProviders.label(o.provider()), o.provider().equalsIgnoreCase(catalogue.detected())))
                .toList();
        String detected = options.stream().filter(MobileMoneyProvidersResponse.ProviderOption::detected)
                .map(MobileMoneyProvidersResponse.ProviderOption::code).findFirst().orElse(null);
        return new MobileMoneyPayerProvidersResponse(catalogue.countryAlpha2(), catalogue.currency(),
                Msisdn.mask(catalogue.msisdn()), detected, options,
                payoutAccounts.acceptedLabels(traveler.getId(), s.getCurrency()),
                traveler.getFirstName());
    }

    // ── Statut ───────────────────────────────────────────────────────────────

    /**
     * {@code GET /bids/{bidId}/supplements/{id}/mobile-money/status}, pour l'expéditeur et le
     * voyageur. Même contrat que le statut du colis ({@code MobileMoneyPaymentStatusResponse}) :
     * {@code paymentStatus} traduit l'état du supplément ({@code PENDING}, {@code ESCROW} une fois
     * payé, {@code RELEASED} versé, {@code REFUND_PENDING} remboursement en cours, {@code REFUNDED}
     * remboursé, {@code CANCELLED} clos sans paiement),
     * {@code deadlineAt} est l'échéance de la demande, {@code amount} le brut.
     */
    @Transactional(readOnly = true)
    public MobileMoneyPaymentStatusResponse status(UUID bidId, UUID supplementId, String firebaseUid) {
        UserEntity caller = user(firebaseUid);
        BidSupplementEntity s = supplements.findByIdAndBidId(supplementId, bidId)
                .orElseThrow(BidSupplementMobileMoneyLedger::notFound);
        if (!caller.getId().equals(s.getSenderId()) && !caller.getId().equals(s.getTravelerId())) {
            throw BidSupplementMobileMoneyLedger.forbidden();
        }
        return status(s);
    }

    private MobileMoneyPaymentStatusResponse status(UUID supplementId) {
        return status(supplements.findById(supplementId).orElseThrow(BidSupplementMobileMoneyLedger::notFound));
    }

    private MobileMoneyPaymentStatusResponse status(BidSupplementEntity s) {
        String bidStatus = bids.findById(s.getBidId()).map(b -> b.getStatus().name()).orElse(null);
        MobileMoneyPaymentStatusResponse.OperationView view = operations
                .findLatestForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)
                .map(o -> new MobileMoneyPaymentStatusResponse.OperationView(o.getId(), o.getStatus().name(),
                        o.getProvider(), PawapayProviders.label(o.getProvider()), o.getMsisdnMasked(),
                        o.getAuthorizationUrl(), o.getFailureCode(), o.getFailureMessage()))
                .orElse(null);
        return new MobileMoneyPaymentStatusResponse(s.getBidId(), bidStatus, paymentStatus(s),
                s.getExpiresAt(), s.getGrossAmount(), s.getCurrency(), view);
    }

    /**
     * Statut du supplément dans le vocabulaire du paiement mobile money du colis, lu par l'app. Un
     * remboursement pas encore confirmé par pawaPay est {@code REFUND_PENDING}, jamais {@code REFUNDED} :
     * l'app ne compare {@code paymentStatus} qu'à {@code ESCROW}, {@code RELEASED} et {@code CANCELLED},
     * une valeur nouvelle n'y est ni « payé » ni « annulé ».
     */
    static String paymentStatus(BidSupplementStatus status) {
        return switch (status) {
            case COMMISSION_PENDING, PROPOSED, PAYMENT_PENDING -> "PENDING";
            case PAID, PAYOUT_PENDING -> "ESCROW";
            case PAID_OUT -> "RELEASED";
            case REFUND_PENDING -> "REFUND_PENDING";
            case REFUNDED -> "REFUNDED";
            case DECLINED, EXPIRED, WITHDRAWN, CANCELLED -> "CANCELLED";
        };
    }

    /** Comme {@link #paymentStatus(BidSupplementStatus)} ; un paiement tardif encore à rendre est en cours de remboursement. */
    static String paymentStatus(BidSupplementEntity s) {
        boolean lateUnrefunded = BidSupplementCardLedger.CLOSED_UNPAID.contains(s.getStatus())
                && s.getPaidAt() != null && s.getRefundedAt() == null;
        return lateUnrefunded ? "REFUND_PENDING" : paymentStatus(s.getStatus());
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private UserEntity user(String firebaseUid) {
        return users.findByFirebaseUid(firebaseUid).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }

    private UserEntity traveler(BidEntity bid) {
        AnnouncementEntity announcement = announcements.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found",
                        "Announcement Not Found", "Annonce introuvable"));
        return users.findById(announcement.getTravelerId()).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Voyageur introuvable"));
    }

    private static String payerReason(PawapayProviderResolver.UnsupportedNumberException e, String currency) {
        return switch (e.reason()) {
            case NO_PROVIDER -> "Aucun opérateur mobile money reconnu pour ce numéro.";
            case OPERATION_CLOSED -> e.providerLabel() + " ne permet pas le paiement pour le moment.";
            case CURRENCY_MISMATCH -> "Ce numéro paie en " + e.providerCurrency() + ", ce colis est en " + currency + ".";
            case COUNTRY_UNKNOWN -> "Pays non reconnu pour ce numéro.";
            case PROVIDER_NOT_AVAILABLE -> "Réseau " + e.providerLabel() + " indisponible pour ce numéro.";
        };
    }

    private static YadonyBusinessException payerUnsupported(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-payer-unsupported",
                "Mobile Money Payer Unsupported", detail);
    }
}
