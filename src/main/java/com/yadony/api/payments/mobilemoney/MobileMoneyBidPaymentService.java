package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.Msisdn;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.PriceBreakdown;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.MobileMoneyDepositFailedEvent;
import com.yadony.api.payments.events.MobileMoneyPaymentConfirmedEvent;
import com.yadony.api.payments.events.MobileMoneyPaymentExpiredEvent;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPaymentStatusResponse;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayCountries;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import com.yadony.api.promo.PromoRedemptionEntity;
import com.yadony.api.promo.PromoService;
import com.yadony.api.voucher.CommissionVoucherService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Rail mobile money côté bid. Acceptation dans la même transaction que la création du
 * paiement (comme {@code CashCommissionService#acceptCashBid} pour le cash) ; initiation
 * du deposit à la demande de l'expéditeur ; statut pour les deux parties.
 *
 * <p><b>Frontière transactionnelle critique</b> : {@link #acceptBid} crée le
 * {@code PaymentEntity} et se termine (donc commite) SANS jamais appeler pawaPay.
 * {@link #initiateDeposit} est un appel séparé, ultérieur (un second aller-retour HTTP,
 * déclenché par l'expéditeur) : il relit un paiement déjà durablement commité par un appel
 * {@code acceptBid} antérieur et terminé, puis seulement alors appelle
 * {@link PawapaySubmissionService#submitDeposit}. Ne jamais faire créer le paiement et
 * appeler {@code submitDeposit} par la même méthode transactionnelle :
 * {@link PawapayOperationService#create} s'exécute en {@code REQUIRES_NEW}, sur une
 * connexion distincte qui ne voit pas les écritures non encore commitées de la transaction
 * appelante — la ligne {@code payments} qu'elle référence par clé étrangère semblerait ne
 * pas exister, et l'échec serait aujourd'hui déguisé en 409 « opération déjà en cours »
 * (voir le commentaire de {@code PawapayOperationService#create} sur ce point précis).
 * Le verrou pessimiste pris par {@link PaymentRepository#findByBidIdForUpdate} au début de
 * {@code initiateDeposit} n'est pas concerné par ce risque : il pose un {@code FOR NO KEY
 * UPDATE} (voir le Javadoc de cette méthode), compatible avec le {@code KEY SHARE} que
 * prend l'INSERT de l'opération pawaPay — seul un {@code FOR UPDATE} natif bloquerait cette
 * insertion.
 */
@Service
public class MobileMoneyBidPaymentService {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyBidPaymentService.class);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final PawapayOperationService operations;
    private final PawapaySubmissionService submission;
    private final PawapayClient client;
    private final MobileMoneyBidPricing pricing;
    private final FirebaseContactService firebaseContact;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final CommissionRateResolver commissionRateResolver;
    private final PromoService promoService;
    private final CommissionVoucherService voucherService;
    private final PawapayProperties props;

    /**
     * Transaction INDÉPENDANTE, réservée à l'entrée d'audit d'un dépôt refusé (Ronde 1,
     * point 3) — {@code @Transactional(REQUIRES_NEW)} sur une méthode privée ne servirait
     * à rien ici (auto-invocation : l'appel `this.xxx()` ne passe jamais par le proxy Spring
     * qui porte l'annotation). Même motif que {@code GuestUserCleanupScheduler}.
     */
    private final TransactionTemplate independentAuditTransaction;

    /**
     * Injection par champ, pas par constructeur : {@code AdminAlertService} n'est utile
     * qu'à {@link #confirmEscrow} (alerte admin sur un deposit encaissé après annulation),
     * ajouter un 17e paramètre constructeur aurait cassé tous les appels déjà couverts par
     * {@code MobileMoneyBidPaymentServiceTest} (tâche 13) sans aucun bénéfice pour ces
     * tests-là. {@code required = false} : reste {@code null} si jamais non résolu, auquel
     * cas {@link #confirmEscrow} continue de fonctionner sans lever d'alerte (garde
     * explicite dans {@code refundAfterCancel}).
     */
    @Autowired(required = false)
    private AdminAlertService adminAlert;

    public MobileMoneyBidPaymentService(BidRepository bidRepository, AnnouncementRepository announcementRepository,
                                        UserRepository userRepository, PaymentRepository paymentRepository,
                                        PawapayOperationService operations, PawapaySubmissionService submission,
                                        PawapayClient client, MobileMoneyBidPricing pricing,
                                        FirebaseContactService firebaseContact, AuditService audit,
                                        ApplicationEventPublisher events, CommissionRateResolver commissionRateResolver,
                                        PromoService promoService, CommissionVoucherService voucherService,
                                        PlatformTransactionManager transactionManager, PawapayProperties props) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.operations = operations;
        this.submission = submission;
        this.client = client;
        this.pricing = pricing;
        this.firebaseContact = firebaseContact;
        this.audit = audit;
        this.events = events;
        this.commissionRateResolver = commissionRateResolver;
        this.promoService = promoService;
        this.voucherService = voucherService;
        this.props = props;
        this.independentAuditTransaction = new TransactionTemplate(transactionManager);
        this.independentAuditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ── Acceptation ─────────────────────────────────────────────────────────

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public MobileMoneyPaymentStatusResponse acceptBid(UUID bidId, UUID travelerId) {
        // Ronde 1, point 4 : interrupteur d'urgence. Vérifié à la création du bid
        // (BidService.resolvePaymentMethodFor, tâche 12) mais jamais ici — un exploitant qui
        // coupe yadony.pawapay.enabled après incident/fraude constatée doit empêcher TOUTE
        // acceptation et TOUTE initiation ultérieure, pas seulement les nouveaux bids. Vérifié
        // avant tout accès repository : couper le rail ne doit jamais dépendre d'un verrou
        // pessimiste pris pour rien.
        if (!props.enabled()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-disabled",
                    "Mobile Money Disabled", "Le mobile money n'est pas encore disponible.");
        }
        BidEntity bid = bidRepository.findByIdForUpdate(bidId).orElseThrow(() -> notFound("bid-not-found", "Demande introuvable"));
        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(bid.getAnnouncementId())
                .orElseThrow(() -> notFound("announcement-not-found", "Annonce introuvable"));
        if (!announcement.getTravelerId().equals(travelerId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Ce trajet ne vous appartient pas");
        }
        if (bid.getPaymentMethod() != PaymentMethod.MOBILE_MONEY) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-payment-method",
                    "Invalid Payment Method", "Ce colis n'est pas payé en mobile money");
        }
        // Ronde 1, point 10 : filtré par rail comme les deux autres lectures de ce même
        // paiement dans ce fichier (initiateDeposit, status) — un paiement d'un autre rail
        // sur ce bid (état incohérent, jamais rencontré aujourd'hui) ne doit jamais déclencher
        // ce retour idempotent.
        Optional<PaymentEntity> existing = paymentRepository.findByBidId(bidId)
                .filter(p -> p.getRail() == PaymentRail.PAWAPAY);
        if (bid.getStatus() == BidStatus.AWAITING_PAYMENT && existing.isPresent()) {
            return status(bid, announcement, existing, operations.findLatest(existing.get().getId(), PawapayOperationKind.DEPOSIT));
        }
        if (bid.getStatus() != BidStatus.PENDING) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "invalid-status", "Invalid Status",
                    "Ce colis n'est plus en attente de réponse");
        }
        UserEntity traveler = userRepository.findById(travelerId).orElseThrow(() -> notFound("user-not-found", "Voyageur introuvable"));
        if (!traveler.hasActiveMobileMoney()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-account-required",
                    "Mobile Money Account Required", "Activez votre compte de versement mobile money avant d'accepter.");
        }
        // Écart déclaré par rapport au cahier des charges (voir task-13-report.md) : le compte
        // de versement du voyageur a pu être désactivé puis réactivé dans une autre devise
        // depuis la création du bid (garde de la tâche 12, dans
        // BidService.resolvePaymentMethodFor) — acceptBid est le DERNIER portail avant que
        // l'argent bouge, il doit donc revérifier la devise et pas seulement l'activité du
        // compte. Sans cette ligne : le dépôt réussirait et le versement serait rejeté à la
        // livraison, argent encaissé, voyageur impayable.
        if (!announcement.getCurrency().equalsIgnoreCase(traveler.getMobileMoneyCurrency())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-currency-mismatch",
                    "Mobile Money Currency Mismatch",
                    "Votre compte de versement est en " + traveler.getMobileMoneyCurrency()
                            + ", ce trajet est en " + announcement.getCurrency() + ".");
        }
        if (AnnouncementStatus.OUT_OF_MARKET.contains(announcement.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "announcement-not-accepting",
                    "Announcement Not Accepting", "Ce trajet n'accepte plus de colis");
        }
        boolean kgFree = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
        if (!kgFree && bid.getWeightKg() != null && bid.getWeightKg().compareTo(announcement.getAvailableKg()) > 0) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "capacity-insufficient", "Insufficient Capacity",
                    "Capacité insuffisante pour accepter cette demande");
        }

        PriceBreakdown price = pricing.price(bid, announcement);

        // Ronde 1, point 1 (CRITIQUE) : rachat du promo et consommation du bon de
        // parrainage — même motif et même position que le rail espèces
        // (PaymentService.createEscrow) : APRÈS le calcul du taux (sinon la remise déjà
        // figée dans `price`/`bid.commissionRate` disparaîtrait du taux qu'on vient de
        // recalculer), AVANT la création du PaymentEntity. MobileMoneyBidPricing ne fait
        // que LIRE le bon/le promo (CommissionRateResolver#resolve, lecture pure, cf. son
        // propre Javadoc) pour calculer le taux — sans ce rachat/cette consommation ici,
        // un même code promo à usage unique ou un même bon de parrainage réduirait la
        // commission d'un nombre illimité d'envois mobile money.
        boolean promoApplied = false;
        if (bid.getPromoCode() != null) {
            try {
                commissionRateResolver.resolve(announcement.getTravelerId(), bid.getSenderId(), bid.getPromoCode(),
                        bid.getSenderId(), bid.getId());
                promoApplied = true;
            } catch (YadonyBusinessException e) {
                log.warn("Promo {} non rachetable pour le bid mobile money {} (invalide au moment de "
                        + "l'acceptation) — pas de rachat, taux déjà replié par MobileMoneyBidPricing",
                        bid.getPromoCode(), bidId);
            }
        }
        if (promoApplied) {
            PromoRedemptionEntity redemption = promoService.redeem(bid.getPromoCode(), bid.getSenderId(), bidId,
                    bid.getCommissionRate());
            bid.setPromoCodeId(redemption.getPromoCodeId());
        }
        // Best-effort, idempotent par (bid, expéditeur) : no-op silencieux si l'expéditeur ne
        // détient aucun bon actif.
        voucherService.consume(bid.getSenderId(), bidId);

        // Capacité réservée dès l'acceptation, rendue par expire() ou cancelBid.
        if (!kgFree && bid.getWeightKg() != null) {
            announcement.setAvailableKg(announcement.getAvailableKg().subtract(bid.getWeightKg()));
            if (announcement.getAvailableKg().compareTo(BigDecimal.ZERO) <= 0
                    && !AnnouncementStatus.OUT_OF_MARKET.contains(announcement.getStatus())) {
                announcement.setStatus(AnnouncementStatus.FULL);
            }
            announcementRepository.save(announcement);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(now.plusMinutes(props.depositDeadlineMinutes()));
        bidRepository.save(bid);

        PaymentEntity payment = new PaymentEntity();
        payment.setBidId(bidId);
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setStripePaymentIntentId(null);
        payment.setAmount(price.gross());
        payment.setCommissionAmount(price.commission());
        payment.setCurrency(announcement.getCurrency());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setLegacyDestinationCharge(false);
        payment = paymentRepository.save(payment);

        audit.log("BID", bidId, "MM_BID_ACCEPTED_AWAITING_PAYMENT", travelerId,
                Map.of("announcementId", announcement.getId().toString(), "deadlineAt", bid.getAwaitingPaymentExpiresAt().toString()));
        audit.log("PAYMENT", payment.getId(), "MM_PAYMENT_CREATED", travelerId,
                Map.of("bidId", bidId.toString(), "amount", price.gross().toPlainString(),
                        "commission", price.commission().toPlainString(), "currency", announcement.getCurrency()));
        events.publishEvent(new BidAcceptedEvent(bidId, bid.getSenderId(), travelerId, announcement.getId(), true));
        return status(bid, announcement, Optional.of(payment), Optional.empty());
    }

    // ── Initiation du deposit ───────────────────────────────────────────────

    @Transactional
    public MobileMoneyPaymentStatusResponse initiateDeposit(UUID bidId, UUID senderId, String phoneOverride) {
        PaymentEntity payment = paymentRepository.findByBidIdForUpdate(bidId)
                .filter(p -> p.getRail() == PaymentRail.PAWAPAY)
                .orElseThrow(() -> notFound("mobile-money-payment-not-found", "Aucun paiement mobile money pour ce colis"));
        BidEntity bid = bidRepository.findById(bidId).orElseThrow(() -> notFound("bid-not-found", "Demande introuvable"));
        if (!bid.getSenderId().equals(senderId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Vous n'êtes pas l'expéditeur de ce colis");
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "mobile-money-payment-not-pending",
                    "Payment Not Pending", "Ce paiement n'est plus en attente (" + payment.getStatus() + ")");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (bid.getStatus() != BidStatus.AWAITING_PAYMENT || bid.getAwaitingPaymentExpiresAt() == null
                || bid.getAwaitingPaymentExpiresAt().isBefore(now)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-payment-expired",
                    "Payment Expired", "Le délai de paiement est dépassé.");
        }
        // Un deposit vivant ou abouti existe : on le renvoie, on n'en crée pas un second.
        Optional<PawapayOperationEntity> live = operations.findLive(payment.getId(), PawapayOperationKind.DEPOSIT);
        if (live.isPresent()) {
            return status(bid, null, Optional.of(payment), live);
        }

        // Ronde 2, point 3 (tranché par le coordinateur) : l'interrupteur d'urgence est
        // vérifié ICI, APRÈS la branche idempotente ci-dessus, jamais avant. Il doit empêcher
        // tout NOUVEAU mouvement d'argent (tout ce qui suit : résolution du numéro, appels
        // pawaPay, soumission), pas empêcher de relire une opération déjà en vol — relire
        // n'engage aucun débit. Placé avant, un expéditeur dont le dépôt est déjà en cours et
        // qui relance (ou dont l'app repolle le statut) recevrait un 422 au lieu de son
        // opération, alors que couper le rail pendant un incident ne devrait affecter que les
        // dépôts pas encore soumis.
        if (!props.enabled()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-disabled",
                    "Mobile Money Disabled", "Le mobile money n'est pas encore disponible.");
        }

        String msisdn = resolvePayerMsisdn(bid, phoneOverride);
        // Ronde 1, point 2 : les deux appels réseau pawaPay ci-dessous n'étaient pas encadrés
        // — exactement le défaut déjà corrigé à la tâche 11 dans PawapaySubmissionService#submit
        // (cité en commentaire plus bas dans cette même méthode) et repris par
        // MobileMoneyAccountService#activate pour ces deux mêmes appels. Sans ce garde-fou,
        // une panne pawaPay remontait en 500 générique au lieu du 502
        // mobile-money-provider-unavailable déjà normalisé pour ce rail — et le verrou
        // PESSIMISTIC_WRITE pris sur `payments` par findByBidIdForUpdate restait posé pendant
        // tout le timeout HTTP, bloquant toute initiation concurrente derrière.
        Optional<PawapayProviderPrediction> predicted;
        try {
            predicted = client.predictProvider(msisdn);
        } catch (RestClientException e) {
            throw providerUnavailable(bidId, "predict-provider", e);
        }
        PawapayProviderPrediction prediction = predicted
                .orElseThrow(() -> payerUnsupported("Aucun opérateur mobile money reconnu pour ce numéro."));
        Map<String, PawapayProviderConfig> configuration;
        try {
            configuration = client.activeConfiguration();
        } catch (RestClientException e) {
            throw providerUnavailable(bidId, "active-configuration", e);
        }
        PawapayProviderConfig conf = configuration.get(prediction.provider());
        if (conf == null || !conf.supportsDeposit()) {
            throw payerUnsupported(PawapayProviders.label(prediction.provider()) + " ne permet pas le paiement pour le moment.");
        }
        if (!conf.currency().equalsIgnoreCase(payment.getCurrency())) {
            throw payerUnsupported("Ce numéro paie en " + conf.currency() + ", ce colis est en " + payment.getCurrency() + ".");
        }
        if (conf.deposit().minAmount() != null && payment.getAmount().compareTo(conf.deposit().minAmount()) < 0
                || conf.deposit().maxAmount() != null && payment.getAmount().compareTo(conf.deposit().maxAmount()) > 0) {
            throw payerUnsupported("Montant hors des limites de " + PawapayProviders.label(prediction.provider()) + ".");
        }
        String country = PawapayCountries.toAlpha2(prediction.countryAlpha3());
        // Ronde 1, point 9 : PawapayCountries.toAlpha2 rend null pour un alpha-3 non couvert
        // par la table ISO du JDK. pawapay_operations.country est NOT NULL (PawapayOperationEntity) :
        // sans cette garde, submission.submitDeposit lèverait une violation de contrainte brute
        // (500) au lieu d'un 422 propre — sur le chemin qui engage l'argent.
        if (country == null) {
            throw payerUnsupported("Pays non reconnu pour ce numéro.");
        }
        // Écart déclaré (voir task-13-report.md) : phoneOverride est déjà encadré ci-dessous
        // dans resolvePayerMsisdn, mais ce numéro-ci vient de pawaPay (prédiction, après un
        // appel réseau réussi) et n'est pas une saisie de l'expéditeur — même famille de
        // défaut que le @Pattern de BidRequest.phoneNumber non aligné avec les bornes de
        // Msisdn.normalize, déjà corrigé deux fois dans cette branche (tâche 12,
        // MobileMoneyAccountService#activate). Même traitement que ce dernier : 502, pas 422,
        // ce n'est pas la faute de l'expéditeur.
        String normalized;
        try {
            normalized = Msisdn.normalize(prediction.phoneNumber() != null ? prediction.phoneNumber() : msisdn);
        } catch (IllegalArgumentException e) {
            throw providerUnavailable(bidId, "msisdn-normalize", e);
        }
        if (!normalized.equals(bid.getMobileMoneyPhone())) {
            bid.setMobileMoneyPhone(normalized);
            bid.setMobileMoneyCountryCode(country);
            bidRepository.save(bid);
        }
        String successfulUrl = null;
        String failedUrl = null;
        if (conf.isRedirectDeposit()) {
            String base = props.returnBaseUrl() + "/api/v1/pawapay/return/" + bidId;
            successfulUrl = base + "?outcome=success";
            failedUrl = base + "?outcome=failed";
        }
        PawapayOperationEntity op = submission.submitDeposit(payment.getId(), normalized, prediction.provider(), country,
                payment.getAmount(), payment.getCurrency(), "bid-" + bidId, successfulUrl, failedUrl);
        // Ronde 1, point 3 : transaction INDÉPENDANTE — AuditService.log() n'est pas annoté,
        // il rejoindrait sinon la transaction d'initiateDeposit. Sur un SUBMIT_REJECTED, le
        // throw juste en dessous annule cette transaction ; la ligne pawapay_operations créée
        // par submitDeposit (REQUIRES_NEW, déjà commitée avant ce point) y survit, mais cette
        // entrée d'audit disparaissait avec elle — précisément pour les tentatives de débit
        // refusées, celles où la trace compte le plus.
        independentAuditTransaction.executeWithoutResult(status -> audit.log("PAYMENT", payment.getId(),
                "MM_DEPOSIT_INITIATED", senderId,
                Map.of("operationId", op.getId().toString(), "provider", op.getProvider(),
                        "msisdnMasked", op.getMsisdnMasked(), "status", op.getStatus().name())));
        if (op.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-deposit-rejected",
                    "Deposit Rejected", "Paiement refusé par l'opérateur : "
                    + (op.getFailureMessage() != null ? op.getFailureMessage() : op.getFailureCode()));
        }
        return status(bid, null, Optional.of(payment), Optional.of(op));
    }

    private String resolvePayerMsisdn(BidEntity bid, String phoneOverride) {
        if (phoneOverride != null && !phoneOverride.isBlank()) {
            try {
                return Msisdn.normalize(phoneOverride);
            } catch (IllegalArgumentException e) {
                throw payerUnsupported("Numéro de téléphone invalide.");
            }
        }
        if (bid.getMobileMoneyPhone() != null) {
            return bid.getMobileMoneyPhone();
        }
        String firebasePhone = userRepository.findById(bid.getSenderId())
                .map(u -> firebaseContact.getContact(u.getFirebaseUid()).phoneNumber()).orElse(null);
        if (firebasePhone == null || firebasePhone.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-phone-required",
                    "Phone Required", "Indiquez le numéro mobile money qui paiera.");
        }
        // Écart déclaré (voir task-13-report.md) : ce numéro vient de Firebase, pas d'une
        // saisie de CETTE requête, mais reste une valeur externe non garantie dans les bornes
        // de Msisdn.normalize (8-15 chiffres) — même défaut de garde que phoneOverride
        // ci-dessus si on le laissait passer tel quel. Un numéro Firebase inexploitable reste
        // néanmoins un problème que l'expéditeur peut résoudre lui-même (indiquer un autre
        // numéro) : 422, comme les autres branches de cette méthode, pas 502.
        try {
            return Msisdn.normalize(firebasePhone);
        } catch (IllegalArgumentException e) {
            throw payerUnsupported("Le numéro enregistré n'est pas exploitable. Indiquez un autre numéro.");
        }
    }

    // ── Séquestre (tâche 14) ────────────────────────────────────────────────

    /**
     * Deposit COMPLETED : PENDING → ESCROW une seule fois, via l'UPDATE gardé
     * {@link PaymentRepository#markEscrowIfPending} — jamais une lecture d'entité suivie
     * d'une écriture. Un dépôt confirmé peut être notifié deux fois (le callback pawaPay
     * et le poller de réconciliation peuvent tous deux atteindre l'état final) : cette
     * méthode doit donc être idempotente par construction, pas par confiance dans
     * l'appelant. {@code moved == 1} : j'ai gagné la course, je finalise (numéro de suivi,
     * QR, événement, notification). {@code moved == 0} : quelqu'un est déjà passé, je sors
     * silencieusement, sans rien refaire.
     *
     * <p>Deux cas de course supplémentaires, au-delà du simple rejeu, sont couverts :
     * <ul>
     *   <li>le paiement est déjà CANCELLED (la deadline de dépôt est passée pendant que
     *       l'expéditeur saisissait son code PIN, {@code markEscrowIfPending} échoue
     *       donc) : pawaPay a quand même encaissé le deposit, il est immédiatement
     *       remboursé ;</li>
     *   <li>le bid est sorti d'AWAITING_PAYMENT entre-temps (annulation concurrente)
     *       alors que le paiement, lui, vient tout juste de passer en ESCROW : le claim
     *       est aussitôt retourné en REFUNDED via
     *       {@link PaymentRepository#markRefundedIfEscrow}.</li>
     * </ul>
     * Dans les deux cas, l'argent est rendu sur-le-champ, jamais gardé sans colis en face.
     */
    @Transactional
    public void confirmEscrow(UUID operationId, UUID paymentId) {
        int moved = paymentRepository.markEscrowIfPending(paymentId, operationId, Instant.now());
        PaymentEntity payment = paymentRepository.findById(paymentId).orElseThrow(
                () -> new IllegalStateException("Paiement introuvable : " + paymentId));
        if (moved == 0) {
            if (payment.getStatus() == PaymentStatus.CANCELLED) {
                refundAfterCancel(payment, operationId, "MM_DEPOSIT_AFTER_CANCEL_REFUNDED");
            } else {
                log.info("Deposit {} déjà appliqué sur le paiement {} ({})", operationId, paymentId, payment.getStatus());
            }
            return;
        }
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setPawapayDepositId(operationId);

        BidEntity bid = bidRepository.findByIdForUpdate(payment.getBidId())
                .orElseThrow(() -> new IllegalStateException("Bid introuvable : " + payment.getBidId()));
        if (bid.getStatus() != BidStatus.AWAITING_PAYMENT) {
            // Annulé entre-temps : le claim ESCROW qu'on vient de gagner est immédiatement
            // retourné en REFUNDED — jamais gardé sans colis en face.
            // Ronde 1 (revue tâche 17, point 1 — étendu ici) : PAS de payment.setStatus(REFUNDED)
            // après ce claim bulk — refundAfterCancel pose désormais pawapay_refund_id par
            // attachRefundId (UPDATE ciblé), jamais par un setter ; laisser l'entité sale ici
            // aurait réintroduit exactement le même risque (une écriture ultérieure sur
            // `payments` peut, selon l'ordre exact des opérations, faire flusher un statut
            // périmé — voir PaymentRepositoryMobileMoneyTest, Ronde 1, et task-17-report.md).
            if (paymentRepository.markRefundedIfEscrow(paymentId) == 1) {
                refundAfterCancel(payment, operationId, "MM_DEPOSIT_AFTER_CANCEL_REFUNDED");
            }
            return;
        }
        // Ronde 1, point 5 : simple lecture, jamais findByIdForUpdate — rien n'est écrit sur
        // l'annonce ici (la capacité a été réservée à l'acceptation, dans acceptBid). Un
        // verrou pessimiste pris pour ne lire qu'un identifiant sérialiserait inutilement
        // toutes les confirmations de dépôt entre elles et contre chaque acceptation sur la
        // même annonce, tenu jusqu'au commit — et un dépassement de délai sur ce verrou
        // ferait directement échouer confirmEscrow après le point irréversible (point 4).
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new IllegalStateException("Annonce introuvable : " + bid.getAnnouncementId()));
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setAwaitingPaymentExpiresAt(null);
        if (bid.getQrToken() == null) bid.setQrToken(UUID.randomUUID().toString());
        if (bid.getTrackingToken() == null) bid.setTrackingToken(UUID.randomUUID().toString());
        if (bid.getTrackingNumber() == null) bid.setTrackingNumber(BidService.generateTrackingNumber());
        bid.applyHandoverFrom(announcement);
        bidRepository.save(bid);

        audit.log("PAYMENT", paymentId, "MM_ESCROW", bid.getSenderId(),
                Map.of("bidId", bid.getId().toString(), "operationId", operationId.toString(),
                        "amount", payment.getAmount().toPlainString(), "currency", payment.getCurrency()));
        events.publishEvent(new MobileMoneyPaymentConfirmedEvent(
                bid.getId(), bid.getSenderId(), announcement.getTravelerId(), payment.getAmount(), payment.getCurrency()));
        log.info("Séquestre mobile money : paiement {} ESCROW, bid {} ACCEPTED", paymentId, bid.getId());
    }

    /**
     * Un deposit a été encaissé par pawaPay pour un paiement qui, côté yadony, n'est déjà
     * plus réclamable (deadline de paiement dépassée, ou bid annulé entre-temps) :
     * l'argent ne peut rester en séquestre sans colis en face, il est donc reversé
     * immédiatement via un REFUND pawaPay du même montant.
     */
    private void refundAfterCancel(PaymentEntity payment, UUID depositOperationId, String auditAction) {
        PawapayOperationEntity deposit = operations.get(depositOperationId);
        // Ronde 2 (contre-revue), point 2 : montant soumis = celui du DEPOSIT, jamais
        // payment.getAmount() — même mouvement d'argent, même raison que RefundProcessor
        // (Ronde 1, point 7) : égaux aujourd'hui mais sans garantie contractuelle.
        PawapayOperationEntity refund = submission.submitRefund(payment.getId(), deposit, deposit.getAmount());
        // Ronde 1, point 3 (CRITIQUE) : cette méthode ne testait jamais SUBMIT_REJECTED — un
        // refus pawaPay était audité/alerté puis la méthode retournait normalement (donc
        // commitait), alors que RefundProcessor#refundEscrowedMobileMoney lève dans le même cas
        // (rollback du claim). Deux issues opposées pour le même mouvement d'argent selon le
        // point d'entrée : aligné ici sur RefundProcessor — alerte puis throw.
        if (refund.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            if (adminAlert != null) {
                adminAlert.raise("PAWAPAY_DEPOSIT_AFTER_CANCEL_REJECTED",
                        "pawaPay a refusé le remboursement du deposit après annulation (payment "
                                + payment.getId() + ") : " + refund.getFailureCode(),
                        Map.of("paymentId", payment.getId().toString(), "depositOperationId", depositOperationId.toString(),
                                "operationId", refund.getId().toString(), "failureCode", String.valueOf(refund.getFailureCode())));
            }
            throw new IllegalStateException("pawaPay refund rejected after cancel: " + refund.getFailureCode());
        }
        // Ronde 1, point 3 (préexistant, Important) : même défaut que MM_DEPOSIT_INITIATED
        // (tâche 13). À ce point, submitRefund a DÉJÀ commité sa ligne d'opération
        // (REQUIRES_NEW) et l'appel HTTP à pawaPay est déjà parti — irréversible. Audit AVANT
        // rattachement, dans sa propre transaction (motif MobileMoneyPayoutInitiator#release,
        // repris par RefundProcessor tâche 17 Ronde 1 point 2) : la trace du remboursement
        // survit même si l'écriture ambiante suivante (attachRefundId) échouait.
        independentAuditTransaction.executeWithoutResult(status -> audit.log("PAYMENT", payment.getId(), auditAction, null,
                Map.of("depositOperationId", depositOperationId.toString(), "refundOperationId", refund.getId().toString(),
                        "refundStatus", refund.getStatus().name())));
        // Ronde 1, point 4 : UPDATE ciblé, jamais payment.setPawapayRefundId(...) sur l'entité
        // gérée — même piège que RefundProcessor (tâche 17 Ronde 1 point 1), latent ici tant
        // qu'aucun autre bulk ne touche `payments` dans le même appel, mais qui deviendrait actif
        // au moindre changement futur touchant cette méthode ou son appelant.
        paymentRepository.attachRefundId(payment.getId(), refund.getId());
        if (adminAlert != null) {
            adminAlert.raise("PAWAPAY_DEPOSIT_AFTER_CANCEL",
                    "Deposit encaissé après annulation du bid, remboursement " + refund.getStatus() + " (payment " + payment.getId() + ")",
                    Map.of("paymentId", payment.getId().toString(), "refundOperationId", refund.getId().toString()));
        }
    }

    /**
     * Deposit FAILED ou SUBMIT_REJECTED détecté par le poller (PIN refusé, solde
     * insuffisant, opérateur indisponible…) : le paiement reste PENDING, l'expéditeur est
     * notifié et peut relancer {@link #initiateDeposit} depuis l'app.
     *
     * <p>{@code failureCode} vient de pawaPay (callback non authentifié en staging, ou
     * poller) : borné à 64 caractères avant toute écriture, comme toute valeur externe
     * non authentifiée de ce rail (même convention que
     * {@code PawapayCallbackController#truncate}).
     */
    @Transactional
    public void notifyDepositFailed(UUID operationId, UUID paymentId, String failureCode) {
        // Ronde 1, point 6 : ce retour silencieux (cas structurellement impossible, comme
        // les IllegalStateException de confirmEscrow) restait sans la moindre trace sur le
        // chemin de l'argent — corrigé pour au moins journaliser, même si l'action jumelle
        // de confirmEscrow choisit de lever plutôt que de sortir en silence.
        PaymentEntity payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            log.warn("Deposit {} FAILED : paiement {} introuvable, notification abandonnée", operationId, paymentId);
            return;
        }
        BidEntity bid = bidRepository.findById(payment.getBidId()).orElse(null);
        if (bid == null) {
            log.warn("Deposit {} FAILED : bid {} introuvable (paiement {}), notification abandonnée",
                    operationId, payment.getBidId(), paymentId);
            return;
        }
        String safeFailureCode = truncate(failureCode);
        audit.log("PAYMENT", paymentId, "MM_DEPOSIT_FAILED", bid.getSenderId(),
                Map.of("operationId", operationId.toString(), "failureCode", safeFailureCode == null ? "" : safeFailureCode));
        events.publishEvent(new MobileMoneyDepositFailedEvent(bid.getId(), bid.getSenderId(), safeFailureCode));
    }

    // ── Expiration (tâche 15) ────────────────────────────────────────────────

    /**
     * Issue d'un appel à {@link #expire}, à l'usage exclusif du scheduler appelant.
     *
     * <p><b>Ronde 2, points 1 et 2</b> : {@code expire()} ne lève plus d'alerte administrateur et
     * n'évince plus lui-même le cache de recherche — les deux étaient exécutés PENDANT que
     * {@code expire()} tenait ses deux verrous (paiement, bid), l'un et l'autre synchrones
     * ({@code AdminAlertService#raise} poste sur Telegram par HTTP ; l'éviction, elle, était sans
     * conséquence tant qu'aucun bid ne restait en échec fermé — mais ces échecs fermés en créent
     * justement, par construction). Retarder {@link #confirmEscrow} — la transaction même qu'on
     * attend pour résoudre l'anomalie qui a déclenché l'alerte — par les verrous d'{@code expire}
     * était le pire endroit possible pour un appel réseau synchrone. Les deux actions sont
     * désormais décidées par {@code MobileMoneyPaymentDeadlineScheduler}, APRÈS le retour de
     * cette méthode (donc après le commit de son {@code REQUIRES_NEW}, hors verrou), à partir de
     * la seule valeur de retour :
     * <ul>
     *   <li>{@link #CANCELLED} → le scheduler évince le cache {@code announcements-search} ;</li>
     *   <li>{@link #PAYMENT_MISSING} / {@link #DEPOSIT_COMPLETED_NOT_APPLIED} → le scheduler
     *       lève une alerte administrateur, dédupliquée par bid (même motif que
     *       {@code PawapayReconciliationPoller#escalateUnknown}) — sans cette dédup, un bid
     *       resté en échec fermé (donc resélectionné à chaque tick) spammerait Sentry et
     *       Telegram indéfiniment ;</li>
     *   <li>{@link #IGNORED} → rien.</li>
     * </ul>
     */
    public enum ExpireOutcome {
        /** Bid annulé, capacité rendue, notifications publiées. */
        CANCELLED,
        /** Rien à faire : bid déjà sorti d'AWAITING_PAYMENT, deadline pas encore atteinte, dépôt
         *  encore ouvert, ou course perdue contre {@code confirmEscrow}. */
        IGNORED,
        /** Paiement introuvable pour un bid AWAITING_PAYMENT/MOBILE_MONEY — anomalie. */
        PAYMENT_MISSING,
        /** Dépôt pawaPay COMPLETED mais paiement encore PENDING — en vol, pas mort — anomalie. */
        DEPOSIT_COMPLETED_NOT_APPLIED
    }

    /**
     * Deadline de paiement mobile money dépassée (30 min après acceptation) : bid
     * {@code AWAITING_PAYMENT} annulé, capacité rendue à l'annonce, paiement {@code PENDING}
     * annulé, deux notifications (expéditeur, voyageur). Rend l'{@link ExpireOutcome} de
     * l'opération — voir sa Javadoc pour ce que le scheduler appelant en fait (cache, alerte).
     *
     * <p><b>Idempotent</b> par la primitive atomique {@link PaymentRepository#markCancelledIfPending},
     * jamais par la sélection du scheduler appelant (qui peut repasser sur la même ligne tant
     * qu'elle n'a pas bougé) : un bid qui a déjà quitté {@code AWAITING_PAYMENT} (confirmEscrow,
     * cancelBid…) fait sortir tôt, sans rien lire de plus. Re-vérifie aussi
     * {@code paymentMethod == MOBILE_MONEY} (partagé avec le rail carte, où ce statut précède
     * l'acceptation et ne réserve jamais de capacité) et {@code awaitingPaymentExpiresAt}
     * lui-même — le champ qui définit l'opération : ne jamais faire confiance au seul filtre du
     * scheduler appelant pour ces trois conditions.
     *
     * <p><b>Ordre des verrous (Ronde 1, point 1 — CRITIQUE)</b> : le paiement est verrouillé
     * ({@link PaymentRepository#findByBidIdForUpdate}) AVANT le bid
     * ({@link com.yadony.api.matching.BidRepository#findByIdForUpdate}) — exactement comme
     * {@link #confirmEscrow} (qui touche le paiement en premier via {@code markEscrowIfPending})
     * et {@link #initiateDeposit}. L'ordre inverse (bid puis paiement, tenté dans une version
     * antérieure de cette méthode) croiserait les deux transactions en sens opposé à la seconde
     * où la deadline tombe pendant qu'un dépôt vient d'aboutir : PostgreSQL détecte
     * l'interblocage et tue l'une des deux transactions au bout d'une seconde. Perdre ce tirage
     * ne coûte rien à {@code expire} (le tick suivant repasse) ; le perdre côté
     * {@code confirmEscrow} serait définitif — son événement n'est publié qu'une fois et le
     * poller de réconciliation ne relit jamais une opération déjà finale.
     *
     * <p><b>Course avec {@link #confirmEscrow}</b> : les deux méthodes s'affrontent sur le même
     * {@code UPDATE … WHERE status = PENDING} du paiement — un seul gagne. Si
     * {@code markCancelledIfPending} rend 0 (confirmEscrow est passé en premier, ou rejeu de
     * cette méthode elle-même), on ne fait RIEN D'AUTRE : ni bid, ni annonce, ni audit, ni
     * événement. Agir quand même annulerait un colis déjà payé et regonflerait à tort la
     * capacité du trajet — c'est l'invariant central de cette méthode.
     *
     * <p><b>Dépôt {@code COMPLETED} mais paiement encore {@code PENDING} (Ronde 1, point 2 —
     * CRITIQUE)</b> : ce n'est PAS un dépôt mort, c'est un dépôt EN VOL — {@link #confirmEscrow}
     * n'est simplement pas encore passé (listener asynchrone en file, redémarrage, ou victime de
     * l'interblocage ci-dessus). L'annuler solderait en silence un colis déjà payé : bid annulé,
     * capacité regonflée, et « paiement non reçu » notifié à un expéditeur pourtant débité — sans
     * qu'aucun chemin ne puisse plus jamais soumettre de remboursement (le poller ne relit pas
     * les opérations finales). On ne touche à rien, on journalise en ERROR et on rend
     * {@link ExpireOutcome#DEPOSIT_COMPLETED_NOT_APPLIED} : l'invariant est rompu, un humain doit
     * le savoir — c'est au scheduler d'alerter, dédupliqué, hors des verrous tenus ici.
     *
     * <p>Un dépôt encore ouvert (jamais final — {@link PawapayOperationStatus#isFinal()}, donc ni
     * {@code COMPLETED} ni mort) n'est pas davantage annulé : l'expéditeur peut être en train de
     * saisir son code PIN au moment même où la deadline tombe. On attend ; le poller de
     * réconciliation (tâche 10) mènera ce dépôt à son état final. N'écrit jamais sur
     * {@code pawapay_operations}.
     *
     * <p><b>Paiement introuvable (Ronde 1, point 6)</b> : un bid {@code AWAITING_PAYMENT} en
     * mobile money a normalement toujours un paiement PAWAPAY associé (créé dans la même
     * transaction qu'{@link #acceptBid}) — état structurellement impossible en théorie, mais sur
     * le chemin de l'argent on échoue fermé : on n'annule rien, on journalise, et on rend
     * {@link ExpireOutcome#PAYMENT_MISSING} plutôt que de risquer d'annuler un bid dont on ne
     * sait rien du paiement réel.
     *
     * <p>Restitution de capacité : inverse exact de la réservation faite par {@link #acceptBid}
     * — même verrou pessimiste {@link AnnouncementRepository#findByIdForUpdate}, même condition
     * COMBINÉE {@code !kgFree && weightKg != null} pour l'ajout de poids ET la bascule
     * {@code FULL → ACTIVE} (Ronde 1, point 3 — {@link #acceptBid} ne pose {@code FULL} QUE dans
     * cette même branche : un bid sans poids ne rend donc jamais une annonce complète, et si elle
     * l'est, c'est un autre bid qui l'a remplie. Rebasculer {@code ACTIVE} sans avoir rendu le
     * moindre kilo ferait réapparaître en recherche un trajet réellement encore à zéro kilo
     * disponible — défaut présent dans {@code BidService#restoreCapacityIfNeeded}, hors
     * périmètre de cette tâche, à ne surtout pas reproduire ici).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExpireOutcome expire(UUID bidId) {
        // Ronde 1, point 1 : paiement verrouillé AVANT le bid — voir Javadoc.
        Optional<PaymentEntity> payment = paymentRepository.findByBidIdForUpdate(bidId)
                .filter(p -> p.getRail() == PaymentRail.PAWAPAY);
        BidEntity bid = bidRepository.findByIdForUpdate(bidId).orElse(null);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (bid == null || bid.getStatus() != BidStatus.AWAITING_PAYMENT
                || bid.getPaymentMethod() != PaymentMethod.MOBILE_MONEY
                || bid.getAwaitingPaymentExpiresAt() == null || bid.getAwaitingPaymentExpiresAt().isAfter(now)) {
            return ExpireOutcome.IGNORED;
        }
        if (payment.isEmpty()) {
            // Ronde 1, point 6 : échoue fermé, jamais d'annulation à l'aveugle sur le chemin de
            // l'argent — voir Javadoc. Ronde 2, point 1 : ne lève plus l'alerte ici, la rend.
            log.error("Bid {} AWAITING_PAYMENT/MOBILE_MONEY sans paiement PAWAPAY associé, expiration abandonnée", bidId);
            return ExpireOutcome.PAYMENT_MISSING;
        }
        PaymentEntity p = payment.get();
        Optional<PawapayOperationEntity> deposit = operations.findLatest(p.getId(), PawapayOperationKind.DEPOSIT);
        boolean depositOpen = deposit.map(o -> !o.getStatus().isFinal()).orElse(false);
        if (depositOpen) {
            log.info("Bid {} : délai de paiement dépassé mais un deposit est encore ouvert, on attend", bidId);
            return ExpireOutcome.IGNORED;
        }
        boolean depositCompletedNotApplied = deposit.map(o -> o.getStatus() == PawapayOperationStatus.COMPLETED).orElse(false);
        if (depositCompletedNotApplied) {
            // Ronde 1, point 2 (CRITIQUE) : en vol, pas mort — voir Javadoc. Ronde 2, point 1 :
            // ne lève plus l'alerte ici, la rend.
            log.error("Bid {} : deposit COMPLETED mais paiement {} encore PENDING, expiration abandonnée "
                    + "(confirmEscrow n'est pas encore passé)", bidId, p.getId());
            return ExpireOutcome.DEPOSIT_COMPLETED_NOT_APPLIED;
        }
        if (paymentRepository.markCancelledIfPending(p.getId()) == 0) {
            // confirmEscrow a gagné la course (ou rejeu de cette même méthode) : le paiement
            // n'est plus PENDING. Rien à faire : ni bid, ni annonce, ni audit, ni événement.
            log.info("Bid {} : paiement {} déjà sorti de PENDING, expiration abandonnée", bidId, p.getId());
            return ExpireOutcome.IGNORED;
        }

        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(bid.getAnnouncementId()).orElse(null);
        if (announcement != null) {
            // Ronde 1, point 3 : condition COMBINÉE, jamais deux gardes indépendantes — voir
            // Javadoc. Le save() reste nesté dans ce même bloc, comme dans acceptBid.
            boolean kgFree = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
            if (!kgFree && bid.getWeightKg() != null) {
                announcement.setAvailableKg(announcement.getAvailableKg().add(bid.getWeightKg()));
                if (announcement.getStatus() == AnnouncementStatus.FULL) {
                    announcement.setStatus(AnnouncementStatus.ACTIVE);
                }
                announcementRepository.save(announcement);
            }
        }
        bid.setStatus(BidStatus.CANCELLED);
        bid.setAwaitingPaymentExpiresAt(null);
        bidRepository.save(bid);

        audit.log("BID", bidId, "MM_PAYMENT_EXPIRED", null, Map.of("paymentId", p.getId().toString()));
        events.publishEvent(new MobileMoneyPaymentExpiredEvent(
                bidId, bid.getSenderId(), announcement != null ? announcement.getTravelerId() : null));
        log.info("Bid {} annulé : paiement mobile money non reçu dans le délai", bidId);
        // Ronde 2, point 2 : n'évince plus le cache ici (@CacheEvict retiré) — le scheduler le
        // fait après le commit de cette transaction REQUIRES_NEW, sur CANCELLED uniquement.
        return ExpireOutcome.CANCELLED;
    }

    // ── Statut ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public MobileMoneyPaymentStatusResponse status(UUID bidId, UUID callerId) {
        BidEntity bid = bidRepository.findById(bidId).orElseThrow(() -> notFound("bid-not-found", "Demande introuvable"));
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> notFound("announcement-not-found", "Annonce introuvable"));
        boolean isSender = bid.getSenderId().equals(callerId);
        boolean isTraveler = announcement.getTravelerId().equals(callerId);
        if (!isSender && !isTraveler) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Vous n'avez pas accès à ce paiement");
        }
        Optional<PaymentEntity> payment = paymentRepository.findByBidId(bidId).filter(p -> p.getRail() == PaymentRail.PAWAPAY);
        Optional<PawapayOperationEntity> deposit = payment.flatMap(p -> operations.findLatest(p.getId(), PawapayOperationKind.DEPOSIT));
        return status(bid, announcement, payment, deposit);
    }

    private MobileMoneyPaymentStatusResponse status(BidEntity bid, AnnouncementEntity announcement,
                                                    Optional<PaymentEntity> payment,
                                                    Optional<PawapayOperationEntity> deposit) {
        MobileMoneyPaymentStatusResponse.OperationView view = deposit.map(o -> new MobileMoneyPaymentStatusResponse.OperationView(
                o.getId(), o.getStatus().name(), o.getProvider(), PawapayProviders.label(o.getProvider()), o.getMsisdnMasked(),
                o.getAuthorizationUrl(), o.getFailureCode(), o.getFailureMessage())).orElse(null);
        return new MobileMoneyPaymentStatusResponse(bid.getId(), bid.getStatus().name(),
                payment.map(p -> p.getStatus().name()).orElse(null), bid.getAwaitingPaymentExpiresAt(),
                payment.map(PaymentEntity::getAmount).orElse(null),
                payment.map(PaymentEntity::getCurrency).orElse(announcement != null ? announcement.getCurrency() : null), view);
    }

    private static YadonyBusinessException notFound(String code, String detail) {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, code, "Not Found", detail);
    }

    private static YadonyBusinessException payerUnsupported(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-payer-unsupported",
                "Mobile Money Payer Unsupported", detail);
    }

    /**
     * pawaPay indisponible (panne réseau/5xx) ou réponse inexploitable (numéro hors bornes) :
     * dans les deux cas ce n'est pas la faute de l'expéditeur, donc 502 et non 422 — motif et
     * message repris à l'identique de {@code PawapaySubmissionService#submit} /
     * {@code MobileMoneyAccountService#activate}. Ne journalise jamais le numéro : uniquement
     * l'étape et le bid concerné.
     */
    private static YadonyBusinessException providerUnavailable(UUID bidId, String step, Exception cause) {
        log.error("pawaPay indisponible ({}) lors de l'initiation du deposit pour le bid {} : {}",
                step, bidId, cause.toString());
        return new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "mobile-money-provider-unavailable",
                "Mobile Money Provider Unavailable", "Le service mobile money ne répond pas. Réessayez dans quelques instants.");
    }

    /**
     * Borne à 64 caractères une valeur non authentifiée avant qu'elle ne soit écrite en
     * base (audit) ou publiée dans un événement — même convention que
     * {@code PawapayCallbackController#truncate} : {@code failureCode} vient de pawaPay
     * (callback ou poller), jamais garanti dans une borne raisonnable avant ce point.
     */
    private static String truncate(String value) {
        return value != null && value.length() > 64 ? value.substring(0, 64) : value;
    }
}
