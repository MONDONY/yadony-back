package com.yadony.api.payments.wallet.topupcode;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopupCodeHasherTest {

    private static final String KEY = "unit-test-key-0123456789abcdef0123456789";

    @Test
    void hash_stableHexa64_etDependantDeLaCle() {
        TopupCodeHasher hasher = new TopupCodeHasher(KEY);
        String hash = hasher.hash("YD-ABCD-EFGH-JKMN");

        assertThat(hash).matches("[0-9a-f]{64}").isEqualTo(hasher.hash("YD-ABCD-EFGH-JKMN"));
        assertThat(hasher.hash("YD-ABCD-EFGH-JKMP")).isNotEqualTo(hash);
        assertThat(new TopupCodeHasher(KEY.replace('0', '9')).hash("YD-ABCD-EFGH-JKMN")).isNotEqualTo(hash);
    }

    @Test
    void cleAbsente_horsDevTest_refusDeDemarrer() {
        for (String profile : new String[] {"prod", "staging"}) {
            assertThatThrownBy(() -> new TopupCodeHasher("", profile))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TOPUP_CODE_HMAC_KEY");
        }
        assertThatThrownBy(() -> new TopupCodeHasher(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cleAbsente_enDevTestE2e_cleDeRepli() {
        for (String profile : new String[] {"dev", "test", "e2e"}) {
            assertThat(new TopupCodeHasher("  ", profile).hash("CODE"))
                    .isEqualTo(new TopupCodeHasher(TopupCodeHasher.FALLBACK_KEY).hash("CODE"));
        }
    }

    @Test
    void cleTropCourte_refusMemeEnDev() {
        assertThatThrownBy(() -> new TopupCodeHasher("trop-courte", "dev"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void constructeurSpring_litLesProfilsActifs() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThatThrownBy(() -> new TopupCodeHasher("", env)).isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles("test");
        assertThat(new TopupCodeHasher("", env).hash("X")).hasSize(64);
    }

    @Test
    void indice_4CaracteresSurUnCodeLong_2SurUnCodeCourt_jamaisLeCodeEntier() {
        assertThat(TopupCodeHasher.hint("YD-ABCD-EFGH-JKMN")).isEqualTo("JKMN");
        assertThat(TopupCodeHasher.hint("YD-ABCD-EFGH")).isEqualTo("GH");
        assertThat(TopupCodeHasher.hint("DAKAR2000")).isEqualTo("00");
        assertThat(TopupCodeHasher.hint("ABCD")).isEqualTo("CD");
        assertThat(TopupCodeHasher.hint("A")).isEqualTo("A");
    }
}
