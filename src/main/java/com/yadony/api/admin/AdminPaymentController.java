package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminChargebackResponse;
import com.yadony.api.admin.dto.AdminPaymentDetailResponse;
import com.yadony.api.admin.dto.AdminPaymentInsight;
import com.yadony.api.admin.dto.AdminPaymentListItemResponse;
import com.yadony.api.admin.dto.PayoutReleaseRequest;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.RefundProcessor;
import com.yadony.api.payments.chargeback.ChargebackRepository;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.hold.PayoutHoldStatus;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator;
import com.yadony.api.payments.pawapay.PawapayAmounts;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.TransferCreateParams;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Story 6.5 — Admin-only endpoints for manual escrow operations.
 * All endpoints require ROLE_ADMIN.
 */
@RestController
@RequestMapping("/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPaymentController {

    /** Fins de colis où l'escrow est remboursé à l'expéditeur : jamais versé au voyageur. */
    private static final java.util.Set<BidStatus> SENDER_REFUND_STATUSES = java.util.EnumSet.of(
            BidStatus.REJECTED, BidStatus.EXPIRED, BidStatus.NO_SHOW, BidStatus.PARCEL_REFUSED);

    /** Manual-capture PaymentIntent state where the card is authorized and funds are held. */
    private static final String STATUS_REQUIRES_CAPTURE = "requires_capture";
    /** PaymentIntent state après annulation d'un hold (autorisation levée). */
    private static final String STATUS_CANCELED = "canceled";
    /** Codes d'erreur Stripe signifiant que le renversement a déjà eu lieu — traités comme succès. */
    private static final Set<String> ALREADY_REVERSED_CODES = Set.of("charge_already_refunded");

    private static final Logger log = LoggerFactory.getLogger(AdminPaymentController.class);

    private final PaymentRepository paymentRepository;
    private final AdminAlertRepository adminAlertRepository;
    private final AuditService auditService;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ChargebackRepository chargebackRepository;

    /**
     * Rail mobile money — injecté par CONSTRUCTEUR comme le reste de la classe, jamais par
     * champ : une dépendance manquante doit être une erreur de compilation, pas une NPE qui
     * attend son premier paiement PAWAPAY.
     */
    private final MobileMoneyPayoutInitiator payoutInitiator;
    private final PawapayOperationService pawapayOperations;
    private final PawapaySubmissionService pawapaySubmission;
    private final RefundProcessor refundProcessor;
    private final EntityManager entityManager;

    /**
     * Transaction INDÉPENDANTE réservée à l'audit des gestes mobile money de cette classe —
     * même outil, même motif que {@code MobileMoneyPayoutInitiator#independentAuditTransaction} :
     * si une écriture ambiante postérieure (résolution d'alertes, ou simplement le commit final)
     * échouait, la trace d'un versement ou d'un remboursement déjà accepté par pawaPay doit
     * survivre à ce rollback.
     */
    private final TransactionTemplate independentAuditTransaction;

    /** Gel des versements du beneficiaire (banni ou KYC retire), lu avant tout versement. */
    private final PayoutHoldPolicy holdPolicy;

    /** Recherche, totaux, export et contexte (parties, colis, liens Stripe) des paiements. */
    private final AdminPaymentInsights insights;
    private final AdminPaymentTimeline timeline;

    public AdminPaymentController(PaymentRepository paymentRepository,
                                  AdminAlertRepository adminAlertRepository,
                                  AuditService auditService,
                                  BidRepository bidRepository,
                                  AnnouncementRepository announcementRepository,
                                  UserRepository userRepository,
                                  ApplicationEventPublisher eventPublisher,
                                  ChargebackRepository chargebackRepository,
                                  MobileMoneyPayoutInitiator payoutInitiator,
                                  PawapayOperationService pawapayOperations,
                                  PawapaySubmissionService pawapaySubmission,
                                  RefundProcessor refundProcessor,
                                  EntityManager entityManager,
                                  PlatformTransactionManager transactionManager,
                                  PayoutHoldPolicy holdPolicy,
                                  AdminPaymentInsights insights,
                                  AdminPaymentTimeline timeline) {
        this.holdPolicy = holdPolicy;
        this.insights = insights;
        this.timeline = timeline;
        this.paymentRepository = paymentRepository;
        this.adminAlertRepository = adminAlertRepository;
        this.auditService = auditService;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.chargebackRepository = chargebackRepository;
        this.payoutInitiator = payoutInitiator;
        this.pawapayOperations = pawapayOperations;
        this.pawapaySubmission = pawapaySubmission;
        this.refundProcessor = refundProcessor;
        this.entityManager = entityManager;
        this.independentAuditTransaction = new TransactionTemplate(transactionManager);
        this.independentAuditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    @GetMapping
    public ResponseEntity<Page<AdminPaymentListItemResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Boolean held,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean hideAbandoned,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // method filtre réellement par rail (STRIPE/PAWAPAY) ; currency sépare les devises : un
        // tableau qui mêle des EUR et des XOF n'est lisible qu'à condition de pouvoir n'en garder
        // qu'une. Le même filtre sert aux totaux et à l'export.
        AdminPaymentFilter filter = AdminPaymentFilter.of(status, dateFrom, dateTo, method, currency, held, q,
                hideAbandoned);
        Page<PaymentEntity> raw = insights.search(filter, PageRequest.of(page, size));
        Map<UUID, UUID> beneficiaries = beneficiariesOf(raw.getContent());
        Map<UUID, PayoutHoldStatus> holds = beneficiaries.isEmpty()
                ? Map.of()
                : holdPolicy.statusesOf(List.copyOf(new java.util.LinkedHashSet<>(beneficiaries.values())));
        Map<UUID, AdminPaymentInsight> context = insights.insightsOf(raw.getContent());
        return ResponseEntity.ok(raw.map(p -> {
            UUID travelerId = p.getId() == null ? null : beneficiaries.get(p.getId());
            PayoutHoldStatus hold = (travelerId == null || holds == null) ? null : holds.get(travelerId);
            return AdminPaymentListItemResponse.from(p, travelerId, holdOrNone(hold))
                    .withInsight(p.getId() == null ? null : context.get(p.getId()));
        }));
    }

    /** Totaux par devise du périmètre filtré (mêmes filtres que la liste). */
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    @GetMapping("/summary")
    public ResponseEntity<List<AdminPaymentInsights.CurrencyTotals>> summary(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Boolean held,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean hideAbandoned) {
        return ResponseEntity.ok(insights.totals(
                AdminPaymentFilter.of(status, dateFrom, dateTo, method, currency, held, q, hideAbandoned)));
    }

    /**
     * Export CSV de la liste filtrée (au plus {@link AdminPaymentInsights#EXPORT_MAX_ROWS} lignes),
     * audité comme les autres exports ({@code EXPORT_RUN}).
     */
    @PreAuthorize("hasAuthority('PAYMENT_VIEW') and hasAuthority('EXPORT_RUN')")
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Boolean held,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean hideAbandoned,
            Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        AdminPaymentFilter filter = AdminPaymentFilter.of(status, dateFrom, dateTo, method, currency, held, q,
                hideAbandoned);
        List<PaymentEntity> rows = insights.exportRows(filter);
        byte[] csv = AdminPaymentCsv.write(rows, insights.insightsOf(rows));
        auditService.log("EXPORT", null, "EXPORT_RUN", adminId, Map.of(
                "type", "payments",
                "rows", rows.size(),
                "filters", filter.toString()));
        return ResponseEntity.ok()
                .contentType(new org.springframework.http.MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename("paiements_" + java.time.LocalDate.now(java.time.ZoneOffset.UTC) + ".csv")
                                .build().toString())
                .body(csv);
    }

    /** Chronologie du paiement : dates du paiement et journal d'audit, auteurs résolus. */
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    @GetMapping("/{id}/timeline")
    public ResponseEntity<List<AdminPaymentTimeline.Entry>> timeline(@PathVariable UUID id) {
        PaymentEntity p = paymentRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "payment-not-found", "Not Found", "Paiement introuvable"));
        return ResponseEntity.ok(timeline.of(p));
    }

    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    @GetMapping("/{id}")
    public ResponseEntity<AdminPaymentDetailResponse> getById(@PathVariable UUID id) {
        PaymentEntity p = paymentRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "payment-not-found", "Not Found", "Paiement introuvable"));
        return ResponseEntity.ok(detail(p));
    }

    /**
     * POST /admin/payments/{id}/force-release
     * Manually releases the Stripe escrow to the traveler. Only allowed when status is ESCROW.
     *
     * <p>Handles both keying schemes:
     * <ul>
     *   <li><b>Classic bid</b> payment ({@code bid_id} set), and</li>
     *   <li><b>Negotiation / dedicated-trip</b> payment (keyed on {@code negotiation_thread_id},
     *       {@code bid_id} null — the bid is resolved via its linked thread).</li>
     * </ul>
     *
     * <p>Release mechanics mirror {@code DeliveryEventListener}:
     * <ul>
     *   <li><b>legacy destination charge</b> → capture the PaymentIntent (Stripe routes funds to
     *       the traveler via {@code transfer_data} set at creation);</li>
     *   <li><b>separate charges &amp; transfers</b> → capture the held PI if still
     *       {@code requires_capture}, then {@code Transfer} the net (amount − commission) to the
     *       traveler's Connect account. Capturing alone would only move the money to the platform
     *       balance — it would NOT pay the traveler.</li>
     * </ul>
     * Marks any related ESCROW_J48_TIMEOUT alert as resolved.
     */
    @PreAuthorize("hasAuthority('PAYMENT_RELEASE')")
    @PostMapping("/{id}/force-release")
    @Transactional
    public ResponseEntity<AdminPaymentDetailResponse> forceRelease(@PathVariable UUID id,
            @RequestBody(required = false) PayoutReleaseRequest request) {
        PaymentEntity payment = paymentRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "payment-not-found", "Not Found",
                        "Paiement introuvable"));

        // Resolve the bid (classic or negotiation-materialised) → announcement → traveler.
        // Negotiation payments carry a null bid_id, so the bid is found via its linked thread id.
        BidEntity bid = resolveBid(payment);

        // Safety guard: a CANCELLED trip's escrow must be REFUNDED to the sender (cancellation
        // flow), never transferred to the traveler. Refuse the force-release before any status
        // flip or Stripe transfer.
        if (bid != null && bid.getStatus() == BidStatus.CANCELLED) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "bid-cancelled",
                    "Bid Cancelled",
                    "Le colis est annulé — l'escrow doit être remboursé à l'expéditeur, pas transféré au voyageur");
        }
        // Même règle pour les autres fins de colis où l'argent revient à l'expéditeur : refusé
        // par le voyageur, expiré, voyageur absent (NoShowService), colis refusé à la remise.
        if (bid != null && SENDER_REFUND_STATUSES.contains(bid.getStatus())) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "bid-not-releasable",
                    "Bid Not Releasable",
                    "Le colis est " + bid.getStatus() + " — l'escrow revient à l'expéditeur, pas au voyageur");
        }
        // Remboursement partiel déjà passé : le net ci-dessous part du montant TOTAL et
        // verserait au voyageur la part rendue à l'expéditeur. Montant à trancher à la main.
        if (payment.getRefundedAmount() != null && payment.getRefundedAmount().signum() > 0) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "payment-partially-refunded",
                    "Payment Partially Refunded",
                    "Ce paiement a déjà été partiellement remboursé (" + payment.getRefundedAmount().toPlainString()
                            + " " + payment.getCurrency() + ") — la libération forcée verserait le montant total");
        }

        AnnouncementEntity announcement = (bid != null)
                ? announcementRepository.findById(bid.getAnnouncementId()).orElse(null)
                : null;
        UUID travelerId = (announcement != null) ? announcement.getTravelerId() : null;
        UserEntity traveler = (travelerId != null)
                ? userRepository.findById(travelerId).orElse(null)
                : null;
        UUID bidId = (bid != null) ? bid.getId() : payment.getBidId();

        // Lecture non atomique, seulement pour l'ordre des erreurs : un paiement déjà sorti
        // d'ESCROW répond 422 comme avant, pas 409 « gelé ». Le claim ci-dessous reste la garde.
        if (payment.getStatus() != PaymentStatus.ESCROW) {
            throw notInEscrow("Seuls les paiements en statut ESCROW peuvent faire l'objet d'une libération forcée");
        }

        // Bénéficiaire gelé ou paiement en litige : 409 sauf dérogation motivée, AVANT le claim.
        boolean holdOverridden = guardPayout(payment, travelerId, request, "admin-force-release");

        // Compte Connect désactivé ou refusé : Stripe refuserait le Transfer après le claim.
        if (payment.getRail() != PaymentRail.PAWAPAY && !payment.isLegacyDestinationCharge()) {
            requireUsableStripeAccount(payment, traveler, travelerId);
        }

        // Atomic ESCROW → RELEASED transition — prevents a double release/transfer race.
        int updated = paymentRepository.markReleasedIfEscrow(id, LocalDateTime.now(ZoneOffset.UTC));
        if (updated == 0) {
            throw notInEscrow("Seuls les paiements en statut ESCROW peuvent faire l'objet d'une libération forcée");
        }

        // Rail mobile money : bifurque juste après le claim, avant tout appel Stripe. Réutilise
        // EXACTEMENT le chemin de la livraison (DeliveryEventListener#releaseMobileMoney) :
        // MobileMoneyPayoutInitiator#release porte toute la logique (compte de versement, payout
        // déjà vivant repris sans jamais en resoumettre un second, soumission pawaPay), rien
        // n'est réimplémenté ici.
        if (payment.getRail() == PaymentRail.PAWAPAY) {
            if (travelerId == null) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "traveler-not-found",
                        "Invalid Traveler", "Voyageur introuvable pour ce paiement");
            }
            // Résout les alertes ESCROW_J48_TIMEOUT AVANT tout appel pawaPay — si cette écriture
            // ambiante échouait APRÈS payoutInitiator.release, le rollback qu'elle provoquerait
            // annulerait le claim pendant que pawaPay a déjà réellement versé.
            resolveRelatedAlerts(id);
            BigDecimal net = PawapayAmounts.round(
                    payment.getAmount().subtract(payment.getCommissionAmount()), payment.getCurrency());
            try {
                if (holdOverridden) {
                    payoutInitiator.release(payment, bidId, travelerId, net, "admin-force-release", true);
                } else {
                    payoutInitiator.release(payment, bidId, travelerId, net, "admin-force-release");
                }
            } catch (IllegalStateException e) {
                // @Transactional : l'exception annule le claim, le paiement reste ESCROW.
                throw payoutFailed("Versement mobile money impossible : " + e.getMessage());
            }
            // Le claim ci-dessus a posé status/escrowReleasedAt par une écriture ciblée, jamais
            // vue par l'entité `payment` chargée en amont. Plutôt que de reposer ces colonnes une
            // à une avec des setters — liste qui s'est révélée incomplète une première fois —
            // entityManager.refresh(payment) relit la ligne réelle DANS la même transaction et
            // rend à l'entité un snapshot propre : aucun flush ultérieur ne peut régénérer un
            // UPDATE qui écraserait quoi que ce soit, quelle que soit la colonne, présente ou
            // future (voir PaymentRepositoryMobileMoneyTest).
            entityManager.refresh(payment);
            auditMobileMoneyAction(payment.getId(), "ESCROW_FORCE_RELEASED",
                    Map.of("paymentId", id.toString(), "bidId", String.valueOf(bidId), "rail", "PAWAPAY",
                            "amount", payment.getAmount().toPlainString()));
            log.info("Admin force-released mobile money escrow for payment {} (bid={})", id, bidId);
            return ResponseEntity.ok(detail(payment));
        }

        try {
            if (payment.isLegacyDestinationCharge()) {
                // Destination-charge model: capturing routes funds to the traveler via transfer_data.
                PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
                if (STATUS_REQUIRES_CAPTURE.equals(pi.getStatus())) {
                    pi.capture();
                }
            } else {
                // Separate charges & transfers: the traveler must have a Connect account to receive
                // the payout. Fail (and roll back the RELEASED flip) rather than trap captured funds.
                if (traveler == null || traveler.getStripeAccountId() == null
                        || traveler.getStripeAccountId().isBlank()) {
                    throw new YadonyBusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "traveler-no-connect",
                            "Invalid Traveler",
                            "Voyageur introuvable ou sans compte Stripe Connect — transfert impossible");
                }

                PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
                // Ensure the funds are on the platform balance before transferring.
                if (STATUS_REQUIRES_CAPTURE.equals(pi.getStatus())) {
                    pi.capture();
                }
                String chargeId = (payment.getStripeChargeId() != null)
                        ? payment.getStripeChargeId()
                        : pi.getLatestCharge();

                // Devise du paiement, jamais « eur » en dur : un séquestre carte existe aussi en
                // USD, CAD, GBP ou CHF, et un Transfer libellé dans une autre devise que la charge
                // est refusé par Stripe ou, pire, converti au taux du jour.
                BigDecimal net = payment.getAmount().subtract(payment.getCommissionAmount());
                CurrencyAmount localNet = CurrencyAmount.of(net,
                        SupportedCurrency.fromCodeOrDefault(payment.getCurrency()));

                TransferCreateParams.Builder builder = TransferCreateParams.builder()
                        .setAmount(localNet.minor())
                        .setCurrency(localNet.currency().code())
                        .setDestination(traveler.getStripeAccountId())
                        .putMetadata("bid_id", bidId != null ? bidId.toString() : "")
                        .putMetadata("payment_id", id.toString())
                        .putMetadata("source", "admin-force-release");
                if (chargeId != null && !chargeId.isBlank()) {
                    builder.setSourceTransaction(chargeId);
                }
                // Même clé que la livraison (DeliveryEventListener#releaseV2) : si la livraison a
                // déjà émis ce Transfer (réussi chez Stripe, transaction locale annulée ensuite),
                // Stripe renvoie le Transfer existant ou refuse la clé — jamais un second Transfer
                // pendant la durée de vie de la clé.
                Transfer.create(builder.build(),
                        RequestOptions.builder().setIdempotencyKey("transfer-" + id).build());
            }
        } catch (IdempotencyException e) {
            log.error("Admin force-release: idempotency key transfer-{} already used with other parameters: {}",
                    id, e.getMessage());
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "transfer-already-attempted",
                    "Transfer Already Attempted",
                    "Un Transfer a déjà été tenté pour ce paiement avec d'autres paramètres : vérifier dans "
                            + "Stripe avant toute nouvelle tentative");
        } catch (StripeException e) {
            log.error("Admin force-release: Stripe op failed for payment {} (PI={}): {}",
                    id, payment.getStripePaymentIntentId(), e.getMessage(), e);
            throw new YadonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "stripe-release-failed",
                    "Stripe Error",
                    "Impossible de libérer le paiement Stripe. Veuillez réessayer.");
        }

        // Reflect the committed DB transition on the managed entity (the @Modifying CAS above
        // does not refresh it) so the response and any downstream flush are consistent.
        payment.setStatus(PaymentStatus.RELEASED);
        payment.setEscrowReleasedAt(LocalDateTime.now(ZoneOffset.UTC));

        // Resolve any open ESCROW_J48_TIMEOUT alerts for this payment
        resolveRelatedAlerts(id);

        auditService.log(
                "PAYMENT",
                payment.getId(),
                "ESCROW_FORCE_RELEASED",
                bidId,
                Map.of(
                        "paymentId", id.toString(),
                        "bidId", String.valueOf(bidId),
                        "piId", payment.getStripePaymentIntentId(),
                        "amount", payment.getAmount().toPlainString()
                )
        );

        // Notify the traveler of the payout (parity with DeliveryEventListener).
        if (bidId != null && travelerId != null) {
            eventPublisher.publishEvent(PaymentReleasedEvent.card(
                    bidId, travelerId, bid.getSenderId(), payment.getAmount(),
                    payment.getCommissionAmount(), payment.getCurrency()));
        }

        log.info("Admin force-released escrow for payment {} (bid={}, PI={})",
                id, bidId, payment.getStripePaymentIntentId());

        return ResponseEntity.ok(detail(payment));
    }

    /**
     * POST /admin/payments/{id}/refund
     * Rend les fonds à l'expéditeur pour un paiement en escrow — contrepartie du
     * force-release. Utilisé quand un trajet payé est annulé / colis refusé / litige
     * résolu pour l'expéditeur et que le remboursement automatique n'a pas tourné.
     * Uniquement en statut ESCROW.
     *
     * <p>L'escrow est un PaymentIntent {@code capture_method: manual} : tant que les fonds
     * ne sont pas capturés (statut {@code requires_capture}), il faut ANNULER le
     * PaymentIntent pour lever l'autorisation — {@code Refund.create} est réservé aux
     * charges déjà capturées et échoue sur un hold. On distingue donc les deux cas.
     */
    @PreAuthorize("hasAuthority('PAYMENT_REFUND')")
    @PostMapping("/{id}/refund")
    @Transactional
    public ResponseEntity<AdminPaymentDetailResponse> refund(@PathVariable UUID id) {
        PaymentEntity payment = paymentRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "payment-not-found", "Not Found",
                        "Paiement introuvable"));

        // Rail mobile money — délègue à RefundProcessor#processRefund AVANT tout claim ambiant
        // (pas de markRefundedIfEscrow ici pour ce rail). processRefund est
        // @Transactional(REQUIRES_NEW) et refait SON PROPRE claim markRefundedIfEscrow sur la
        // même ligne : le brancher APRÈS un premier claim posé ici provoquerait un
        // auto-interblocage — la transaction ambiante détiendrait déjà, non commité, le verrou
        // de ligne posé par SON markRefundedIfEscrow (une transaction REQUIRES_NEW ne fait que
        // SUSPENDRE l'ambiante, jamais la commiter), et la transaction REQUIRES_NEW de
        // processRefund resterait bloquée en tentant de verrouiller la même ligne — pendant que
        // la transaction ambiante attend précisément le retour de processRefund pour continuer.
        if (payment.getRail() == PaymentRail.PAWAPAY) {
            if (payment.getStatus() != PaymentStatus.ESCROW) {
                throw notInEscrow(REFUND_REQUIRES_ESCROW);
            }
            boolean acted;
            try {
                acted = refundProcessor.processRefund(id, "ESCROW_FORCE_REFUNDED", currentAdminId(),
                        Map.of("bidId", String.valueOf(payment.getBidId())));
            } catch (IllegalStateException e) {
                throw refundFailed("Remboursement mobile money impossible : " + e.getMessage());
            }
            if (!acted) {
                // Race perdue entre notre lecture ci-dessus et le claim interne de processRefund
                // (ex. un autre remboursement concurrent) — même sémantique 422 que le rail carte.
                throw notInEscrow(REFUND_REQUIRES_ESCROW);
            }
            // processRefund a déjà commité (transaction REQUIRES_NEW indépendante) : `payment`,
            // chargé plus haut dans CETTE transaction, ignore encore ce changement — refresh
            // relit la ligne réelle (voir le commentaire équivalent de forceRelease ci-dessus).
            entityManager.refresh(payment);
            resolveRelatedAlerts(id);
            log.info("Admin refunded mobile money escrow for payment {}", id);
            return ResponseEntity.ok(detail(payment));
        }

        // Atomic ESCROW → REFUNDED transition — prevents a double refund race.
        int updated = paymentRepository.markRefundedIfEscrow(id);
        if (updated == 0) {
            throw notInEscrow(REFUND_REQUIRES_ESCROW);
        }

        try {
            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            String piStatus = pi.getStatus();
            if (STATUS_CANCELED.equals(piStatus)) {
                // Déjà annulé côté Stripe (hold levé) → rien à faire, on synchronise juste la DB.
                log.info("Admin refund: PI {} déjà annulé — synchronisation DB uniquement", pi.getId());
            } else if (STATUS_REQUIRES_CAPTURE.equals(piStatus)) {
                // Fonds encore bloqués (non capturés) → annuler le PaymentIntent lève le hold.
                pi.cancel();
            } else {
                // Fonds déjà capturés → remboursement classique.
                Refund.create(RefundCreateParams.builder()
                        .setPaymentIntent(payment.getStripePaymentIntentId())
                        .build());
            }
        } catch (StripeException e) {
            // Idempotence : si Stripe indique que le renversement a déjà eu lieu (charge déjà
            // remboursée / PI déjà annulé), l'argent est déjà revenu à l'expéditeur — on considère
            // l'opération réussie et on met simplement la DB à jour plutôt que d'échouer.
            if (isAlreadyReversed(e)) {
                log.info("Admin refund: renversement déjà effectué côté Stripe pour {} (PI={}, code={}) — DB synchronisée",
                        id, payment.getStripePaymentIntentId(), e.getCode());
            } else {
                log.error("Admin refund: Stripe refund failed for payment {} (PI={}): {}",
                        id, payment.getStripePaymentIntentId(), e.getMessage(), e);
                throw new YadonyBusinessException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "stripe-refund-failed",
                        "Stripe Error",
                        "Impossible de rembourser le paiement Stripe. Veuillez réessayer.");
            }
        }

        // Reflect the committed DB transition on the managed entity for the response.
        payment.setStatus(PaymentStatus.REFUNDED);

        resolveRelatedAlerts(id);

        auditService.log(
                "PAYMENT",
                payment.getId(),
                "ESCROW_FORCE_REFUNDED",
                payment.getBidId(),
                Map.of(
                        "paymentId", id.toString(),
                        "bidId", String.valueOf(payment.getBidId()),
                        "piId", payment.getStripePaymentIntentId(),
                        "amount", payment.getAmount().toPlainString()
                )
        );

        log.info("Admin refunded escrow for payment {} (PI={})", id, payment.getStripePaymentIntentId());

        return ResponseEntity.ok(detail(payment));
    }

    /**
     * POST /admin/payments/{id}/mobile-money/retry-payout
     * Relance un versement mobile money dont la DERNIÈRE tentative connue est morte (FAILED ou
     * SUBMIT_REJECTED). Passe par {@link MobileMoneyPayoutInitiator#release}, exactement le
     * chemin de la livraison et du force-release ci-dessus : sa déduplication de l'alerte
     * orpheline ({@code MM_PAYOUT_ORPHAN_<paymentId>}) protège donc aussi cette relance sans
     * code séparé — c'est précisément son cas nominal.
     *
     * <p>Le paiement doit déjà être {@code RELEASED} : aucun nouveau claim n'a lieu ici, celui-ci
     * a eu lieu à la livraison ou à un force-release antérieur. Refuse (422
     * {@code mobile-money-retry-not-allowed}) si la dernière opération PAYOUT connue n'est ni
     * FAILED ni SUBMIT_REJECTED — vivante ou déjà COMPLETED, la relancer serait un second
     * versement déclenché par un administrateur.
     */
    @PreAuthorize("hasAuthority('PAYMENT_RELEASE')")
    @PostMapping("/{id}/mobile-money/retry-payout")
    @Transactional
    public ResponseEntity<AdminPaymentDetailResponse> retryMobileMoneyPayout(@PathVariable UUID id,
            @RequestBody(required = false) PayoutReleaseRequest request) {
        PaymentEntity payment = requirePawapayPayment(id);
        if (payment.getStatus() != PaymentStatus.RELEASED) {
            throw retryNotAllowed("Le paiement doit être RELEASED pour relancer le versement");
        }
        PawapayOperationEntity deadPayout = requireDeadLastOperation(id, PawapayOperationKind.PAYOUT,
                "Un versement est encore en cours, déjà abouti, ou introuvable");
        BidEntity bid = resolveBid(payment);
        AnnouncementEntity announcement = bid != null
                ? announcementRepository.findById(bid.getAnnouncementId()).orElse(null)
                : null;
        if (bid == null || announcement == null) {
            throw retryNotAllowed("Colis ou trajet introuvable");
        }
        // ARGENT : reprend le montant de la tentative MORTE plutôt que de le recalculer. Le
        // chemin de livraison majore le net d'une part de commission quand le voyageur détient
        // un bon de parrainage actif (DeliveryEventListener#travelerVoucherTopUp) — et ce bon
        // est consommé DÉFINITIVEMENT dès cette première tentative, qu'elle aboutisse ou non
        // chez pawaPay : il ne resservira jamais. Recalculer ici (amount − commission, sans le
        // bon) sous-paierait donc le voyageur exactement de la part que le bon lui garantissait
        // — silencieusement, le montant recalculé n'étant ni nul ni négatif, juste inférieur.
        // Le montant de l'opération morte est PAR DÉFINITION celui à réémettre — déjà arrondi à
        // sa création, jamais recalculé ici.
        BigDecimal net = deadPayout.getAmount();
        boolean holdOverridden = guardPayout(payment, announcement.getTravelerId(), request, "admin-retry");
        PawapayOperationEntity op;
        try {
            op = holdOverridden
                    ? payoutInitiator.release(payment, bid.getId(), announcement.getTravelerId(), net, "admin-retry", true)
                    : payoutInitiator.release(payment, bid.getId(), announcement.getTravelerId(), net, "admin-retry");
        } catch (IllegalStateException e) {
            throw payoutFailed("Versement mobile money impossible : " + e.getMessage());
        }
        auditMobileMoneyAction(id, "MM_PAYOUT_RETRIED",
                Map.of("operationId", op.getId().toString(), "bidId", bid.getId().toString()));
        return ResponseEntity.ok(detail(payment));
    }

    /**
     * POST /admin/payments/{id}/mobile-money/retry-refund
     * Relance un remboursement mobile money dont la dernière tentative connue est morte — même
     * filet que la relance de versement, mais sur {@code pawapay_operations} de type REFUND.
     * Ne mute jamais {@code payments.status} (déjà REFUNDED ou CANCELLED) : seul le refund
     * pawaPay est rejoué.
     *
     * <p>Ouvert aussi à {@code CANCELLED} : un paiement mobile money {@code PENDING} remboursé
     * devient {@code CANCELLED} — jamais {@code REFUNDED} comme sur le rail Stripe (voir
     * {@code RefundProcessor#refundMobileMoney}, cas {@code PENDING}). Un deposit arrivé tard
     * sur un tel paiement ({@code MobileMoneyBidPaymentService#confirmEscrow} →
     * {@code refundAfterCancel}) peut y soumettre un refund qui échoue à son tour : l'alerte
     * {@code PAWAPAY_REFUND_*} demande alors une reprise humaine que seul cet endpoint permet.
     */
    @PreAuthorize("hasAuthority('PAYMENT_RELEASE')")
    @PostMapping("/{id}/mobile-money/retry-refund")
    @Transactional
    public ResponseEntity<AdminPaymentDetailResponse> retryMobileMoneyRefund(@PathVariable UUID id) {
        PaymentEntity payment = requirePawapayPayment(id);
        if (payment.getStatus() != PaymentStatus.REFUNDED && payment.getStatus() != PaymentStatus.CANCELLED) {
            throw retryNotAllowed("Le paiement doit être REFUNDED ou CANCELLED pour relancer le remboursement");
        }
        requireDeadLastOperation(id, PawapayOperationKind.REFUND,
                "Un remboursement est encore en cours, déjà abouti, ou introuvable");
        PawapayOperationEntity deposit = pawapayOperations.findLatest(id, PawapayOperationKind.DEPOSIT)
                .filter(d -> d.getStatus() == PawapayOperationStatus.COMPLETED)
                .orElseThrow(() -> retryNotAllowed("Aucun deposit abouti à rembourser"));
        // Montant du DEPOSIT d'origine, jamais payment.getAmount() — même alignement que
        // RefundProcessor#refundEscrowedMobileMoney (les deux se valent aujourd'hui, sans
        // garantie contractuelle demain).
        PawapayOperationEntity refund = pawapaySubmission.submitRefund(id, deposit, deposit.getAmount());
        if (refund.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            throw refundFailed("Remboursement refusé : " + refund.getFailureCode());
        }
        auditMobileMoneyAction(id, "MM_REFUND_RETRIED", Map.of("operationId", refund.getId().toString()));
        return ResponseEntity.ok(detail(payment));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final String REFUND_REQUIRES_ESCROW = "Seuls les paiements en statut ESCROW peuvent être remboursés";

    /**
     * Détail d'un paiement. Pour le rail PAWAPAY, les identifiants d'opération sont lus dans
     * {@code pawapay_operations} (dernière opération de chaque type) — le seul lien qui fait
     * autorité, jamais une colonne de {@code payments}.
     */
    private AdminPaymentDetailResponse detail(PaymentEntity payment) {
        UUID travelerId = beneficiaryOf(payment);
        PayoutHoldStatus hold = holdOf(travelerId);
        AdminPaymentInsight insight = payment.getId() == null ? null : insights.insightOf(payment);
        if (payment.getRail() != PaymentRail.PAWAPAY) {
            return AdminPaymentDetailResponse.from(payment, null, null, null, travelerId, hold).withInsight(insight);
        }
        return AdminPaymentDetailResponse.from(payment,
                latestOperationId(payment.getId(), PawapayOperationKind.DEPOSIT),
                latestOperationId(payment.getId(), PawapayOperationKind.PAYOUT),
                latestOperationId(payment.getId(), PawapayOperationKind.REFUND),
                travelerId, hold).withInsight(insight);
    }

    // ── Gel du bénéficiaire, litige, dérogation ────────────────────────────────

    /**
     * Refuse (409) un versement à un bénéficiaire gelé ou sur un paiement en litige, sauf
     * dérogation explicite et motivée de l'administrateur, auditée dans une transaction
     * indépendante (la trace survit à un échec ultérieur du versement).
     *
     * @return {@code true} si la dérogation a été utilisée
     */
    private boolean guardPayout(PaymentEntity payment, UUID travelerId, PayoutReleaseRequest request, String source) {
        PayoutHoldStatus hold = holdOf(travelerId);
        List<String> blockers = new java.util.ArrayList<>();
        if (payment.isDisputed()) {
            blockers.add("DISPUTED");
        }
        if (hold.held()) {
            blockers.add("BENEFICIARY_HELD");
        }
        boolean override = request != null && request.override();
        if (override) {
            String reason = request.trimmedReason();
            if (reason == null || reason.length() < PayoutReleaseRequest.REASON_MIN
                    || reason.length() > PayoutReleaseRequest.REASON_MAX) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "override-reason-invalid",
                        "Invalid Override Reason",
                        "Le motif de dérogation est obligatoire : entre " + PayoutReleaseRequest.REASON_MIN
                                + " et " + PayoutReleaseRequest.REASON_MAX + " caractères");
            }
        }
        if (blockers.isEmpty()) {
            return false;
        }
        List<String> holdReasons = hold.reasons().stream().map(Enum::name).toList();
        if (!override) {
            boolean disputed = payment.isDisputed();
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    disputed ? "payment-disputed" : "payout-beneficiary-held",
                    disputed ? "Payment Disputed" : "Payout Beneficiary Held",
                    disputed
                            ? "Paiement en litige (chargeback) : versement refusé sans dérogation motivée"
                            : "Les versements de ce voyageur sont gelés (" + String.join(", ", holdReasons)
                                    + ") : versement refusé sans dérogation motivée",
                    Map.of("blockers", List.copyOf(blockers), "holdReasons", holdReasons,
                            "travelerId", String.valueOf(travelerId)));
        }
        UUID adminId = currentAdminId();
        Map<String, Object> payload = Map.of(
                "paymentId", payment.getId().toString(),
                "travelerId", String.valueOf(travelerId),
                "blockers", String.join(",", blockers),
                "holdReasons", String.join(",", holdReasons),
                "overrideReason", request.trimmedReason(),
                "source", source);
        independentAuditTransaction.executeWithoutResult(status ->
                auditService.log("PAYMENT", payment.getId(), "PAYOUT_HOLD_OVERRIDDEN_BY_ADMIN", adminId, payload));
        log.warn("Admin {} overrode payout blockers {} on payment {} ({})", adminId, blockers, payment.getId(), source);
        return true;
    }

    /**
     * Un compte Connect {@code DISABLED} ou {@code REJECTED} ne recevra jamais le Transfer :
     * 409 {@code stripe-account-unusable}, audité, AVANT le claim (le paiement reste ESCROW).
     * Aucune dérogation : Stripe refuserait de toute façon.
     */
    private void requireUsableStripeAccount(PaymentEntity payment, UserEntity traveler, UUID travelerId) {
        StripeAccountStatus status = traveler != null ? traveler.getStripeAccountStatus() : null;
        if (status != StripeAccountStatus.DISABLED && status != StripeAccountStatus.REJECTED) {
            return;
        }
        UUID adminId = currentAdminId();
        independentAuditTransaction.executeWithoutResult(tx -> auditService.log("PAYMENT", payment.getId(),
                "PAYOUT_BLOCKED_STRIPE_ACCOUNT_UNUSABLE", adminId, Map.of(
                        "paymentId", payment.getId().toString(),
                        "travelerId", String.valueOf(travelerId),
                        "stripeAccountStatus", status.name(),
                        "source", "admin-force-release")));
        throw new YadonyBusinessException(HttpStatus.CONFLICT, "stripe-account-unusable",
                "Stripe Account Unusable",
                "Le compte Stripe du voyageur est " + status + " : aucun Transfer possible, le paiement reste en séquestre",
                Map.of("stripeAccountStatus", status.name()));
    }

    private PayoutHoldStatus holdOf(UUID travelerId) {
        return travelerId == null ? PayoutHoldStatus.NONE : holdOrNone(holdPolicy.statusOf(travelerId));
    }

    private static PayoutHoldStatus holdOrNone(PayoutHoldStatus status) {
        return status != null ? status : PayoutHoldStatus.NONE;
    }

    /** Voyageur bénéficiaire d'un paiement, résolu comme au force-release ; {@code null} si inconnu. */
    private UUID beneficiaryOf(PaymentEntity payment) {
        BidEntity bid = resolveBid(payment);
        if (bid == null) {
            return null;
        }
        return announcementRepository.findById(bid.getAnnouncementId())
                .map(AnnouncementEntity::getTravelerId)
                .orElse(null);
    }

    /** Bénéficiaires d'une page de paiements, en une requête : {@code paymentId → travelerId}. */
    private Map<UUID, UUID> beneficiariesOf(List<PaymentEntity> payments) {
        List<UUID> ids = payments.stream().map(PaymentEntity::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> result = new java.util.HashMap<>();
        List<Object[]> rows = paymentRepository.findBeneficiaries(ids);
        if (rows != null) {
            for (Object[] row : rows) {
                if (row != null && row.length >= 2 && row[0] != null && row[1] != null) {
                    result.put(toUuid(row[0]), toUuid(row[1]));
                }
            }
        }
        return result;
    }

    private static UUID toUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private UUID latestOperationId(UUID paymentId, PawapayOperationKind kind) {
        return pawapayOperations.findLatest(paymentId, kind).map(PawapayOperationEntity::getId).orElse(null);
    }

    /** Charge le paiement et vérifie qu'il s'agit bien d'un paiement mobile money (rail PAWAPAY). */
    private PaymentEntity requirePawapayPayment(UUID id) {
        PaymentEntity payment = paymentRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "payment-not-found", "Not Found", "Paiement introuvable"));
        if (payment.getRail() != PaymentRail.PAWAPAY) {
            throw retryNotAllowed("Ce paiement n'est pas un paiement mobile money");
        }
        return payment;
    }

    /**
     * La dernière opération {@code kind} du paiement doit exister ET être morte (FAILED ou
     * SUBMIT_REJECTED) : vivante ou déjà COMPLETED, la relancer serait un second mouvement
     * d'argent déclenché par un administrateur.
     */
    private PawapayOperationEntity requireDeadLastOperation(UUID paymentId, PawapayOperationKind kind, String detail) {
        Optional<PawapayOperationEntity> last = pawapayOperations.findLatest(paymentId, kind);
        if (last.isEmpty() || !PawapayOperationStatus.DEAD.contains(last.get().getStatus())) {
            throw retryNotAllowed(detail);
        }
        return last.get();
    }

    /**
     * Audit d'un geste mobile money dans une transaction indépendante, déjà commitée quand la
     * méthode appelante retourne. Acteur = l'administrateur qui agit, jamais bidId ni null :
     * {@code audit_log} est immuable.
     */
    private void auditMobileMoneyAction(UUID paymentId, String action, Map<String, Object> payload) {
        UUID adminId = currentAdminId();
        independentAuditTransaction.executeWithoutResult(status ->
                auditService.log("PAYMENT", paymentId, action, adminId, payload));
    }

    private static YadonyBusinessException notInEscrow(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "payment-not-in-escrow",
                "Invalid Status", detail);
    }

    private static YadonyBusinessException payoutFailed(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-payout-failed",
                "Mobile Money Payout Failed", detail);
    }

    private static YadonyBusinessException refundFailed(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-refund-failed",
                "Mobile Money Refund Failed", detail);
    }

    private static YadonyBusinessException retryNotAllowed(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-retry-not-allowed",
                "Retry Not Allowed", detail);
    }

    /**
     * Identifiant de l'administrateur qui agit, pour la trace d'audit — même garde que
     * {@code AdminUserController#adminId}, mais lu depuis le {@code SecurityContext} plutôt que
     * reçu en paramètre {@code Authentication} : {@code forceRelease}/{@code refund} sont appelés
     * directement, hors Spring Security, par les tests unitaires de {@code AdminPaymentControllerTest}
     * (tous sur des paiements de rail STRIPE) — leur ajouter un paramètre casserait leur
     * compilation pour une branche PAWAPAY qu'ils n'atteignent jamais. {@code audit_log} est
     * immuable : désigner la CIBLE (bidId) ou rien comme acteur rendrait l'administrateur
     * responsable d'un versement ou d'un remboursement introuvable pour toujours.
     */
    private UUID currentAdminId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }

    /**
     * Resolves the bid behind a payment. Classic payments key on {@code bid_id};
     * negotiation/dedicated-trip payments key on {@code negotiation_thread_id} and the bid
     * is the one materialised from that thread.
     */
    private BidEntity resolveBid(PaymentEntity payment) {
        if (payment.getBidId() != null) {
            return bidRepository.findById(payment.getBidId()).orElse(null);
        }
        if (payment.getNegotiationThreadId() != null) {
            return bidRepository.findByLinkedNegotiationThreadId(payment.getNegotiationThreadId())
                    .orElse(null);
        }
        return null;
    }

    /** Vrai si l'erreur Stripe signifie que le paiement a déjà été remboursé/annulé (idempotence). */
    private static boolean isAlreadyReversed(StripeException e) {
        return e.getCode() != null && ALREADY_REVERSED_CODES.contains(e.getCode());
    }

    private void resolveRelatedAlerts(UUID paymentId) {
        String paymentIdStr = paymentId.toString();
        List<AdminAlertEntity> alerts =
                adminAlertRepository.findByTypeAndResolved("ESCROW_J48_TIMEOUT", false);

        alerts.stream()
                .filter(a -> a.getPayload() != null && a.getPayload().contains(paymentIdStr))
                .forEach(a -> {
                    a.setResolved(true);
                    adminAlertRepository.save(a);
                });
    }

}
