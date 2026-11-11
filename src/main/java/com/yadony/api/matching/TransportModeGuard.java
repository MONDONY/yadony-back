package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Garde des modes de transport éditables depuis le back-office (interrupteurs route et bateau).
 *
 * <p>Côté trajet, seul le bateau est bloqué tant que son interrupteur est éteint : voiture, bus,
 * train et autre restent toujours acceptés (dony-pro et l'assistant les publient déjà). Un trajet
 * bateau déjà en base reste modifiable. Côté demande d'envoi, route et bateau sont tous deux
 * soumis à leur interrupteur.
 */
@Component
public class TransportModeGuard {

    static final String ERROR_CODE = "transport-mode-disabled";
    private static final String TITLE = "Mode de transport indisponible";

    private final PlatformSettingsService settings;

    public TransportModeGuard(PlatformSettingsService settings) {
        this.settings = settings;
    }

    /**
     * @param newMode      mode demandé pour le trajet (null toléré : rien à contrôler)
     * @param previousMode mode déjà enregistré en base, {@code null} à la création
     */
    public void assertTripModeAllowed(TransportMode newMode, TransportMode previousMode) {
        if (newMode == TransportMode.BOAT && previousMode != TransportMode.BOAT && !settings.transportBoatEnabled()) {
            throw boatClosed();
        }
    }

    /** Mode de transport d'une demande d'envoi : {@code null} et avion sont toujours acceptés. */
    public void assertRequestModeAllowed(TransportMode mode) {
        if (mode == TransportMode.BOAT && !settings.transportBoatEnabled()) {
            throw boatClosed();
        }
        if ((mode == TransportMode.CAR || mode == TransportMode.BUS) && !settings.transportRoadEnabled()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, ERROR_CODE, TITLE,
                    "Le transport par la route n'est pas encore ouvert.");
        }
    }

    private static YadonyBusinessException boatClosed() {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, ERROR_CODE, TITLE,
                "Le transport par bateau n'est pas encore ouvert.");
    }
}
