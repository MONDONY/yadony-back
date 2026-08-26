package com.yadony.api.matching;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchingDeletionImpactContributorTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;

    private MatchingDeletionImpactContributor contributor() {
        return new MatchingDeletionImpactContributor(bidRepository, announcementRepository);
    }

    private BidRepository.BidCounterparty counterparty(UUID bidId, UUID otherUserId) {
        return new BidRepository.BidCounterparty() {
            @Override public UUID getBidId() { return bidId; }
            @Override public UUID getCounterpartyId() { return otherUserId; }
        };
    }

    private void noBids() {
        lenient().when(bidRepository.findTravelerCounterpartiesForSender(any(), any()))
                .thenReturn(List.of());
        lenient().when(bidRepository.findSenderCounterpartiesForTraveler(any(), any()))
                .thenReturn(List.of());
    }

    private void noAnnouncements() {
        lenient().when(announcementRepository.countByTravelerIdAndStatusIn(any(), any()))
                .thenReturn(0L);
    }

    @Test
    @DisplayName("un colis en transit nomme la contrepartie qui l'attend")
    void parcelInTransit_namesCounterparty() {
        UUID travelerId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        noBids();
        noAnnouncements();
        when(bidRepository.findTravelerCounterpartiesForSender(eq(USER_ID), any()))
                .thenAnswer(inv -> {
                    Collection<BidStatus> statuses = inv.getArgument(1);
                    return statuses.contains(BidStatus.IN_TRANSIT)
                            ? List.of(counterparty(bidId, travelerId)) : List.of();
                });

        List<ImpactFinding> findings = contributor().contribute(USER_ID);

        ImpactFinding transit = findings.stream()
                .filter(f -> f.code().equals("PARCEL_IN_TRANSIT")).findFirst().orElseThrow();
        assertThat(transit.severity()).isEqualTo(ImpactSeverity.WARNING);
        assertThat(transit.affectedParties())
                .containsExactly(new ImpactFinding.AffectedParty(travelerId, bidId));
    }

    @Test
    @DisplayName("les deux rôles sont agrégés dans un seul constat de transit")
    void parcelInTransit_mergesBothRoles() {
        UUID asSenderCounterparty = UUID.randomUUID();
        UUID asTravelerCounterparty = UUID.randomUUID();
        noAnnouncements();
        when(bidRepository.findTravelerCounterpartiesForSender(eq(USER_ID), any()))
                .thenAnswer(inv -> ((Collection<BidStatus>) inv.getArgument(1)).contains(BidStatus.IN_TRANSIT)
                        ? List.of(counterparty(UUID.randomUUID(), asSenderCounterparty)) : List.of());
        when(bidRepository.findSenderCounterpartiesForTraveler(eq(USER_ID), any()))
                .thenAnswer(inv -> ((Collection<BidStatus>) inv.getArgument(1)).contains(BidStatus.IN_TRANSIT)
                        ? List.of(counterparty(UUID.randomUUID(), asTravelerCounterparty)) : List.of());

        ImpactFinding transit = contributor().contribute(USER_ID).stream()
                .filter(f -> f.code().equals("PARCEL_IN_TRANSIT")).findFirst().orElseThrow();

        assertThat(transit.count()).isEqualTo(2);
        assertThat(transit.affectedParties())
                .extracting(ImpactFinding.AffectedParty::userId)
                .containsExactlyInAnyOrder(asSenderCounterparty, asTravelerCounterparty);
    }

    @Test
    @DisplayName("les deux rôles sont agrégés dans un seul constat d'offre en attente")
    void pendingBid_mergesBothRoles() {
        UUID asSenderCounterparty = UUID.randomUUID();
        UUID asTravelerCounterparty = UUID.randomUUID();
        noAnnouncements();
        when(bidRepository.findTravelerCounterpartiesForSender(eq(USER_ID), any()))
                .thenAnswer(inv -> ((Collection<BidStatus>) inv.getArgument(1)).contains(BidStatus.PENDING)
                        ? List.of(counterparty(UUID.randomUUID(), asSenderCounterparty)) : List.of());
        when(bidRepository.findSenderCounterpartiesForTraveler(eq(USER_ID), any()))
                .thenAnswer(inv -> ((Collection<BidStatus>) inv.getArgument(1)).contains(BidStatus.PENDING)
                        ? List.of(counterparty(UUID.randomUUID(), asTravelerCounterparty)) : List.of());

        ImpactFinding pending = contributor().contribute(USER_ID).stream()
                .filter(f -> f.code().equals("PENDING_BID")).findFirst().orElseThrow();

        assertThat(pending.count()).isEqualTo(2);
        assertThat(pending.affectedParties())
                .extracting(ImpactFinding.AffectedParty::userId)
                .containsExactlyInAnyOrder(asSenderCounterparty, asTravelerCounterparty);
    }

    // Les offres d'une annonce sont déjà rapportées par PENDING_BID et PARCEL_IN_TRANSIT :
    // les lister une seconde fois ici afficherait deux fois les mêmes personnes.
    @Test
    @DisplayName("une annonce à venir est un simple décompte, sans contrepartie")
    void upcomingAnnouncement_hasNoParties() {
        noBids();
        when(announcementRepository.countByTravelerIdAndStatusIn(eq(USER_ID), any())).thenReturn(3L);

        ImpactFinding upcoming = contributor().contribute(USER_ID).stream()
                .filter(f -> f.code().equals("UPCOMING_ANNOUNCEMENT")).findFirst().orElseThrow();

        assertThat(upcoming.count()).isEqualTo(3);
        assertThat(upcoming.affectedParties()).isEmpty();
    }

    @Test
    @DisplayName("un compte sans activité ne rapporte rien")
    void idleAccount_reportsNothing() {
        noBids();
        noAnnouncements();
        assertThat(contributor().contribute(USER_ID)).isEmpty();
    }
}
