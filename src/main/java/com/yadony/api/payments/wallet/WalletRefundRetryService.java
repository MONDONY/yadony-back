package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.pawapay.PawapayErrors;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.wallet.fees.PawapayFeeTable;
import com.yadony.api.payments.wallet.fees.WalletRefundFeeCalculator;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relance, depuis l'admin, d'un remboursement de solde mobile money passé en manuel : verse
 * réellement l'argent sur le numéro qui a servi à recharger, au lieu d'enregistrer un paiement
 * fait hors app ({@link WalletRefundRequestService#resolve}).
 *
 * <p><b>Éligibilité.</b> Ticket {@code MANUAL_ADMIN} en {@code PENDING} dont tous les items
 * PENDING viennent d'une recharge pawaPay (dépôt {@code WALLET_TOPUP}) vers une même destination
 * (numéro, opérateur, pays, devise) : c'est le ticket enfant qu'ouvre l'échec du flux
 * automatique. Un ticket racine sans item, une recharge carte ou des destinations mêlées
 * répondent 422 {@code wallet-refund-retry-not-eligible}.
 *
 * <p><b>Montant.</b> {@code dû} est le net que le flux automatique enverrait aujourd'hui :
 * brut de l'item moins le frais du barème actuel ({@link WalletRefundFeeCalculator#feeFor} sur
 * {@link PawapayFeeTable}, comme {@code WalletSelfRefundService#request}). Le montant envoyé
 * ne dépasse jamais le montant sûr calculé sur le solde pawaPay relu juste avant l'envoi
 * ({@link PawapayRefundFunds}) : au-delà, 422 {@code wallet-refund-insufficient-pawapay-funds}
 * sans aucune opération créée.
 *
 * <p><b>Rail.</b> Aucun envoi n'est réécrit ici : les items sont confiés à
 * {@link WalletPawapayRefundIssuer#issue} (REFUND du dépôt, ou PAYOUT au même numéro si
 * l'opérateur ne rembourse pas, puis repli PAYOUT sur un REFUND en échec). Le net envoyé est
 * {@code amount - feeAmount} de l'item : la relance pose donc {@code feeAmount = brut - net
 * choisi}. La demande passe {@code PROCESSING} ; l'issue est traitée par
 * {@link WalletRefundOutcomeListener} puis {@code WalletSelfRefundService#resolveIfComplete},
 * exactement comme le flux automatique : succès → {@code REFUNDED} (débit du brut,
 * {@code paidAmount} = net versé) ; échec → {@code FAILED}, alerte, et un nouveau ticket
 * manuel {@code PENDING} reprend les items en échec avec leur motif.
 *
 * <p><b>Concurrence.</b> La demande est verrouillée et relue avant tout changement : un double
 * clic trouve la demande déjà {@code PROCESSING} (422 {@code wallet-refund-retry-in-progress}).
 * Une opération pawaPay de remboursement encore ouverte pour l'utilisateur et la devise bloque
 * aussi la relance.
 */
@Service
public class WalletRefundRetryService {

    public static final String AUDIT_ACTION = "WALLET_REFUND_RETRIED";
    /** Motif d'un item que le montant choisi ne couvre plus (demande à plusieurs recharges). */
    static final String REASON_AMOUNT_SHORT = "retry-amount-short";

    private static final Logger log = LoggerFactory.getLogger(WalletRefundRetryService.class);

    private final WalletRefundRequestRepository requestRepository;
    private final WalletRefundRequestItemRepository itemRepository;
    private final WalletTransactionRepository transactionRepository;
    private final PawapayOperationRepository operationRepository;
    private final PawapayFeeTable feeTable;
    private final PawapayRefundFunds funds;
    private final WalletPawapayRefundIssuer issuer;
    private final AuditService auditService;
    private final EntityManager entityManager;

    public WalletRefundRetryService(WalletRefundRequestRepository requestRepository,
                                    WalletRefundRequestItemRepository itemRepository,
                                    WalletTransactionRepository transactionRepository,
                                    PawapayOperationRepository operationRepository,
                                    PawapayFeeTable feeTable,
                                    PawapayRefundFunds funds,
                                    WalletPawapayRefundIssuer issuer,
                                    AuditService auditService,
                                    EntityManager entityManager) {
        this.requestRepository = requestRepository;
        this.itemRepository = itemRepository;
        this.transactionRepository = transactionRepository;
        this.operationRepository = operationRepository;
        this.feeTable = feeTable;
        this.funds = funds;
        this.issuer = issuer;
        this.auditService = auditService;
        this.entityManager = entityManager;
    }

    /**
     * Aperçu d'une relance. Solde pawaPay illisible : réponse 200 quand même, avec
     * {@code walletBalance}, {@code maxSafeAmount}, {@code suggestedAmount} et
     * {@code topUpNeeded} nuls, {@code retryAvailable = false} et
     * {@code unavailableReason = "pawapay-balance-unavailable"} : l'admin voit le dû et le numéro,
     * le bouton d'envoi reste fermé.
     */
    public record Preview(UUID requestId, String currency, String country, String provider, String msisdnMasked,
                          BigDecimal grossAmount, BigDecimal amountDue, BigDecimal walletBalance,
                          BigDecimal maxSafeAmount, BigDecimal suggestedAmount, BigDecimal topUpNeeded,
                          BigDecimal refundRatePercent, boolean retryAvailable, String unavailableReason) {}

    /** Relance acceptée : demande passée PROCESSING, montant confié au rail. */
    public record Retried(WalletRefundRequestEntity request, BigDecimal amount, BigDecimal amountDue,
                          BigDecimal walletBalance, BigDecimal maxSafeAmount) {}

    /** Une recharge à rembourser : l'item du ticket, son dépôt d'origine et son net dû. */
    private record Line(WalletRefundRequestItemEntity item, PawapayOperationEntity deposit, BigDecimal due) {}

    /** Ticket éligible : ses lignes (ordre de création), la destination commune et le dû total. */
    private record Target(WalletRefundRequestEntity request, List<Line> lines, PawapayOperationEntity deposit,
                          BigDecimal due) {}

    @Transactional(readOnly = true)
    public Preview preview(UUID requestId) {
        Target target = target(find(requestId), false);
        PawapayOperationEntity deposit = target.deposit();
        String currency = target.request().getCurrency();
        BigDecimal rate = funds.ratePercent(deposit.getProvider());
        BigDecimal gross = target.lines().stream().map(l -> l.item().getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        Optional<BigDecimal> balance = funds.balance(deposit.getCountry(), deposit.getCurrency());
        if (balance.isEmpty()) {
            return new Preview(target.request().getId(), currency, deposit.getCountry(), deposit.getProvider(),
                    deposit.getMsisdnMasked(), gross, target.due(), null, null, null, null, rate, false,
                    "pawapay-balance-unavailable");
        }
        BigDecimal maxSafe = funds.maxSafe(balance.get(), deposit.getProvider(), currency);
        BigDecimal suggested = target.due().min(maxSafe);
        BigDecimal topUp = funds.topUpNeeded(target.due(), balance.get(), deposit.getProvider(), currency);
        boolean available = suggested.signum() > 0;
        return new Preview(target.request().getId(), currency, deposit.getCountry(), deposit.getProvider(),
                deposit.getMsisdnMasked(), gross, target.due(), balance.get(), maxSafe, suggested, topUp, rate,
                available, available ? null : "pawapay-insufficient-funds");
    }

    /**
     * Relance le remboursement pour {@code amount} ({@code null} : le montant suggéré,
     * {@code min(dû, maxSûr)}). Le solde pawaPay est lu juste avant de verrouiller la demande
     * (aucun appel réseau sous le verrou pour cette lecture) puis le montant est validé contre
     * le montant sûr recalculé.
     *
     * @throws YadonyBusinessException 404 demande introuvable ; 422 non éligible, relance déjà
     *         en cours, montant invalide ou fonds pawaPay insuffisants ; 503 solde pawaPay
     *         illisible ; 502 opération impossible à réserver chez pawaPay
     */
    @Transactional
    public Retried retry(UUID requestId, BigDecimal amount, UUID adminId) {
        // Premier passage sans verrou : seulement pour savoir quel wallet pawaPay lire.
        Target unlocked = target(find(requestId), false);
        PawapayOperationEntity source = unlocked.deposit();
        BigDecimal balance = funds.balance(source.getCountry(), source.getCurrency())
                .orElseThrow(WalletRefundRetryService::balanceUnavailable);

        WalletRefundRequestEntity locked = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(WalletRefundRetryService::notFound);
        entityManager.refresh(locked);
        Target target = target(locked, true);
        WalletRefundRequestEntity request = target.request();
        String currency = request.getCurrency();
        String provider = target.deposit().getProvider();

        BigDecimal maxSafe = funds.maxSafe(balance, provider, currency);
        BigDecimal due = target.due();
        BigDecimal chosen;
        if (amount == null) {
            chosen = due.min(maxSafe);
            if (chosen.signum() <= 0) {
                throw insufficientFunds(target, balance, maxSafe, due);
            }
        } else {
            validateAmount(amount, due, currency);
            chosen = amount;
        }
        if (chosen.compareTo(maxSafe) > 0) {
            throw insufficientFunds(target, balance, maxSafe, chosen);
        }
        chosen = chosen.setScale(scale(currency), RoundingMode.UNNECESSARY);

        List<WalletRefundRequestItemEntity> covered = distribute(target.lines(), chosen);
        request.setStatus(WalletRefundRequestStatus.PROCESSING);
        requestRepository.saveAndFlush(request);

        // Garde de solde déjà faite ci-dessus sur un solde relu : le rail ne la refait pas.
        issuer.issue(request, covered, false);
        List<Map<String, String>> auditItems = new ArrayList<>();
        for (WalletRefundRequestItemEntity item : covered) {
            if (item.getPawapayRefundId() == null && item.getPawapayPayoutId() == null) {
                // Réservation impossible (configuration pawaPay illisible, dépôt introuvable) :
                // tout est annulé, la demande reste PENDING et rien n'est parti.
                log.warn("Relance du remboursement {} : item {} non emis, relance annulee", requestId, item.getId());
                throw PawapayErrors.providerUnavailable();
            }
            auditItems.add(Map.of("itemId", String.valueOf(item.getId()),
                    "net", item.getAmount().subtract(item.getFeeAmount()).toPlainString(),
                    "kind", item.getPawapayRefundId() != null ? "REFUND" : "PAYOUT",
                    "operationId", String.valueOf(item.getPawapayRefundId() != null
                            ? item.getPawapayRefundId() : item.getPawapayPayoutId())));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("adminId", String.valueOf(adminId));
        payload.put("currency", currency);
        payload.put("country", target.deposit().getCountry());
        payload.put("provider", provider);
        payload.put("amount", chosen.toPlainString());
        payload.put("amountDue", due.toPlainString());
        payload.put("walletBalance", balance.toPlainString());
        payload.put("maxSafe", maxSafe.toPlainString());
        payload.put("refundRatePercent", funds.ratePercent(provider).toPlainString());
        payload.put("items", List.copyOf(auditItems));
        auditService.log("wallet_refund_request", request.getId(), AUDIT_ACTION, adminId, payload);
        return new Retried(request, chosen, due, balance, maxSafe);
    }

    /**
     * Répartit {@code amount} sur les lignes dans l'ordre de création : chacune reçoit au plus son
     * dû. Le net choisi devient {@code amount - feeAmount} de l'item (seul montant que lit
     * l'émetteur, pour le REFUND comme pour son repli PAYOUT). Une ligne que le montant ne couvre
     * plus passe FAILED {@value #REASON_AMOUNT_SHORT} : à la résolution, un nouveau ticket manuel
     * la reprend.
     */
    private List<WalletRefundRequestItemEntity> distribute(List<Line> lines, BigDecimal amount) {
        List<WalletRefundRequestItemEntity> covered = new ArrayList<>();
        BigDecimal left = amount;
        for (Line line : lines) {
            WalletRefundRequestItemEntity item = line.item();
            BigDecimal net = line.due().signum() > 0 ? left.min(line.due()) : BigDecimal.ZERO;
            if (net.signum() > 0) {
                item.setFeeAmount(item.getAmount().subtract(net));
                covered.add(item);
                left = left.subtract(net);
            } else {
                item.setStatus(WalletRefundItemStatus.FAILED);
                item.setFailureReason(REASON_AMOUNT_SHORT);
            }
            itemRepository.save(item);
        }
        return covered;
    }

    private WalletRefundRequestEntity find(UUID requestId) {
        return requestRepository.findById(requestId).orElseThrow(WalletRefundRetryService::notFound);
    }

    /**
     * Contrôle d'éligibilité et calcul du dû ; lève les 422 décrits en tête de classe.
     * {@code fresh} : relit chaque item en base (passage sous verrou, après une éventuelle
     * relance concurrente déjà committée).
     */
    private Target target(WalletRefundRequestEntity request, boolean fresh) {
        if (request.getChannel() != WalletRefundChannel.MANUAL_ADMIN) {
            throw notEligible("not-manual", "Seul un remboursement traité à la main peut être relancé");
        }
        if (request.getStatus() == WalletRefundRequestStatus.PROCESSING) {
            throw inProgress();
        }
        if (request.getStatus() != WalletRefundRequestStatus.PENDING) {
            throw notEligible("not-pending", "Cette demande a déjà été traitée");
        }
        List<WalletRefundRequestItemEntity> all = itemRepository.findByRefundRequestId(request.getId());
        if (fresh) {
            all.forEach(entityManager::refresh);
        }
        List<WalletRefundRequestItemEntity> items = all.stream()
                .filter(i -> i.getStatus() == WalletRefundItemStatus.PENDING)
                .sorted(Comparator.comparing(WalletRefundRequestItemEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        if (items.isEmpty()) {
            throw notEligible("no-mobile-money-source",
                    "Aucune recharge mobile money identifiée sur cette demande : remboursez hors app");
        }
        List<Line> lines = new ArrayList<>();
        PawapayOperationEntity reference = null;
        for (WalletRefundRequestItemEntity item : items) {
            if (WalletRefundRail.of(item.getPaymentIntentId()) != WalletRefundRail.PAWAPAY) {
                throw notEligible("not-mobile-money-topup",
                        "Cette demande ne vient pas d'une recharge mobile money : remboursez hors app");
            }
            if (item.getPawapayRefundId() != null || item.getPawapayPayoutId() != null) {
                throw inProgress();
            }
            PawapayOperationEntity deposit = deposit(item);
            if (!request.getCurrency().equalsIgnoreCase(deposit.getCurrency())) {
                throw notEligible("currency-mismatch", "La devise de la recharge ne correspond pas à la demande");
            }
            if (reference != null && !sameDestination(reference, deposit)) {
                throw notEligible("multiple-destinations",
                        "Les recharges de cette demande viennent de plusieurs numéros : remboursez hors app");
            }
            reference = reference == null ? deposit : reference;
            lines.add(new Line(item, deposit, due(item, deposit, request.getCurrency())));
        }
        if (refundOperationOpen(request)) {
            throw inProgress();
        }
        BigDecimal due = lines.stream().map(Line::due).filter(d -> d.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (due.signum() <= 0) {
            throw notEligible("nothing-to-refund", "Les frais de remboursement absorbent tout le montant");
        }
        return new Target(request, List.copyOf(lines), reference, due);
    }

    private PawapayOperationEntity deposit(WalletRefundRequestItemEntity item) {
        UUID depositId;
        try {
            depositId = WalletRefundRail.depositId(item.getPaymentIntentId());
        } catch (IllegalArgumentException e) {
            throw notEligible("deposit-missing", "Recharge mobile money d'origine introuvable");
        }
        return operationRepository.findById(depositId)
                .filter(op -> op.getKind() == PawapayOperationKind.DEPOSIT
                        && op.getPurpose() == PawapayOperationPurpose.WALLET_TOPUP)
                .orElseThrow(() -> notEligible("deposit-missing", "Recharge mobile money d'origine introuvable"));
    }

    private static boolean sameDestination(PawapayOperationEntity a, PawapayOperationEntity b) {
        return Objects.equals(a.getMsisdn(), b.getMsisdn()) && Objects.equals(a.getProvider(), b.getProvider())
                && Objects.equals(a.getCountry(), b.getCountry()) && Objects.equals(a.getCurrency(), b.getCurrency());
    }

    /**
     * Net que le flux automatique enverrait aujourd'hui pour cet item : même calcul que
     * {@code WalletSelfRefundService#request} (frais nuls sur une recharge entamée, sinon barème
     * pawaPay de l'opérateur, plafonné au montant).
     */
    private BigDecimal due(WalletRefundRequestItemEntity item, PawapayOperationEntity deposit, String currency) {
        BigDecimal original = transactionRepository.findById(item.getWalletTransactionId())
                .map(WalletTransactionEntity::getAmount)
                .map(BigDecimal::abs)
                .orElse(item.getAmount());
        WalletRefundAllocation.RefundableTopup topup = new WalletRefundAllocation.RefundableTopup(
                item.getWalletTransactionId(), item.getPaymentIntentId(), original, item.getAmount(), null,
                WalletRefundRail.PAWAPAY, deposit.getProvider());
        BigDecimal fee = WalletRefundFeeCalculator.feeFor(topup, currency, new WalletRefundFeeCalculator.FeeSources() {
            @Override
            public BigDecimal stripeFee(String paymentIntentId, BigDecimal amount, String feeCurrency) {
                return BigDecimal.ZERO;
            }

            @Override
            public BigDecimal pawapayFee(String provider, BigDecimal amount, String feeCurrency) {
                return feeTable.fee(provider, amount, feeCurrency);
            }
        });
        return item.getAmount().subtract(fee).setScale(scale(currency), RoundingMode.DOWN);
    }

    private boolean refundOperationOpen(WalletRefundRequestEntity request) {
        for (PawapayOperationKind kind : List.of(PawapayOperationKind.REFUND, PawapayOperationKind.PAYOUT)) {
            if (operationRepository.existsByUserIdAndKindAndCurrencyAndPurposeAndStatusIn(request.getUserId(), kind,
                    request.getCurrency(), PawapayOperationPurpose.WALLET_REFUND, PawapayOperationStatus.OPEN)) {
                return true;
            }
        }
        return false;
    }

    private static void validateAmount(BigDecimal amount, BigDecimal due, String currency) {
        if (amount.signum() <= 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-refund-retry-amount-invalid",
                    "Unprocessable", "Le montant à verser doit être strictement positif");
        }
        if (amount.compareTo(due) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-refund-retry-amount-too-high",
                    "Unprocessable", "Le montant dépasse le montant dû (" + due.toPlainString() + " " + currency + ")",
                    Map.of("amountDue", due));
        }
        int maxDecimals = scale(currency);
        if (Math.max(amount.stripTrailingZeros().scale(), 0) > maxDecimals) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-refund-retry-amount-scale",
                    "Unprocessable", "Le montant a trop de décimales pour la devise " + currency
                            + " (" + maxDecimals + " au plus)", Map.of("maxDecimals", maxDecimals));
        }
    }

    private YadonyBusinessException insufficientFunds(Target target, BigDecimal balance, BigDecimal maxSafe,
                                                      BigDecimal amount) {
        PawapayOperationEntity deposit = target.deposit();
        String currency = target.request().getCurrency();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("amount", amount);
        props.put("amountDue", target.due());
        props.put("walletBalance", balance);
        props.put("maxSafeAmount", maxSafe);
        props.put("topUpNeeded", funds.topUpNeeded(target.due(), balance, deposit.getProvider(), currency));
        props.put("topUpNeededForAmount", funds.topUpNeeded(amount, balance, deposit.getProvider(), currency));
        props.put("currency", currency);
        props.put("country", deposit.getCountry());
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-refund-insufficient-pawapay-funds",
                "Unprocessable", "Le wallet pawaPay " + deposit.getCountry() + " " + currency
                        + " ne permet de verser au plus que " + maxSafe.toPlainString() + " " + currency
                        + " en sécurité", props);
    }

    private static int scale(String currency) {
        return SupportedCurrency.fromCodeOrDefault(currency).minorUnit();
    }

    private static YadonyBusinessException notFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "wallet-refund-request-not-found", "Not Found",
                "Demande introuvable");
    }

    private static YadonyBusinessException notEligible(String reason, String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-refund-retry-not-eligible",
                "Unprocessable", detail, Map.of("reason", reason));
    }

    private static YadonyBusinessException inProgress() {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-refund-retry-in-progress",
                "Unprocessable", "Un remboursement mobile money est déjà en cours pour cette demande");
    }

    private static YadonyBusinessException balanceUnavailable() {
        return new YadonyBusinessException(HttpStatus.SERVICE_UNAVAILABLE, "pawapay-balance-unavailable",
                "Service Unavailable", "Solde du wallet pawaPay illisible : réessayez dans quelques instants");
    }
}
