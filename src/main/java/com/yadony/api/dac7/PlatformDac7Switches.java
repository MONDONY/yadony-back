package com.yadony.api.dac7;

import com.yadony.api.config.PlatformSettingsService;
import org.springframework.stereotype.Component;

/** {@link Dac7Switches} lus dans les réglages plateforme (cache en mémoire, relu à chaque appel). */
@Component
public class PlatformDac7Switches implements Dac7Switches {

    private final PlatformSettingsService settings;

    public PlatformDac7Switches(PlatformSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public boolean collectionEnabled() {
        return settings.dac7Enabled();
    }

    @Override
    public boolean remindersEnabled() {
        return settings.dac7Enabled() && settings.dac7RemindersEnabled();
    }
}
