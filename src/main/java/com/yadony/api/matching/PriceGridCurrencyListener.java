package com.yadony.api.matching;

import com.yadony.api.settings.ActiveCurrencyChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Garde la grille tarifaire du voyageur dans sa devise active quand celle-ci change
 * (FLUTTER-8F). Écouteur synchrone : il s'exécute dans la transaction du changement de
 * devise, qu'un échec de conversion annule.
 */
@Component
public class PriceGridCurrencyListener {

    private final PriceGridService priceGridService;

    public PriceGridCurrencyListener(PriceGridService priceGridService) {
        this.priceGridService = priceGridService;
    }

    @EventListener
    public void onActiveCurrencyChanged(ActiveCurrencyChangedEvent event) {
        priceGridService.convertGridCurrency(
                event.userId(), event.previousCurrency(), event.newCurrency());
    }
}
