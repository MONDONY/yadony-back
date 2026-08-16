package com.yadony.api.payments.cash;

import com.yadony.api.requests.event.NegotiationCommissionDeclinedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Rembourse la commission d'un voyageur qui renonce à un accord en espèces après
 * avoir déjà été débité.
 *
 * <p>Un renoncement peut parfaitement suivre un débit réel, contrairement à ce
 * qu'on croirait : le règlement crée le PaymentIntent avec {@code confirm=true} et
 * le persiste quel que soit son statut. Le voyageur qui lance un paiement par
 * carte, valide sa 3DS dans son application bancaire (Stripe encaisse), ne revient
 * jamais dans dony (donc la confirmation n'est jamais appelée), puis renonce,
 * laisserait sa commission à Yadony pour un accord qu'il vient de refuser.
 *
 * <p>{@code AFTER_COMMIT} + {@code REQUIRES_NEW} : le remboursement ne peut pas
 * être lancé en ligne depuis {@code declineCommission}. Celle-ci a déjà muté la
 * ligne du fil et la détient ; ouvrir une transaction séparée qui écrit la même
 * ligne bloquerait sur une connexion que l'appelant ne libérera jamais, sans
 * qu'aucun cycle ne soit visible pour PostgreSQL, donc sans détection
 * d'interblocage. Attendre le commit lève l'obstacle.
 *
 * <p>Le balayage de {@code CommissionWindowExpiryRunner} reste le filet : il reprend
 * les fils {@code CANCELLED} porteurs d'un PaymentIntent, pour la course étroite où
 * la 3DS n'aboutit qu'après le passage de cet écouteur.
 */
@Component
public class NegotiationCommissionDeclinedRefundListener {

    private static final Logger log =
            LoggerFactory.getLogger(NegotiationCommissionDeclinedRefundListener.class);

    private final CashCommissionService cashCommissionService;

    public NegotiationCommissionDeclinedRefundListener(CashCommissionService cashCommissionService) {
        this.cashCommissionService = cashCommissionService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCommissionDeclined(NegotiationCommissionDeclinedEvent event) {
        try {
            cashCommissionService.refundNegotiationCommissionIfCharged(
                    event.threadId(), event.travelerId());
        } catch (RuntimeException e) {
            // Ne jamais laisser une erreur ici remonter : le renoncement est déjà
            // commité et l'utilisateur a eu sa réponse. Le balayage repassera.
            log.error("Remboursement au renoncement échoué pour le fil {} : {}",
                    event.threadId(), e.getMessage());
        }
    }
}
