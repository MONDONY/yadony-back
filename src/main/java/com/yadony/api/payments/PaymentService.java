package com.yadony.api.payments;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.promo.PromoService;
import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.payments.exceptions.TravelerNotEligibleForPaymentException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.events.BidCreatedEvent;
import com.yadony.api.payments.dto.ConnectAccountResponse;
import com.yadony.api.payments.dto.CreatePaymentRequest;
import com.yadony.api.payments.dto.EphemeralKeyResponse;
import com.yadony.api.payments.dto.OnboardingLinkResponse;
import com.yadony.api.payments.dto.PaymentMethodResponse;
import com.yadony.api.payments.dto.PaymentResponse;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.CurrencyPaymentRails;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.events.StripeOnboardingCompletedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.EphemeralKey;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.AccountUpdateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.EphemeralKeyCreateParams;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentUpdateParams;
import com.stripe.param.PaymentMethodListParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yadony.api.payments.events.PaymentEscrowReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final UserRepository userRepository;
    private final BidRepository bidRepository;
    private final BidGridItemRepository bidGridItemRepository;
    private final AnnouncementRepository announcementRepository;
    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final StripeConnectProperties stripeConnectProperties;
    private final ObjectMapper objectMapper;
    private final AdminAlertService adminAlert;
    private final CommissionRateResolver commissionRateResolver;
    private final PromoService promoService;
    private final StripeGateway stripeGateway;
    private final FirebaseContactService firebaseContact;
    private final com.yadony.api.voucher.CommissionVoucherService voucherService;
    private final ConnectAccountProvisioner connectAccountProvisioner;

    public PaymentService(UserRepository userRepository,
                          BidRepository bidRepository,
                          BidGridItemRepository bidGridItemRepository,
                          AnnouncementRepository announcementRepository,
                          PaymentRepository paymentRepository,
                          AuditService auditService,
                          ApplicationEventPublisher eventPublisher,
                          StripeConnectProperties stripeConnectProperties,
                          ObjectMapper objectMapper,
                          AdminAlertService adminAlert,
                          CommissionRateResolver commissionRateResolver,
                          PromoService promoService,
                          StripeGateway stripeGateway,
                          FirebaseContactService firebaseContact,
                          com.yadony.api.voucher.CommissionVoucherService voucherService,
                          ConnectAccountProvisioner connectAccountProvisioner) {
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.bidGridItemRepository = bidGridItemRepository;
        this.announcementRepository = announcementRepository;
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.stripeConnectProperties = stripeConnectProperties;
        this.objectMapper = objectMapper;
        this.adminAlert = adminAlert;
        this.commissionRateResolver = commissionRateResolver;
        this.promoService = promoService;
        this.stripeGateway = stripeGateway;
        this.firebaseContact = firebaseContact;
        this.voucherService = voucherService;
        this.connectAccountProvisioner = connectAccountProvisioner;
    }

    // ── Story 6.2 : Onboarding Stripe Connect ────────────────────────────────

    public ConnectAccountResponse getConnectAccountStatus(String firebaseUid) {
        UserEntity user = findUser(firebaseUid);
        return new ConnectAccountResponse(user.getStripeAccountId(), user.getStripeAccountStatus(),
                connectAvailableFor(user));
    }

    /**
     * Stripe ne couvre pas tous les pays desservis par yadony (zone XOF, zone XAF, US, CA).
     * L'exposer permet a l'application de masquer l'activation du paiement par carte plutot
     * que de laisser le voyageur la tenter et echouer.
     */
    private boolean connectAvailableFor(UserEntity user) {
        return StripeConnectCountries.isSupported(user.getCountry());
    }

    public ConnectAccountResponse createConnectAccount(String firebaseUid) {
        UserEntity user = findUser(firebaseUid);

        // Lock the user row to prevent concurrent Stripe account creation (race condition guard)
        user = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));

        // Re-check after acquiring lock — another thread may have created the account already.
        // Also verify the existing account still exists in Stripe (it can be manually deleted in
        // sandbox or expire). If it's missing, reset and recreate.
        if (user.getStripeAccountId() != null) {
            try {
                stripeGateway.retrieveAccount(user.getStripeAccountId());
                return new ConnectAccountResponse(user.getStripeAccountId(),
                        user.getStripeAccountStatus(), connectAvailableFor(user));
            } catch (StripeException e) {
                if (!isStripeAccountMissing(e)) {
                    log.error("Failed to verify Stripe account {} for user {}",
                            user.getStripeAccountId(), user.getId(), e);
                    throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                            "stripe-account-verification-failed", "Stripe Error",
                            "Impossible de vérifier le compte de paiement");
                }
                log.warn("Stripe account {} no longer exists for user {} — resetting and recreating",
                        user.getStripeAccountId(), user.getId());
                resetStripeAccountState(user);
                // fall through to creation below
            }
        }

        try {
            // La construction des paramètres et l'appel Stripe sont isolés derrière
            // ConnectAccountProvisioner : cette méthode ne connaît que l'identifiant rendu.
            String accountId = connectAccountProvisioner.provision(user);
            user.setStripeAccountId(accountId);
            user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
            user.setStripeAccountCreatedAt(java.time.Instant.now());
            try {
                userRepository.save(user);
            } catch (Exception saveEx) {
                log.error("Failed to save user after Stripe account creation. orphan_account_id={}, user_id={}",
                        accountId, user.getId(), saveEx);
                throw saveEx;
            }

            auditService.log("USER", user.getId(), "STRIPE_ACCOUNT_CREATED", user.getId(),
                    Map.of("stripeAccountId", accountId));

            log.info("Stripe Connect account created for user {} : {}", user.getId(), accountId);
            return new ConnectAccountResponse(accountId, StripeAccountStatus.PENDING_ONBOARDING,
                    connectAvailableFor(user));

        } catch (YadonyBusinessException e) {
            // Erreur métier déjà qualifiée (ex. 422 country-required levé par
            // ConnectAccountProvisioner.provision) : la laisser remonter telle quelle
            // vers le GlobalExceptionHandler plutôt que de la réemballer en 500 générique.
            throw e;
        } catch (Exception e) {
            log.error("Failed to create Stripe account for user {}", user.getId(), e);
            throw new YadonyBusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "stripe-account-creation-failed", "Stripe Error",
                    "Impossible de créer le compte de paiement");
        }
    }

    public OnboardingLinkResponse createOnboardingLink(String firebaseUid) {
        UserEntity user = findUser(firebaseUid);

        if (user.getStripeAccountId() == null) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "stripe-account-required", "No Stripe Account",
                    "Créez d'abord un compte Stripe Connect");
        }

        try {
            AccountLinkCreateParams params = AccountLinkCreateParams.builder()
                    .setAccount(user.getStripeAccountId())
                    .setReturnUrl(stripeConnectProperties.returnUrl())
                    .setRefreshUrl(stripeConnectProperties.refreshUrl())
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .build();

            AccountLink link = stripeGateway.createAccountLink(params);
            return new OnboardingLinkResponse(link.getUrl());

        } catch (StripeException e) {
            if (isStripeAccountMissing(e)) {
                log.warn("Stripe account {} no longer exists for user {} — resetting state so user can recreate",
                        user.getStripeAccountId(), user.getId());
                resetStripeAccountState(user);
                userRepository.save(user);
                throw new YadonyBusinessException(HttpStatus.CONFLICT,
                        "stripe-account-invalid", "Stripe Account Invalid",
                        "Votre compte de paiement n'est plus valide. Réessayez pour en créer un nouveau.");
            }
            log.error("Failed to create onboarding link for user {}", user.getId(), e);
            throw new YadonyBusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "stripe-link-creation-failed", "Stripe Error",
                    "Impossible de générer le lien d'onboarding");
        } catch (Exception e) {
            log.error("Failed to create onboarding link for user {}", user.getId(), e);
            throw new YadonyBusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "stripe-link-creation-failed", "Stripe Error",
                    "Impossible de générer le lien d'onboarding");
        }
    }

    /**
     * Returns true if the Stripe exception is a "resource_missing" error, typically thrown
     * when an account, payment intent, or other resource ID stored in our DB no longer exists
     * in Stripe (account deleted in sandbox, key rotation, expired test data, etc.).
     */
    private static boolean isStripeAccountMissing(StripeException e) {
        return "resource_missing".equals(e.getCode());
    }

    /**
     * Resets the user's Stripe Connect state in memory. Caller is responsible for persisting
     * the change via {@code userRepository.save(user)} when this is not part of the same
     * transactional flow (e.g. fall-through to immediate recreation does not require save).
     */
    private void resetStripeAccountState(UserEntity user) {
        user.setStripeAccountId(null);
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        user.setStripeAccountCreatedAt(null);
        user.setStripeOnboardingCompletedAt(null);
    }

    /**
     * Pulls the latest state of the user's Stripe Connect account from Stripe and syncs the
     * local {@code stripe_onboarded} flag. Useful when the {@code account.updated} webhook
     * was missed (e.g. local dev without Stripe CLI, or transient network issue in prod).
     *
     * <p>Authoritative source = Stripe ({@code account.charges_enabled}). We mirror it.
     */
    public ConnectAccountResponse refreshConnectAccount(String firebaseUid) {
        UserEntity user = findUser(firebaseUid);

        if (user.getStripeAccountId() == null || user.getStripeAccountId().isBlank()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "stripe-account-required", "No Stripe Account",
                    "Aucun compte Stripe à rafraîchir — créez-en un d'abord");
        }

        try {
            Account account = stripeGateway.retrieveAccount(user.getStripeAccountId());
            boolean chargesEnabled = Boolean.TRUE.equals(account.getChargesEnabled());
            StripeAccountStatus newStatus = deriveStripeAccountStatus(account);

            if (newStatus != user.getStripeAccountStatus()) {
                user.setStripeAccountStatus(newStatus);
                if (newStatus == StripeAccountStatus.ONBOARDING_COMPLETE
                        && user.getStripeOnboardingCompletedAt() == null) {
                    user.setStripeOnboardingCompletedAt(java.time.Instant.now());
                }
                userRepository.save(user);
                String action = chargesEnabled ? "STRIPE_ONBOARDING_COMPLETE" : "STRIPE_ONBOARDING_REVOKED";
                auditService.log("USER", user.getId(), action, user.getId(),
                        Map.of("stripeAccountId", account.getId(),
                                "source", "manual-refresh"));
                log.info("Stripe onboarding state synced for user {} : newStatus={}",
                        user.getId(), newStatus);
            }

            return new ConnectAccountResponse(account.getId(), user.getStripeAccountStatus(),
                    connectAvailableFor(user));

        } catch (StripeException e) {
            log.error("Failed to refresh Stripe account {} for user {}",
                    user.getStripeAccountId(), user.getId(), e);
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                    "stripe-refresh-failed", "Stripe Error",
                    "Impossible de récupérer l'état du compte Stripe");
        }
    }

    // ── Story 6.3 : Paiement expéditeur avec création d'escrow ───────────────

    public PaymentResponse createEscrow(CreatePaymentRequest request, String firebaseUid) {
        UserEntity sender = findUser(firebaseUid);
        UUID bidId = request.getBidId();

        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "bid-not-found", "Bid Not Found", "Demande introuvable"));

        if (!bid.getSenderId().equals(sender.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                    "forbidden", "Forbidden", "Cette demande ne vous appartient pas");
        }

        // Le paiement est autorisé dès que le bid est PENDING ou ACCEPTED.
        // Si le voyageur refuse (REJECTED), le paiement sera annulé/remboursé automatiquement.
        if (bid.getStatus() == BidStatus.REJECTED
                || bid.getStatus() == BidStatus.CANCELLED
                || bid.getStatus() == BidStatus.COMPLETED) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "bid-not-payable", "Bid Not Payable",
                    "Cette demande ne peut plus être payée (statut : " + bid.getStatus() + ")");
        }

        // Devise figée au bid, pas la préférence courante de l'expéditeur : elle a pu
        // changer depuis la création du bid (lot 2 — gel au premier mouvement d'argent).
        // Comparer les deux ici renvoyait 422 à un expéditeur payant son propre colis.
        SupportedCurrency currency = SupportedCurrency.fromCode(bid.getCurrency());

        // Zone CFA = pas un pays Stripe Connect (country_unsupported empirique) : le
        // versement au voyageur y est impossible, donc aucun PaymentIntent ne doit
        // jamais être créé dans cette devise. BidService.resolvePaymentMethodFor
        // referme déjà cette porte à la création du bid, mais createEscrow est un
        // endpoint atteignable indépendamment — la garde doit être répétée ici.
        if (!CurrencyPaymentRails.allows(currency, com.yadony.api.payments.cash.PaymentMethod.STRIPE)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "payment-method-unavailable-for-currency", "Payment Method Unavailable For Currency",
                    "La zone CFA n'accepte pas le paiement par carte pour un colis.");
        }

        // Currency ownership is checked before idempotency and any persistent/Stripe effect.
        Optional<PaymentEntity> existing = paymentRepository.findByBidId(bidId);
        if (existing.isPresent()) {
            PaymentEntity payment = existing.get();
            if (payment.getStatus() == PaymentStatus.ESCROW
                    || payment.getStatus() == PaymentStatus.RELEASED) {
                throw new YadonyBusinessException(HttpStatus.CONFLICT,
                        "payment-already-completed", "Payment Already Completed",
                        "Le paiement pour cette demande a déjà été effectué");
            }
        }

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        // Lot B (revue round 3 — inventaire, trouvaille non pointée explicitement) : garde
        // centralisée ici plutôt que dupliquée chez chaque appelant. createEscrow a TROIS
        // appelants : BidCheckoutService.checkout (déjà gardé en amont, allowlist stricte
        // == ACTIVE), BidCheckoutService.negotiationCheckout (gardé round 3, OUT_OF_MARKET)
        // — et POST /payments (PaymentController.createEscrow), qui n'avait ABSOLUMENT
        // AUCUNE garde : un sender avec un bid PENDING pouvait ouvrir un escrow Stripe en
        // appelant cet endpoint directement, sur un trajet déjà retiré par la modération.
        // OUT_OF_MARKET (pas != ACTIVE) : reste compatible avec negotiationCheckout sur un
        // trajet partagé passé FULL entretemps.
        if (AnnouncementStatus.OUT_OF_MARKET.contains(announcement.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "announcement-not-active", "Announcement Not Active",
                    "Cette annonce n'est plus disponible");
        }

        // SECURITE : le montant net est TOUJOURS recalculé côté serveur à partir
        // des données persistées du bid (grid items snapshotés + poids × prix/kg
        // de l'annonce), jamais pris depuis le client. Le totalNetEur du corps de
        // requête n'est qu'indicatif : s'il est fourni, il doit correspondre au
        // montant serveur, sinon c'est une tentative de falsification (HTTP 422).
        // Sans ce recoupement, un expéditeur pouvait payer un bid 0,01 € et faire
        // sous-payer le voyageur / sous-encaisser la commission.
        //
        // Bid NÉGOCIÉ (fil de prix sur un trajet, cf. BidNegotiationService) : le barème
        // ci-dessous ne décrit plus rien de ce que les deux parties ont convenu. Le
        // montant reste calculé côté serveur — mais depuis l'accord figé à l'acceptation
        // (negotiated_gross_eur / negotiated_net_eur), et non depuis la grille. Le
        // recoupement du totalNetEur client garde donc tout son sens : il est simplement
        // recoupé contre l'accord. Sans cette branche, l'expéditeur serait débité du
        // tarif catalogue et le 422 amount-mismatch tomberait à chaque paiement négocié.
        boolean negotiated = bid.getNegotiatedGrossEur() != null && bid.getNegotiatedNetEur() != null;
        BigDecimal totalNet;
        if (negotiated) {
            totalNet = bid.getNegotiatedNetEur().setScale(2, RoundingMode.HALF_UP);
        } else {
            BigDecimal gridNet = BigDecimal.ZERO;
            for (BidGridItemEntity item : bidGridItemRepository.findByBidId(bidId)) {
                gridNet = gridNet.add(
                    item.getUnitPriceNetSnapshot().multiply(BigDecimal.valueOf(item.getQuantity())));
            }
            BigDecimal kgNet = (bid.getWeightKg() != null && announcement.getPricePerKg() != null)
                    ? bid.getWeightKg().multiply(announcement.getPricePerKg())
                    : BigDecimal.ZERO;
            totalNet = gridNet.add(kgNet).setScale(2, RoundingMode.HALF_UP);
        }
        if (totalNet.compareTo(BigDecimal.ZERO) <= 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid-amount", "Invalid Amount",
                "Le montant calculé est invalide (≤ 0)");
        }
        BigDecimal rate;
        boolean promoApplied = false;
        if (negotiated) {
            // Taux figé à l'ouverture du fil : le prix affiché pendant la discussion
            // doit rester exact même si un override ou une promo change entre-temps.
            rate = bid.getCommissionRate();
            if (rate == null) {
                throw new YadonyBusinessException(HttpStatus.CONFLICT,
                        "payment-pricing-context-missing", "Payment Pricing Context Missing",
                        "Le taux de commission figé de cette négociation est introuvable");
            }
        } else if (existing.isPresent()) {
            // A payment row means pricing was already committed server-side. Never
            // revalidate a consumed promo or current overrides on retries: both may
            // legitimately have changed since the first PaymentIntent was created.
            rate = bid.getCommissionRate();
            if (rate == null) {
                throw new YadonyBusinessException(HttpStatus.CONFLICT,
                        "payment-pricing-context-missing", "Payment Pricing Context Missing",
                        "Le taux de commission figé de ce paiement est introuvable");
            }
        } else {
            // Truly new payment only: promo > overrides > global.
            if (bid.getPromoCode() != null) {
                try {
                    rate = commissionRateResolver.resolve(
                            announcement.getTravelerId(), sender.getId(), bid.getPromoCode());
                    promoApplied = true;
                } catch (YadonyBusinessException e) {
                    log.warn("Promo {} no longer valid for bid {} ({}): falling back to override/global rate",
                            bid.getPromoCode(), bidId, e.getErrorCode());
                    rate = commissionRateResolver.resolve(announcement.getTravelerId(), sender.getId());
                }
            } else {
                rate = commissionRateResolver.resolve(announcement.getTravelerId(), sender.getId());
            }
        }
        // Sur un accord négocié, le brut est le nombre que l'expéditeur a tapé et vu :
        // on le prend tel quel et on en déduit la commission par soustraction, exactement
        // comme BidNegotiationPricing. Le recalculer en net × (1 + taux) pourrait dériver
        // d'un centime après arrondi, et l'expéditeur serait débité d'autre chose que le
        // montant sur lequel il s'est engagé.
        BigDecimal amount = negotiated
                ? bid.getNegotiatedGrossEur().setScale(2, RoundingMode.HALF_UP)
                : totalNet.multiply(BigDecimal.ONE.add(rate)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = negotiated
                ? amount.subtract(totalNet)
                : totalNet.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        CurrencyAmount localAmount = CurrencyAmount.of(amount, currency);
        CurrencyAmount localCommission = CurrencyAmount.of(commission, currency);

        PaymentEntity recyclable = null;
        if (existing.isPresent()) {
            PaymentEntity payment = existing.get();
            if (payment.getStatus() == PaymentStatus.PENDING) {
                try {
                    PaymentIntent pi = stripeGateway.retrievePaymentIntent(payment.getStripePaymentIntentId());
                    String piStatus = pi.getStatus();
                    if ("requires_payment_method".equals(piStatus)
                            || "requires_confirmation".equals(piStatus)) {
                        if (matchesExpectedAmountAndCurrency(payment, pi, localAmount)) {
                            return toPaymentResponse(payment, pi);
                        }
                        log.info("Canceling incompatible legacy PaymentIntent {} for bid {}",
                                payment.getStripePaymentIntentId(), bidId);
                        cancelAndConfirmForRecycle(pi);
                    } else if (!"canceled".equals(piStatus)) {
                        // PI déjà autorisé côté Stripe mais pas encore mis à jour en DB → conflict
                        throw new YadonyBusinessException(HttpStatus.CONFLICT,
                                "payment-already-completed", "Payment Already Completed",
                                "Le paiement pour cette demande a déjà été effectué");
                    }
                } catch (StripeException e) {
                    log.error("Could not retrieve or cancel existing PaymentIntent for bid {}", bidId, e);
                    throw stripeRecoveryFailed();
                }
            }
            recyclable = payment;
        }

        UserEntity traveler = userRepository.findById(announcement.getTravelerId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "traveler-not-found", "Traveler Not Found", "Voyageur introuvable"));

        if (traveler.getStripeAccountStatus() != StripeAccountStatus.ONBOARDING_COMPLETE
                || traveler.getStripeAccountId() == null
                || traveler.getStripeAccountId().isBlank()) {
            throw new TravelerNotEligibleForPaymentException(traveler.getId());
        }

        if (request.getTotalNetEur() != null
                && request.getTotalNetEur().setScale(2, RoundingMode.HALF_UP).compareTo(totalNet) != 0) {
            log.warn("Paiement bid {} rejeté : totalNetEur client {} ≠ montant serveur {}",
                    bidId, request.getTotalNetEur(), totalNet);
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "amount-mismatch", "Amount Mismatch",
                "Le montant fourni ne correspond pas au montant calculé pour cette demande");
        }

        if (existing.isEmpty()) {
            bid.setCommissionRate(rate);
            bidRepository.save(bid);
            // Rachat du promo (idempotent) — même tx que le bid.save ci-dessus.
            if (promoApplied) {
                var redemption = promoService.redeem(bid.getPromoCode(), sender.getId(), bidId, rate);
                bid.setPromoCodeId(redemption.getPromoCodeId());
                bidRepository.save(bid);
            }
            // Consommation du bon de parrainage (lot 3) — même tx, best-effort : sans
            // effet si l'expéditeur n'en détient aucun (déjà pris en compte, ou jamais eu).
            voucherService.consume(sender.getId(), bidId);
        }

        try {
            // Compatibilité comptes legacy : avant cette fix, certains comptes Stripe Connect
            // ont été créés sans la capacité card_payments (seulement transfers).
            // Stripe rejette PaymentIntent.create(on_behalf_of=…) si card_payments n'est pas active.
            // On la demande de manière idempotente : si déjà active, no-op.
            ensureCardPaymentsCapability(traveler.getStripeAccountId());

            long commissionCents = localCommission.minor();

            // Customer attaché : rend les cartes réutilisables (YadonyPaymentSheet).
            // Sans effet sur l'escrow (separate charges & transfers inchangé).
            String customerId = ensureStripeCustomer(sender);

            PaymentIntentCreateParams.Builder paramsBuilder = PaymentIntentCreateParams.builder()
                    .setAmount(localAmount.minor())
                    .setCurrency(currency.code())
                    .setCustomer(customerId)
                    .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                    // Approche A : carte + Apple Pay + Google Pay (= "card") + PayPal,
                    // tous dans la PaymentSheet. on_behalf_of retiré : PayPal ne le
                    // supporte pas, et il n'est pas requis en separate charges & transfers.
                    .addPaymentMethodType("card")
                    .addPaymentMethodType("paypal")
                    .setStatementDescriptorSuffix("YADONY")
                    // Separate charges and transfers: no transfer_data, no application_fee_amount.
                    // Funds stay on platform balance (on_behalf_of = statement/dashboard only).
                    // At delivery, DeliveryEventListener creates Transfer(amount - commission).
                    // Commission is explicit in metadata; fund hold is the implicit collection mechanism.
                    .putMetadata("bid_id", bidId.toString())
                    .putMetadata("sender_id", sender.getId().toString())
                    .putMetadata("traveler_id", traveler.getId().toString())
                    .putMetadata("commission_amount", localCommission.major().toPlainString())
                    .putMetadata("currency", currency.code())
                    .putMetadata("commission_rate", rate.toPlainString())
                    .putMetadata("commission_minor", String.valueOf(commissionCents));

            // Défaut aligné sur le toggle « Enregistrer cette carte » (ON) de la sheet.
            // Décochable ensuite via PATCH /payments/intents/{id}/save-payment-method.
            if (!Boolean.FALSE.equals(request.getSavePaymentMethod())) {
                paramsBuilder.setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION);
            }

            PaymentIntent pi = stripeGateway.createPaymentIntent(paramsBuilder.build());

            PaymentEntity payment = recyclable != null ? recyclable : new PaymentEntity();
            payment.setBidId(bidId);
            payment.setStripePaymentIntentId(pi.getId());
            payment.setAmount(localAmount.major());
            payment.setCommissionAmount(localCommission.major());
            payment.setCurrency(currency.code());
            payment.setStatus(PaymentStatus.PENDING);
            payment.setLegacyDestinationCharge(false);
            if (recyclable != null) {
                payment.clearLegacyFxData();
            }
            paymentRepository.save(payment);

            auditService.log("PAYMENT", payment.getId(), "PAYMENT_ESCROW_CREATED", sender.getId(),
                    Map.of("bidId", bidId, "amount", localAmount.major(), "commission", localCommission.major(),
                            "piId", pi.getId()));

            log.info("PaymentIntent {} created for bid {} (sender={})", pi.getId(), bidId, sender.getId());
            return toPaymentResponse(payment, pi);

        } catch (StripeException e) {
            if (isStripeAccountMissing(e)) {
                log.warn("Traveler {} stripe account {} no longer exists in Stripe — resetting state",
                        traveler.getId(), traveler.getStripeAccountId(), e);
                resetStripeAccountState(traveler);
                userRepository.save(traveler);
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "traveler-stripe-invalid", "Traveler Stripe Account Invalid",
                        "Le voyageur doit reconfigurer son compte de paiement avant de pouvoir recevoir cette demande.");
            }
            log.error("Stripe PaymentIntent creation failed for bid {}", bidId, e);
            throw new YadonyBusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "payment-creation-failed", "Payment Error",
                    "Impossible de créer le paiement. Veuillez réessayer.");
        }
    }

    // ── Support YadonyPaymentSheet : customer + cartes enregistrées ─────────────

    /**
     * Garantit un customer Stripe pour l'utilisateur (créé et persisté si absent).
     * Même pattern que CashCommissionService#ensureStripeCustomer.
     */
    private String ensureStripeCustomer(UserEntity user) throws StripeException {
        if (user.getStripeCustomerId() != null) {
            return user.getStripeCustomerId();
        }
        Customer customer = stripeGateway.createCustomer(CustomerCreateParams.builder()
                .setEmail(firebaseContact.getContact(user.getFirebaseUid()).email())
                .putMetadata("yadony_user_id", user.getId().toString())
                .build());
        user.setStripeCustomerId(customer.getId());
        userRepository.save(user);
        log.info("Stripe customer {} created for user {}", customer.getId(), user.getId());
        return customer.getId();
    }

    /**
     * Clé éphémère Stripe pour la PaymentSheet native (flutter_stripe) : lui permet de lire/gérer
     * les cartes enregistrées du customer sans exposer la clé secrète Stripe au client. Doit être
     * créée avec la version d'API exacte demandée par le SDK — sinon la sheet native rejette la clé.
     */
    public EphemeralKeyResponse createEphemeralKey(String firebaseUid, String stripeVersion) {
        UserEntity user = findUser(firebaseUid);
        try {
            String customerId = ensureStripeCustomer(user);
            // stripe-java exige la version d'API du client mobile sur les params eux-mêmes
            // (EphemeralKeyCreateParams.setStripeVersion) — un override via RequestOptions
            // est rejeté à l'exécution par EphemeralKey.create.
            EphemeralKey key = stripeGateway.createEphemeralKey(
                    EphemeralKeyCreateParams.builder()
                            .setCustomer(customerId)
                            .setStripeVersion(stripeVersion)
                            .build());
            return new EphemeralKeyResponse(key.getSecret(), customerId);
        } catch (StripeException e) {
            log.error("Failed to create Stripe ephemeral key for user {}", user.getId(), e);
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                    "ephemeral-key-creation-failed", "Stripe Error",
                    "Impossible de préparer la fiche de paiement. Veuillez réessayer.");
        }
    }

    /**
     * Cartes enregistrées du customer de l'utilisateur courant.
     * Jamais bloquant : pas de customer ou Stripe en erreur → liste vide.
     */
    @Transactional(readOnly = true)
    public List<PaymentMethodResponse> listSavedPaymentMethods(String firebaseUid) {
        UserEntity user = findUser(firebaseUid);
        if (user.getStripeCustomerId() == null) {
            return List.of();
        }
        try {
            return PaymentMethod.list(PaymentMethodListParams.builder()
                            .setCustomer(user.getStripeCustomerId())
                            .setType(PaymentMethodListParams.Type.CARD)
                            .build())
                    .getData().stream()
                    .map(pm -> new PaymentMethodResponse(
                            pm.getId(),
                            pm.getCard().getBrand(),
                            pm.getCard().getLast4(),
                            pm.getCard().getExpMonth().intValue(),
                            pm.getCard().getExpYear().intValue()))
                    .toList();
        } catch (StripeException e) {
            log.warn("Could not list payment methods for user {} (customer {})",
                    user.getId(), user.getStripeCustomerId(), e);
            return List.of();
        }
    }

    /**
     * Met à jour le flag « enregistrer la carte » (setup_future_usage) sur un
     * PaymentIntent non confirmé. Ownership vérifié via metadata sender_id.
     */
    public void updateSavePaymentMethod(String paymentIntentId, boolean save, String firebaseUid) {
        UserEntity user = findUser(firebaseUid);

        PaymentIntent pi;
        try {
            pi = PaymentIntent.retrieve(paymentIntentId);
        } catch (StripeException e) {
            throw new YadonyBusinessException(HttpStatus.NOT_FOUND,
                    "payment-intent-not-found", "Payment Intent Not Found",
                    "Paiement introuvable");
        }

        String senderId = pi.getMetadata() == null ? null : pi.getMetadata().get("sender_id");
        if (senderId == null || !senderId.equals(user.getId().toString())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                    "forbidden", "Forbidden", "Ce paiement ne vous appartient pas");
        }

        String status = pi.getStatus();
        if (!"requires_payment_method".equals(status) && !"requires_confirmation".equals(status)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "intent-not-editable", "Payment Intent Not Editable",
                    "Le paiement est déjà confirmé, l'option d'enregistrement ne peut plus changer");
        }

        try {
            PaymentIntentUpdateParams.Builder update = PaymentIntentUpdateParams.builder();
            if (save) {
                update.setSetupFutureUsage(PaymentIntentUpdateParams.SetupFutureUsage.OFF_SESSION);
            } else {
                // Chaîne vide = clear du champ côté API Stripe (le setter n'accepte pas null).
                update.putExtraParam("setup_future_usage", "");
            }
            pi.update(update.build());
        } catch (StripeException e) {
            log.error("Failed to update setup_future_usage on {} for user {}",
                    paymentIntentId, user.getId(), e);
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                    "stripe-update-failed", "Stripe Error",
                    "Impossible de mettre à jour l'option d'enregistrement de la carte");
        }
    }

    // ── Webhook handlers (package-private — appelés depuis PaymentStripeWebhookHandler) ──

    void handleAccountUpdated(Event event) {
        Account account = resolveAccountFromEvent(event);
        if (account == null) return;

        String accountId = account.getId();
        userRepository.findByStripeAccountId(accountId).ifPresent(user -> {
            StripeAccountStatus newStatus = deriveStripeAccountStatus(account);

            if (newStatus == StripeAccountStatus.PENDING_ONBOARDING
                    && user.getStripeAccountStatus() == StripeAccountStatus.PENDING_ONBOARDING) {
                return;
            }

            if (newStatus == StripeAccountStatus.ONBOARDING_COMPLETE
                    && user.getStripeAccountStatus() != StripeAccountStatus.ONBOARDING_COMPLETE) {
                user.setStripeOnboardingCompletedAt(java.time.Instant.now());
                eventPublisher.publishEvent(new StripeOnboardingCompletedEvent(user.getId()));
                auditService.log("USER", user.getId(), "STRIPE_ONBOARDING_COMPLETE",
                        user.getId(), Map.of("stripeAccountId", accountId));
                log.info("Stripe onboarding complete for user {}", user.getId());
            }

            user.setStripeAccountStatus(newStatus);
            userRepository.save(user);
        });
    }

    private Account resolveAccountFromEvent(Event event) {
        var deserialized = event.getDataObjectDeserializer().getObject();
        if (deserialized.isPresent() && deserialized.get() instanceof Account a) {
            return a;
        }
        // Fallback : version API du payload ≠ SDK → récupérer via API
        String accountId = event.getAccount();
        if (accountId == null) {
            log.warn("handleAccountUpdated: no account ID in event {}", event.getId());
            return null;
        }
        try {
            return stripeGateway.retrieveAccount(accountId);
        } catch (StripeException e) {
            log.error("handleAccountUpdated: failed to fetch account {} for event {}", accountId, event.getId(), e);
            return null;
        }
    }

    /**
     * Derives the {@link StripeAccountStatus} from a Stripe {@link Account} object.
     * Single source of truth used by both {@code handleAccountUpdated} and {@code refreshConnectAccount}.
     */
    private StripeAccountStatus deriveStripeAccountStatus(Account account) {
        boolean chargesEnabled = Boolean.TRUE.equals(account.getChargesEnabled());
        boolean payoutsEnabled = Boolean.TRUE.equals(account.getPayoutsEnabled());
        String disabledReason = account.getRequirements() != null
                ? account.getRequirements().getDisabledReason()
                : null;

        if (chargesEnabled && payoutsEnabled) {
            return StripeAccountStatus.ONBOARDING_COMPLETE;
        } else if (disabledReason != null && disabledReason.startsWith("rejected")) {
            return StripeAccountStatus.REJECTED;
        } else if (disabledReason != null && disabledReason.startsWith("requirements.")) {
            // "requirements.past_due" / "requirements.currently_due" /
            // "requirements.pending_verification" : Stripe attend des
            // informations, l'onboarding n'est simplement pas fini. C'est le
            // cas de TOUT compte Express fraîchement créé — le classer DISABLED
            // envoyait l'utilisateur sur un écran « compte désactivé » sans
            // issue, alors que la seule action utile est de reprendre
            // l'onboarding.
            return StripeAccountStatus.PENDING_ONBOARDING;
        } else if (disabledReason != null) {
            // Raisons hors "requirements" : "listed", "platform_paused",
            // "under_review", "other" — restriction subie, l'utilisateur ne
            // peut rien fournir pour la lever lui-même.
            return StripeAccountStatus.DISABLED;
        } else {
            return StripeAccountStatus.PENDING_ONBOARDING;
        }
    }

    void handlePaymentEscrowActive(Event event) {
        PaymentIntent pi;
        Optional<com.stripe.model.StripeObject> objOpt = event.getDataObjectDeserializer().getObject();
        if (objOpt.isPresent()) {
            pi = (PaymentIntent) objOpt.get();
        } else {
            // API version mismatch between Stripe CLI/server and Java SDK — fall back to live API fetch.
            try {
                String rawJson = event.getDataObjectDeserializer().getRawJson();
                JsonNode node = objectMapper.readTree(rawJson);
                String piId = node.get("id").asText();
                pi = stripeGateway.retrievePaymentIntent(piId);
                log.debug("handlePaymentEscrowActive: deserializer was empty for event {}, fetched PI {} via API",
                        event.getId(), piId);
            } catch (Exception e) {
                log.error("handlePaymentEscrowActive: cannot resolve PaymentIntent from event {}: {}",
                        event.getId(), e.getMessage(), e);
                return;
            }
        }

        final PaymentIntent finalPi = pi;

        paymentRepository.findByStripePaymentIntentId(finalPi.getId()).ifPresent(payment -> {
            boolean changed = false;

            // Persist the Stripe Charge id for later Transfer.sourceTransaction reconciliation.
            // Idempotent: only set once.
            String chargeId = finalPi.getLatestCharge();
            if (chargeId != null && payment.getStripeChargeId() == null) {
                payment.setStripeChargeId(chargeId);
                changed = true;
            }

            if (payment.getStatus() == PaymentStatus.PENDING) {
                payment.setStatus(PaymentStatus.ESCROW);
                changed = true;
                auditService.log("PAYMENT", payment.getId(), "PAYMENT_ESCROW_ACTIVE",
                        payment.getBidId(),
                        Map.of("piId", finalPi.getId(), "amountCapturable", finalPi.getAmountCapturable()));
                log.info("Payment {} now in ESCROW (PI={})", payment.getId(), finalPi.getId());
                eventPublisher.publishEvent(new PaymentEscrowReadyEvent(payment.getBidId(), payment.getId()));
            }

            if (changed) {
                paymentRepository.save(payment);
            }
        });

        // Promote the AWAITING_PAYMENT bid to PENDING (independent of Payment row state)
        promoteBidOnPaymentAuthorized(finalPi.getId());

        // Marketplace package_request flow: if this PI is bound to a negotiation thread,
        // ask the NegotiationService to finalize via the application context (avoid circular DI).
        String scope = finalPi.getMetadata().get("scope");
        String negotiationThreadId = finalPi.getMetadata().get("negotiation_thread_id");
        if ("NEGOTIATION".equals(scope) && negotiationThreadId != null) {
            eventPublisher.publishEvent(new NegotiationPaymentAuthorizedEvent(
                    java.util.UUID.fromString(negotiationThreadId),
                    java.util.UUID.fromString(finalPi.getMetadata().get("sender_id")),
                    finalPi.getId()
            ));
        }
    }

    /**
     * Promotes a bid from AWAITING_PAYMENT → PENDING when the Stripe PaymentIntent
     * has been authorized (capture_method=manual hold posted).
     * Publishes BidCreatedEvent so the traveler is notified.
     * Idempotent: silent no-op if bid not in AWAITING_PAYMENT.
     */
    @Transactional
    public void promoteBidOnPaymentAuthorized(String paymentIntentId) {
        bidRepository.findByPaymentIntentId(paymentIntentId).ifPresent(bid -> {
            if (bid.getStatus() != BidStatus.AWAITING_PAYMENT) return;

            bid.setStatus(BidStatus.PAYMENT_ESCROWED);
            bid.setAwaitingPaymentExpiresAt(null);
            bidRepository.save(bid);

            AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                    .orElseThrow(() -> new IllegalStateException("announcement not found for bid " + bid.getId()));
            UserEntity sender = userRepository.findById(bid.getSenderId()).orElse(null);
            String senderName = (sender != null && sender.getFirstName() != null && !sender.getFirstName().isBlank())
                    ? sender.getFirstName() : "Un expéditeur";
            String corridor = announcement.getDepartureCity() + " → " + announcement.getArrivalCity();

            auditService.log("BID", bid.getId(), "BID_CREATED", bid.getSenderId(),
                    Map.of("announcementId", bid.getAnnouncementId().toString(),
                            "weightKg", bid.getWeightKg() != null ? bid.getWeightKg().toString() : "0",
                            "paymentIntentId", paymentIntentId));

            eventPublisher.publishEvent(new BidCreatedEvent(
                    bid.getId(), announcement.getId(), announcement.getTravelerId(), bid.getSenderId(),
                    senderName, bid.getWeightKg(), corridor));

            log.info("Bid {} promoted to PENDING (PI={})", bid.getId(), paymentIntentId);
        });
    }

    /**
     * Synchronous safety net for clients that just confirmed payment via Stripe SDK.
     * Retrieves the PaymentIntent from Stripe and, if authorized
     * (requires_capture / succeeded / processing), promotes the bid to PENDING.
     *
     * This avoids depending on the Stripe webhook in environments where it
     * cannot reach the backend (local dev) and acts as a redundant safety net
     * in production. Idempotent: no-op if the bid is not in AWAITING_PAYMENT.
     *
     * @return true if the bid is now in PENDING state, false otherwise
     */
    @Transactional
    public boolean confirmBidPayment(UUID bidId) {
        BidEntity bid = bidRepository.findById(bidId).orElseThrow(() ->
                new YadonyBusinessException(HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Bid introuvable"));

        if (bid.getStatus() == BidStatus.PAYMENT_ESCROWED) {
            return true;
        }
        if (bid.getStatus() != BidStatus.AWAITING_PAYMENT) {
            return false;
        }

        String piId = bid.getPaymentIntentId();
        if (piId == null) {
            log.warn("Bid {} in AWAITING_PAYMENT has no paymentIntentId", bidId);
            return false;
        }

        try {
            PaymentIntent pi = stripeGateway.retrievePaymentIntent(piId);
            String status = pi.getStatus();
            if ("requires_capture".equals(status)
                    || "succeeded".equals(status)
                    || "processing".equals(status)) {
                promoteBidOnPaymentAuthorized(piId);
                // Also transition the payment entity to ESCROW so DeliveryEventListener can release it.
                paymentRepository.findByBidId(bidId).ifPresent(payment -> {
                    boolean changed = false;
                    String chargeId = pi.getLatestCharge();
                    if (chargeId != null && payment.getStripeChargeId() == null) {
                        payment.setStripeChargeId(chargeId);
                        changed = true;
                    }
                    if (payment.getStatus() == PaymentStatus.PENDING) {
                        payment.setStatus(PaymentStatus.ESCROW);
                        changed = true;
                        auditService.log("PAYMENT", payment.getId(), "PAYMENT_ESCROW_ACTIVE",
                                payment.getBidId(),
                                Map.of("piId", pi.getId(), "source", "confirmBidPayment"));
                        log.info("Payment {} set to ESCROW via confirmBidPayment (PI={})",
                                payment.getId(), pi.getId());
                    }
                    if (changed) {
                        paymentRepository.save(payment);
                    }
                });
                return true;
            }
            log.info("Bid {} not promoted: PI {} status={}", bidId, piId, status);
            return false;
        } catch (StripeException e) {
            log.error("Failed to retrieve PI {} for bid {}: {}", piId, bidId, e.getMessage());
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "stripe-error", "Stripe Error",
                    "Impossible de vérifier le paiement auprès de Stripe");
        }
    }

    void handlePaymentFailed(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            PaymentIntent pi = (PaymentIntent) obj;
            paymentRepository.findByStripePaymentIntentId(pi.getId()).ifPresent(payment -> {
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    payment.setStatus(PaymentStatus.FAILED);
                    paymentRepository.save(payment);
                    auditService.log("PAYMENT", payment.getId(), "PAYMENT_FAILED",
                            payment.getBidId(),
                            Map.of("piId", pi.getId()));
                    log.warn("Payment {} FAILED (PI={})", payment.getId(), pi.getId());
                }
            });
        });
    }

    void handleChargeRefunded(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            Charge charge = (Charge) obj;
            String piId = charge.getPaymentIntent();
            if (piId == null) {
                log.debug("charge.refunded event has no paymentIntent — ignoring");
                return;
            }
            paymentRepository.findByStripePaymentIntentId(piId).ifPresent(payment -> {
                Long amountRefundedCents = charge.getAmountRefunded();
                Long amountCents = charge.getAmount();
                if (amountRefundedCents == null || amountCents == null) {
                    log.warn("charge.refunded pour PI {} sans montant exploitable — ignoré", piId);
                    return;
                }
                // Montants Stripe en unités mineures de la devise persistée. amount_refunded est CUMULÉ et
                // ABSOLU : rejouer le même webhook réécrit la même valeur (idempotent).
                SupportedCurrency paymentCurrency = SupportedCurrency.fromCode(payment.getCurrency());
                if (paymentCurrency == null) {
                    paymentCurrency = SupportedCurrency.EUR;
                }
                BigDecimal refunded = BigDecimal.valueOf(amountRefundedCents, paymentCurrency.minorUnit());
                boolean fullRefund = amountRefundedCents >= amountCents;
                // compareTo (et non equals) : insensible à l'échelle BigDecimal — la valeur
                // relue depuis NUMERIC(10,2) peut différer d'échelle sans changer de montant.
                boolean alreadyRecorded = payment.getRefundedAmount() != null
                        && payment.getRefundedAmount().compareTo(refunded) == 0;

                payment.setRefundedAmount(refunded);

                if (payment.getStatus() == PaymentStatus.RELEASED) {
                    paymentRepository.save(payment);
                    if (!alreadyRecorded) {
                        auditService.log("PAYMENT", payment.getId(), "REFUND_AFTER_RELEASE",
                                payment.getBidId(),
                                Map.of("piId", piId, "refundedAmount", refunded.toPlainString(),
                                        "full", String.valueOf(fullRefund)));
                        adminAlert.raise("REFUND_AFTER_RELEASE",
                                "Remboursement Stripe reçu sur un paiement déjà versé au voyageur — "
                                        + "réconciliation manuelle requise (payment " + payment.getId() + ")",
                                Map.of("paymentId", payment.getId().toString(), "piId", piId,
                                        "refundedAmount", refunded.toPlainString()));
                    }
                    return;
                }

                if (fullRefund && payment.getStatus() != PaymentStatus.REFUNDED) {
                    payment.setStatus(PaymentStatus.REFUNDED);
                    auditService.log("PAYMENT", payment.getId(), "PAYMENT_REFUNDED",
                            payment.getBidId(), Map.of("piId", piId, "source", "stripe_webhook"));
                    log.info("Refund total confirmé par webhook pour payment {}", payment.getId());
                } else if (!fullRefund && !alreadyRecorded) {
                    auditService.log("PAYMENT", payment.getId(), "PAYMENT_PARTIALLY_REFUNDED",
                            payment.getBidId(),
                            Map.of("piId", piId, "refundedAmount", refunded.toPlainString()));
                    log.info("Refund partiel {} enregistré pour payment {} (statut {} conservé)",
                            refunded, payment.getId(), payment.getStatus());
                }
                paymentRepository.save(payment);
            });
        });
    }

    // ── Story 6.3 : Statut paiement pour un bid ──────────────────────────────

    public Optional<PaymentResponse> getPaymentStatusForBid(UUID bidId, String callerFirebaseUid) {
        UserEntity caller = findUser(callerFirebaseUid);

        BidEntity bid = bidRepository.findById(bidId).orElse(null);
        if (bid == null) {
            return Optional.empty();
        }

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);

        boolean isSender = bid.getSenderId().equals(caller.getId());
        boolean isTraveler = announcement != null && announcement.getTravelerId().equals(caller.getId());

        if (!isSender && !isTraveler) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "access-denied",
                    "Access Denied", "Vous n'êtes pas autorisé à accéder à ce paiement");
        }

        return paymentRepository.findByBidId(bidId)
                // Negotiation-flow shipments (dedicated / linked trips paid via Stripe)
                // store the escrow payment against the negotiation thread, with a NULL
                // bid_id — the bid is materialised AFTER payment (ThreadAcceptedBidListener).
                // Fall back to the thread payment so the sender's shipment screen shows it
                // as paid (escrow badge) instead of the stale "Payer mon envoi" button.
                .or(() -> {
                    UUID threadId = bid.getLinkedNegotiationThreadId();
                    return threadId != null
                            ? paymentRepository.findByNegotiationThreadId(threadId)
                            : Optional.empty();
                })
                .map(payment -> toPaymentResponse(payment, (String) null));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserEntity findUser(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }

    /**
     * Garantit que la capacité {@code card_payments} est demandée sur le compte Connect.
     * <p>
     * Historiquement requise : Stripe rejetait {@code PaymentIntent.create(on_behalf_of=…)}
     * sans elle. Depuis le passage aux wallets (Approche A), {@code on_behalf_of} a été retiré
     * des PaymentIntents escrow — ce check n'est donc plus strictement nécessaire à la création
     * du PaymentIntent. Conservé (idempotent : no-op si déjà active/pending) en attendant un
     * nettoyage côté onboarding Connect ; aucun effet de bord négatif.
     */
    private void ensureCardPaymentsCapability(String stripeAccountId) throws StripeException {
        Account account = stripeGateway.retrieveAccount(stripeAccountId);
        String currentState = account.getCapabilities() == null
                ? null
                : account.getCapabilities().getCardPayments();
        if ("active".equals(currentState)) {
            return;
        }
        // pending → la capacité est demandée mais Stripe attend encore des infos
        // (typiquement un document KYC). On lève une erreur métier claire plutôt
        // que de laisser Stripe rejeter le PaymentIntent avec un message technique.
        if ("pending".equals(currentState)) {
            log.warn("card_payments pending on Stripe account {} — traveler needs to complete onboarding (KYC docs)",
                    stripeAccountId);
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "traveler-onboarding-pending", "Traveler Onboarding Pending",
                    "Le voyageur doit finaliser son inscription Stripe (pièce d'identité requise) "
                    + "avant de pouvoir recevoir des paiements.");
        }
        // null / inactive / unrequested → demander la capacité (idempotent côté Stripe)
        log.info("Requesting card_payments capability on legacy Stripe account {} (current={})",
                stripeAccountId, currentState);
        AccountUpdateParams updateParams = AccountUpdateParams.builder()
                .setCapabilities(
                        AccountUpdateParams.Capabilities.builder()
                                .setCardPayments(
                                        AccountUpdateParams.Capabilities.CardPayments.builder()
                                                .setRequested(true)
                                                .build()
                                )
                                .setTransfers(
                                        AccountUpdateParams.Capabilities.Transfers.builder()
                                                .setRequested(true)
                                                .build()
                                )
                                .build()
                )
                .build();
        Account updated = account.update(updateParams);
        String newState = updated.getCapabilities() == null
                ? null
                : updated.getCapabilities().getCardPayments();
        if (!"active".equals(newState)) {
            log.warn("card_payments still {} after update on account {} — traveler needs to complete onboarding",
                    newState, stripeAccountId);
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "traveler-onboarding-pending", "Traveler Onboarding Pending",
                    "Le voyageur doit finaliser son inscription Stripe (pièce d'identité requise) "
                    + "avant de pouvoir recevoir des paiements.");
        }
    }

    private PaymentResponse toPaymentResponse(PaymentEntity payment, String clientSecret) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBidId(),
                clientSecret,
                payment.getAmount(),
                payment.getCommissionAmount(),
                payment.getStatus().name(),
                payment.getStripePaymentIntentId(),
                payment.getCurrency()
        );
    }

    /** Variante avec PaymentIntent : expose aussi payment_method_types (YadonyPaymentSheet). */
    private PaymentResponse toPaymentResponse(PaymentEntity payment, PaymentIntent pi) {
        PaymentResponse response = toPaymentResponse(payment, pi.getClientSecret());
        response.setPaymentMethodTypes(pi.getPaymentMethodTypes());
        return response;
    }

    private PaymentResponse toNegotiationPaymentResponse(
            PaymentEntity payment, PaymentIntent paymentIntent,
            BigDecimal commissionRate, boolean promoApplied) {
        PaymentResponse response = toPaymentResponse(payment, paymentIntent);
        response.setCommissionRate(commissionRate);
        response.setPromoApplied(promoApplied);
        return response;
    }

    private boolean matchesExpectedAmountAndCurrency(
            PaymentEntity payment, PaymentIntent paymentIntent, CurrencyAmount expected) {
        boolean entityAmountMatches = payment.getAmount() != null
                && payment.getAmount().compareTo(expected.major()) == 0;
        boolean entityCurrencyMatches = sameCurrency(payment.getCurrency(), expected.currency());
        Long intentAmount = paymentIntent.getAmount();
        boolean intentAmountMatches = intentAmount != null && intentAmount == expected.minor();
        boolean intentCurrencyMatches = sameCurrency(paymentIntent.getCurrency(), expected.currency());
        return entityAmountMatches && entityCurrencyMatches
                && intentAmountMatches && intentCurrencyMatches;
    }

    private boolean sameCurrency(String actual, SupportedCurrency expected) {
        return actual != null && actual.equalsIgnoreCase(expected.code());
    }

    private void cancelAndConfirmForRecycle(PaymentIntent paymentIntent) throws StripeException {
        PaymentIntent canceled = paymentIntent.cancel(PaymentIntentCancelParams.builder()
                .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                .build());
        if (canceled == null || !"canceled".equals(canceled.getStatus())) {
            log.error("Stripe did not confirm cancellation of PaymentIntent {}", paymentIntent.getId());
            throw stripeRecoveryFailed();
        }
    }

    private YadonyBusinessException stripeRecoveryFailed() {
        return new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                "stripe-error", "Stripe Error",
                "Impossible de vérifier ou d'annuler le paiement existant auprès de Stripe");
    }

    /**
     * Annule un PaymentIntent en mode pré-autorisation.
     * No-op si paymentIntentId est null/blank.
     * Throws StripeException pour permettre au scheduler de détecter la race condition (PI déjà succeeded).
     */
    public void cancelPaymentIntent(String paymentIntentId) throws StripeException {
        if (paymentIntentId == null || paymentIntentId.isBlank()) return;
        PaymentIntent pi = stripeGateway.retrievePaymentIntent(paymentIntentId);
        pi.cancel();
        log.info("PaymentIntent {} cancelled", paymentIntentId);
    }

    /**
     * Releases the in-flight Stripe escrow (if any) for a negotiation thread,
     * canceling the PaymentIntent and marking the payment CANCELLED. Used when the
     * sender switches the thread to another payment method (e.g. CASH) at checkout,
     * so the Stripe card hold is not left orphaned.
     *
     * @param reason audit_log reason recorded on the {@code NEGOTIATION_ESCROW_CANCELED}
     *               entry — distinguishes why the hold was released (e.g.
     *               {@code "payment-method-switch"} vs {@code "negotiation-cancelled"}).
     * @return {@code true} if there is no in-flight escrow to release, or it was
     *         released; {@code false} if a live escrow exists but Stripe refused to
     *         cancel it (e.g. already captured) — the caller MUST then keep the
     *         current method (do not switch).
     */
    public boolean cancelNegotiationEscrow(java.util.UUID threadId, String reason) {
        if (threadId == null) {
            return true;
        }
        PaymentEntity payment = paymentRepository.findByNegotiationThreadId(threadId).orElse(null);
        if (payment == null) {
            return true; // no escrow created for this thread
        }
        if (payment.getStatus() != PaymentStatus.PENDING
                && payment.getStatus() != PaymentStatus.ESCROW) {
            return true; // terminal — no live hold to release
        }
        try {
            PaymentIntent pi = stripeGateway.retrievePaymentIntent(payment.getStripePaymentIntentId());
            // Idempotent: a PaymentIntent already 'canceled' (e.g. a prior method
            // switch whose transaction rolled back AFTER canceling at Stripe) is a
            // benign no-op — just sync the DB and report success. Only a genuinely
            // uncancelable PI (captured/succeeded — a live hold) throws on cancel()
            // below and yields false, so we never switch away from real card money.
            if (!"canceled".equals(pi.getStatus())) {
                pi.cancel();
            }
            payment.setStatus(PaymentStatus.CANCELLED);
            paymentRepository.save(payment);
            auditService.log("PAYMENT", payment.getId(), "NEGOTIATION_ESCROW_CANCELED", null,
                    Map.of("piId", payment.getStripePaymentIntentId(),
                            "reason", reason));
            log.info("Negotiation escrow released (thread={}, pi={}) for payment-method switch",
                    threadId, payment.getStripePaymentIntentId());
            return true;
        } catch (StripeException e) {
            log.warn("Could not release negotiation escrow (thread={}, pi={}): {}",
                    threadId, payment.getStripePaymentIntentId(), e.getMessage());
            return false; // keep the escrow rather than orphan it (PI captured/succeeded)
        }
    }

    /**
     * Capture un PaymentIntent pré-autorisé (manual capture).
     * No-op si paymentIntentId est null/blank.
     */
    public void capturePaymentIntent(String paymentIntentId) throws StripeException {
        if (paymentIntentId == null || paymentIntentId.isBlank()) return;
        PaymentIntent pi = stripeGateway.retrievePaymentIntent(paymentIntentId);
        stripeGateway.capturePaymentIntent(pi);
        log.info("PaymentIntent {} captured", paymentIntentId);
    }

    // ── Task 12 — payment_intent.canceled + transfer.* + payout.* ───────────

    void handlePaymentIntentCanceled(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            PaymentIntent pi = (PaymentIntent) obj;
            paymentRepository.findByStripePaymentIntentId(pi.getId()).ifPresent(payment -> {
                if (payment.getStatus() == PaymentStatus.ESCROW
                        || payment.getStatus() == PaymentStatus.PENDING) {
                    payment.setStatus(PaymentStatus.CANCELLED);
                    paymentRepository.save(payment);
                    auditService.log("PAYMENT", payment.getId(), "PAYMENT_INTENT_CANCELED",
                            payment.getBidId(), Map.of("piId", pi.getId()));
                    log.info("PaymentIntent {} canceled — payment {} set CANCELLED", pi.getId(), payment.getId());
                }
            });
        });
    }

    void handleTransferReversed(Event event) {
        try {
            JsonNode root = objectMapper.readTree(event.getDataObjectDeserializer().getRawJson());
            String transferId = root.path("id").asText();
            auditService.log("TRANSFER", null, "TRANSFER_REVERSED", null,
                    Map.of("transferId", transferId));
            adminAlert.raise("STRIPE_TRANSFER_REVERSED",
                    "Transfer " + transferId + " a été reversé",
                    Map.of("transferId", transferId));
        } catch (Exception e) {
            log.warn("Could not parse transfer.reversed event: {}", e.getMessage());
        }
    }

    void handleTransferCreated(Event event) {
        logTransferAudit(event, "TRANSFER_CREATED");
    }

    void handleTransferUpdated(Event event) {
        logTransferAudit(event, "TRANSFER_UPDATED");
    }

    private void logTransferAudit(Event event, String action) {
        try {
            JsonNode root = objectMapper.readTree(event.getDataObjectDeserializer().getRawJson());
            auditService.log("TRANSFER", null, action, null,
                    Map.of("transferId", root.path("id").asText()));
        } catch (Exception e) {
            log.warn("Could not parse transfer event {}: {}", action, e.getMessage());
        }
    }

    void handlePayoutFailed(Event event) {
        try {
            JsonNode root = objectMapper.readTree(event.getDataObjectDeserializer().getRawJson());
            String payoutId = root.path("id").asText();
            String failureCode = root.path("failure_code").asText("unknown");
            auditService.log("PAYOUT", null, "PAYOUT_FAILED", null,
                    Map.of("payoutId", payoutId, "failureCode", failureCode));
            adminAlert.raise("STRIPE_PAYOUT_FAILED",
                    "Paiement banque " + payoutId + " échoué — code: " + failureCode,
                    Map.of("payoutId", payoutId));
        } catch (Exception e) {
            log.warn("Could not parse payout.failed: {}", e.getMessage());
        }
    }

    void handlePayoutPaid(Event event) {
        try {
            JsonNode root = objectMapper.readTree(event.getDataObjectDeserializer().getRawJson());
            auditService.log("PAYOUT", null, "PAYOUT_PAID", null,
                    Map.of("payoutId", root.path("id").asText()));
        } catch (Exception e) {
            log.warn("Could not parse payout.paid: {}", e.getMessage());
        }
    }

    // ── Task 13 — account.deauthorized + capability + refund.updated + fraud_warning ──

    void handleAccountDeauthorized(Event event) {
        try {
            String accountId = null;
            var deserialized = event.getDataObjectDeserializer().getObject();
            if (deserialized.isPresent() && deserialized.get() instanceof com.stripe.model.Account account) {
                accountId = account.getId();
            } else {
                JsonNode root = objectMapper.readTree(event.getDataObjectDeserializer().getRawJson());
                accountId = root.path("id").asText(null);
            }
            if (accountId == null) {
                log.warn("account.application.deauthorized: no accountId found in event {}", event.getId());
                return;
            }
            final String aid = accountId;
            userRepository.findByStripeAccountId(aid).ifPresentOrElse(user -> {
                user.setStripeAccountStatus(StripeAccountStatus.DISABLED);
                userRepository.save(user);
                auditService.log("USER", user.getId(), "STRIPE_ACCOUNT_DEAUTHORIZED",
                        user.getId(), Map.of("stripeAccountId", aid));
                adminAlert.raise("STRIPE_ACCOUNT_DEAUTHORIZED",
                        "Compte Connect " + aid + " déconnecté",
                        Map.of("userId", user.getId().toString(), "stripeAccountId", aid));
            }, () -> log.warn("account.application.deauthorized: no user for accountId={}", aid));
        } catch (Exception e) {
            log.warn("Could not parse account.application.deauthorized: {}", e.getMessage());
        }
    }

    void handleCapabilityUpdated(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            try {
                com.stripe.model.Capability cap = (com.stripe.model.Capability) obj;
                String accountId = cap.getAccount();
                String status = cap.getStatus();
                String capabilityId = cap.getId(); // e.g. "transfers", "card_payments"
                if ("inactive".equals(status) && accountId != null) {
                    userRepository.findByStripeAccountId(accountId).ifPresent(user -> {
                        user.setStripeAccountStatus(StripeAccountStatus.DISABLED);
                        userRepository.save(user);
                        auditService.log("USER", user.getId(), "STRIPE_CAPABILITY_LOST",
                                user.getId(), Map.of("capability", capabilityId, "status", status));
                        adminAlert.raise("STRIPE_CAPABILITY_LOST",
                                "Compte " + accountId + " a perdu la capacité " + capabilityId,
                                Map.of("accountId", accountId, "status", status));
                    });
                } else if ("pending".equals(status)) {
                    log.info("Capability {} pending on account {} — awaiting Stripe review", capabilityId, accountId);
                }
            } catch (Exception e) {
                log.warn("Could not parse capability.updated: {}", e.getMessage());
            }
        });
    }

    void handleRefundUpdated(Event event) {
        try {
            JsonNode root = objectMapper.readTree(event.getDataObjectDeserializer().getRawJson());
            String refundStatus = root.path("status").asText();
            if ("failed".equals(refundStatus)) {
                String refundId = root.path("id").asText();
                String piId = root.path("payment_intent").asText(null);
                auditService.log("PAYMENT", null, "REFUND_FAILED", null,
                        Map.of("refundId", refundId, "piId", piId != null ? piId : "unknown"));
                adminAlert.raise("STRIPE_REFUND_FAILED",
                        "Remboursement " + refundId + " a échoué",
                        Map.of("refundId", refundId, "piId", piId != null ? piId : "unknown"));
            }
        } catch (Exception e) {
            log.warn("Could not parse charge.refund.updated: {}", e.getMessage());
        }
    }

    void handleEarlyFraudWarning(Event event) {
        try {
            JsonNode root = objectMapper.readTree(event.getDataObjectDeserializer().getRawJson());
            String warningId = root.path("id").asText();
            String chargeId  = root.path("charge").asText(null);
            String fraudType = root.path("fraud_type").asText(null);
            auditService.log("FRAUD", null, "EARLY_FRAUD_WARNING", null,
                    Map.of("warningId", warningId, "chargeId", chargeId != null ? chargeId : "unknown",
                            "fraudType", fraudType != null ? fraudType : "unknown"));
            adminAlert.raise("STRIPE_EARLY_FRAUD_WARNING",
                    "Alerte fraude précoce sur charge " + chargeId + " (" + fraudType + ")",
                    Map.of("warningId", warningId, "chargeId", chargeId != null ? chargeId : "unknown"));
        } catch (Exception e) {
            log.warn("Could not parse early_fraud_warning: {}", e.getMessage());
        }
    }

    // ─── Package-request marketplace : escrow on negotiation thread ──────────────

    /**
     * Creates a Stripe escrow PaymentIntent for an AWAITING_PAYMENT negotiation thread.
     * Mirrors {@link #createEscrow} but bound to a negotiation_thread_id instead of bid_id.
     *
     * The traveler must have a fully-onboarded Stripe Connect account
     * (same eligibility rules as for bids).
     *
     * Returns clientSecret that the Flutter side passes to Stripe SDK confirmPayment.
     * On webhook payment_intent.amount_capturable_updated, the thread is finalized as ACCEPTED
     * via {@code com.yadony.api.requests.service.NegotiationService.finalizeAfterPayment}.
     */
    public PaymentResponse createNegotiationEscrow(
            UUID threadId,
            UUID senderId,
            UUID travelerId,
            BigDecimal amountEur,
            String promoCode,
            String serverCurrency) {
        return createNegotiationEscrow(
                threadId, senderId, travelerId, amountEur, promoCode, null, serverCurrency);
    }

    public PaymentResponse createNegotiationEscrow(
            UUID threadId,
            UUID senderId,
            UUID travelerId,
            BigDecimal amountEur,
            String promoCode,
            BigDecimal persistedCommissionRate,
            String serverCurrency) {
        log.info("createNegotiationEscrow(threadId={}, senderId={}, travelerId={}, amount={})",
                threadId, senderId, travelerId, amountEur);

        UserEntity sender = userRepository.findById(senderId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Sender introuvable"));

        // Même raison que createEscrow : plus de garde contre la préférence courante,
        // la devise du fil de négociation est déjà figée (serverCurrency).
        SupportedCurrency currency = SupportedCurrency.fromCode(serverCurrency);

        // Même garde que createEscrow : zone CFA = versement Stripe impossible.
        if (!CurrencyPaymentRails.allows(currency, com.yadony.api.payments.cash.PaymentMethod.STRIPE)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "payment-method-unavailable-for-currency", "Payment Method Unavailable For Currency",
                    "La zone CFA n'accepte pas le paiement par carte pour un colis.");
        }

        UserEntity traveler = userRepository.findById(travelerId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "traveler-not-found", "Traveler Not Found", "Voyageur introuvable"));

        // Taux effectif (override voyageur/expéditeur ; min le plus favorable) — SOURCE UNIQUE.
        // Promo optionnel : résolu strictement (invalide/expiré → repli silencieux sur le
        // taux sans promo, même contrat que le flux bid — cf. BidService.quote et
        // PaymentService.createEscrow). Le rachat (redeem) n'a PAS lieu ici : aucun bid_id
        // n'existe encore pour ce thread, il n'est matérialisé qu'après paiement confirmé
        // (ThreadAcceptedBidListener). L'appelant (NegotiationController) persiste le promo
        // sur le thread quand promoApplied=true pour que le rachat puisse s'y raccrocher.
        // Ce calcul et sa normalisation précèdent toute reprise de PaymentIntent.
        BigDecimal rate;
        boolean promoApplied = false;
        String code = promoCode != null ? promoCode.strip() : null;
        if (persistedCommissionRate != null) {
            rate = persistedCommissionRate;
            promoApplied = code != null && !code.isBlank();
        } else if (code != null && !code.isBlank()) {
            try {
                rate = commissionRateResolver.resolve(traveler.getId(), sender.getId(), code);
                promoApplied = true;
            } catch (com.yadony.api.common.YadonyBusinessException e) {
                log.warn("Promo {} invalide pour la négociation {} ({}): repli sur le taux sans promo",
                        code, threadId, e.getErrorCode());
                rate = commissionRateResolver.resolve(traveler.getId(), sender.getId());
            }
        } else {
            rate = commissionRateResolver.resolve(traveler.getId(), sender.getId());
        }
        // Modèle B : amountEur = NET voyageur. L'expéditeur paie gross = net*(1+rate).
        PriceBreakdown b = PriceBreakdown.fromNet(amountEur, rate);
        CurrencyAmount localGross = CurrencyAmount.of(b.gross(), currency);
        CurrencyAmount localCommission = CurrencyAmount.of(b.commission(), currency);

        // Idempotency: reuse existing non-failed payment for this thread
        Optional<PaymentEntity> existing = paymentRepository.findByNegotiationThreadId(threadId);
        PaymentEntity recyclable = null; // stale row to recycle (negotiation_thread_id is UNIQUE)
        if (existing.isPresent()) {
            PaymentEntity payment = existing.get();
            if (payment.getStatus() == PaymentStatus.ESCROW
                    || payment.getStatus() == PaymentStatus.RELEASED) {
                throw new YadonyBusinessException(HttpStatus.CONFLICT,
                        "payment-already-completed", "Payment Already Completed",
                        "Le paiement pour cette négociation a déjà été effectué");
            }
            if (payment.getStatus() == PaymentStatus.PENDING) {
                try {
                    PaymentIntent pi = stripeGateway.retrievePaymentIntent(payment.getStripePaymentIntentId());
                    String piStatus = pi.getStatus();
                    if ("requires_payment_method".equals(piStatus)
                            || "requires_confirmation".equals(piStatus)) {
                        if (matchesExpectedAmountAndCurrency(payment, pi, localGross)) {
                            return toNegotiationPaymentResponse(
                                    payment, pi, rate, promoApplied); // resume compatible in-flight PI
                        }
                        log.info("Canceling incompatible legacy PaymentIntent {} for thread {}",
                                payment.getStripePaymentIntentId(), threadId);
                        cancelAndConfirmForRecycle(pi);
                    } else if ("requires_action".equals(piStatus)) {
                        // Abandoned 3DS/PayPal redirect: nothing was captured, but the PI
                        // can no longer be resumed by a fresh PaymentSheet. Cancel it on
                        // Stripe and recycle the row instead of blocking the sender with
                        // a spurious "already completed" conflict.
                        log.info("Canceling abandoned requires_action PaymentIntent {} for thread {}",
                                pi.getId(), threadId);
                        cancelAndConfirmForRecycle(pi);
                    } else if (!"canceled".equals(piStatus)) {
                        // requires_capture / processing / succeeded → a live or used PI
                        throw new YadonyBusinessException(HttpStatus.CONFLICT,
                                "payment-already-completed", "Payment Already Completed",
                                "Le paiement pour cette négociation a déjà été effectué");
                    }
                    // canceled (or just-canceled requires_action) → stale; recycle below
                } catch (StripeException e) {
                    log.error("Could not retrieve or cancel existing PaymentIntent for thread {}",
                            threadId, e);
                    throw stripeRecoveryFailed();
                }
            }
            // PENDING-with-confirmed-canceled-PI, FAILED, REFUNDED or CANCELLED:
            // recycle this row for a fresh PaymentIntent rather than inserting a second
            // one (which would violate the UNIQUE negotiation_thread_id constraint).
            recyclable = payment;
        }

        if (traveler.getStripeAccountStatus() != StripeAccountStatus.ONBOARDING_COMPLETE
                || traveler.getStripeAccountId() == null
                || traveler.getStripeAccountId().isBlank()) {
            throw new TravelerNotEligibleForPaymentException(traveler.getId());
        }

        try {
            ensureCardPaymentsCapability(traveler.getStripeAccountId());

            // Customer attaché (même règle que createEscrow) : sans lui, Stripe rejette
            // un payment_method enregistré ("pm_... appartient au client cus_...") quand
            // la PaymentSheet native paie la négociation avec une carte enregistrée.
            String customerId = ensureStripeCustomer(sender);

            PaymentIntentCreateParams.Builder paramsBuilder = PaymentIntentCreateParams.builder()
                    .setAmount(localGross.minor())                     // gross, pas net
                    .setCurrency(currency.code())
                    .setCustomer(customerId)
                    .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                    // Approche A : carte + wallets + PayPal dans la PaymentSheet.
                    // on_behalf_of retiré (PayPal ne le supporte pas).
                    .addPaymentMethodType("card")
                    .addPaymentMethodType("paypal")
                    // Modèle "separate charges and transfers" (cohérent avec createEscrow) :
                    // PAS d'application_fee_amount ni de transfer_data ici. Le gross reste sur
                    // le solde plateforme ; à la livraison, DeliveryEventListener crée un
                    // Transfer(net = gross - commission) vers le voyageur, la commission restant
                    // acquise à Yadony. NB : Stripe rejette application_fee_amount sans
                    // transfer_data[destination] (destination/direct charge requis).
                    .setStatementDescriptorSuffix("YADONY")
                    .putMetadata("negotiation_thread_id", threadId.toString())
                    .putMetadata("sender_id", sender.getId().toString())
                    .putMetadata("traveler_id", traveler.getId().toString())
                    .putMetadata("commission_rate", rate.toPlainString())
                    .putMetadata("currency", currency.code())
                    .putMetadata("scope", "NEGOTIATION");
            PaymentIntentCreateParams params = paramsBuilder.build();

            PaymentIntent pi = stripeGateway.createPaymentIntent(params);

            // Recycle a stale row when present (UNIQUE negotiation_thread_id), else insert.
            PaymentEntity payment = (recyclable != null) ? recyclable : new PaymentEntity();
            payment.setNegotiationThreadId(threadId);
            payment.setStripePaymentIntentId(pi.getId());
            payment.setAmount(localGross.major());          // total payé par l'expéditeur (gross)
            payment.setCommissionAmount(localCommission.major());
            payment.setCurrency(currency.code());
            payment.setStatus(PaymentStatus.PENDING);
            if (recyclable != null) {
                payment.clearLegacyFxData();
            }
            paymentRepository.save(payment);

            auditService.log("PAYMENT", payment.getId(), "NEGOTIATION_ESCROW_CREATED",
                    sender.getId(),
                    java.util.Map.of("threadId", threadId.toString(),
                            "amount", localGross.major(),
                            "commission", localCommission.major(),
                            "currency", currency.code(),
                            "stripePaymentIntentId", pi.getId()));

            log.info("PaymentIntent {} created for negotiation thread {} (sender={})",
                    pi.getId(), threadId, sender.getId());
            return toNegotiationPaymentResponse(payment, pi, rate, promoApplied);
        } catch (StripeException e) {
            log.error("Stripe PaymentIntent creation failed for negotiation thread {}", threadId, e);
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                    "stripe-error", "Stripe Error",
                    "Échec création PaymentIntent : " + e.getMessage());
        }
    }
}
