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
    private static final String NEGO_COMMISSION_FAILED = CommissionStatus.FAILED.name();
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
     */
    public BigDecimal computeBidCommission(BidEntity bid, AnnouncementEntity announcement) {
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
     * Prélève la commission Yadony (net × taux) depuis le voyageur pour un thread de
     * négociation CASH — wallet en priorité, carte en fallback off-session.
     *
     * <p>Appelée synchroniquement depuis {@code NegotiationService.finalizeAfterPayment}
     * (via {@link com.yadony.api.requests.CashGatePort}) au moment où l'expéditeur confirme
     * un trajet négocié en espèces.
     *
     * <p>Contrat strict : ne JAMAIS lever d'exception sur un refus normal (carte refusée,
     * 3DS requis, solde insuffisant sans carte) — retourner {@code false} et poser
     * {@code commissionStatus="FAILED"} sur le thread. Ne lever que sur erreur interne
     * réellement inattendue. La méthode est {@code @Transactional} pour rejoindre la tx
     * du finalize : un débit wallet réussi committe avec la finalisation.
     *
     * @return {@code true} si la commission a été prélevée (ou l'était déjà — idempotent),
     *         {@code false} si elle n'a pas pu l'être.
     */
    @Transactional
    public boolean chargeNegotiationCommission(UUID travelerId, UUID senderId, UUID threadId, BigDecimal net) {
        com.yadony.api.requests.entity.NegotiationThreadEntity thread =
                negotiationThreadRepository.findById(threadId).orElseThrow();

        // Idempotence : déjà prélevé → succès sans re-débit.
        if (NEGO_COMMISSION_CHARGED.equals(thread.getCommissionStatus())) {
            return true;
        }

        BigDecimal rate = commissionRateResolver.resolve(travelerId, senderId);
        BigDecimal commission = net.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        if (commission.signum() <= 0) {
            return true; // rien à prélever
        }

        // 1) Wallet prioritaire — débit SANS bid (réf = threadId dans payment_ref/
        // idempotency_key). On ne passe PAS le threadId dans la colonne FK bid_id.
        BigDecimal balance = walletService.getBalance(travelerId, thread.getCurrency());
        if (balance.compareTo(commission) >= 0) {
            try {
                walletService.debit(travelerId, thread.getCurrency(), commission,
                        WalletTransactionType.COMMISSION_DEDUCTED,
                        threadId.toString(), "nego_commission_wallet_" + threadId);
                thread.setCommissionStatus(NEGO_COMMISSION_CHARGED);
                thread.setCommissionChargedVia(NEGO_COMMISSION_VIA_WALLET);
                negotiationThreadRepository.save(thread);
                auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_CHARGED", travelerId,
                        Map.of("commission", commission.toPlainString(), "via", "WALLET"));
                return true;
            } catch (InsufficientWalletBalanceException e) {
                // Race TOCTOU : solde a chuté entre getBalance et debit → fallback carte
                log.warn("Race TOCTOU wallet pour thread {} — fallback carte", threadId);
            }
        }

        // 2) Fallback carte off-session
        UserEntity traveler = userRepo.findById(travelerId).orElseThrow();
        if (traveler.getCommissionPaymentMethodId() != null) {
            CurrencyAmount commissionAmount = CurrencyAmount.of(
                    commission, SupportedCurrency.fromCodeOrDefault(thread.getCurrency()));
            String idempotencyKey = "nego_commission_" + threadId;
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
                RequestOptions opts = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
                PaymentIntent pi = stripeCashGateway.createPaymentIntent(params, opts);

                if ("succeeded".equals(pi.getStatus())) {
                    thread.setCommissionStatus(NEGO_COMMISSION_CHARGED);
                    thread.setCommissionChargedVia(NEGO_COMMISSION_VIA_CARD);
                    thread.setCommissionPaymentIntentId(pi.getId());
                    negotiationThreadRepository.save(thread);
                    auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_CHARGED", travelerId,
                            Map.of("commission", commission.toPlainString(), "via", "CARD",
                                    "paymentIntentId", pi.getId()));
                    return true;
                }
                // "requires_action" (3DS) ou tout autre statut : le voyageur n'est pas présent
                // au moment de la confirmation de l'expéditeur → échec, on bloque.
                thread.setCommissionStatus(NEGO_COMMISSION_FAILED);
                negotiationThreadRepository.save(thread);
                auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_FAILED", travelerId,
                        Map.of("reason", "card-status-" + pi.getStatus()));
                return false;
            } catch (CardException e) {
                thread.setCommissionStatus(NEGO_COMMISSION_FAILED);
                negotiationThreadRepository.save(thread);
                auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_FAILED", travelerId,
                        Map.of("reason", "card-declined",
                                "code", e.getCode() != null ? e.getCode() : ""));
                return false;
            } catch (StripeException e) {
                thread.setCommissionStatus(NEGO_COMMISSION_FAILED);
                negotiationThreadRepository.save(thread);
                auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_FAILED", travelerId,
                        Map.of("reason", "stripe-error"));
                log.error("Commission carte négociation thread {} : erreur Stripe {}", threadId, e.getMessage());
                return false;
            }
        }

        // 3) Ni wallet suffisant ni carte disponible → échec
        thread.setCommissionStatus(NEGO_COMMISSION_FAILED);
        negotiationThreadRepository.save(thread);
        auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_FAILED", travelerId,
                Map.of("reason", "no-wallet-no-card"));
        log.error("Commission cash négociation impossible pour thread {} traveler {} — ni wallet ni carte",
                threadId, travelerId);
        return false;
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
