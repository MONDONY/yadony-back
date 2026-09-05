package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminChargebackResponse;
import com.yadony.api.admin.dto.AdminPaymentDetailResponse;
import com.yadony.api.admin.dto.AdminPaymentListItemResponse;
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
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator;
import com.yadony.api.payments.pawapay.PawapayAmounts;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.Transfer;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
     * Tâche 18 — injectées par CONSTRUCTEUR, comme le reste de cette classe. Ronde 1 (revue) :
     * la version précédente les injectait par champ au motif que {@code AdminPaymentControllerTest}
     * construit ce contrôleur à la main et n'aurait pas pu fournir de nouveaux paramètres — mais
     * ce test n'a qu'un seul site de construction, trivialement mis à jour, et son propre
     * Javadoc invoquait {@code DeliveryEventListener} comme précédent en affirmant l'inverse de
     * ce que ce dernier documente réellement (injection par CONSTRUCTEUR, jamais par champ,
     * précisément pour qu'une dépendance manquante soit une erreur de compilation et non une NPE
     * qui attend son premier paiement PAWAPAY). Un champ non nul dans TOUS les tests, y compris
     * ceux qui n'exercent jamais le rail mobile money, est strictement plus sûr qu'un champ
     * potentiellement null.
     */
    private final MobileMoneyPayoutInitiator payoutInitiator;
    private final PawapayOperationService pawapayOperations;
    private final PawapaySubmissionService pawapaySubmission;
    private final RefundProcessor refundProcessor;
    private final EntityManager entityManager;

    /**
     * Transaction INDÉPENDANTE réservée à l'audit des trois gestes mobile money de cette classe
     * (Ronde 1, point 7) — même outil, même motif que
     * {@code MobileMoneyPayoutInitiator#independentAuditTransaction} : si une écriture ambiante
     * postérieure (résolution d'alertes, ou simplement le commit final) échouait, la trace d'un
     * versement ou d'un remboursement déjà accepté par pawaPay doit survivre à ce rollback.
     */
    private final TransactionTemplate independentAuditTransaction;

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
                                  PlatformTransactionManager transactionManager) {
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // Ronde 1, point 6 : method filtre désormais réellement par rail (STRIPE/PAWAPAY, tâche
        // 18) — l'ancien raccourci « method != STRIPE → page vide » datait d'avant la tâche 12
        // (paiements mobile money) et rendait la liste incohérente avec le détail, qui rend déjà
        // PAWAPAY pour ces mêmes paiements.
        String rail = (method != null && !method.isBlank()) ? method.toUpperCase(Locale.ROOT) : null;
        Page<PaymentEntity> raw = paymentRepository.findAdminFiltered(status, dateFrom, dateTo, rail, PageRequest.of(page, size));
        return ResponseEntity.ok(raw.map(AdminPaymentListItemResponse::from));
    }

    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    @GetMapping("/{id}")
    public ResponseEntity<AdminPaymentDetailResponse> getById(@PathVariable UUID id) {
        PaymentEntity p = paymentRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "payment-not-found", "Not Found", "Paiement introuvable"));
        return ResponseEntity.ok(AdminPaymentDetailResponse.from(p));
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
    public ResponseEntity<AdminPaymentDetailResponse> forceRelease(@PathVariable UUID id) {
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

        AnnouncementEntity announcement = (bid != null)
                ? announcementRepository.findById(bid.getAnnouncementId()).orElse(null)
                : null;
        UUID travelerId = (announcement != null) ? announcement.getTravelerId() : null;
        UserEntity traveler = (travelerId != null)
                ? userRepository.findById(travelerId).orElse(null)
                : null;
        UUID bidId = (bid != null) ? bid.getId() : payment.getBidId();

        // Atomic ESCROW → RELEASED transition — prevents a double release/transfer race.
        int updated = paymentRepository.markReleasedIfEscrow(id, LocalDateTime.now(ZoneOffset.UTC));
        if (updated == 0) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "payment-not-in-escrow",
                    "Invalid Status",
                    "Seuls les paiements en statut ESCROW peuvent faire l'objet d'une libération forcée");
        }

        // Tâche 18 — rail mobile money : bifurque juste après le claim, avant tout appel Stripe.
        // Réutilise EXACTEMENT le chemin de la tâche 16 (DeliveryEventListener#releaseMobileMoney) :
        // MobileMoneyPayoutInitiator#release porte toute la logique (compte de versement, payout
        // orphelin déjà vivant rattaché sans jamais en resoumettre un second, soumission pawaPay),
        // rien n'est réimplémenté ici.
        if (payment.getRail() == PaymentRail.PAWAPAY) {
            if (travelerId == null) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "traveler-not-found",
                        "Invalid Traveler", "Voyageur introuvable pour ce paiement");
            }
            // Ronde 1, point 7 : résout les alertes ESCROW_J48_TIMEOUT AVANT tout appel pawaPay —
            // si cette écriture ambiante échouait APRÈS payoutInitiator.release, le rollback
            // qu'elle provoquerait annulerait le claim pendant que pawaPay a déjà réellement versé.
            resolveRelatedAlerts(id);
            BigDecimal net = PawapayAmounts.round(
                    payment.getAmount().subtract(payment.getCommissionAmount()), payment.getCurrency());
            try {
                payoutInitiator.release(payment, bidId, travelerId, net, "admin-force-release");
            } catch (IllegalStateException e) {
                // @Transactional : l'exception annule le claim, le paiement reste ESCROW.
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-payout-failed",
                        "Mobile Money Payout Failed", "Versement mobile money impossible : " + e.getMessage());
            }
            // Ronde 1, point 1 : payoutInitiator.release a déjà posé status/escrowReleasedAt (via
            // le claim ci-dessus) et pawapay_payout_id (via PaymentRepository#attachPayoutId) par
            // des écritures ciblées, jamais vues par l'entité `payment` chargée en amont. Plutôt
            // que de reposer ces colonnes une à une avec des setters — liste qui s'est révélée
            // incomplète une première fois (escrowReleasedAt aurait été réécrit avec une valeur
            // postérieure à l'aller-retour HTTP pawaPay, pas de quelques millisecondes) — un simple
            // entityManager.refresh(payment) relit la ligne réelle DANS la même transaction (elle y
            // voit ses propres écritures non commitées) et rend à l'entité un snapshot propre :
            // plus aucun flush ultérieur ne peut régénérer un UPDATE qui écraserait quoi que ce
            // soit, quelle que soit la colonne, présente ou future. Voir
            // PaymentRepositoryMobileMoneyTest#markReleasedIfEscrow_thenAttachPayoutId_thenRefresh_generatesNoUpdate.
            entityManager.refresh(payment);
            // Ronde 1, point 7 (suite) : audit dans une transaction indépendante, déjà commitée par
            // le temps que la méthode retourne — même motif que
            // MobileMoneyPayoutInitiator#independentAuditTransaction. Acteur = l'administrateur qui
            // agit (Ronde 1, point 3), jamais bidId ni null : audit_log est immuable.
            independentAuditTransaction.executeWithoutResult(status -> auditService.log(
                    "PAYMENT", payment.getId(), "ESCROW_FORCE_RELEASED", currentAdminId(),
                    Map.of("paymentId", id.toString(), "bidId", String.valueOf(bidId), "rail", "PAWAPAY",
                            "amount", payment.getAmount().toPlainString())));
            log.info("Admin force-released mobile money escrow for payment {} (bid={})", id, bidId);
            return ResponseEntity.ok(AdminPaymentDetailResponse.from(payment));
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

                BigDecimal net = payment.getAmount().subtract(payment.getCommissionAmount());
                long netCents = net.multiply(BigDecimal.valueOf(100)).longValueExact();

                TransferCreateParams.Builder builder = TransferCreateParams.builder()
                        .setAmount(netCents)
                        .setCurrency("eur")
                        .setDestination(traveler.getStripeAccountId())
                        .putMetadata("bid_id", bidId != null ? bidId.toString() : "")
                        .putMetadata("payment_id", id.toString())
                        .putMetadata("source", "admin-force-release");
                if (chargeId != null && !chargeId.isBlank()) {
                    builder.setSourceTransaction(chargeId);
                }
                Transfer.create(builder.build());
            }
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
            eventPublisher.publishEvent(new PaymentReleasedEvent(
                    bidId, travelerId, bid.getSenderId(), payment.getAmount()));
        }

        log.info("Admin force-released escrow for payment {} (bid={}, PI={})",
                id, bidId, payment.getStripePaymentIntentId());

        return ResponseEntity.ok(AdminPaymentDetailResponse.from(payment));
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

        // Ronde 1, point 4 : rail mobile money — délègue à RefundProcessor#processRefund AVANT
        // tout claim ambiant (pas de markRefundedIfEscrow ici pour ce rail). processRefund est
        // @Transactional(REQUIRES_NEW) et refait SON PROPRE claim markRefundedIfEscrow sur la
        // même ligne : le brancher APRÈS un premier claim posé ici reproduirait l'auto-
        // interblocage démontré à la tâche 17 — la transaction ambiante détiendrait déjà, non
        // commité, le verrou de ligne posé par SON markRefundedIfEscrow (une transaction
        // REQUIRES_NEW ne fait que SUSPENDRE l'ambiante, jamais la commiter), et la transaction
        // REQUIRES_NEW de processRefund resterait bloquée en tentant de verrouiller la même
        // ligne — pendant que la transaction ambiante attend précisément le retour de
        // processRefund pour continuer. Aucune requête ne peut alors progresser.
        if (payment.getRail() == PaymentRail.PAWAPAY) {
            if (payment.getStatus() != PaymentStatus.ESCROW) {
                throw new YadonyBusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "payment-not-in-escrow",
                        "Invalid Status",
                        "Seuls les paiements en statut ESCROW peuvent être remboursés");
            }
            boolean acted;
            try {
                acted = refundProcessor.processRefund(id, "ESCROW_FORCE_REFUNDED", currentAdminId(),
                        Map.of("bidId", String.valueOf(payment.getBidId())));
            } catch (IllegalStateException e) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-payout-failed",
                        "Mobile Money Refund Failed", "Remboursement mobile money impossible : " + e.getMessage());
            }
            if (!acted) {
                // Race perdue entre notre lecture ci-dessus et le claim interne de processRefund
                // (ex. un autre remboursement concurrent) — même sémantique 422 que le rail carte.
                throw new YadonyBusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "payment-not-in-escrow",
                        "Invalid Status",
                        "Seuls les paiements en statut ESCROW peuvent être remboursés");
            }
            // processRefund a déjà commité (transaction REQUIRES_NEW indépendante) : `payment`,
            // chargé plus haut dans CETTE transaction, ignore encore ce changement — refresh
            // relit la ligne réelle (voir le commentaire équivalent de forceRelease ci-dessus).
            entityManager.refresh(payment);
            resolveRelatedAlerts(id);
            log.info("Admin refunded mobile money escrow for payment {}", id);
            return ResponseEntity.ok(AdminPaymentDetailResponse.from(payment));
        }

        // Atomic ESCROW → REFUNDED transition — prevents a double refund race.
        int updated = paymentRepository.markRefundedIfEscrow(id);
        if (updated == 0) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "payment-not-in-escrow",
                    "Invalid Status",
                    "Seuls les paiements en statut ESCROW peuvent être remboursés");
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

        return ResponseEntity.ok(AdminPaymentDetailResponse.from(payment));
    }

    /**
     * POST /admin/payments/{id}/mobile-money/retry-payout
     * Relance un versement mobile money dont la DERNIÈRE tentative connue est morte (FAILED ou
     * SUBMIT_REJECTED). Passe par {@link MobileMoneyPayoutInitiator#release}, exactement le
     * chemin de la livraison (tâche 16) et du force-release ci-dessus : sa déduplication de
     * l'alerte orpheline ({@code MM_PAYOUT_ORPHAN_<paymentId>}) protège donc aussi cette relance
     * sans code séparé — c'est précisément son cas nominal (§ tâche 18 du cahier des charges).
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
    public ResponseEntity<AdminPaymentDetailResponse> retryMobileMoneyPayout(@PathVariable UUID id) {
        PaymentEntity payment = requirePawapayPayment(id);
        if (payment.getStatus() != PaymentStatus.RELEASED) {
            throw retryNotAllowed("Le paiement doit être RELEASED pour relancer le versement");
        }
        Optional<PawapayOperationEntity> lastPayout = pawapayOperations.findLatest(id, PawapayOperationKind.PAYOUT);
        if (lastPayout.isEmpty() || !PawapayOperationStatus.DEAD.contains(lastPayout.get().getStatus())) {
            throw retryNotAllowed("Un versement est encore en cours, déjà abouti, ou introuvable");
        }
        PawapayOperationEntity deadPayout = lastPayout.get();
        BidEntity bid = resolveBid(payment);
        AnnouncementEntity announcement = bid != null
                ? announcementRepository.findById(bid.getAnnouncementId()).orElse(null)
                : null;
        if (bid == null || announcement == null) {
            throw retryNotAllowed("Colis ou trajet introuvable");
        }
        // Ronde 1, point 2 (ARGENT) : reprend le montant de la tentative MORTE plutôt que de le
        // recalculer. Le chemin de livraison majore le net d'une part de commission quand le
        // voyageur détient un bon de parrainage actif (DeliveryEventListener#travelerVoucherTopUp)
        // — et ce bon est consommé DÉFINITIVEMENT dès cette première tentative, qu'elle aboutisse
        // ou non chez pawaPay : il ne resservira jamais. Recalculer ici (amount − commission, sans
        // le bon) sous-paierait donc le voyageur exactement de la part que le bon lui garantissait
        // — silencieusement, le montant recalculé n'étant ni nul ni négatif, juste inférieur. Le
        // montant de l'opération morte est PAR DÉFINITION celui à réémettre — déjà arrondi à sa
        // création, jamais recalculé ici.
        BigDecimal net = deadPayout.getAmount();
        PawapayOperationEntity op;
        try {
            op = payoutInitiator.release(payment, bid.getId(), announcement.getTravelerId(), net, "admin-retry");
        } catch (IllegalStateException e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-payout-failed",
                    "Mobile Money Payout Failed", "Versement mobile money impossible : " + e.getMessage());
        }
        // Réconciliation par refresh, jamais par setter — voir le commentaire de forceRelease.
        entityManager.refresh(payment);
        independentAuditTransaction.executeWithoutResult(status -> auditService.log(
                "PAYMENT", id, "MM_PAYOUT_RETRIED", currentAdminId(),
                Map.of("operationId", op.getId().toString(), "bidId", bid.getId().toString())));
        return ResponseEntity.ok(AdminPaymentDetailResponse.from(payment));
    }

    /**
     * POST /admin/payments/{id}/mobile-money/retry-refund
     * Relance un remboursement mobile money dont la dernière tentative connue est morte — même
     * filet que la relance de versement, mais sur {@code pawapay_operations} de type REFUND.
     * Ne mute jamais {@code payments.status} (déjà REFUNDED ou CANCELLED) : seul le refund
     * pawaPay est rejoué.
     *
     * <p>Revue finale, point 3(b) (Important) : élargi à {@code CANCELLED}. Un paiement mobile
     * money {@code PENDING} remboursé devient {@code CANCELLED} — jamais {@code REFUNDED} comme
     * sur le rail Stripe (voir {@code RefundProcessor#refundMobileMoney}, cas {@code PENDING}).
     * Un deposit arrivé tard sur un tel paiement ({@code MobileMoneyBidPaymentService#confirmEscrow}
     * → {@code refundAfterCancel}) peut y soumettre un refund qui échoue à son tour : sans cet
     * élargissement, l'alerte {@code PAWAPAY_REFUND_*} demandait une reprise humaine que ce
     * endpoint ne permettait pas (il exigeait {@code REFUNDED}, statut que ce paiement
     * n'atteindra jamais).
     */
    @PreAuthorize("hasAuthority('PAYMENT_RELEASE')")
    @PostMapping("/{id}/mobile-money/retry-refund")
    @Transactional
    public ResponseEntity<AdminPaymentDetailResponse> retryMobileMoneyRefund(@PathVariable UUID id) {
        PaymentEntity payment = requirePawapayPayment(id);
        if (payment.getStatus() != PaymentStatus.REFUNDED && payment.getStatus() != PaymentStatus.CANCELLED) {
            throw retryNotAllowed("Le paiement doit être REFUNDED ou CANCELLED pour relancer le remboursement");
        }
        Optional<PawapayOperationEntity> lastRefund = pawapayOperations.findLatest(id, PawapayOperationKind.REFUND);
        if (lastRefund.isEmpty() || !PawapayOperationStatus.DEAD.contains(lastRefund.get().getStatus())) {
            throw retryNotAllowed("Un remboursement est encore en cours, déjà abouti, ou introuvable");
        }
        PawapayOperationEntity deposit = pawapayOperations.findLatest(id, PawapayOperationKind.DEPOSIT)
                .filter(d -> d.getStatus() == PawapayOperationStatus.COMPLETED)
                .orElseThrow(() -> retryNotAllowed("Aucun deposit abouti à rembourser"));
        // Ronde 1, point 8 : montant du DEPOSIT d'origine, jamais payment.getAmount() — même
        // alignement que RefundProcessor#refundEscrowedMobileMoney (les deux se valent
        // aujourd'hui, sans garantie contractuelle demain — voir son propre commentaire).
        PawapayOperationEntity refund = pawapaySubmission.submitRefund(id, deposit, deposit.getAmount());
        if (refund.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-payout-failed",
                    "Mobile Money Refund Failed", "Remboursement refusé : " + refund.getFailureCode());
        }
        // Ronde 1, point 9 : attachRefundId (UPDATE ciblé) — sa Javadoc dit « à utiliser TOUJOURS »
        // à la place d'un setter sur l'entité gérée. Puis refresh, comme forceRelease ci-dessus.
        paymentRepository.attachRefundId(id, refund.getId());
        entityManager.refresh(payment);
        independentAuditTransaction.executeWithoutResult(status -> auditService.log(
                "PAYMENT", id, "MM_REFUND_RETRIED", currentAdminId(),
                Map.of("operationId", refund.getId().toString())));
        return ResponseEntity.ok(AdminPaymentDetailResponse.from(payment));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private static YadonyBusinessException retryNotAllowed(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-retry-not-allowed",
                "Retry Not Allowed", detail);
    }

    /**
     * Identifiant de l'administrateur qui agit, pour la trace d'audit (Ronde 1, point 3) — même
     * garde que {@code AdminUserController#adminId}, mais lu depuis le {@code SecurityContext}
     * plutôt que reçu en paramètre {@code Authentication} : {@code forceRelease}/{@code refund}
     * sont appelés directement, hors Spring Security, par 15 tests unitaires existants de
     * {@code AdminPaymentControllerTest} (tous sur des paiements de rail STRIPE) — leur ajouter un
     * paramètre casserait leur compilation pour une branche PAWAPAY qu'ils n'atteignent jamais.
     * {@code audit_log} est immuable : désigner la CIBLE (bidId) ou rien comme acteur rendrait
     * l'administrateur responsable d'un versement ou d'un remboursement introuvable pour toujours.
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
