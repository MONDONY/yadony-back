package com.yadony.api.disputes;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeDeletionImpactContributorTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();

    @Mock DisputeRepository disputeRepository;

    private DisputeEntity dispute(UUID senderId, UUID travelerId, String status) {
        DisputeEntity d = new DisputeEntity();
        ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        d.setSenderId(senderId);
        d.setTravelerId(travelerId);
        d.setStatus(status);
        return d;
    }

    @Test
    @DisplayName("un litige ouvert nomme l'autre partie, jamais le compte supprimé")
    void openDispute_namesTheOtherParty() {
        when(disputeRepository.findBySenderIdOrTravelerIdOrderByCreatedAtDesc(USER_ID, USER_ID))
                .thenReturn(List.of(dispute(USER_ID, OTHER_ID, "OPEN")));

        List<ImpactFinding> findings =
                new DisputeDeletionImpactContributor(disputeRepository).contribute(USER_ID);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().severity()).isEqualTo(ImpactSeverity.WARNING);
        assertThat(findings.getFirst().code()).isEqualTo("OPEN_DISPUTE");
        assertThat(findings.getFirst().affectedParties())
                .extracting(ImpactFinding.AffectedParty::userId)
                .containsExactly(OTHER_ID);
    }

    @Test
    @DisplayName("un litige résolu n'est pas rapporté")
    void resolvedDispute_isIgnored() {
        when(disputeRepository.findBySenderIdOrTravelerIdOrderByCreatedAtDesc(USER_ID, USER_ID))
                .thenReturn(List.of(dispute(USER_ID, OTHER_ID, "RESOLVED")));

        assertThat(new DisputeDeletionImpactContributor(disputeRepository).contribute(USER_ID))
                .isEmpty();
    }

    // Un litige dont les deux parties sont le même compte n'a pas de contrepartie à nommer,
    // mais il doit rester compté : le taire ferait disparaître le litige de l'écran.
    @Test
    @DisplayName("un litige sans contrepartie identifiable reste compté")
    void disputeWithoutCounterparty_isStillCounted() {
        when(disputeRepository.findBySenderIdOrTravelerIdOrderByCreatedAtDesc(USER_ID, USER_ID))
                .thenReturn(List.of(dispute(USER_ID, null, "OPEN")));

        List<ImpactFinding> findings =
                new DisputeDeletionImpactContributor(disputeRepository).contribute(USER_ID);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().count()).isEqualTo(1);
        assertThat(findings.getFirst().affectedParties()).isEmpty();
    }

    @Test
    @DisplayName("un litige où le compte est des deux côtés n'expose jamais son propre identifiant")
    void disputeWhereAccountIsBothSides_noCounterparty() {
        when(disputeRepository.findBySenderIdOrTravelerIdOrderByCreatedAtDesc(USER_ID, USER_ID))
                .thenReturn(List.of(dispute(USER_ID, USER_ID, "OPEN")));

        List<ImpactFinding> findings =
                new DisputeDeletionImpactContributor(disputeRepository).contribute(USER_ID);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().count()).isEqualTo(1);
        assertThat(findings.getFirst().affectedParties()).isEmpty();
    }
}
