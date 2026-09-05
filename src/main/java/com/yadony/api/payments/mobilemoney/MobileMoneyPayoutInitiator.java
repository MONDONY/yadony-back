package com.yadony.api.payments.mobilemoney;

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

/**
 * Versement du net au voyageur, partagé par la livraison (tâche 16), le force-release et la
 * relance admin (tâche 18). L'appelant a DÉJÀ gagné le claim ESCROW → RELEASED avant
 * d'invoquer {@link #release} : ici on garantit qu'aucun second payout vivant n'est jamais
 * soumis pour le même paiement (spec §7.1 : le lien qui fait foi est
 * {@code pawapay_operations.payment_id}, jamais {@code payments.pawapay_payout_id}, qui n'est
 * qu'un confort de lecture) et qu'un échec remonte en exception pour que l'appelant annule
 * son claim.
 */
@Component
public class MobileMoneyPayoutInitiator {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyPayoutInitiator.class);

    private final UserRepository userRepository;
    private final PawapayOperationService operations;
    private final PawapaySubmissionService submission;
    private final AdminAlertService adminAlert;
    private final AuditService audit;

    public MobileMoneyPayoutInitiator(UserRepository userRepository, PawapayOperationService operations,
                                      PawapaySubmissionService submission, AdminAlertService adminAlert,
                                      AuditService audit) {
        this.userRepository = userRepository;
        this.operations = operations;
        this.submission = submission;
        this.adminAlert = adminAlert;
        this.audit = audit;
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
     *       AUCUN appel pawaPay — l'argent reste sous séquestre, un administrateur reprendra
     *       par la relance (tâche 18) ; jamais de versement vers un numéro périmé ni vers le
     *       numéro Firebase de secours ;</li>
     *   <li><b>payout déjà vivant ou abouti</b> ({@link PawapayOperationService#findLive}) :
     *       une soumission antérieure a déjà été acceptée par pawaPay (relance après un
     *       claim annulé pour une raison indépendante, ou événement de livraison rejoué) —
     *       on RATTACHE cette opération, on n'en soumet JAMAIS une seconde. Alerte
     *       {@code PAWAPAY_PAYOUT_ORPHANED} : ce cas mérite un regard humain même s'il n'est
     *       pas fautif ;</li>
     *   <li><b>soumission</b> : {@link PawapaySubmissionService#submitPayout} appelle
     *       pawaPay. Un {@code SUBMIT_REJECTED} (rien n'est parti) lève après une alerte
     *       {@code PAWAPAY_PAYOUT_REJECTED}. Un {@code ACCEPTED} enregistre l'id du payout
     *       sur le paiement et audite — <b>après ce point, plus rien ici ne doit pouvoir
     *       lever</b> : l'appelant tient un claim atomique ESCROW → RELEASED gagné AVANT cet
     *       appel, et si du code après un {@code submitPayout} accepté levait, le rollback de
     *       la transaction appelante dé-réclamerait le paiement (retour en ESCROW) alors que
     *       l'argent est déjà parti chez pawaPay — le prochain {@code DeliveryConfirmedEvent}
     *       rejouerait alors cette méthode. Ce n'est structurellement PAS un double versement
     *       même dans ce cas résiduel : {@code submission.submitPayout} a déjà commité
     *       durablement la ligne {@code pawapay_operations} (create + markSubmitted sont
     *       chacun en transaction {@code REQUIRES_NEW} indépendante côté
     *       {@code PawapayOperationService}, indépendante du sort de la transaction
     *       appelante), et le rejeu retrouverait cette opération via {@code findLive} — issue
     *       n°2 ci-dessus — plutôt que d'en soumettre une seconde. Les deux seules
     *       instructions qui suivent {@code submitPayout} sont {@code payment.setPawapayPayoutId}
     *       (écriture en mémoire sur l'entité déjà chargée par l'appelant, ne peut pas lever)
     *       et {@code audit.log} (laissé volontairement dans la MÊME transaction que le
     *       claim, comme le fait déjà le rail Stripe pour {@code auditService.log} après
     *       {@code Transfer.create} dans {@code DeliveryEventListener} — cette entrée n'a pas
     *       besoin de survivre à un rollback qui, structurellement, ne doit plus jamais se
     *       produire à ce stade).</li>
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
            payment.setPawapayPayoutId(op.getId());
            audit.log("PAYMENT", payment.getId(), "ESCROW_RELEASED_MOBILE_MONEY_RECOVERED", bidId,
                    Map.of("bidId", bidId.toString(), "operationId", op.getId().toString(), "source", source));
            adminAlert.raise("PAWAPAY_PAYOUT_ORPHANED",
                    "Payout déjà en vol rattaché au paiement " + payment.getId() + " (" + op.getStatus() + ")",
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
        // Plus rien de faillible après ce point (voir le Javadoc de la méthode) : écriture en
        // mémoire, puis un audit qui reste volontairement dans cette même transaction.
        payment.setPawapayPayoutId(op.getId());
        audit.log("PAYMENT", payment.getId(), "ESCROW_RELEASED_MOBILE_MONEY", bidId,
                Map.of("bidId", bidId.toString(), "operationId", op.getId().toString(), "net", net.toPlainString(),
                        "currency", payment.getCurrency(), "msisdnMasked", op.getMsisdnMasked(), "source", source));
        log.info("Payout mobile money {} soumis pour le paiement {} ({})", op.getId(), payment.getId(), source);
        return op;
    }
}
