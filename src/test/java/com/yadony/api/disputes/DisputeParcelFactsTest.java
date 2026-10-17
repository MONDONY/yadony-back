package com.yadony.api.disputes;

import com.yadony.api.common.assistant.ParcelFacts;
import com.yadony.api.common.assistant.ParcelLink;
import com.yadony.api.common.assistant.ParcelRef;
import com.yadony.api.common.assistant.ParcelRole;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DisputeParcelFactsTest {

    private final DisputeRepository disputes = mock(DisputeRepository.class);
    private final DisputeParcelFacts facts = new DisputeParcelFacts(disputes);

    private static ParcelRef ref(ParcelRole role) {
        return new ParcelRef(UUID.randomUUID(), UUID.randomUUID(), role, "ARRIVED", null, null, null, null, true);
    }

    @Test
    void litigeOuvertGeleOuTranche() {
        DisputeEntity open = new DisputeEntity();
        open.setStatus(DisputeTypes.STATUS_OPEN);
        open.setRefundFrozen(true);
        DisputeEntity resolved = new DisputeEntity();
        resolved.setStatus(DisputeTypes.STATUS_RESOLVED);
        resolved.setResolvedAt(OffsetDateTime.parse("2026-10-15T10:00:00Z"));
        when(disputes.findByBidIdIn(any())).thenReturn(List.of(open, resolved));
        ParcelFacts f = facts.facts(ref(ParcelRole.SENDER));
        assertThat(f.lines()).containsExactly(
                "A dispute is open on this parcel and the team is reviewing it; it can be followed in My disputes. "
                        + "Refunds and payouts for this parcel are frozen until the decision.",
                "A dispute on this parcel was decided by the team on 2026-10-15; the decision is in My disputes.");
        assertThat(f.links()).containsExactly(ParcelLink.DISPUTES);
    }

    @Test
    void sansLitigeNiPourLeDestinataire() {
        when(disputes.findByBidIdIn(any())).thenReturn(List.of());
        assertThat(facts.facts(ref(ParcelRole.TRAVELER)).lines()).isEmpty();
        DisputeParcelFacts other = new DisputeParcelFacts(mock(DisputeRepository.class));
        assertThat(other.facts(ref(ParcelRole.RECIPIENT))).isEqualTo(ParcelFacts.NONE);
    }

    @Test
    void leDestinataireNInterrogeMemePasLesLitiges() {
        DisputeRepository repo = mock(DisputeRepository.class);
        new DisputeParcelFacts(repo).facts(ref(ParcelRole.RECIPIENT));
        verifyNoInteractions(repo);
    }
}
