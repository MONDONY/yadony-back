package com.yadony.api.matching;

import com.yadony.api.payments.currency.ExchangeRateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Recalcule le pivot EUR ({@code announcements.price_per_kg_eur}) de toutes les
 * annonces d'une devise quand son taux administré change — le pivot est une dérivée
 * du taux, le laisser en l'état ferait filtrer et trier le fil sur l'ancien taux
 * jusqu'à la prochaine écriture de chaque annonce.
 *
 * <p>{@code @EventListener} SYNCHRONE, même transaction que l'écriture du taux, à
 * dessein (pas de {@code @TransactionalEventListener(AFTER_COMMIT)}) : taux et pivots
 * doivent commiter ou échouer ensemble. Un échec ici doit annuler le changement de
 * taux — ne surtout pas avaler l'exception.
 */
@Component
public class ExchangeRatePivotListener {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRatePivotListener.class);

    private final AnnouncementRepository announcementRepository;

    public ExchangeRatePivotListener(AnnouncementRepository announcementRepository) {
        this.announcementRepository = announcementRepository;
    }

    @EventListener
    public void onExchangeRateChanged(ExchangeRateChangedEvent event) {
        int repivoted = announcementRepository.recomputeEurPivotForCurrency(
                event.currency(), event.unitsPerEur());
        log.info("Pivot EUR recalculé pour {} annonces {} (taux {})",
                repivoted, event.currency(), event.unitsPerEur());
    }
}
