package com.yadony.api.common.i18n;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MessagesTest {

    private StaticMessageSource source;

    @BeforeEach
    void setUp() {
        source = new StaticMessageSource();
        source.setUseCodeAsDefaultMessage(false);
        source.setAlwaysUseMessageFormat(true);
        source.addMessage("greeting.app", Locale.FRENCH, "l''app");
        source.addMessage("greeting.app", Locale.ENGLISH, "l''app");
        source.addMessage("amount.value", Locale.FRENCH, "{0}");
        source.addMessage("amount.value", Locale.ENGLISH, "{0}");
        source.addMessage("parcel.count.one", Locale.FRENCH, "{0} colis");
        source.addMessage("parcel.count.other", Locale.FRENCH, "{0} colis");
        source.addMessage("parcel.count.one", Locale.ENGLISH, "{0} parcel");
        source.addMessage("parcel.count.other", Locale.ENGLISH, "{0} parcels");
    }

    @Test
    void get_desechappeLApostropheDoublee() {
        Messages messages = new Messages(source, AppLanguage.FR);
        assertThat(messages.get("greeting.app")).isEqualTo("l'app");
    }

    @Test
    void get_argumentNumberRenduSansGroupement() {
        Messages messages = new Messages(source, AppLanguage.FR);
        assertThat(messages.get("amount.value", 1250)).isEqualTo("1250");
    }

    @Test
    void plural_choisitOneOuOtherEnFrancais() {
        Messages messages = new Messages(source, AppLanguage.FR);
        assertThat(messages.plural("parcel.count", 0, 0)).isEqualTo("0 colis");
        assertThat(messages.plural("parcel.count", 1, 1)).isEqualTo("1 colis");
        assertThat(messages.plural("parcel.count", 2, 2)).isEqualTo("2 colis");
    }

    @Test
    void plural_choisitOneOuOtherEnAnglais() {
        Messages messages = new Messages(source, AppLanguage.EN);
        assertThat(messages.plural("parcel.count", 0, 0)).isEqualTo("0 parcels");
        assertThat(messages.plural("parcel.count", 1, 1)).isEqualTo("1 parcel");
        assertThat(messages.plural("parcel.count", 2, 2)).isEqualTo("2 parcels");
    }

    @Test
    void locale_suitLaLangue() {
        assertThat(new Messages(source, AppLanguage.FR).locale()).isEqualTo(Locale.FRENCH);
        assertThat(new Messages(source, AppLanguage.EN).locale()).isEqualTo(Locale.ENGLISH);
    }
}
