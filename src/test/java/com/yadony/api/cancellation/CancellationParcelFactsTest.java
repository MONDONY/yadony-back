package com.yadony.api.cancellation;

import com.yadony.api.common.assistant.ParcelFacts;
import com.yadony.api.common.assistant.ParcelLink;
import com.yadony.api.common.assistant.ParcelRef;
import com.yadony.api.common.assistant.ParcelRole;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CancellationParcelFactsTest {

    private final BidRepository bids = mock(BidRepository.class);
    private final AnnouncementRepository announcements = mock(AnnouncementRepository.class);
    private final CancellationRepository cancellations = mock(CancellationRepository.class);
    private final CancellationParcelFacts facts = new CancellationParcelFacts(bids, announcements, cancellations,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));

    private ParcelFacts factsFor(BidEntity bid, ParcelRole role) {
        when(bids.findById(any())).thenReturn(Optional.of(bid));
        return facts.facts(new ParcelRef(UUID.randomUUID(), UUID.randomUUID(), role, bid.getStatus().name(),
                null, null, null, null, true));
    }

    private BidEntity bid(BidStatus status, LocalDate departure) {
        BidEntity b = new BidEntity();
        b.setStatus(status);
        b.setAnnouncementId(UUID.randomUUID());
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureDate(departure);
        when(announcements.findById(any())).thenReturn(Optional.of(a));
        return b;
    }

    @Test
    void annulationSelonLEtape() {
        LocalDate future = LocalDate.now().plusDays(10);
        assertThat(factsFor(bid(BidStatus.ACCEPTED, future), ParcelRole.SENDER).lines())
                .containsExactly("Cancellation is still possible from the parcel page.");
        assertThat(factsFor(bid(BidStatus.HANDED_OVER, future), ParcelRole.TRAVELER).lines().get(0))
                .contains("until departure", "return", "3 days");
        assertThat(factsFor(bid(BidStatus.HANDED_OVER, LocalDate.now().minusDays(2)), ParcelRole.SENDER).lines().get(0))
                .startsWith("Cancellation is no longer possible");
        assertThat(factsFor(bid(BidStatus.IN_TRANSIT, future), ParcelRole.SENDER).lines().get(0))
                .startsWith("Cancellation is no longer possible");
        assertThat(factsFor(bid(BidStatus.ARRIVED, future), ParcelRole.RECIPIENT).lines())
                .containsExactly("Only the sender or the traveler can cancel this parcel.");
        assertThat(factsFor(bid(BidStatus.COMPLETED, future), ParcelRole.SENDER).lines()).isEmpty();
    }

    @Test
    void retourEnCoursSansJamaisLeCode() {
        BidEntity b = bid(BidStatus.CANCELLED, null);
        b.setReturnCode("123456");
        b.setReturnDeadline(LocalDateTime.of(2026, 10, 19, 10, 0));
        b.setReturnCodeExpiry(LocalDateTime.of(2026, 10, 19, 10, 0));
        ParcelFacts sender = factsFor(b, ParcelRole.SENDER);
        assertThat(String.join(" ", sender.lines())).contains("before 2026-10-19", "holds the return code")
                .doesNotContain("123456");
        assertThat(sender.links()).isEmpty();
        assertThat(factsFor(b, ParcelRole.TRAVELER).lines().get(0)).contains("enter the sender's return code");
        assertThat(factsFor(b, ParcelRole.RECIPIENT).lines()).containsExactly("The parcel is being returned to the sender.");
    }

    @Test
    void codeDeRetourExpireOuBloqueRenvoieVersLEquipe() {
        BidEntity expired = bid(BidStatus.CANCELLED, null);
        expired.setReturnDeadline(LocalDateTime.of(2026, 10, 16, 10, 0));
        expired.setReturnCodeExpiry(LocalDateTime.of(2026, 10, 16, 10, 0));
        ParcelFacts f = factsFor(expired, ParcelRole.SENDER);
        assertThat(f.lines()).anyMatch(l -> l.contains("expired"));
        assertThat(f.links()).containsExactly(ParcelLink.SUPPORT);

        BidEntity blocked = bid(BidStatus.CANCELLED, null);
        blocked.setReturnDeadline(LocalDateTime.of(2026, 10, 19, 10, 0));
        blocked.setReturnCodeAttempts(3);
        assertThat(factsFor(blocked, ParcelRole.TRAVELER).lines()).anyMatch(l -> l.contains("blocked after 3"));
    }

    @Test
    void colisRendu() {
        BidEntity b = bid(BidStatus.CANCELLED, null);
        b.setReturnDeadline(LocalDateTime.of(2026, 10, 19, 10, 0));
        b.setReturnedAt(LocalDateTime.of(2026, 10, 18, 9, 0));
        assertThat(factsFor(b, ParcelRole.SENDER).lines())
                .containsExactly("The parcel was returned to the sender on 2026-10-18.");
    }

    @Test
    void absencesDeclareesEtCeQueLeDestinataireEnApprend() {
        BidEntity b = bid(BidStatus.NO_SHOW, null);
        CancellationEntity sender = new CancellationEntity();
        sender.setReason(NoShowReasons.SENDER_NO_SHOW);
        sender.setScope(CancellationScope.HANDOVER);
        sender.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        sender.setContestationDeadline(OffsetDateTime.parse("2026-10-18T10:00:00Z"));
        CancellationEntity recipient = new CancellationEntity();
        recipient.setReason(DeliveryNoShowTypes.REASON_RECIPIENT_NO_SHOW);
        recipient.setScope(CancellationScope.DELIVERY);
        recipient.setNoShowStatus(CancellationStatus.CONTESTED);
        CancellationEntity plain = new CancellationEntity();
        plain.setReason("Changement de programme");
        when(cancellations.findAllByBidId(any())).thenReturn(List.of(sender, recipient, plain));

        assertThat(factsFor(b, ParcelRole.SENDER).lines()).containsExactly(
                "The sender was declared absent by the traveler at handover; it can be contested until 2026-10-18 "
                        + "from the parcel page.",
                "The recipient was declared absent by the traveler at delivery; it was contested, so a dispute is "
                        + "open and the team decides.");
        assertThat(factsFor(b, ParcelRole.RECIPIENT).lines()).hasSize(1).allMatch(l -> l.startsWith("The recipient"));

        recipient.setNoShowStatus(CancellationStatus.RESOLVED);
        sender.setNoShowStatus(CancellationStatus.CONFIRMED);
        assertThat(factsFor(b, ParcelRole.TRAVELER).lines()).anyMatch(l -> l.endsWith("the absence is confirmed."))
                .anyMatch(l -> l.endsWith("the team has made its decision."));
    }

    @Test
    void colisInconnu() {
        when(bids.findById(any())).thenReturn(Optional.empty());
        assertThat(facts.facts(new ParcelRef(UUID.randomUUID(), UUID.randomUUID(), ParcelRole.SENDER, "CANCELLED",
                null, null, null, null, false))).isEqualTo(ParcelFacts.NONE);
    }
}
