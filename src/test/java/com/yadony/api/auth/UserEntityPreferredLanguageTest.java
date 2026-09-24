package com.yadony.api.auth;

import com.yadony.api.common.i18n.AppLanguage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** Colonne {@code preferred_language} (V264) : défaut FR, aller-retour EN, repli FR. */
class UserEntityPreferredLanguageTest {

    @Test
    void defautFrSansAucunReglage() {
        assertThat(new UserEntity().getPreferredLanguage()).isEqualTo(AppLanguage.FR);
    }

    @Test
    void allerRetourEn() {
        UserEntity user = new UserEntity();
        user.setPreferredLanguage(AppLanguage.EN);

        assertThat(user.getPreferredLanguage()).isEqualTo(AppLanguage.EN);
    }

    @Test
    void allerRetourFrApresEn() {
        UserEntity user = new UserEntity();
        user.setPreferredLanguage(AppLanguage.EN);
        user.setPreferredLanguage(AppLanguage.FR);

        assertThat(user.getPreferredLanguage()).isEqualTo(AppLanguage.FR);
    }

    @Test
    void valeurInconnueEnColonneRendFr() {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "preferredLanguage", "de");

        assertThat(user.getPreferredLanguage()).isEqualTo(AppLanguage.FR);
    }
}
