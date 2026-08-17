package com.yadony.api.payments.cash;

import com.yadony.api.requests.event.NegotiationCancelledEvent;
import com.yadony.api.requests.event.NegotiationCommissionDeclinedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Rembourse la commission d'un voyageur débité pour un accord en espèces qui
 * n'aura finalement pas lieu. Deux chemins y mènent :
 *
 * <ul>
 *   <li>le voyageur renonce lui-même ({@code declineCommission}) ;</li>
 *   <li>la négociation meurt sans lui — l'autre partie ferme le fil, ou
 *       l'expéditeur annule toute sa demande.</li>
 * </ul>
 *
 * <p>Un débit réel peut parfaitement précéder ces deux cas, contrairement à ce
 * qu'on croirait : le règlement crée le PaymentIntent avec {@code confirm=true} et
 * le persiste quel que soit son statut. Le voyageur qui lance un paiement par
 * carte, valide sa 3DS dans son application bancaire (Stripe encaisse), ne revient
 * jamais dans yadony (donc la confirmation n'est jamais appelée), laisserait sa
 * commission à yadony pour un accord qui vient de tomber.
 *
 * <p>{@code AFTER_COMMIT} + {@code REQUIRES_NEW} : le remboursement ne peut pas
 * être lancé en ligne depuis les services appelants. Ceux-ci ont déjà muté la
 * ligne du fil et la détiennent ; ouvrir une transaction séparée qui écrit la même
 * ligne bloquerait sur une connexion que l'appelant ne libérera jamais, sans
 * qu'aucun cycle ne soit visible pour PostgreSQL, donc sans détection
 * d'interblocage. Attendre le commit lève l'obstacle — et un rollback ne
 * déclenchant aucun {@code AFTER_COMMIT}, aucun remboursement ne part pour une
 * annulation qui n'a pas eu lieu.
 *
 * <p>Le balayage de {@code CommissionWindowExpiryRunner} reste le filet : il reprend
 * les fils {@code CANCELLED} / {@code AUTO_REJECTED} porteurs d'un PaymentIntent,
 * pour la course étroite où la 3DS n'aboutit qu'après le passage de cet écouteur.
 */
@Component
public class NegotiationCommissionRefundListener {

    private static final Logger log =
            LoggerFactory.getLogger(NegotiationCommissionRefundListener.class);

    private final CashCommissionService cashCommissionService;

    public NegotiationCommissionRefundListener(CashCommissionService cashCommissionService) {
        this.cashCommissionService = cashCommissionService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCommissionDeclined(NegotiationCommissionDeclinedEvent event) {
        refund(event.threadId(), event.travelerId(), "renoncement");
    }

    /**
     * Le fil a été fermé par l'autre partie ou emporté par l'annulation de la
     * demande. La condition SpEL évite de relire Stripe pour les annulations qui
     * n'ont aucune commission en jeu, c'est-à-dire la grande majorité.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT,
            condition = "#event.refundCommission()")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onNegotiationCancelled(NegotiationCancelledEvent event) {
        // byUserId, jamais toUserId : ce paramètre est l'ACTEUR de la trace
        // d'audit, et toUserId désigne « l'autre partie » — donc l'expéditeur
        // quand c'est le voyageur qui a fermé le fil. audit_log étant immuable,
        // un mauvais acteur sur une écriture financière ne se corrige jamais.
        refund(event.threadId(), event.byUserId(), "annulation");
    }

    private void refund(UUID threadId, UUID actorId, String cause) {
        try {
            cashCommissionService.refundNegotiationCommissionIfCharged(threadId, actorId);
        } catch (RuntimeException e) {
            // Ne jamais laisser une erreur ici remonter : la transaction d'origine
            // est déjà commitée et l'utilisateur a eu sa réponse. Le balayage repassera.
            log.error("Remboursement de commission ({}) échoué pour le fil {} : {}",
                    cause, threadId, e.getMessage());
        }
    }
}
