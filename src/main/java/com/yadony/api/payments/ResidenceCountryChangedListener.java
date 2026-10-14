package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.yadony.api.settings.events.ResidenceCountryChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Changement de pays de résidence avec un compte Stripe Connect encore en attente d'onboarding
 * (le pays n'est pas verrouillé, voir {@code CountryLockService}) : le pays d'un compte Connect
 * étant immuable chez Stripe, ce compte ne peut pas suivre. Il est détaché, et l'utilisateur en
 * créera un nouveau dans le bon pays à sa prochaine activation des paiements par carte.
 *
 * <ul>
 *   <li><b>Détachement, synchrone</b> ({@code @EventListener}, exception assumée à la règle
 *       AFTER_COMMIT des listeners de paiement) : il doit être atomique avec le changement de
 *       pays. Un refus (compte devenu verrouillant, 422) annule aussi le changement de pays.</li>
 *   <li><b>Fermeture chez Stripe, après commit</b>, au mieux : jamais bloquante. Un échec laisse
 *       un compte orphelin vide (aucun argent, onboarding jamais fini), signalé en WARN par
 *       {@code orphan_account_id}. Le compte n'étant plus rattaché à personne, ses webhooks
 *       {@code account.updated} ne retrouvent plus d'utilisateur et sont ignorés.</li>
 * </ul>
 */
@Component
public class ResidenceCountryChangedListener {

    private static final Logger log = LoggerFactory.getLogger(ResidenceCountryChangedListener.class);

    private final PaymentService paymentService;
    private final StripeGateway stripeGateway;

    public ResidenceCountryChangedListener(PaymentService paymentService, StripeGateway stripeGateway) {
        this.paymentService = paymentService;
        this.stripeGateway = stripeGateway;
    }

    @EventListener
    public void detachPendingAccount(ResidenceCountryChangedEvent event) {
        if (event.pendingStripeAccountId() == null) {
            return;
        }
        paymentService.detachPendingConnectAccountForCountryChange(event.userId(),
                event.pendingStripeAccountId(), event.previousCountry(), event.newCountry());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void closeDetachedAccount(ResidenceCountryChangedEvent event) {
        String accountId = event.pendingStripeAccountId();
        if (accountId == null) {
            return;
        }
        try {
            stripeGateway.closeAccountV2(accountId);
            log.info("Detached Stripe account {} closed (user {})", accountId, event.userId());
        } catch (StripeException | RuntimeException e) {
            log.warn("Could not close detached Stripe account. orphan_account_id={}, user_id={}: {}",
                    accountId, event.userId(), e.getMessage());
        }
    }
}
