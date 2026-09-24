package com.yadony.api.common.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YadonyLocaleResolverTest {

    private final YadonyLocaleResolver resolver = new YadonyLocaleResolver();

    @Test
    void resolveLocale_francaisSansEnTete() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.FRENCH);
    }

    @Test
    void resolveLocale_anglaisAvecEnTete() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void setLocale_leveUnsupportedOperationException() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThatThrownBy(() -> resolver.setLocale(request, null, Locale.ENGLISH))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
