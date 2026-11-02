package com.yadony.api.matching;

import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMethodFilterTest {

    @Test
    void parse_acceptsCardAliasCommaListsAndCase() {
        assertThat(PaymentMethodFilter.parse(List.of("card,cash", " MOBILE_MONEY ")))
                .containsExactlyInAnyOrder(PaymentMethod.STRIPE, PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY);
        assertThat(PaymentMethodFilter.parse(List.of("STRIPE"))).containsExactly(PaymentMethod.STRIPE);
    }

    @Test
    void parse_ignoresUnknownRetiredAndNull() {
        assertThat(PaymentMethodFilter.parse(null)).isEmpty();
        assertThat(PaymentMethodFilter.parse(Arrays.asList("WAVE", "ORANGE_MONEY", "BITCOIN", null, "")))
                .isEmpty();
    }

    @Test
    void extras_cacheKey_isOrderIndependent() {
        var a = new AnnouncementSearchExtras(0, PaymentMethodFilter.parse(List.of("CASH,CARD")));
        var b = new AnnouncementSearchExtras(0, PaymentMethodFilter.parse(List.of("STRIPE", "CASH")));
        assertThat(a.cacheKey()).isEqualTo(b.cacheKey());
        assertThat(AnnouncementSearchExtras.NONE.cacheKey()).isNotEqualTo(a.cacheKey());
        assertThat(new AnnouncementSearchExtras(null, null).paymentMethods()).isEmpty();
    }

    @Test
    void extras_publicView_aSaPropreCleDeCacheEtResteFauxParDefaut() {
        // La recherche publique saute le bloc 2 et les conversions : jamais la même entrée
        // de cache qu'une recherche de l'app aux mêmes critères.
        assertThat(AnnouncementSearchExtras.PUBLIC_VIEW.publicView()).isTrue();
        assertThat(AnnouncementSearchExtras.PUBLIC_VIEW.cacheKey())
                .isNotEqualTo(AnnouncementSearchExtras.NONE.cacheKey());
        assertThat(AnnouncementSearchExtras.NONE.publicView()).isFalse();
        assertThat(new AnnouncementSearchExtras(1, Set.of(), true).publicView()).isFalse();
        assertThat(new AnnouncementSearchExtras(1, null).publicView()).isFalse();
    }
}
