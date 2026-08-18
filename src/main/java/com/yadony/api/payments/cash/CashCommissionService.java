package com.yadony.api.payments.cash;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.cash.dto.AcceptBidResponse;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import com.yadony.api.payments.cash.dto.CommissionMethodResponse;
import com.yadony.api.payments.cash.dto.ConfirmAcceptanceResponse;
import com.yadony.api.payments.cash.dto.SetupCommissionMethodResponse;
import com.yadony.api.payments.cash.event.CommissionMethodDetachedEvent;
import com.yadony.api.payments.cash.exception.CommissionChargeFailedException;
import com.yadony.api.payments.cash.exception.CommissionMethodMissingException;
import com.yadony.api.payments.cash.exception.InvalidPaymentMethodForAnnouncementException;
import com.yadony.api.payments.wallet.InsufficientWalletBalanceException;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CashCommissionService {

    private static final Logger log = LoggerFactory.getLogger(CashCommissionService.class);
    private static final String TRACKING_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom SECURE_RNG = new SecureRandom();

    // String status values stored on NegotiationThreadEntity (decoupled from the
    // payments/cash enums). Names mirror CommissionStatus / CommissionChargedVia.
    private static final String NEGO_COMMISSION_CHARGED = CommissionStatus.CHARGED.name();
    private static final String NEGO_COMMISSION_REQUIRES_3DS = CommissionStatus.REQUIRES_3DS.name();
    private static final String NEGO_COMMISSION_FAILED = CommissionStatus.FAILED.name();
    private static final String NEGO_COMMISSION_REFUNDED = CommissionStatus.REFUNDED.name();
    private static final String NEGO_COMMISSION_REFUND_FAILED = CommissionStatus.REFUND_FAILED.name();
    private static final String NEGO_COMMISSION_VIA_WALLET = CommissionChargedVia.WALLET.name();
    private static final String NEGO_COMMISSION_VIA_CARD = CommissionChargedVia.CARD.name();

    private final CommissionProperties props;
    private final UserRepository userRepo;
    private final BidRepository bidRepo;
    private final AnnouncementRepository announcementRepo;
    private final ApplicationEventPublisher events;
    private final WalletService walletService;
    private final WalletTransactionRepository walletTransactionRepository;
    private final AuditService auditService;
    private final CommissionRateResolver commissionRateResolver;
    private final com.yadony.api.requests.repository.NegotiationThreadRepository negotiationThreadRepository;
    private final StripeCashGateway stripeCashGateway;
    private final FirebaseContactService firebaseContact;
    private final com.yadony.api.matching.BidGridItemRepository bidGridItemRepository;
    private Clock clock = Clock.systemUTC();

    public CashCommissionService(CommissionProperties props,
                                 UserRepository userRepo,
                                 BidRepository bidRepo,
                                 AnnouncementRepository announcementRepo,
                                 ApplicationEventPublisher events,
                                 WalletService walletService,
                                 WalletTransactionRepository walletTransactionRepository,
                                 AuditService auditService,
                                 CommissionRateResolver commissionRateResolver,
                                 com.yadony.api.requests.repository.NegotiationThreadRepository negotiationThreadRepository,
                                 StripeCashGateway stripeCashGateway,
                                 com.yadony.api.matching.BidGridItemRepository bidGridItemRepository,
                                 FirebaseContactService firebaseContact) {
        this.props = props;
        this.userRepo = userRepo;
        this.bidRepo = bidRepo;
        this.announcementRepo = announcementRepo;
        this.events = events;
        this.walletService = walletService;
        this.walletTransactionRepository = walletTransactionRepository;
        this.auditService = auditService;
        this.commissionRateResolver = commissionRateResolver;
        this.negotiationThreadRepository = negotiationThreadRepository;
        this.stripeCashGateway = stripeCashGateway;
        this.bidGridItemRepository = bidGridItemRepository;
        this.firebaseContact = firebaseContact;
    }

    /** Visible for testing — injects a fixed clock. */
    void setClock(Clock clock) { this.clock = clock; }

    // --- Commission calculation ---

    /** Commission au taux global (estimation sans contexte voyageur/expéditeur). */
    public BigDecimal computeCommission(BigDecimal declaredValue) {
        return computeCommission(declaredValue, props.rate());
    }

    /** Commission à un taux donné, avec plancher {@code minimumAmount}. */
    public BigDecimal computeCommission(BigDecimal declaredValue, BigDecimal rate) {
        BigDecimal pct = declaredValue.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return pct.compareTo(props.minimumAmount()) < 0 ? props.minimumAmount() : pct;
    }

    /**
     * Calcule la commission pour un bid (weightKg × pricePerKg) au taux EFFECTIF
     * (promo > overrides > global via {@link CommissionRateResolver}) et fige ce taux
     * en snapshot sur le bid ({@code bids.commission_rate}) — même sémantique que l'escrow.
     * Si un promoCode est présent mais invalide (expiré, épuisé), fallback silencieux.
     *
     * <p><b>Bid négocié</b> ({@code negotiatedNetEur} renseigné) : le barème de
     * l'annonce n'a plus aucun rapport avec ce que les deux parties ont convenu, et le
     * taux a été figé à l'ouverture du fil. On lit donc l'accord, on ne recalcule ni
     * l'assiette ni le taux — même raisonnement que
     * {@link #settleNegotiationCommission} pour les fils de demande d'envoi. Sans
     * cela, un voyageur ayant accepté 45 € pour un colis tarifé 200 € au barème se
     * verrait prélever la commission des 200 €.
     */
    public BigDecimal computeBidCommission(BidEntity bid, AnnouncementEntity announcement) {
        if (bid.getNegotiatedNetEur() != null && bid.getCommissionRate() != null) {
            return computeCommission(bid.getNegotiatedNetEur(), bid.getCommissionRate());
        }
        BigDecimal rate;
        if (bid.getPromoCode() != null) {
            try {
                rate = commissionRateResolver.resolve(
                        announcement.getTravelerId(), bid.getSenderId(), bid.getPromoCode());
            } catch (com.yadony.api.common.YadonyBusinessException e) {
                log.warn("Promo {} invalid for cash bid {} — fallback", bid.getPromoCode(), bid.getId());
                rate = commissionRateResolver.resolve(announcement.getTravelerId(), bid.getSenderId());
            }
        } else {
            rate = commissionRateResolver.resolve(announcement.getTravelerId(), bid.getSenderId());
        }
        bid.setCommissionRate(rate);
        // Base de commission = part kilo (poids × prix/kg, null en mode GRID pur)
        // + part grille (somme des articles du bid). Aligné sur le calcul net de
        // BidService — sans quoi un bid grille (weightKg null) provoquait un NPE.
        BigDecimal kgNet = (bid.getWeightKg() != null && announcement.getPricePerKg() != null)
                ? bid.getWeightKg().multiply(announcement.getPricePerKg())
                : BigDecimal.ZERO;
        BigDecimal gridNet = bidGridItemRepository.findByBidId(bid.getId()).stream()
                .map(i -> i.getUnitPriceNetSnapshot().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashAmount = kgNet.add(gridNet);
        return computeCommission(cashAmount, rate);
    }

    // --- Card registration ---

    @Transactional
    public void saveCommissionMethod(UUID userId, String paymentMethodOrSetupIntentId) {
        UserEntity user = userRepo.findById(userId).orElseThrow();
        try {
            String paymentMethodId;
            if (paymentMethodOrSetupIntentId.startsWith("seti_")) {
                SetupIntent si = stripeCashGateway.retrieveSetupIntent(paymentMethodOrSetupIntentId);
                paymentMethodId = si.getPaymentMethod();
                if (paymentMethodId == null) {
                    throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "setup-not-confirmed", "Setup Not Confirmed",
                            "Le setup intent n'a pas encore de méthode de paiement attachée");
                }
            } else {
                paymentMethodId = paymentMethodOrSetupIntentId;
            }
            PaymentMethod pm = stripeCashGateway.retrievePaymentMethod(paymentMethodId);
            if (pm.getCard() == null) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-payment-method", "Invalid Payment Method",
                        "La méthode de paiement fournie n'est pas une carte");
            }
            user.setCommissionPaymentMethodId(paymentMethodId);
            user.setCommissionCardBrand(pm.getCard().getBrand());
            user.setCommissionCardLast4(pm.getCard().getLast4());
            user.setCommissionCardExpMonth(pm.getCard().getExpMonth().intValue());
            user.setCommissionCardExpYear(pm.getCard().getExpYear().intValue());
            userRepo.save(user);
        } catch (StripeException e) {
            throw new RuntimeException("Impossible de récupérer les données Stripe", e);
        }
    }

    @Transactional
    public SetupCommissionMethodResponse setupCommissionMethod(UUID userId) {
        UserEntity user = userRepo.findById(userId).orElseThrow();
        ensureStripeCustomer(user);
        try {
            SetupIntentCreateParams params = SetupIntentCreateParams.builder()
                    .setCustomer(user.getStripeCustomerId())
                    .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
                    .addPaymentMethodType("card")
                    .build();
            SetupIntent intent = stripeCashGateway.createSetupIntent(params);
            return new SetupCommissionMethodResponse(intent.getClientSecret());
        } catch (StripeException e) {
            throw new RuntimeException("Failed to create SetupIntent", e);
        }
    }

    public CommissionMethodResponse getCommissionMethod(UUID userId) {
        UserEntity user = userRepo.findById(userId).orElseThrow();
        if (user.getCommissionPaymentMethodId() == null) return null;

        YearMonth cardExp = YearMonth.of(user.getCommissionCardExpYear(), user.getCommissionCardExpMonth());
        YearMonth now = YearMonth.from(LocalDate.now(clock));
        long monthsUntilExpiry = now.until(cardExp, ChronoUnit.MONTHS);

        ExpirationStatus status;
        if (cardExp.isBefore(now)) {
            status = ExpirationStatus.EXPIRED;
        } else if (monthsUntilExpiry <= 1) {
            status = ExpirationStatus.EXPIRES_SOON;
        } else {
            status = ExpirationStatus.VALID;
        }

        return new CommissionMethodResponse(
                user.getCommissionCardBrand(),
                user.getCommissionCardLast4(),
                user.getCommissionCardExpMonth(),
                user.getCommissionCardExpYear(),
                status);
    }

    @Transactional
    public void detachCommissionMethod(UUID userId) {
        UserEntity user = userRepo.findById(userId).orElseThrow();
        if (user.getCommissionPaymentMethodId() == null) return;

        String pmId = user.getCommissionPaymentMethodId();
        try {
            PaymentMethod pm = stripeCashGateway.retrievePaymentMethod(pmId);
            pm.detach();
        } catch (StripeException e) {
            log.warn("Stripe detach failed for PM {}, cleaning up DB anyway: {}", pmId, e.getMessage());
        }

        user.setCommissionPaymentMethodId(null);
        user.setCommissionCardBrand(null);
        user.setCommissionCardLast4(null);
        user.setCommissionCardExpMonth(null);
        user.setCommissionCardExpYear(null);
        userRepo.save(user);

        events.publishEvent(new CommissionMethodDetachedEvent(userId));
    }

    // --- Commission charging ---

    @Transactional
    public AcceptBidResponse chargeCommission(BidEntity bid, UUID travelerId) {
        if (bid.getCommissionStatus() == CommissionStatus.CHARGED) {
            return AcceptBidResponse.accepted();
        }

        UserEntity traveler = userRepo.findById(travelerId).orElseThrow();
        if (traveler.getCommissionPaymentMethodId() == null) {
            throw new CommissionMethodMissingException();
        }

        AnnouncementEntity announcement = announcementRepo.findById(bid.getAnnouncementId()).orElseThrow();
        BigDecimal commission = computeBidCommission(bid, announcement);
        // La commission est libellée dans la devise snapshottée du bid : convertir en
        // unités mineures avec un x100 fixe fausserait XOF/XAF (minorUnit = 0) d'un
        // facteur 100, et un PaymentIntent en "eur" débiterait la mauvaise devise.
        CurrencyAmount commissionAmount = CurrencyAmount.of(
                commission, SupportedCurrency.fromCodeOrDefault(bid.getCurrency()));
        String idempotencyKey = "bid_accept_" + bid.getId() + "_v" + bid.getCommissionRetryCount();

        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(commissionAmount.minor())
                    .setCurrency(commissionAmount.currency().code())
                    .setCustomer(traveler.getStripeCustomerId())
                    .setPaymentMethod(traveler.getCommissionPaymentMethodId())
                    .setOffSession(true)
                    .setConfirm(true)
                    .setDescription("Commission cash bid " + bid.getId())
                    .putMetadata("bid_id", bid.getId().toString())
                    .putMetadata("commission_purpose", "cash_bid")
                    .build();
            RequestOptions opts = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
            PaymentIntent pi = stripeCashGateway.createPaymentIntent(params, opts);

            bid.setCommissionPaymentIntentId(pi.getId());

            return switch (pi.getStatus()) {
                case "succeeded" -> {
                    bid.setCommissionStatus(CommissionStatus.CHARGED);
                    bid.setCommissionChargedVia(CommissionChargedVia.CARD);
                    bidRepo.save(bid);
                    yield AcceptBidResponse.accepted();
                }
                case "requires_action" -> {
                    bid.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
                    bidRepo.save(bid);
                    yield AcceptBidResponse.requires3ds(pi.getClientSecret(), pi.getId());
                }
                default -> {
                    bid.setCommissionStatus(CommissionStatus.FAILED);
                    bidRepo.save(bid);
                    yield AcceptBidResponse.failed("Statut PaymentIntent inattendu : " + pi.getStatus());
                }
            };
        } catch (CardException e) {
            bid.setCommissionStatus(CommissionStatus.FAILED);
            bid.setCommissionRetryCount(bid.getCommissionRetryCount() + 1);
            bidRepo.save(bid);
            // e.getCode() can be null for some Stripe error subtypes — guard before switch
            String userMessage = switch (e.getCode() != null ? e.getCode() : "") {
                case "expired_card" -> "Votre carte de commission est expirée.";
                case "insufficient_funds" -> "Fonds insuffisants sur votre carte de commission.";
                case "authentication_required" -> "Votre carte nécessite une authentification supplémentaire.";
                default -> "Votre carte de commission a été refusée.";
            };
            return AcceptBidResponse.failed(userMessage);
        } catch (StripeException e) {
            throw new CommissionChargeFailedException("Erreur Stripe lors du débit de commission", e);
        }
    }

    /**
     * Prélève la commission depuis le wallet du voyageur.
     * Garde idempotente : vérifie qu'aucune transaction COMMISSION_DEDUCTED n'existe déjà
     * pour ce bid (WalletService.debit n'est pas idempotent en lui-même).
     * Pose commissionStatus=CHARGED et commissionChargedVia=WALLET.
     */
    @Transactional
    public void chargeCommissionFromWallet(BidEntity bid, UUID travelerId, BigDecimal commission) {
        if (walletTransactionRepository.existsByUserIdAndBidIdAndType(
                travelerId, bid.getId(), WalletTransactionType.COMMISSION_DEDUCTED)) {
            log.info("Commission wallet déjà prélevée pour bid {}, idempotent skip", bid.getId());
            return;
        }
        walletService.debit(travelerId, bid.getCurrency(), commission, WalletTransactionType.COMMISSION_DEDUCTED, bid.getId());
        bid.setCommissionStatus(CommissionStatus.CHARGED);
        bid.setCommissionChargedVia(CommissionChargedVia.WALLET);
        bidRepo.save(bid);
        auditService.log("payment", bid.getId(), "COMMISSION_CHARGED_WALLET",
                travelerId, Map.of("commission", commission.toPlainString()));
    }

    /**
     * Prélève la commission automatiquement : wallet en priorité, carte en fallback.
     * Utilisé par les flux asynchrones (mobile money) où il n'y a pas d'interaction utilisateur.
     * Si ni wallet ni carte ne sont disponibles ou si la carte nécessite 3DS (impossible en async),
     * pose commissionStatus=FAILED et logue un audit (créance à recouvrer).
     */
    @Transactional
    public void chargeCommissionAuto(BidEntity bid, UUID travelerId) {
        AnnouncementEntity announcement = announcementRepo.findById(bid.getAnnouncementId()).orElseThrow();
        BigDecimal commission = computeBidCommission(bid, announcement);

        // 1) Wallet prioritaire
        BigDecimal balance = walletService.getBalance(travelerId, bid.getCurrency());
        if (balance.compareTo(commission) >= 0) {
            try {
                chargeCommissionFromWallet(bid, travelerId, commission);
                return;
            } catch (InsufficientWalletBalanceException e) {
                // Race TOCTOU : solde a chuté entre getBalance et debit → fallback carte
                log.warn("Race TOCTOU wallet pour bid {} — fallback carte", bid.getId());
            }
        }

        // 2) Fallback carte automatique
        UserEntity traveler = userRepo.findById(travelerId).orElseThrow();
        if (traveler.getCommissionPaymentMethodId() != null) {
            try {
                AcceptBidResponse r = chargeCommission(bid, travelerId);
                if (r.status() == AcceptanceStatusDto.ACCEPTED) {
                    bid.setCommissionChargedVia(CommissionChargedVia.CARD);
                    bidRepo.save(bid);
                    return;
                }
                // REQUIRES_3DS impossible en async → créance
                log.warn("Commission carte bid {} : statut={} en async → créance commission",
                        bid.getId(), r.status());
            } catch (RuntimeException e) {
                // Erreur Stripe transitoire (panne API, timeout, rate limit) — créance.
                // On NE laisse PAS l'exception remonter pour ne pas rollback la tx REQUIRES_NEW
                // du listener MM (qui a déjà commité le paiement principal).
                log.error("Commission carte async échouée pour bid {} : {}", bid.getId(), e.getMessage());
            }
        }

        // 3) Ni wallet ni carte disponible/valide → créance
        bid.setCommissionStatus(CommissionStatus.FAILED);
        bidRepo.save(bid);
        auditService.log("payment", bid.getId(), "COMMISSION_AUTO_FAILED", travelerId,
                Map.of("reason", "no-wallet-no-card"));
        log.error("Commission auto impossible pour bid {} travelerId {} — ni wallet ni carte", bid.getId(), travelerId);
    }

    /**
     * Règle la commission Yadony (net × taux) d'un thread de négociation CASH, à la
     * demande du voyageur. Le montant se calcule TOUJOURS depuis le net négocié passé
     * en paramètre, jamais depuis le prix/kg de l'annonce liée ({@link #computeBidCommission}),
     * qui n'a aucun rapport avec le prix convenu dès que le trajet n'est pas dédié.
     *
     * <p>{@code WALLET_FIRST} ne bascule PAS sur la carte tout seul : un solde court
     * renvoie {@code INSUFFICIENT_WALLET} et laisse le voyageur choisir entre
     * recharger et payer par carte. Le débit carte n'a lieu que sur
     * {@code CommissionSource.CARD}, choix explicite. Même contrat que
     * {@link #acceptCashBid(UUID, UUID, CommissionSource)} pour le flux classique.
     *
     * <p>Ne lève jamais sur un refus normal (solde court, carte refusée, 3DS
     * requis) : renvoie le statut correspondant. Idempotente : renvoie
     * {@code accepted()} si {@code thread.commissionStatus} vaut déjà {@code CHARGED}.
     *
     * <p>Un statut Stripe {@code requires_action} renvoie {@code REQUIRES_3DS}
     * (client secret + paymentIntentId) plutôt qu'un échec définitif : contrairement
     * au flux classique (déclenché par l'expéditeur, voyageur absent), c'est ici le
     * voyageur qui règle lui-même depuis son téléphone — il peut compléter le 3DS.
     *
     * <p>Appelée par {@code NegotiationService.settleCommission} (via
     * {@link com.yadony.api.requests.CashGatePort}) lorsque le voyageur règle
     * volontairement la commission d'un thread {@code AWAITING_COMMISSION} — seul
     * chemin de prélèvement de commission pour une négociation cash, la demande
     * restant intacte tant qu'il n'a pas eu lieu.
     */
    @Transactional
    public AcceptBidResponse settleNegotiationCommission(
            UUID travelerId, UUID senderId, UUID threadId, BigDecimal net, CommissionSource source) {
        com.yadony.api.requests.entity.NegotiationThreadEntity thread =
                negotiationThreadRepository.findById(threadId).orElseThrow();

        if (NEGO_COMMISSION_CHARGED.equals(thread.getCommissionStatus())) {
            return AcceptBidResponse.accepted();
        }

        BigDecimal rate = commissionRateResolver.resolve(travelerId, senderId);
        BigDecimal commission = net.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        if (commission.signum() <= 0) {
            markNegotiationCommissionCharged(thread, NEGO_COMMISSION_VIA_WALLET, travelerId, commission);
            return AcceptBidResponse.accepted();
        }

        if (source == CommissionSource.WALLET_FIRST) {
            BigDecimal balance = walletService.getBalance(travelerId, thread.getCurrency());
            if (balance.compareTo(commission) >= 0) {
                try {
                    walletService.debit(travelerId, thread.getCurrency(), commission,
                            WalletTransactionType.COMMISSION_DEDUCTED,
                            threadId.toString(), "nego_commission_wallet_" + threadId);
                    markNegotiationCommissionCharged(thread, NEGO_COMMISSION_VIA_WALLET, travelerId, commission);
                    return AcceptBidResponse.accepted();
                } catch (InsufficientWalletBalanceException e) {
                    // Race TOCTOU : le solde a chuté entre getBalance et debit. On ne
                    // bascule pas sur la carte sans consentement — le voyageur relancera.
                    balance = e.getAvailableBalance();
                }
            }
            UserEntity traveler = userRepo.findById(travelerId).orElseThrow();
            return AcceptBidResponse.insufficientWallet(
                    balance, commission, traveler.getCommissionPaymentMethodId() != null, thread.getCurrency());
        }

        // CommissionSource.CARD : le voyageur a explicitement choisi sa carte.
        UserEntity traveler = userRepo.findById(travelerId).orElseThrow();
        if (traveler.getCommissionPaymentMethodId() == null) {
            auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_FAILED", travelerId,
                    Map.of("reason", "no-commission-card"));
            return AcceptBidResponse.failed("no-commission-card");
        }
        CurrencyAmount commissionAmount = CurrencyAmount.of(
                commission, SupportedCurrency.fromCodeOrDefault(thread.getCurrency()));
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(commissionAmount.minor())
                    .setCurrency(commissionAmount.currency().code())
                    .setCustomer(traveler.getStripeCustomerId())
                    .setPaymentMethod(traveler.getCommissionPaymentMethodId())
                    .setOffSession(true)
                    .setConfirm(true)
                    .setDescription("Commission cash négociation " + threadId)
                    .putMetadata("negotiation_thread_id", threadId.toString())
                    .putMetadata("commission_purpose", "cash_negotiation")
                    .build();
            // Discriminant de réessai (commissionRetryCount) : sans lui, la clé reste
            // figée pour toujours après un échec et Stripe rejoue la première réponse
            // en cache (ex. "requires_action" mort) au lieu de retenter un débit réel —
            // le voyageur ne pourrait alors plus jamais régler sa commission (cf. revue
            // task 5, critique 4). Même convention que chargeCommission (bid cash).
            RequestOptions opts = RequestOptions.builder()
                    .setIdempotencyKey("nego_commission_" + threadId + "_v" + thread.getCommissionRetryCount())
                    .build();
            PaymentIntent pi = stripeCashGateway.createPaymentIntent(params, opts);
            // Persisté quel que soit le statut (comme chargeCommission pour le flux
            // classique) : une confirmation ultérieure doit pouvoir retrouver ce PI,
            // notamment pour compléter le 3DS.
            thread.setCommissionPaymentIntentId(pi.getId());
            if ("succeeded".equals(pi.getStatus())) {
                markNegotiationCommissionCharged(thread, NEGO_COMMISSION_VIA_CARD, travelerId, commission);
                return AcceptBidResponse.accepted();
            }
            if ("requires_action".equals(pi.getStatus())) {
                // Contrairement au flux classique où l'expéditeur déclenche le débit à
                // l'insu du voyageur (absent, 3DS impossible), ici c'est le voyageur
                // lui-même qui règle depuis son téléphone : il est présent et peut
                // compléter l'authentification. Ne PAS bloquer en FAILED, ne PAS
                // incrémenter le compteur de réessai — l'attente de la 3DS n'est pas un
                // échec, {@link #confirmNegotiationCommissionAcceptance} tranchera.
                thread.setCommissionStatus(NEGO_COMMISSION_REQUIRES_3DS);
                negotiationThreadRepository.save(thread);
                return AcceptBidResponse.requires3ds(pi.getClientSecret(), pi.getId());
            }
            recordCommissionFailure(thread, travelerId, Map.of("reason", "card-status-" + pi.getStatus()));
            return AcceptBidResponse.failed("card-status-" + pi.getStatus());
        } catch (CardException e) {
            // La carte a été refusée : la clé d'idempotence a bien été consommée par
            // Stripe, qui rejouerait ce refus à l'identique. Il FAUT donc incrémenter
            // le compteur pour que la prochaine tentative parte sur une clé neuve.
            recordCommissionFailure(thread, travelerId,
                    Map.of("reason", "card-declined", "code", e.getCode() != null ? e.getCode() : ""));
            return AcceptBidResponse.failed("card-declined");
        } catch (StripeException e) {
            // Volontairement SANS recordCommissionFailure : sur une erreur réseau/API,
            // on ne sait pas si Stripe a reçu la demande. Réutiliser la MÊME clé
            // d'idempotence est la conduite recommandée — elle laisse Stripe reprendre
            // l'appel plutôt que d'ouvrir un second PaymentIntent, donc un second débit.
            // Incrémenter le compteur ici échangerait une erreur transitoire contre un
            // risque de double prélèvement. Le statut local reste donc inchangé : rien
            // n'a été durablement débité, le voyageur peut relancer tel quel.
            log.error("Commission carte négociation thread {} : erreur Stripe {}", threadId, e.getMessage());
            auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_FAILED", travelerId,
                    Map.of("reason", "stripe-error"));
            return AcceptBidResponse.failed("stripe-error");
        }
    }

    /**
     * Marque un règlement de commission de négociation comme échoué : statut
     * {@code FAILED}, compteur de réessai incrémenté, puis trace d'audit.
     *
     * <p>L'incrément du compteur est le cœur de la méthode : il alimente le suffixe
     * {@code _v&lt;n&gt;} de la clé d'idempotence Stripe. Sans lui, la clé reste figée
     * après un échec et Stripe rejoue indéfiniment sa première réponse en cache au
     * lieu de retenter un débit réel — le voyageur ne pourrait alors plus jamais
     * régler sa commission. À n'appeler que sur un échec où la clé courante a
     * réellement été consommée par Stripe (refus de carte, statut terminal
     * inattendu), jamais sur une erreur de transport.
     */
    private void recordCommissionFailure(com.yadony.api.requests.entity.NegotiationThreadEntity thread,
                                         UUID travelerId, Map<String, Object> auditPayload) {
        thread.setCommissionStatus(NEGO_COMMISSION_FAILED);
        thread.setCommissionRetryCount(thread.getCommissionRetryCount() + 1);
        negotiationThreadRepository.save(thread);
        auditService.log("NEGOTIATION_THREAD", thread.getId(), "CASH_COMMISSION_FAILED", travelerId, auditPayload);
    }

    /**
     * Confirme, après authentification 3D Secure, le règlement de commission d'un
     * thread de négociation CASH : relit le PaymentIntent auprès de Stripe plutôt
     * que de rappeler {@link #settleNegotiationCommission} — la clé d'idempotence
     * Stripe ("nego_commission_" + threadId) rejouerait sinon indéfiniment la
     * réponse "requires_action" au lieu de refléter l'issue réelle de la 3DS.
     * Miroir de {@link #confirmCommissionAcceptance(UUID, UUID)} pour le flux
     * classique (bid cash).
     *
     * <p>Ownership vérifiée avant toute lecture Stripe, même si {@code
     * NegotiationService.confirmCommission} l'a déjà fait — défense en profondeur,
     * même contrat que {@link #confirmCommissionAcceptance(UUID, UUID)}.
     *
     * <p>Idempotente : renvoie {@code ok()} si {@code thread.commissionStatus} vaut
     * déjà {@code CHARGED}.
     */
    @Transactional
    public ConfirmAcceptanceResponse confirmNegotiationCommissionAcceptance(UUID threadId, UUID travelerId) {
        com.yadony.api.requests.entity.NegotiationThreadEntity thread =
                negotiationThreadRepository.findById(threadId).orElseThrow();
        if (!thread.getTravelerId().equals(travelerId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                    "forbidden", "Forbidden", "Ce fil de négociation ne vous appartient pas");
        }
        if (NEGO_COMMISSION_CHARGED.equals(thread.getCommissionStatus())) {
            return ConfirmAcceptanceResponse.ok();
        }
        if (thread.getCommissionPaymentIntentId() == null) {
            return ConfirmAcceptanceResponse.fail("Aucun PaymentIntent à confirmer.");
        }
        try {
            PaymentIntent pi = stripeCashGateway.retrievePaymentIntent(thread.getCommissionPaymentIntentId());
            if ("succeeded".equals(pi.getStatus())) {
                SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(thread.getCurrency());
                BigDecimal commission = BigDecimal.valueOf(pi.getAmount()).movePointLeft(currency.minorUnit());
                markNegotiationCommissionCharged(thread, NEGO_COMMISSION_VIA_CARD, travelerId, commission);
                return ConfirmAcceptanceResponse.ok();
            }
            // La 3DS n'a pas abouti (refusée, abandonnée, statut inattendu) : marquer
            // l'échec ET incrémenter le compteur de réessai, sinon un nouveau règlement
            // (settleCommission) réutiliserait la même clé d'idempotence Stripe et
            // rejouerait indéfiniment ce PaymentIntent mort (critique 4).
            //
            // Annuler d'abord ce PaymentIntent, sinon on l'abandonne vivant tout en
            // autorisant son successeur : deux PaymentIntents coexisteraient sur le
            // thread, et si la banque finalisait le challenge du premier après coup,
            // ce débit ne serait ni scellé, ni remboursé, ni tracé — le champ
            // commissionPaymentIntentId ayant déjà été écrasé. Même convention que
            // OrphanedPaymentIntentCleanupJob pour le flux bid classique.
            cancelOrphanedCommissionPaymentIntent(threadId, thread.getCommissionPaymentIntentId());
            recordCommissionFailure(thread, travelerId, Map.of("reason", "card-status-" + pi.getStatus()));
            return ConfirmAcceptanceResponse.fail("PaymentIntent status: " + pi.getStatus());
        } catch (StripeException e) {
            // Voir settleNegotiationCommission : pas de recordCommissionFailure sur une
            // erreur de transport, la clé d'idempotence doit rester réutilisable.
            auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_FAILED", travelerId,
                    Map.of("reason", "stripe-error"));
            return ConfirmAcceptanceResponse.fail("Erreur Stripe : " + e.getMessage());
        }
    }

    /**
     * Rembourse via Stripe une commission de négociation qui a fini par être
     * débitée (PaymentIntent {@code succeeded}, 3DS aboutie) alors que le thread
     * n'est plus éligible au scellement — la demande a été emportée par un
     * concurrent pendant que le voyageur complétait l'authentification. Appelée
     * par {@code NegotiationService.confirmCommission} quand elle détecte que la
     * place est déjà prise (cf. revue task 5, critique 2).
     *
     * <p>Relit systématiquement le PaymentIntent auprès de Stripe plutôt que de se
     * fier au {@code commissionStatus} local du thread : ce dernier peut encore
     * porter {@code REQUIRES_3DS} alors que la 3DS a très bien pu réussir entre
     * temps côté Stripe. No-op (retourne {@code false}) si rien n'a jamais été
     * débité par carte, ou si un remboursement a déjà eu lieu. En cas d'échec du
     * remboursement lui-même, une entrée {@code audit_log} dédiée (paymentIntentId,
     * montant, motif) rend le cas traçable pour un remboursement manuel — jamais
     * d'exception qui remonte, l'appelant doit toujours pouvoir répondre au
     * voyageur.
     *
     * <p>{@code REQUIRES_NEW} est vital, pas cosmétique : l'appelant ({@code
     * NegotiationService.confirmCommission}) lève un 409 juste après nous avoir
     * appelés, pour dire au voyageur que sa place a été prise. En propagation
     * {@code REQUIRED}, ce rollback effacerait le {@code REFUNDED} et l'entrée
     * d'audit — alors que le remboursement Stripe, lui, est un appel HTTP externe
     * bien parti. Il resterait un remboursement sans aucune trace applicative, et
     * surtout, sur la branche d'échec, plus rien pour déclencher le remboursement
     * manuel. Même pattern que {@code BidCancelledCommissionRefundListener}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean refundNegotiationCommissionIfCharged(UUID threadId, UUID travelerId) {
        com.yadony.api.requests.entity.NegotiationThreadEntity thread =
                negotiationThreadRepository.findById(threadId).orElseThrow();
        if (NEGO_COMMISSION_REFUNDED.equals(thread.getCommissionStatus())) {
            return false;
        }
        String paymentIntentId = thread.getCommissionPaymentIntentId();
        if (paymentIntentId == null) {
            return false;
        }
        PaymentIntent pi;
        try {
            pi = stripeCashGateway.retrievePaymentIntent(paymentIntentId);
        } catch (StripeException e) {
            log.error("Impossible de relire le PaymentIntent {} du thread {} pour décider d'un remboursement : {}",
                    paymentIntentId, threadId, e.getMessage());
            auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_REFUND_CHECK_FAILED", travelerId,
                    Map.of("paymentIntentId", paymentIntentId,
                            "reason", e.getMessage() != null ? e.getMessage() : "stripe-read-error"));
            return false;
        }
        if (!"succeeded".equals(pi.getStatus())) {
            // Rien n'a été réellement débité (3DS jamais complétée, ou refusée) —
            // rien à rembourser, le voyageur n'a rien perdu.
            return false;
        }
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(thread.getCurrency());
        BigDecimal amount = BigDecimal.valueOf(pi.getAmount()).movePointLeft(currency.minorUnit());
        try {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntentId)
                    .build();
            RequestOptions opts = RequestOptions.builder()
                    .setIdempotencyKey("nego_commission_refund_" + threadId)
                    .build();
            stripeCashGateway.createRefund(params, opts);
            thread.setCommissionStatus(NEGO_COMMISSION_REFUNDED);
            negotiationThreadRepository.save(thread);
            auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_REFUNDED_SPOT_TAKEN", travelerId,
                    Map.of("paymentIntentId", paymentIntentId, "amount", amount.toPlainString(),
                            "reason", "request-already-accepted-by-competitor"));
            return true;
        } catch (StripeException e) {
            thread.setCommissionStatus(NEGO_COMMISSION_REFUND_FAILED);
            negotiationThreadRepository.save(thread);
            log.error("Remboursement commission négociation thread {} échoué : {}", threadId, e.getMessage());
            // Traçabilité obligatoire pour un remboursement manuel : le voyageur a bien
            // été débité, Stripe refuse le remboursement automatique.
            auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_REFUND_FAILED", travelerId,
                    Map.of("paymentIntentId", paymentIntentId, "amount", amount.toPlainString(),
                            "reason", e.getMessage() != null ? e.getMessage() : "stripe-error"));
            return false;
        }
    }

    /**
     * Annule chez Stripe un PaymentIntent de commission de négociation qu'on
     * s'apprête à abandonner, pour qu'il ne puisse plus jamais aboutir dans le dos
     * de la plateforme. N'échoue jamais : un PaymentIntent déjà annulé, expiré ou
     * inaccessible ne doit pas empêcher le voyageur de retenter son règlement.
     */
    private void cancelOrphanedCommissionPaymentIntent(UUID threadId, String paymentIntentId) {
        if (paymentIntentId == null) {
            return;
        }
        try {
            stripeCashGateway.cancelPaymentIntent(paymentIntentId);
        } catch (StripeException e) {
            log.warn("Annulation du PaymentIntent commission {} (thread {}) échouée : {}",
                    paymentIntentId, threadId, e.getMessage());
        }
    }

    private void markNegotiationCommissionCharged(
            com.yadony.api.requests.entity.NegotiationThreadEntity thread,
            String via, UUID travelerId, BigDecimal commission) {
        thread.setCommissionStatus(NEGO_COMMISSION_CHARGED);
        thread.setCommissionChargedVia(via);
        negotiationThreadRepository.save(thread);
        auditService.log("NEGOTIATION_THREAD", thread.getId(), "CASH_COMMISSION_CHARGED", travelerId,
                Map.of("commission", commission.toPlainString(), "via", via));
    }

    /**
     * True if the traveler has a commission payment card registered. Used by the
     * negotiation trip-linking gate to let a wallet-short traveler consent to a
     * card charge (collected at finalize, wallet-first then card).
     */
    @Transactional(readOnly = true)
    public boolean hasCommissionCard(UUID travelerId) {
        return userRepo.findById(travelerId)
                .map(u -> u.getCommissionPaymentMethodId() != null)
                .orElse(false);
    }

    // --- Cash bid acceptance ---

    @Transactional
    public AcceptBidResponse acceptCashBid(UUID bidId, UUID travelerId, CommissionSource commissionSource) {
        BidEntity bid = bidRepo.findByIdForUpdate(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "bid-not-found", "Bid Not Found", "Demande introuvable"));
        AnnouncementEntity announcement = announcementRepo.findByIdForUpdate(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        if (!announcement.getTravelerId().equals(travelerId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                    "forbidden", "Forbidden", "Ce trajet ne vous appartient pas");
        }
        if (!announcement.getAcceptedPaymentMethods().contains(com.yadony.api.payments.cash.PaymentMethod.CASH)) {
            throw new InvalidPaymentMethodForAnnouncementException(
                    "Cette annonce n'accepte pas le paiement en espèces");
        }
        if (bid.getPaymentMethod() != com.yadony.api.payments.cash.PaymentMethod.CASH) {
            throw new InvalidPaymentMethodForAnnouncementException(
                    "Ce bid n'est pas un bid cash");
        }
        if (bid.getStatus() == BidStatus.ACCEPTED
                && bid.getCommissionStatus() == CommissionStatus.CHARGED) {
            return AcceptBidResponse.accepted();
        }
        // « Kilo libre » (KG_FREE) : capacité non bornée — pas de rejet de capacité.
        // Un bid grille pure n'a pas de poids (weightKg null) → aucun contrôle de
        // capacité kilo à faire (sinon NPE).
        if (announcement.getCapacityUnit() != CapacityUnit.KG_FREE
                && bid.getWeightKg() != null
                && bid.getWeightKg().compareTo(announcement.getAvailableKg()) > 0) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "capacity-insufficient", "Insufficient Capacity",
                    "Capacité insuffisante pour accepter cette demande");
        }

        BigDecimal commission = computeBidCommission(bid, announcement);

        if (commissionSource == CommissionSource.WALLET_FIRST) {
            BigDecimal balance = walletService.getBalance(travelerId, bid.getCurrency());
            if (balance.compareTo(commission) >= 0) {
                try {
                    chargeCommissionFromWallet(bid, travelerId, commission);
                    finalizeBidAcceptance(bid, announcement, travelerId);
                    return AcceptBidResponse.accepted();
                } catch (InsufficientWalletBalanceException e) {
                    // Race TOCTOU : solde a chuté entre getBalance et debit
                    balance = e.getAvailableBalance();
                }
            }
            // Solde insuffisant → informer le voyageur
            UserEntity traveler = userRepo.findById(travelerId).orElseThrow();
            boolean hasCard = traveler.getCommissionPaymentMethodId() != null;
            return AcceptBidResponse.insufficientWallet(balance, commission, hasCard, bid.getCurrency());
        }

        // commissionSource == CARD → comportement carte existant
        AcceptBidResponse response = chargeCommission(bid, travelerId);
        if (response.status() == AcceptanceStatusDto.ACCEPTED) {
            bid.setCommissionChargedVia(CommissionChargedVia.CARD);
            bidRepo.save(bid);
            finalizeBidAcceptance(bid, announcement, travelerId);
        }
        return response;
    }

    @Transactional
    public ConfirmAcceptanceResponse confirmCommissionAcceptance(UUID bidId, UUID travelerId) {
        BidEntity bid = bidRepo.findById(bidId).orElseThrow();
        if (bid.getCommissionStatus() == CommissionStatus.CHARGED
                && bid.getStatus() == BidStatus.ACCEPTED) {
            return ConfirmAcceptanceResponse.ok();
        }
        // Ownership : seul le voyageur propriétaire du trajet peut confirmer, AVANT
        // toute lecture Stripe ou mutation d'état (aligné sur acceptCashBid). Sans ce
        // contrôle, un tiers (tout compte a ROLE_TRAVELER) pouvait déclencher/casser
        // l'acceptation d'un bid d'autrui (flip commissionStatus=FAILED = griefing).
        AnnouncementEntity announcement = announcementRepo.findByIdForUpdate(bid.getAnnouncementId()).orElseThrow();
        if (!announcement.getTravelerId().equals(travelerId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                    "forbidden", "Forbidden", "Ce trajet ne vous appartient pas");
        }
        if (bid.getCommissionStatus() == CommissionStatus.CHARGED) {
            // Pose CARD si absent (idempotent — un bid CHARGED via PI a toujours une carte)
            if (bid.getCommissionChargedVia() == null && bid.getCommissionPaymentIntentId() != null) {
                bid.setCommissionChargedVia(CommissionChargedVia.CARD);
            }
            finalizeBidAcceptance(bid, announcement, announcement.getTravelerId());
            return ConfirmAcceptanceResponse.ok();
        }
        if (bid.getCommissionPaymentIntentId() == null) {
            return ConfirmAcceptanceResponse.fail("Aucun PaymentIntent à confirmer.");
        }
        try {
            PaymentIntent pi = stripeCashGateway.retrievePaymentIntent(bid.getCommissionPaymentIntentId());
            if ("succeeded".equals(pi.getStatus())) {
                bid.setCommissionStatus(CommissionStatus.CHARGED);
                bid.setCommissionChargedVia(CommissionChargedVia.CARD);
                finalizeBidAcceptance(bid, announcement, announcement.getTravelerId());
                return ConfirmAcceptanceResponse.ok();
            }
            bid.setCommissionStatus(CommissionStatus.FAILED);
            bidRepo.save(bid);
            return ConfirmAcceptanceResponse.fail("PaymentIntent status: " + pi.getStatus());
        } catch (StripeException e) {
            return ConfirmAcceptanceResponse.fail("Erreur Stripe : " + e.getMessage());
        }
    }

    /**
     * Rembourse la commission en créditant le wallet du voyageur.
     * Utilisé quand commissionChargedVia=WALLET et qu'un remboursement est dû.
     * Clé d'idempotence intentionnellement distincte selon le déclencheur :
     * les appelants passent la clé appropriée (noshow vs trip-cancel).
     */
    @Transactional
    public void refundCommissionToWallet(BidEntity bid, UUID travelerId, String idempotencyKey) {
        if (bid.getCommissionStatus() == CommissionStatus.REFUNDED) return;
        if (bid.getCommissionStatus() != CommissionStatus.CHARGED) {
            log.warn("refundCommissionToWallet called on bid {} with status {}", bid.getId(), bid.getCommissionStatus());
            return;
        }
        Optional<com.yadony.api.payments.wallet.WalletTransactionEntity> commissionTx =
                walletTransactionRepository.findByUserIdAndBidIdAndType(
                        travelerId, bid.getId(), WalletTransactionType.COMMISSION_DEDUCTED);
        if (commissionTx.isEmpty()) {
            log.warn("refundCommissionToWallet: aucune tx COMMISSION_DEDUCTED pour bid {} traveler {}", bid.getId(), travelerId);
            return;
        }
        BigDecimal refundAmount = commissionTx.get().getAmount().abs();
        walletService.credit(travelerId, commissionTx.get().getCurrency(), refundAmount,
                com.yadony.api.payments.wallet.WalletTransactionType.REFUND,
                "refund-" + bid.getId(), idempotencyKey);
        bid.setCommissionStatus(CommissionStatus.REFUNDED);
        bidRepo.save(bid);
        auditService.log("payment", bid.getId(), "COMMISSION_REFUNDED_TO_WALLET",
                travelerId, Map.of("amount", refundAmount.toPlainString(), "idempotencyKey", idempotencyKey));
    }

    @Transactional
    public void refundCommission(BidEntity bid) {
        if (bid.getCommissionStatus() == CommissionStatus.REFUNDED) return;
        if (bid.getCommissionStatus() != CommissionStatus.CHARGED) {
            log.warn("refundCommission called on bid {} with status {}", bid.getId(), bid.getCommissionStatus());
            return;
        }
        try {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(bid.getCommissionPaymentIntentId())
                    .build();
            RequestOptions opts = RequestOptions.builder()
                    .setIdempotencyKey("bid_refund_" + bid.getId())
                    .build();
            stripeCashGateway.createRefund(params, opts);
            bid.setCommissionStatus(CommissionStatus.REFUNDED);
            bidRepo.save(bid);
        } catch (StripeException e) {
            bid.setCommissionStatus(CommissionStatus.REFUND_FAILED);
            bidRepo.save(bid);
            log.error("Refund failed for bid {}: {}", bid.getId(), e.getMessage());
        }
    }

    // --- Private helpers ---

    private void finalizeBidAcceptance(BidEntity bid, AnnouncementEntity announcement, UUID travelerId) {
        if (bid.getStatus() == BidStatus.ACCEPTED) return;

        bid.setStatus(BidStatus.ACCEPTED);
        if (bid.getQrToken() == null) bid.setQrToken(UUID.randomUUID().toString());
        if (bid.getTrackingToken() == null) bid.setTrackingToken(UUID.randomUUID().toString());
        if (bid.getTrackingNumber() == null) bid.setTrackingNumber(generateTrackingNumber());

        // « Kilo libre » (KG_FREE) : capacité non bornée — on ne décrémente pas
        // availableKg et l'annonce ne passe jamais FULL (cohérent avec BidService).
        final boolean isKgFree = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
        // Un bid grille pure n'a pas de poids (weightKg null) → ne décrémente pas
        // la capacité kilo et ne passe pas l'annonce FULL (cohérent avec BidService).
        if (!isKgFree && bid.getWeightKg() != null) {
            announcement.setAvailableKg(announcement.getAvailableKg().subtract(bid.getWeightKg()));
            if (announcement.getAvailableKg().compareTo(BigDecimal.ZERO) <= 0) {
                announcement.setStatus(AnnouncementStatus.FULL);
            }
        }
        announcementRepo.save(announcement);
        bid.applyHandoverFrom(announcement);
        bidRepo.save(bid);

        events.publishEvent(new BidAcceptedEvent(
                bid.getId(), bid.getSenderId(), travelerId, bid.getAnnouncementId(),
                bid.getPaymentMethod() != null && bid.getPaymentMethod().isMobileMoney()));
        log.info("Cash bid {} finalized as ACCEPTED for traveler {}", bid.getId(), travelerId);
    }

    private String generateTrackingNumber() {
        StringBuilder sb = new StringBuilder("DON-");
        for (int i = 0; i < 8; i++) {
            sb.append(TRACKING_CHARS.charAt(SECURE_RNG.nextInt(TRACKING_CHARS.length())));
        }
        return sb.toString();
    }

    private void ensureStripeCustomer(UserEntity user) {
        if (user.getStripeCustomerId() != null) return;
        try {
            Customer c = stripeCashGateway.createCustomer(CustomerCreateParams.builder()
                    .setEmail(firebaseContact.getContact(user.getFirebaseUid()).email())
                    .putMetadata("yadony_user_id", user.getId().toString())
                    .build());
            user.setStripeCustomerId(c.getId());
            userRepo.save(user);
        } catch (StripeException e) {
            throw new RuntimeException("Failed to create Stripe customer", e);
        }
    }
}
