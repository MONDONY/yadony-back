package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.Msisdn;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersResponse;
import com.yadony.api.payments.pawapay.PawapayErrors;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.PawapayText;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.wallet.dto.WalletTopupRequest;
import com.yadony.api.payments.wallet.dto.WalletTopupResponse;
import com.yadony.api.payments.wallet.dto.WalletTopupStatusResponse;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recharge du portefeuille par mobile money : initiation d'un dépôt pawaPay de purpose
 * {@code WALLET_TOPUP} et lecture de son statut. Le crédit du portefeuille N'EST PAS fait
 * ici : il n'arrive qu'à l'issue du dépôt, par l'écouteur de
 * {@code PawapayOperationCompletedEvent} — aucune somme ne doit être créditée sur la foi
 * d'une initiation acceptée.
 *
 * <p>La devise est celle de l'OPÉRATEUR du numéro, jamais celle du portefeuille ni celle
 * demandée par le client : un numéro ivoirien recharge en XOF, et c'est ce solde XOF qui
 * est crédité (créé au besoin par l'écouteur). Le résolveur est donc appelé sans devise
 * attendue, comme l'activation d'un compte de versement.
 */
@Service
public class WalletMobileMoneyTopupService {

    private static final Logger log = LoggerFactory.getLogger(WalletMobileMoneyTopupService.class);

    /** Libellé métier des journaux d'indisponibilité pawaPay — jamais un numéro. */
    static final String CONTEXT = "la recharge du portefeuille";

    private final PawapayProviderResolver resolver;
    private final PawapaySubmissionService submission;
    private final PawapayOperationRepository operations;
    private final WalletService walletService;
    private final AuditService auditService;
    private final PawapayProperties props;
    private final MessagesResolver messagesResolver;

    public WalletMobileMoneyTopupService(PawapayProviderResolver resolver, PawapaySubmissionService submission,
                                         PawapayOperationRepository operations, WalletService walletService,
                                         AuditService auditService, PawapayProperties props,
                                         MessagesResolver messagesResolver) {
        this.resolver = resolver;
        this.submission = submission;
        this.operations = operations;
        this.walletService = walletService;
        this.auditService = auditService;
        this.props = props;
        this.messagesResolver = messagesResolver;
    }

    /**
     * Demande un dépôt à l'opérateur du numéro. L'utilisateur valide ensuite sur son
     * téléphone (code PIN) ou sur la page de l'opérateur ({@code authorizationUrl}, Wave) ;
     * le portefeuille n'est crédité qu'au retour de pawaPay.
     */
    public WalletTopupResponse initiate(UUID userId, WalletTopupRequest request) {
        // Interrupteur d'urgence : il n'y a rien d'idempotent à relire ici (le statut a son
        // propre point d'entrée), donc aucune raison de le vérifier plus tard — couper le
        // rail doit empêcher toute nouvelle demande de débit.
        if (!props.enabled()) {
            throw PawapayErrors.disabled();
        }
        Messages m = messagesResolver.forRequest();
        String phoneNumber = normalizedPhone(request.getPhoneNumber());
        PawapayProviderResolver.Resolved resolved;
        try {
            resolved = resolver.resolve(phoneNumber, PawapayOperationKind.DEPOSIT, null, request.getProvider(),
                    CONTEXT + " de " + userId);
        } catch (PawapayProviderResolver.UnsupportedNumberException e) {
            throw unsupported(userId, reasonDetail(m, e));
        }
        String currency = resolved.config().currency();
        BigDecimal amount = withinBounds(m, request.getAmount(), resolved.config().deposit(), currency);
        // Une seule recharge NON TERMINALE par utilisateur et devise : empiler les demandes
        // de code sur un même téléphone n'apporte rien et multiplie les débits accidentels.
        // OPEN et non LIVE_OR_DONE : une recharge déjà COMPLETED est finie, elle ne doit
        // jamais bloquer la suivante.
        if (operations.existsByUserIdAndKindAndCurrencyAndPurposeAndStatusIn(userId, PawapayOperationKind.DEPOSIT,
                currency, PawapayOperationPurpose.WALLET_TOPUP, PawapayOperationStatus.OPEN)) {
            throw PawapayErrors.walletTopupAlreadyPending();
        }
        String successfulUrl = null;
        String failedUrl = null;
        if (resolved.config().isRedirectDeposit()) {
            // L'id de l'opération n'existe qu'APRÈS la soumission, qui a justement besoin de
            // ces URLs : la page de rebond ne peut donc pas être keyée dessus, contrairement
            // à un paiement de colis (bidId connu d'avance). Elle ne décide de rien de toute
            // façon — elle renvoie sur l'écran du portefeuille, qui relit le statut ; aucun
            // identifiant utilisateur n'a donc à transiter par cette URL (dashboard pawaPay,
            // historique du navigateur), `PawapayReturnController.backWalletTopup` ne lit
            // d'ailleurs aucun paramètre de la requête.
            String base = props.returnBaseUrl() + "/api/v1/pawapay/return/wallet-topup?outcome=";
            successfulUrl = base + "success";
            failedUrl = base + "failed";
        }
        PawapayOperationEntity op = submission.submitWalletDeposit(userId, resolved.msisdn(), resolved.provider(),
                resolved.countryAlpha2(), amount, currency, "wallet-topup-" + userId, successfulUrl, failedUrl);
        auditService.log("wallet_topup", op.getId(), "MOBILE_MONEY_INITIATED", userId,
                Map.of("currency", currency, "amount", amount.toPlainString(), "provider", op.getProvider(),
                        "msisdnMasked", op.getMsisdnMasked(), "status", op.getStatus().name()));
        return WalletTopupResponse.mobileMoney(op);
    }

    /**
     * Statut d'une recharge. {@code findByIdAndUserId} porte la propriété : une opération
     * d'un autre utilisateur est introuvable, jamais « interdite » — un 403 confirmerait
     * l'existence de l'identifiant.
     */
    @Transactional(readOnly = true)
    public WalletTopupStatusResponse status(UUID userId, UUID topupId) {
        PawapayOperationEntity op = operations.findByIdAndUserId(topupId, userId)
                .filter(o -> o.getPurpose() == PawapayOperationPurpose.WALLET_TOPUP
                        && o.getKind() == PawapayOperationKind.DEPOSIT)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "topup-not-found",
                        "Not Found", "Recharge introuvable."));
        String status = WalletTopupStatusResponse.statusOf(op.getStatus());
        // Solde relu seulement une fois la recharge confirmée : le renvoyer pendant l'attente
        // afficherait un solde d'avant crédit à côté d'un écran « recharge en cours ».
        BigDecimal balance = WalletTopupStatusResponse.CONFIRMED.equals(status)
                ? walletService.getBalance(userId, op.getCurrency()) : null;
        return new WalletTopupStatusResponse(op.getId(), status, op.getAmount(), op.getCurrency(), op.getProvider(),
                PawapayProviders.label(op.getProvider()), op.getMsisdnMasked(),
                op.getAuthorizationUrl(), failureReason(op), balance);
    }

    /**
     * Réseaux utilisables pour payer une recharge depuis ce numéro, dans la devise de son
     * opérateur. Lecture seule, rien n'est écrit : l'app l'appelle dès la saisie du numéro.
     */
    public MobileMoneyProvidersResponse providers(String phoneNumber) {
        if (!props.enabled()) {
            throw PawapayErrors.disabled();
        }
        Messages m = messagesResolver.forRequest();
        String msisdn = normalizedPhone(phoneNumber);
        try {
            return MobileMoneyProvidersResponse.from(resolver.catalogue(msisdn, PawapayOperationKind.DEPOSIT,
                    null, "le catalogue de " + CONTEXT));
        } catch (PawapayProviderResolver.UnsupportedNumberException e) {
            throw unsupported(null, reasonDetail(m, e));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Numéro mis à la forme attendue par pawaPay AVANT tout appel réseau : chiffres seuls,
     * indicatif inclus, sans {@code +} ({@link Msisdn#normalize}), comme sur tous les autres
     * points d'entrée du rail. Sans cette normalisation, une saisie hors bornes partirait
     * chez pawaPay et reviendrait en 502 « opérateur indisponible » alors que c'est
     * l'utilisateur qui peut la corriger — d'où le 422 ici, avec le code déjà utilisé par
     * l'activation du versement.
     */
    private static String normalizedPhone(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "topup-phone-required",
                    "Phone Required", "Indiquez le numéro mobile money qui paie la recharge.");
        }
        try {
            return Msisdn.normalize(raw);
        } catch (IllegalArgumentException e) {
            // Le numéro n'est jamais reflété dans le détail ni dans un journal.
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-invalid-phone",
                    "Mobile Money Invalid Phone", "Numéro de téléphone invalide pour la recharge.");
        }
    }

    /**
     * Montant accepté par l'opérateur : entre ses bornes, et sans centimes là où la devise
     * n'en a pas ({@code SupportedCurrency.minorUnit() == 0} pour XOF/XAF). Le test de
     * décimales passe par {@code stripTrailingZeros} : « 10000.00 » envoyé par un client
     * JSON est un montant entier, « 1000.50 » non.
     */
    private static BigDecimal withinBounds(Messages m, BigDecimal amount, PawapayProviderConfig.Limits deposit,
                                           String currency) {
        int minorUnit = SupportedCurrency.fromCodeOrDefault(currency).minorUnit();
        BigDecimal min = deposit == null ? null : deposit.minAmount();
        BigDecimal max = deposit == null ? null : deposit.maxAmount();
        if (amount == null || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > minorUnit
                || min != null && amount.compareTo(min) < 0
                || max != null && amount.compareTo(max) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "topup-amount-out-of-range",
                    "Amount Out Of Range", boundsDetail(m, min, max, currency, minorUnit));
        }
        return amount;
    }

    private static String boundsDetail(Messages m, BigDecimal min, BigDecimal max, String currency, int minorUnit) {
        String cents = m.get(minorUnit == 0 ? "problem.topup.amount.end.no-cents" : "problem.topup.amount.end.plain");
        if (min != null && max != null) {
            return m.get("problem.topup.amount.between", WalletAmountText.format(min, currency),
                    WalletAmountText.format(max, currency), cents);
        }
        if (min != null) {
            return m.get("problem.topup.amount.at-least", WalletAmountText.format(min, currency), cents);
        }
        if (max != null) {
            return m.get("problem.topup.amount.at-most", WalletAmountText.format(max, currency), cents);
        }
        return m.get("problem.topup.amount.invalid", cents);
    }

    /** Message d'échec borné ({@code failure_code} fait 64 caractères), jamais reflété brut. */
    private static String failureReason(PawapayOperationEntity op) {
        return op.getFailureMessage() != null ? PawapayText.clamp(op.getFailureMessage()) : op.getFailureCode();
    }

    /** Libellé métier d'un numéro reconnu mais inexploitable pour une recharge. */
    private static String reasonDetail(Messages m, PawapayProviderResolver.UnsupportedNumberException e) {
        return switch (e.reason()) {
            case NO_PROVIDER -> m.get("problem.topup.no-provider");
            case OPERATION_CLOSED -> m.get("problem.topup.operation-closed", e.providerLabel());
            // Aucune devise n'est imposée au résolveur ici : ce cas ne peut venir que d'un
            // appel futur qui en passerait une.
            case CURRENCY_MISMATCH -> m.get("problem.topup.currency-mismatch", e.providerCurrency());
            case COUNTRY_UNKNOWN -> m.get("problem.mobile-money.country-unknown");
            case PROVIDER_NOT_AVAILABLE -> m.get("problem.mobile-money.network-unavailable", e.providerLabel());
        };
    }

    /** Rejet métier (422) : trace la branche d'échec pour le support, jamais le numéro. */
    private static YadonyBusinessException unsupported(UUID userId, String detail) {
        log.warn("Recharge mobile money refusée{} : {}", userId != null ? " pour " + userId : "", detail);
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "topup-phone-unsupported",
                "Phone Unsupported", detail);
    }
}
