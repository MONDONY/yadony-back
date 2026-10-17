package com.yadony.api.gppro.events;

import java.util.UUID;

/** Le badge GP Pro vient d'être attribué ({@code gpPro = true}) ou retiré. */
public record GpProStatusChangedEvent(UUID userId, boolean gpPro) {
}
