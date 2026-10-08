package com.yadony.api.triptemplate;

import com.yadony.api.settings.ActiveCurrencyChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Au changement de devise active (FLUTTER-8F), fige les anciens modèles de trajet sans
 * devise dans la devise qu'ils représentaient jusque-là. Synchrone, dans la transaction
 * du changement.
 */
@Component
public class TripTemplateCurrencyListener {

    private final TripTemplateService tripTemplateService;

    public TripTemplateCurrencyListener(TripTemplateService tripTemplateService) {
        this.tripTemplateService = tripTemplateService;
    }

    @EventListener
    public void onActiveCurrencyChanged(ActiveCurrencyChangedEvent event) {
        tripTemplateService.pinLegacyTemplatesCurrency(event.userId(), event.previousCurrency());
    }
}
