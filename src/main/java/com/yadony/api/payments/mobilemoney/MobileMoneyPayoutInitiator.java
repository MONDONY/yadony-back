package com.yadony.api.payments.mobilemoney;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.PaymentEntity;
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
 * Versement du net au voyageur, partagé par la livraison, le force-release et la relance
 * admin. L'appelant a DÉJÀ gagné le claim ESCROW → RELEASED avant d'invoquer {@link #release} :
 * ici on garantit qu'aucun second payout vivant n'est jamais soumis pour le même paiement
 * (spec §7.1 : le lien qui fait foi est {@code pawapay_operations.payment_id}) et qu'un échec
 * remonte en exception pour que l'appelant annule son claim.
 *
 * <p>Cette classe n'écrit JAMAIS sur l'entité {@link PaymentEntity} reçue : après le claim
 * bulk de l'appelant ({@code markReleasedIfEscrow}, {@code @Modifying} sans
 * {@code clearAutomatically}), l'entité chargée en amont garde un snapshot périmé
 * ({@code status = ESCROW}) ; {@code PaymentEntity} n'a ni {@code @DynamicUpdate} ni
 * {@code @Version}, le moindre setter la rendrait sale et le flush régénérerait un UPDATE de
 * TOUTES les colonnes — {@code status = 'ESCROW'} écraserait le {@code RELEASED} qui vient
 * d'être posé.
 */
@Component
public class MobileMoneyPayoutInitiator {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyPayoutInitiator.class);

    /**
     * {@code admin_alerts.type} est {@code VARCHAR(60)} : préfixe + UUID (36) doit rester sous
     * 60 avec marge — {@code PAWAPAY_PAYOUT_ORPHANED_} + UUID ferait exactement 60 (marge
     * nulle). Celui-ci fait 17 + 36 = 53. Voir {@code MobileMoneyPayoutInitiatorTest}.
     */
    static final String ORPHAN_ALERT_PREFIX = "MM_PAYOUT_ORPHAN_";

    private final UserRepository userRepository;
    private final PawapayOperationService operations;
    private final PawapaySubmissionService submission;
    private final AdminAlertService adminAlert;
    private final AdminAlertEscalator alerts;
    private final AuditService audit;

    /**
     * Transaction INDÉPENDANTE réservée à l'audit d'un versement : si cet INSERT échouait dans
     * la transaction ambiante (celle qui porte le claim), son rollback dé-réclamerait le
     * paiement (retour en ESCROW) alors que l'argent est déjà parti chez pawaPay (soumission
     * acceptée) ou déjà identifié comme parti (rattachement orphelin) — sans laisser la moindre
     * trace de cette tentative.
     */
    private final TransactionTemplate independentAuditTransaction;

    public MobileMoneyPayoutInitiator(UserRepository userRepository, PawapayOperationService operations,
                                      PawapaySubmissionService submission, AdminAlertService adminAlert,
                                      AdminAlertEscalator alerts, AuditService audit,
                                      PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.operations = operations;
        this.submission = submission;
        this.adminAlert = adminAlert;
        this.alerts = alerts;
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
     *       est désormais dans une autre devise que {@code payment.getCurrency()}
     *       ({@link UserEntity#canReceiveMobileMoney}, même règle qu'à l'acceptation).
     *       Alerte {@code PAWAPAY_PAYOUT_NO_ACCOUNT}, exception, AUCUN appel pawaPay ;</li>
     *   <li><b>payout déjà vivant ou abouti</b> ({@link PawapayOperationService#findLive}) :
     *       une soumission antérieure a déjà été acceptée par pawaPay (relance après un
     *       claim annulé pour une raison indépendante, ou événement de livraison rejoué) —
     *       on le reprend tel quel, on n'en soumet JAMAIS un second. Alerte
     *       {@code MM_PAYOUT_ORPHAN_<paymentId>}, dédupliquée ({@link AdminAlertEscalator}) :
     *       la relance admin rappelle {@code release()} sur ce même payout vivant à chaque
     *       tentative d'un opérateur — sans dédup, chaque tentative posterait sur Telegram ;</li>
     *   <li><b>soumission</b> : {@link PawapaySubmissionService#submitPayout} appelle
     *       pawaPay. Un {@code SUBMIT_REJECTED} (rien n'est parti) lève après une alerte
     *       {@code PAWAPAY_PAYOUT_REJECTED}. Un {@code ACCEPTED} est audité dans
     *       {@link #independentAuditTransaction} — après ce point, la seule chose qui suit
     *       sur le chemin nominal est un {@code log.info}, qui ne peut rien faire annuler.</li>
     * </ol>
     */
    public PawapayOperationEntity release(PaymentEntity payment, UUID bidId, UUID travelerId, BigDecimal net, String source) {
        UserEntity traveler = userRepository.findById(travelerId)
                .orElseThrow(() -> new IllegalStateException("Traveler not found: " + travelerId));
        if (!traveler.canReceiveMobileMoney(payment.getCurrency())) {
            adminAlert.raise("PAWAPAY_PAYOUT_NO_ACCOUNT",
                    "Versement impossible : le voyageur n'a pas de compte mobile money actif compatible "
                            + "avec ce paiement (payment " + payment.getId() + ")",
                    Map.of("paymentId", payment.getId().toString(), "travelerId", travelerId.toString(), "source", source));
            throw new IllegalStateException("Traveler " + travelerId
                    + " has no active mobile money account for currency " + payment.getCurrency());
        }

        Optional<PawapayOperationEntity> live = operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT);
        if (live.isPresent()) {
            PawapayOperationEntity op = live.get();
            independentAuditTransaction.executeWithoutResult(status -> audit.log("PAYMENT", payment.getId(),
                    "ESCROW_RELEASED_MOBILE_MONEY_RECOVERED", bidId,
                    Map.of("bidId", String.valueOf(bidId), "operationId", op.getId().toString(), "source", source)));
            alerts.raiseOnce(ORPHAN_ALERT_PREFIX + payment.getId(),
                    "Payout déjà en vol repris pour le paiement " + payment.getId() + " (" + op.getStatus() + ")",
                    Map.of("paymentId", payment.getId().toString(), "operationId", op.getId().toString(), "source", source));
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
        independentAuditTransaction.executeWithoutResult(status -> audit.log("PAYMENT", payment.getId(),
                "ESCROW_RELEASED_MOBILE_MONEY", bidId,
                Map.of("bidId", String.valueOf(bidId), "operationId", op.getId().toString(), "net", net.toPlainString(),
                        "currency", payment.getCurrency(), "msisdnMasked", op.getMsisdnMasked(), "source", source)));
        log.info("Payout mobile money {} soumis pour le paiement {} ({})", op.getId(), payment.getId(), source);
        return op;
    }
}
