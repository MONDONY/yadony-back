package com.yadony.api.dac7;

import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Interrupteurs DAC7 : les messages exigent la collecte")
class PlatformDac7SwitchesTest {

    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final PlatformDac7Switches switches = new PlatformDac7Switches(settings);

    @Test
    @DisplayName("relus dans les réglages à chaque appel : un changement du back-office s'applique tout de suite")
    void relusAChaqueAppel() {
        when(settings.dac7Enabled()).thenReturn(false, true);
        when(settings.dac7RemindersEnabled()).thenReturn(true);

        assertThat(switches.collectionEnabled()).isFalse();
        assertThat(switches.collectionEnabled()).isTrue();
    }

    @Test
    @DisplayName("messages : vrais seulement si la collecte ET les messages sont allumés")
    void messagesExigentLaCollecte() {
        when(settings.dac7Enabled()).thenReturn(false);
        when(settings.dac7RemindersEnabled()).thenReturn(true);
        assertThat(switches.remindersEnabled()).isFalse();

        when(settings.dac7Enabled()).thenReturn(true);
        assertThat(switches.remindersEnabled()).isTrue();

        when(settings.dac7RemindersEnabled()).thenReturn(false);
        assertThat(switches.remindersEnabled()).isFalse();
    }

    @Test
    @DisplayName("valeurs fixes de test : mêmes règles")
    void fixed() {
        assertThat(Dac7Switches.fixed(true, true).remindersEnabled()).isTrue();
        assertThat(Dac7Switches.fixed(false, true).remindersEnabled()).isFalse();
        assertThat(Dac7Switches.fixed(true, false).remindersEnabled()).isFalse();
        assertThat(Dac7Switches.fixed(true, false).collectionEnabled()).isTrue();
        assertThat(Dac7Switches.fixed(false, false).collectionEnabled()).isFalse();
    }
}
