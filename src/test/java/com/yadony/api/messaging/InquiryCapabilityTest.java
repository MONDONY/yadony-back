package com.yadony.api.messaging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/** Activation explicite de la messagerie avant réservation par l'en-tête X-Dony-Capabilities. */
class InquiryCapabilityTest {

    private final InquiryCapability capability = new InquiryCapability();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void request(String header) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (header != null) {
            req.addHeader("X-Dony-Capabilities", header);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    void enabled_whenTheListContainsInquiry() {
        request("inquiry");
        assertThat(capability.enabled()).isTrue();
        request("foo, Inquiry ,bar");
        assertThat(capability.enabled()).isTrue();
    }

    @Test
    void disabled_withoutHeader_orWithOtherValues() {
        request(null);
        assertThat(capability.enabled()).isFalse();
        request("foo,bar");
        assertThat(capability.enabled()).isFalse();
        request("inquiry2,preinquiry");
        assertThat(capability.enabled()).isFalse();
        request("");
        assertThat(capability.enabled()).isFalse();
    }

    @Test
    void disabled_outsideOfARequest() {
        RequestContextHolder.resetRequestAttributes();
        assertThat(capability.enabled()).isFalse();
    }
}
