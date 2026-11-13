package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
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
    static final String TEMPLATE_ERROR_CODE = "boat-template-unsupported";

    private final PlatformSettingsService settings;
    private final BoatCarrierPolicy boatCarrierPolicy;

    public TransportModeGuard(PlatformSettingsService settings, BoatCarrierPolicy boatCarrierPolicy) {
        this.settings = settings;
        this.boatCarrierPolicy = boatCarrierPolicy;
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

    /**
     * Le bateau est réservé aux transporteurs vérifiés (GP Pro ou compte PRO). À appeler après
     * {@link #assertTripModeAllowed} : l'interrupteur d'abord, le profil ensuite. Un trajet qui garde
     * son mode bateau n'est pas recontrôlé.
     *
     * @param newMode      mode demandé pour le trajet (null toléré : rien à contrôler)
     * @param previousMode mode déjà enregistré en base, {@code null} à la création
     * @param carrier      le voyageur qui crée, publie ou modifie le trajet
     */
    public void assertBoatCarrierAllowed(TransportMode newMode, TransportMode previousMode, UserEntity carrier) {
        if (newMode == TransportMode.BOAT && previousMode != TransportMode.BOAT) {
            boatCarrierPolicy.assertCanPublishBoat(carrier);
        }
    }

    /**
     * Les modèles et les récurrences ne gèrent pas le bateau (une traversée ne se répète pas à date
     * fixe, et sa capacité est en m³) : refusés à l'enregistrement.
     */
    public void assertTemplateModeSupported(TransportMode mode) {
        if (mode == TransportMode.BOAT) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, TEMPLATE_ERROR_CODE,
                    "problem.boat-template-unsupported");
        }
    }

    /** Mode de transport d'une demande d'envoi : {@code null} et avion sont toujours acceptés. */
    public void assertRequestModeAllowed(TransportMode mode) {
        if (mode == TransportMode.BOAT && !settings.transportBoatEnabled()) {
            throw boatClosed();
        }
        if ((mode == TransportMode.CAR || mode == TransportMode.BUS) && !settings.transportRoadEnabled()) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, ERROR_CODE,
                    "problem.transport-mode-disabled-road");
        }
    }

    private static YadonyBusinessException boatClosed() {
        return YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, ERROR_CODE,
                "problem.transport-mode-disabled-boat");
    }
}
