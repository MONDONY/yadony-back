package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.Transfer;
import com.stripe.param.TransferListParams;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Garde anti double Transfer : la clé d'idempotence Stripe {@code transfer-<paymentId>} n'est
 * valable que 24 h. Passé ce délai, un rejeu (livraison rejouée, versement tardif, libération
 * forcée après un Transfer réussi chez Stripe mais une transaction locale annulée) créerait un
 * second Transfer et paierait le voyageur deux fois.
 *
 * <p>Avant chaque création, on demande donc à Stripe s'il existe déjà un Transfer pour ce
 * paiement. Les Transfers du versement portent la métadonnée {@code payment_id} mais aucun
 * {@code transfer_group} (non filtrable sur les métadonnées) : la recherche se fait par compte
 * de destination, bornée à la date de création du paiement, puis filtrée sur
 * {@code metadata.payment_id}. Un Transfer entièrement annulé ({@code reversed}) ne compte pas.
 *
 * <p>Un échec de lecture remonte en {@link StripeException} : l'appelant n'émet alors aucun
 * Transfer (mieux vaut un versement retardé qu'un versement double).
 */
@Component
public class StripeTransferLookup {

    /** Marge sur la borne basse : horloges et fuseaux, la date locale est en UTC. */
    static final long CREATED_MARGIN_SECONDS = 86_400L;

    /**
     * @param destination compte Connect du voyageur ({@code acct_…})
     * @param notBefore   date de création du paiement (UTC), {@code null} pour ne pas borner
     */
    public Optional<String> findExistingTransfer(UUID paymentId, String destination, LocalDateTime notBefore)
            throws StripeException {
        if (paymentId == null || destination == null || destination.isBlank()) {
            return Optional.empty();
        }
        TransferListParams.Builder params = TransferListParams.builder()
                .setDestination(destination)
                .setLimit(100L);
        if (notBefore != null) {
            params.setCreated(TransferListParams.Created.builder()
                    .setGte(notBefore.toEpochSecond(ZoneOffset.UTC) - CREATED_MARGIN_SECONDS)
                    .build());
        }
        String expected = paymentId.toString();
        for (Transfer t : list(params.build()).autoPagingIterable()) {
            if (t.getMetadata() != null && expected.equals(t.getMetadata().get("payment_id"))
                    && !Boolean.TRUE.equals(t.getReversed())) {
                return Optional.of(t.getId());
            }
        }
        return Optional.empty();
    }

    /** Point d'appel du SDK, isolé pour les tests. */
    com.stripe.model.TransferCollection list(TransferListParams params) throws StripeException {
        return Transfer.list(params);
    }
}
