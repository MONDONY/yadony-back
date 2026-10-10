package com.yadony.api.admin.dto;

import com.yadony.api.admin.AdminAlertEntity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code paymentId} exposé au back-office pour afficher « Resynchroniser » / « Libérer » sur une alerte. */
class AdminAlertResponsePaymentIdTest {

    private static AdminAlertEntity alert(String type, String payload) {
        AdminAlertEntity a = new AdminAlertEntity();
        a.setType(type);
        a.setPayload(payload);
        return a;
    }

    @Test
    void reconStripe_takesTheReferenceSuffix() {
        UUID id = UUID.randomUUID();
        assertThat(AdminAlertResponse.from(alert("RECON_STRIPE_" + id, "{\"ecart\":\"SEQUESTRE_NON_CAPTURE\"}")).paymentId())
                .isEqualTo(id);
    }

    @Test
    void payloadPaymentId_wins_forJ48Timeout() {
        UUID id = UUID.randomUUID();
        assertThat(AdminAlertResponse.from(alert("ESCROW_J48_TIMEOUT", "{\"paymentId\":\"" + id + "\"}")).paymentId())
                .isEqualTo(id);
    }

    @Test
    void captureFailed_takesTheSuffix() {
        UUID id = UUID.randomUUID();
        assertThat(AdminAlertResponse.from(alert("ESCROW_CAPTURE_FAILED_" + id, "{}")).paymentId()).isEqualTo(id);
    }

    @Test
    void commissionOrTopupReferences_areNotPayments() {
        UUID bid = UUID.randomUUID();
        assertThat(AdminAlertResponse.paymentIdOf("RECON_STRIPE_" + bid, Map.of("ecart", "COMMISSION_NON_ENCAISSEE"))).isNull();
        assertThat(AdminAlertResponse.paymentIdOf("RECON_STRIPE_pi_123", Map.of("ecart", "RECHARGE_NON_CREDITEE"))).isNull();
        assertThat(AdminAlertResponse.paymentIdOf("ESCROW_J48_TIMEOUT", Map.of("paymentId", "pas-un-uuid"))).isNull();
        assertThat(AdminAlertResponse.paymentIdOf(null, Map.of())).isNull();
        assertThat(AdminAlertResponse.from(alert("OTHER", null)).paymentId()).isNull();
    }
}
