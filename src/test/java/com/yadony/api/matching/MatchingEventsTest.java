package com.yadony.api.matching;

import com.yadony.api.matching.events.AnnouncementInProgressEvent;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.matching.events.BidCreatedEvent;
import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import com.yadony.api.matching.events.HandoverAlertEvent;
import com.yadony.api.matching.events.ParcelRefusedEvent;
import com.yadony.api.matching.events.VoyageurNoShowEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingEventsTest {

    @Test
    void bidAcceptedEvent_getters_returnConstructorValues() {
        UUID bidId = UUID.randomUUID(), senderId = UUID.randomUUID(),
                travelerId = UUID.randomUUID(), announcementId = UUID.randomUUID();
        BidAcceptedEvent e = new BidAcceptedEvent(bidId, senderId, travelerId, announcementId);
        assertThat(e.getAnnouncementId()).isEqualTo(announcementId);
    }

    @Test
    void bidCreatedEvent_getters_returnConstructorValues() {
        UUID bidId = UUID.randomUUID(), annId = UUID.randomUUID(),
                travId = UUID.randomUUID(), sendId = UUID.randomUUID();
        BidCreatedEvent e = new BidCreatedEvent(bidId, annId, travId, sendId,
                "Alice", BigDecimal.valueOf(5), "Paris-Dakar");
        assertThat(e.getSenderId()).isEqualTo(sendId);
    }

    @Test
    void voyageurNoShowEvent_getters_returnConstructorValues() {
        UUID bidId = UUID.randomUUID(), travId = UUID.randomUUID(), sendId = UUID.randomUUID();
        VoyageurNoShowEvent e = new VoyageurNoShowEvent(bidId, travId, sendId, 2);
        assertThat(e.getSenderId()).isEqualTo(sendId);
        assertThat(e.getNoShowCount()).isEqualTo(2);
    }

    @Test
    void handoverAlertEvent_fieldsAccessible() {
        UUID bidId = UUID.randomUUID(), sendId = UUID.randomUUID();
        LocalDateTime start = LocalDateTime.now();
        HandoverAlertEvent e = new HandoverAlertEvent(bidId, sendId, "CDG", start.plusHours(2));
        assertThat(e.bidId()).isEqualTo(bidId);
        assertThat(e.senderId()).isEqualTo(sendId);
    }

    @Test
    void bidExpiredOnDepartureEvent_getters_returnConstructorValues() {
        UUID bidId = UUID.randomUUID(), sendId = UUID.randomUUID(),
                annId = UUID.randomUUID(), travId = UUID.randomUUID();
        BidExpiredOnDepartureEvent e = new BidExpiredOnDepartureEvent(bidId, sendId, annId, travId);
        assertThat(e.getBidId()).isEqualTo(bidId);
        assertThat(e.getSenderId()).isEqualTo(sendId);
        assertThat(e.getAnnouncementId()).isEqualTo(annId);
        assertThat(e.getTravelerId()).isEqualTo(travId);
    }

    @Test
    void parcelRefusedEvent_getters_returnConstructorValues() {
        UUID bidId = UUID.randomUUID(), travId = UUID.randomUUID(), sendId = UUID.randomUUID();
        ParcelRefusedEvent e = new ParcelRefusedEvent(bidId, travId, sendId, "damaged");
        assertThat(e.getTravelerId()).isEqualTo(travId);
        assertThat(e.getSenderId()).isEqualTo(sendId);
        assertThat(e.getReason()).isEqualTo("damaged");
    }

    @Test
    void announcementInProgressEvent_getters_returnConstructorValues() {
        UUID annId = UUID.randomUUID(), travId = UUID.randomUUID();
        AnnouncementInProgressEvent e = new AnnouncementInProgressEvent(annId, travId);
        assertThat(e.getAnnouncementId()).isEqualTo(annId);
        assertThat(e.getTravelerId()).isEqualTo(travId);
    }
}
