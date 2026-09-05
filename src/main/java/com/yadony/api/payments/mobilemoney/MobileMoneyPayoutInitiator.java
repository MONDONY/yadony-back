package com.yadony.api.payments.mobilemoney;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Versement du net au voyageur, partagé par la livraison (tâche 16), le force-release et la
 * relance admin (tâche 18). L'appelant a DÉJÀ gagné le claim ESCROW → RELEASED avant
 * d'invoquer {@link #release} : ici on garantit qu'aucun second payout vivant n'est jamais
 * soumis pour le même paiement (spec §7.1 : le lien qui fait foi est
 * {@code pawapay_operations.payment_id}, jamais {@code payments.pawapay_payout_id}, qui n'est
 * qu'un confort de lecture) et qu'un échec remonte en exception pour que l'appelant annule
 * son claim.
 *
 * <p><b>Ronde 1 (revue), point 1 — CRITIQUE</b> : après {@code markReleasedIfEscrow} (bulk JPQL
 * {@code @Modifying} sans {@code clearAutomatically}), l'entité {@link PaymentEntity} chargée en
 * amont par l'appelant garde un snapshot Hibernate périmé ({@code status = ESCROW}).
 * {@code PaymentEntity} n'a ni {@code @DynamicUpdate} ni {@code @Version} : un simple
 * {@code payment.setPawapayPayoutId(...)} sur cette entité gérée la rend sale, et au flush
 * (souvent au commit) Hibernate régénère un UPDATE de TOUTES les colonnes avec les valeurs en
 * mémoire — {@code status = 'ESCROW'} écraserait silencieusement le {@code RELEASED} qui vient
 * d'être posé, à CHAQUE versement mobile money. Cette classe ne mute donc plus JAMAIS l'entité
 * gérée après le claim : {@link PaymentRepository#attachPayoutId} pose la colonne par un UPDATE
 * ciblé. Voir {@code PaymentRepositoryMobileMoneyTest#markReleasedIfEscrow_thenAttachPayoutId_doesNotRevertStatus}.
 */
@Component
public class MobileMoneyPayoutInitiator {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyPayoutInitiator.class);

    /**
     * Ronde 1, point 3 : {@code admin_alerts.type} est {@code VARCHAR(60)}. Un préfixe +
     * UUID (36) doit rester sous 60 avec marge — {@code PAWAPAY_PAYOUT_ORPHANED_} + UUID ferait
     * exactement 60 (marge nulle). Celui-ci fait 17 + 36 = 53. Voir
     * {@code MobileMoneyPayoutInitiatorTest#orphanAlertType_fitsInAdminAlertsTypeColumn}.
     */
    static final String ORPHAN_ALERT_PREFIX = "MM_PAYOUT_ORPHAN_";

    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final PawapayOperationService operations;
    private final PawapaySubmissionService submission;
    private final AdminAlertService adminAlert;
    private final AdminAlertRepository alertRepository;
    private final AuditService audit;

    /**
     * Transaction INDÉPENDANTE réservée à l'audit d'un versement (Ronde 1, point 2) — même
     * outil, même motif que {@code MobileMoneyBidPaymentService#independentAuditTransaction} :
     * si cet INSERT échouait dans la transaction ambiante (celle qui porte le claim), son
     * rollback dé-réclamerait le paiement (retour en ESCROW) alors que l'argent est déjà parti
     * chez pawaPay (soumission acceptée) ou déjà identifié comme parti (rattachement orphelin) —
     * sans laisser la moindre trace de cette tentative.
     */
    private final TransactionTemplate independentAuditTransaction;

    public MobileMoneyPayoutInitiator(UserRepository userRepository, PaymentRepository paymentRepository,
                                      PawapayOperationService operations, PawapaySubmissionService submission,
                                      AdminAlertService adminAlert, AdminAlertRepository alertRepository,
                                      AuditService audit, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.operations = operations;
        this.submission = submission;
        this.adminAlert = adminAlert;
        this.alertRepository = alertRepository;
        this.audit = audit;
        this.independentAuditTransaction = new TransactionTemplate(transactionManager);
        this.independentAuditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Verse {@code net} au voyageur {@code travelerId} pour {@code payment}. Trois issues
     * possibles :
     * <ol>
     *   <li><b>compte absent ou incompatible</b> : le voyageur a désactivé son compte de
     *       versement mobile money entre le séquestre et la livraison, ou son compte actif
     *       est désormais dans une autre devise que {@code payment.getCurrency()} (parité
     *       avec la revérification faite par {@code MobileMoneyBidPaymentService#acceptBid}
     *       à l'acceptation, tâche 13). Alerte {@code PAWAPAY_PAYOUT_NO_ACCOUNT}, exception,
     *       AUCUN appel pawaPay ;</li>
     *   <li><b>payout déjà vivant ou abouti</b> ({@link PawapayOperationService#findLive}) :
     *       une soumission antérieure a déjà été acceptée par pawaPay (relance après un
     *       claim annulé pour une raison indépendante, ou événement de livraison rejoué) —
     *       on RATTACHE cette opération par {@link PaymentRepository#attachPayoutId} (jamais
     *       un setter, voir le Javadoc de la classe), on n'en soumet JAMAIS une seconde.
     *       Alerte {@code MM_PAYOUT_ORPHAN_<paymentId>}, dédupliquée par
     *       {@link AdminAlertRepository#findByTypeAndResolved} (Ronde 1, point 3) : la
     *       relance admin (tâche 18) rappelle {@code release()} sur ce même payout vivant à
     *       chaque tentative d'un opérateur — sans dédup, chaque tentative posterait sur
     *       Telegram ;</li>
     *   <li><b>soumission</b> : {@link PawapaySubmissionService#submitPayout} appelle
     *       pawaPay. Un {@code SUBMIT_REJECTED} (rien n'est parti) lève après une alerte
     *       {@code PAWAPAY_PAYOUT_REJECTED}. Un {@code ACCEPTED} enregistre l'id du payout
     *       via {@code attachPayoutId} et audite dans {@link #independentAuditTransaction} —
     *       après ce point, la seule chose qui suit sur le chemin nominal est un
     *       {@code log.info}, qui ne peut pas faire annuler quoi que ce soit.</li>
     * </ol>
     */
    public PawapayOperationEntity release(PaymentEntity payment, UUID bidId, UUID travelerId, BigDecimal net, String source) {
        UserEntity traveler = userRepository.findById(travelerId)
                .orElseThrow(() -> new IllegalStateException("Traveler not found: " + travelerId));
        boolean currencyMatches = traveler.getMobileMoneyCurrency() != null
                && traveler.getMobileMoneyCurrency().equalsIgnoreCase(payment.getCurrency());
        if (!traveler.hasActiveMobileMoney() || !currencyMatches) {
            adminAlert.raise("PAWAPAY_PAYOUT_NO_ACCOUNT",
                    "Versement impossible : le voyageur n'a pas de compte mobile money actif compatible "
                            + "avec ce paiement (payment " + payment.getId() + ")",
                    Map.of("paymentId", payment.getId().toString(), "travelerId", travelerId.toString(), "source", source));
            throw new IllegalStateException("Traveler " + travelerId
                    + " has no active mobile money account for currency " + payment.getCurrency());
        }

        Optional<PawapayOperationEntity> live = operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT);
        if (live.isPresent()) {
            // Un payout est déjà vivant ou abouti (claim annulé après une soumission
            // acceptée, ou événement rejoué) : on le rattache, on n'en soumet JAMAIS un
            // second — l'autorité est pawapay_operations.payment_id (spec §7.1), jamais
            // payments.pawapay_payout_id (confort de lecture seulement).
            PawapayOperationEntity op = live.get();
            paymentRepository.attachPayoutId(payment.getId(), op.getId());
            independentAuditTransaction.executeWithoutResult(status -> audit.log("PAYMENT", payment.getId(),
                    "ESCROW_RELEASED_MOBILE_MONEY_RECOVERED", bidId,
                    Map.of("bidId", String.valueOf(bidId), "operationId", op.getId().toString(), "source", source)));
            escalateOrphan(payment, op, source);
            return op;
        }

        PawapayOperationEntity op = submission.submitPayout(payment.getId(), traveler.getMobileMoneyMsisdn(),
                traveler.getMobileMoneyProvider(), traveler.getMobileMoneyCountry(), net, payment.getCurrency(), "bid-" + bidId);
        if (op.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            adminAlert.raise("PAWAPAY_PAYOUT_REJECTED",
                    "pawaPay a refusé le payout du paiement " + payment.getId() + " : " + op.getFailureCode(),
                    Map.of("paymentId", payment.getId().toString(), "operationId", op.getId().toString(),
                            "failureCode", String.valueOf(op.getFailureCode()), "source", source));
            throw new IllegalStateException("pawaPay payout rejected: " + op.getFailureCode());
        }
        // Ronde 1, point 1 : UPDATE ciblé, jamais un setter sur l'entité gérée (voir Javadoc de
        // la classe). Ronde 1, point 2 : audit dans sa propre transaction.
        paymentRepository.attachPayoutId(payment.getId(), op.getId());
        independentAuditTransaction.executeWithoutResult(status -> audit.log("PAYMENT", payment.getId(),
                "ESCROW_RELEASED_MOBILE_MONEY", bidId,
                Map.of("bidId", String.valueOf(bidId), "operationId", op.getId().toString(), "net", net.toPlainString(),
                        "currency", payment.getCurrency(), "msisdnMasked", op.getMsisdnMasked(), "source", source)));
        log.info("Payout mobile money {} soumis pour le paiement {} ({})", op.getId(), payment.getId(), source);
        return op;
    }

    /**
     * Ronde 1, point 3 : dédupliquée par paiement, structure reprise à l'identique de
     * {@code MobileMoneyPaymentDeadlineScheduler#escalate} / {@code PawapayReconciliationPoller#escalateUnknown}
     * — une alerte non résolue du même type est cherchée AVANT d'en créer une nouvelle et
     * d'appeler {@link AdminAlertService#raise}. Sans cette dédup, chaque tentative de relance
     * admin (tâche 18) sur un payout déjà vivant — son cas nominal de vérification — posterait
     * une alerte Telegram.
     */
    private void escalateOrphan(PaymentEntity payment, PawapayOperationEntity op, String source) {
        String type = ORPHAN_ALERT_PREFIX + payment.getId();
        if (!alertRepository.findByTypeAndResolved(type, false).isEmpty()) {
            return;
        }
        AdminAlertEntity alert = new AdminAlertEntity();
        alert.setType(type);
        alert.setPayload("{\"paymentId\":\"" + payment.getId() + "\",\"operationId\":\"" + op.getId() + "\"}");
        alert.setResolved(false);
        alertRepository.save(alert);
        adminAlert.raise(type, "Payout déjà en vol rattaché au paiement " + payment.getId() + " (" + op.getStatus() + ")",
                Map.of("paymentId", payment.getId().toString(), "operationId", op.getId().toString(), "source", source));
    }
}
