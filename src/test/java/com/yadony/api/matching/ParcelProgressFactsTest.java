package com.yadony.api.matching;

import com.yadony.api.common.assistant.ParcelFacts;
import com.yadony.api.common.assistant.ParcelRef;
import com.yadony.api.common.assistant.ParcelRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParcelProgressFactsTest {

    private final BidRepository bids = mock(BidRepository.class);
    private final ParcelProgressFacts facts = new ParcelProgressFacts(bids);

    private ParcelRef ref(ParcelRole role, String departure) {
        return new ParcelRef(UUID.randomUUID(), UUID.randomUUID(), role, "COMPLETED", null, departure, "Dakar",
                LocalDate.of(2026, 10, 20), false);
    }

    @Test
    void roleTrajetEtEtape() {
        BidEntity b = new BidEntity();
        b.setStatus(BidStatus.COMPLETED);
        b.markDelivered(LocalDateTime.of(2026, 10, 22, 9, 0));
        when(bids.findById(any())).thenReturn(Optional.of(b));
        ParcelFacts f = facts.facts(ref(ParcelRole.TRAVELER, "Paris"));
        assertThat(f.lines()).containsExactly(
                "The user is the traveler carrying this parcel.",
                "Trip: Paris to Dakar, departure on 2026-10-20.",
                "Current step: delivered to the recipient on 2026-10-22.");
        assertThat(facts.facts(ref(ParcelRole.SENDER, null)).lines())
                .containsExactly("The user is the sender of this parcel.",
                        "Current step: delivered to the recipient on 2026-10-22.");
        assertThat(facts.facts(ref(ParcelRole.RECIPIENT, null)).lines().get(0))
                .isEqualTo("The user is the recipient of this parcel.");
    }

    @ParameterizedTest
    @EnumSource(BidStatus.class)
    void chaqueStatutADesMots(BidStatus status) {
        BidEntity b = new BidEntity();
        b.setStatus(status);
        assertThat(ParcelProgressFacts.step(b)).isNotBlank().endsWith(".");
    }

    @Test
    void colisDisparuRienADire() {
        when(bids.findById(any())).thenReturn(Optional.empty());
        assertThat(facts.facts(ref(ParcelRole.SENDER, "Paris"))).isEqualTo(ParcelFacts.NONE);
    }
}
