package com.yadony.api.requests.entity;

import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Negotiation entity accessors")
class NegotiationEntityTest {

    @Test
    @DisplayName("NegotiationThreadEntity getters retournent les valeurs setées")
    void negotiationThreadEntity_getters() {
        NegotiationThreadEntity e = new NegotiationThreadEntity();
        UUID pkgId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID annId = UUID.randomUUID();

        assertThat(e.getCurrency()).isEqualTo("EUR");

        e.setPackageRequestId(pkgId);
        e.setTravelerId(travelerId);
        e.setTravelerAnnouncementId(annId);
        e.setTravelerTravelDate(LocalDate.now());
        e.setTravelerAvailableKg(new BigDecimal("10"));
        e.setCurrency("CAD");
        e.setStatus(NegotiationThreadStatus.OPEN);
        e.setCurrentPriceEur(new BigDecimal("35"));
        e.setRoundsCount((short) 2);
        e.setLastActivityAt(LocalDateTime.now());
        e.setPaymentIntentId("pi_test");
        e.setPaymentMethod(PaymentMethod.STRIPE);

        assertThat(e.getPackageRequestId()).isEqualTo(pkgId);
        assertThat(e.getTravelerId()).isEqualTo(travelerId);
        assertThat(e.getTravelerAnnouncementId()).isEqualTo(annId);
        assertThat(e.getCurrency()).isEqualTo("CAD");
        assertThat(e.getStatus()).isEqualTo(NegotiationThreadStatus.OPEN);
        assertThat(e.getCurrentPriceEur()).isEqualByComparingTo("35");
        assertThat(e.getRoundsCount()).isEqualTo((short) 2);
        assertThat(e.getPaymentIntentId()).isEqualTo("pi_test");
        assertThat(e.getPaymentMethod()).isEqualTo(PaymentMethod.STRIPE);
    }

    @Test
    @DisplayName("NegotiationMessageEntity.create() initialise les champs correctement")
    void negotiationMessageEntity_createFactory() {
        UUID threadId = UUID.randomUUID();
        UUID fromUserId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("40");

        NegotiationMessageEntity msg = NegotiationMessageEntity.create(
            threadId, fromUserId, NegotiationMessageKind.PROPOSAL, price, "Ma proposition");

        assertThat(msg.getThreadId()).isEqualTo(threadId);
        assertThat(msg.getFromUserId()).isEqualTo(fromUserId);
        assertThat(msg.getKind()).isEqualTo(NegotiationMessageKind.PROPOSAL);
        assertThat(msg.getProposedPriceEur()).isEqualByComparingTo("40");
        assertThat(msg.getBody()).isEqualTo("Ma proposition");
    }

    @Test
    @DisplayName("NegotiationMessageEntity setters permettent la mise à jour des champs")
    void negotiationMessageEntity_setters() {
        NegotiationMessageEntity msg = new NegotiationMessageEntity();
        UUID threadId = UUID.randomUUID();
        UUID fromUserId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        msg.setThreadId(threadId);
        msg.setFromUserId(fromUserId);
        msg.setKind(NegotiationMessageKind.COUNTER);
        msg.setProposedPriceEur(new BigDecimal("28"));
        msg.setBody("Contre-offre");
        msg.setCreatedAt(now);

        assertThat(msg.getThreadId()).isEqualTo(threadId);
        assertThat(msg.getFromUserId()).isEqualTo(fromUserId);
        assertThat(msg.getKind()).isEqualTo(NegotiationMessageKind.COUNTER);
        assertThat(msg.getProposedPriceEur()).isEqualByComparingTo("28");
        assertThat(msg.getBody()).isEqualTo("Contre-offre");
        assertThat(msg.getCreatedAt()).isEqualTo(now);
    }
}
