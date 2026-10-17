package com.yadony.api.tracking;

import com.yadony.api.common.assistant.ParcelFacts;
import com.yadony.api.common.assistant.ParcelLink;
import com.yadony.api.common.assistant.ParcelRef;
import com.yadony.api.common.assistant.ParcelRole;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PickupCodeFactsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 17, 12, 0);
    private final BidRepository bids = mock(BidRepository.class);
    private final PickupCodeFacts facts = new PickupCodeFacts(bids,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));

    private ParcelFacts factsFor(BidEntity bid, ParcelRole role) {
        when(bids.findById(any())).thenReturn(Optional.of(bid));
        return facts.facts(new ParcelRef(UUID.randomUUID(), UUID.randomUUID(), role, bid.getStatus().name(),
                null, null, null, null, true));
    }

    private static BidEntity inHands(String code, LocalDateTime expiry) {
        BidEntity b = new BidEntity();
        b.setStatus(BidStatus.IN_TRANSIT);
        b.setConfirmationCode(code);
        b.setConfirmationCodeExpiry(expiry);
        return b;
    }

    @Test
    void codeValideJamaisRecopie() {
        BidEntity b = inHands("482913", NOW.plusDays(3));
        b.setConfirmationCodeAttempts(1);
        for (ParcelRole role : ParcelRole.values()) {
            assertThat(String.join(" ", factsFor(b, role).lines())).doesNotContain("482913").contains("valid");
        }
        assertThat(factsFor(b, ParcelRole.TRAVELER).lines().get(0)).contains("1 of 3");
    }

    @Test
    void codeBloqueOuExpireLExpediteurPeutRegenerer() {
        ParcelFacts sender = factsFor(inHands(null, null), ParcelRole.SENDER);
        assertThat(sender.lines()).anyMatch(l -> l.contains("no longer usable") && l.contains("Regenerate"))
                .anyMatch(l -> l.contains("5 time(s)"));
        assertThat(sender.links()).containsExactly(ParcelLink.RENEW_PICKUP_CODE);

        ParcelFacts traveler = factsFor(inHands("482913", NOW.minusMinutes(1)), ParcelRole.TRAVELER);
        assertThat(traveler.lines().get(0)).contains("no longer usable", "15 minutes");
        assertThat(traveler.links()).isEmpty();
        assertThat(factsFor(inHands(null, null), ParcelRole.RECIPIENT).lines().get(0)).contains("sender must");
    }

    @Test
    void limiteDeRegenerationAtteinteRetireLeBouton() {
        BidEntity b = inHands(null, null);
        b.setConfirmationCodeRefreshWindowStart(NOW.minusHours(2));
        b.setConfirmationCodeRefreshCount(5);
        ParcelFacts f = factsFor(b, ParcelRole.SENDER);
        assertThat(f.lines()).anyMatch(l -> l.contains("reached the limit of 5"));
        assertThat(f.links()).isEmpty();

        b.setConfirmationCodeRefreshWindowStart(NOW.minusHours(25));
        assertThat(PickupCodeFacts.refreshesLeft(b, NOW)).isEqualTo(5);
        b.setConfirmationCodeRefreshWindowStart(NOW.minusHours(1));
        b.setConfirmationCodeRefreshCount(2);
        assertThat(PickupCodeFacts.refreshesLeft(b, NOW)).isEqualTo(3);
    }

    @Test
    void avantLaRemiseLeCodeNExistePasEncore() {
        BidEntity b = new BidEntity();
        b.setStatus(BidStatus.ACCEPTED);
        assertThat(factsFor(b, ParcelRole.SENDER).lines().get(0)).contains("does not exist yet");
        assertThat(factsFor(b, ParcelRole.TRAVELER).lines().get(0)).contains("never receives it in advance");
        assertThat(factsFor(b, ParcelRole.RECIPIENT).lines().get(0)).contains("Receptions page");
    }

    @Test
    void colisLivreOuInconnuSansFait() {
        BidEntity b = new BidEntity();
        b.setStatus(BidStatus.COMPLETED);
        assertThat(factsFor(b, ParcelRole.SENDER).lines()).isEmpty();
        when(bids.findById(any())).thenReturn(Optional.empty());
        assertThat(facts.facts(new ParcelRef(UUID.randomUUID(), UUID.randomUUID(), ParcelRole.SENDER, "COMPLETED",
                null, null, null, null, false))).isEqualTo(ParcelFacts.NONE);
    }
}
