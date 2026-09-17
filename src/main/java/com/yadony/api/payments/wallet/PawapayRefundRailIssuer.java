package com.yadony.api.payments.wallet;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implémentation provisoire de {@link WalletRefundRailIssuer} (lot 2, tâche 3) : journalise
 * un WARN et laisse les items PENDING tels quels, sans appeler pawaPay. Remplacée en tâche 4
 * par l'émission réelle (refund pawaPay + payout vers le numéro d'origine).
 */
@Component
public class PawapayRefundRailIssuer implements WalletRefundRailIssuer {

    private static final Logger log = LoggerFactory.getLogger(PawapayRefundRailIssuer.class);

    @Override
    public void issue(WalletRefundRequestEntity request, List<WalletRefundRequestItemEntity> items) {
        if (items.isEmpty()) {
            return;
        }
        log.warn("Emission pawaPay non implementee (tache 4) : {} item(s) PENDING non emis pour "
                + "la demande wallet {}", items.size(), request.getId());
    }
}
