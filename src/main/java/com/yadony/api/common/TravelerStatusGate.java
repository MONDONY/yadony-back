package com.yadony.api.common;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.config.PlatformSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Garde de publication : quand l'interrupteur {@code traveler_status_required} est actif, un voyageur
 * qui n'a pas déclaré son statut légal ne peut pas publier de trajet. Partagé par tous les chemins qui
 * créent un trajet visible des expéditeurs ({@code AnnouncementService}, et le trajet dédié créé par
 * {@code NegotiationService}). Ne s'applique jamais à un brouillon : l'appelant ne le consulte pas.
 */
@Component
public class TravelerStatusGate {

    private final PlatformSettingsService settings;

    public TravelerStatusGate(PlatformSettingsService settings) {
        this.settings = settings;
    }

    /** @throws YadonyBusinessException 403 {@code traveler-status-required} si le statut manque et que l'interrupteur est actif */
    public void assertDeclaredIfRequired(UserEntity user) {
        if (settings.travelerStatusRequired() && user.getTravelerStatus() == null) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "traveler-status-required",
                    "Traveler Status Required",
                    "Indiquez si vous voyagez en tant que particulier ou professionnel.");
        }
    }
}
