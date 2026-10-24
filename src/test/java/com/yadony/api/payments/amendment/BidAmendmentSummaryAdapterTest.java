package com.yadony.api.payments.amendment;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemRepository;
import com.yadony.api.matching.BidAmendmentSummaryPort;
import com.yadony.api.matching.BidAmendmentView;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.BidSupplementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.yadony.api.payments.amendment.BidAmendmentTestSupport.setId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Vue des modifications pour {@code BidResponse} : visibilité, droit de modifier et lectures groupées des listes. */
class BidAmendmentSummaryAdapterTest {

    private final BidAmendmentRepository amendments = mock(BidAmendmentRepository.class);
    private final BidAmendmentItemRepository items = mock(BidAmendmentItemRepository.class);
    private final BidSupplementRepository supplements = mock(BidSupplementRepository.class);
    private final AnnouncementPriceGridItemRepository gridItems = mock(AnnouncementPriceGridItemRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final BidAmendmentRailHandler cashRail = mock(BidAmendmentRailHandler.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BidAmendmentRailHandler> handlers = mock(ObjectProvider.class);
    private final BidAmendmentProperties properties = new BidAmendmentProperties();
    private final List<BidAmendmentEntity> stored = new ArrayList<>();

    private final UserEntity traveler = new UserEntity();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private BidEntity bid;
    private AnnouncementEntity announcement;
    private BidAmendmentSummaryAdapter adapter;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        setId(traveler, travelerId);
        announcement = new AnnouncementEntity();
        setId(announcement, UUID.randomUUID());
        announcement.setTravelerId(travelerId);
        announcement.setDepartureDate(LocalDate.now().plusDays(10));
        announcement.setTimezone("Europe/Paris");
        announcement.setPricePerKg(new BigDecimal("10.00"));
        announcement.setAvailableKg(new BigDecimal("6.00"));
        announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
        bid = new BidEntity();
        setId(bid, UUID.randomUUID());
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(senderId);
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setCurrency("EUR");
        bid.setWeightKg(new BigDecimal("5.40"));

        when(cashRail.rail()).thenReturn(BidSupplementRail.CASH);
        when(handlers.orderedStream()).thenAnswer(inv -> Stream.of(cashRail));
        when(users.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(amendments.findByBidIdOrderByCreatedAtDesc(bid.getId())).thenReturn(stored);
        BidAmendmentWindow window = new BidAmendmentWindow(properties);
        adapter = new BidAmendmentSummaryAdapter(amendments, items, supplements, gridItems, users,
                new BidAmendmentEligibility(properties, window, amendments, supplements, gridItems), window, handlers);
    }

    private BidAmendmentEntity add(BidSupplementRail rail, BidAmendmentStatus status, String kg, int hoursAgo) {
        BidAmendmentEntity a = new BidAmendmentEntity();
        setId(a, UUID.randomUUID());
        LocalDateTime created = LocalDateTime.now(ZoneOffset.UTC).minusHours(hoursAgo);
        ReflectionTestUtils.setField(a, "createdAt", created);
        ReflectionTestUtils.setField(a, "updatedAt", created);
        a.setBidId(bid.getId());
        a.setRail(rail);
        a.setStatus(status);
        a.setCurrency("EUR");
        a.setAddedWeightKg(new BigDecimal(kg));
        a.setNetAmount(new BigDecimal("23.50"));
        a.setCommissionAmount(new BigDecimal("3.30"));
        a.setGrossAmount(new BigDecimal("26.80"));
        a.setExpiresAt(created.plusHours(48));
        if (status.isSecured()) {
            a.setAcceptedAt(created.plusMinutes(5));
        }
        stored.add(a);
        return a;
    }

    @Test
    void bidResponse_amendmentAllowed_falseForTraveler() {
        assertThat(adapter.view(bid, announcement, senderId, null).amendmentAllowed()).isTrue();
        assertThat(adapter.view(bid, announcement, travelerId, null).amendmentAllowed()).isFalse();
        assertThat(adapter.view(bid, announcement, null, null).amendmentAllowed()).isFalse();
    }

    @Test
    void amendmentAllowed_faux_drapeauEteint_ouRailNonLivre() {
        properties.setEnabled(false);
        assertThat(adapter.view(bid, announcement, senderId, null).amendmentAllowed()).isFalse();

        properties.setEnabled(true);
        when(handlers.orderedStream()).thenAnswer(inv -> Stream.empty());
        assertThat(adapter.view(bid, announcement, senderId, null).amendmentAllowed()).isFalse();
    }

    @Test
    void acquisesEtOuverte_kilosAcquisEtTransactions() {
        add(BidSupplementRail.CASH, BidAmendmentStatus.ACCEPTED, "3.00", 30);
        add(BidSupplementRail.CASH, BidAmendmentStatus.PROPOSED, "2.00", 1);

        BidAmendmentView v = adapter.view(bid, announcement, travelerId, null);

        assertThat(v.amendments()).extracting(r -> r.status()).containsExactly("ACCEPTED", "PROPOSED");
        assertThat(v.securedAddedKg()).isEqualByComparingTo("3.00");
        assertThat(v.moneyEntries()).extracting(e -> e.secured()).containsExactly(true, false);
        assertThat(v.moneyEntries().get(0).commission()).isEqualByComparingTo("3.30");
        assertThat(adapter.view(bid, announcement, senderId, null).amendmentAllowed()).as("une ouverte").isFalse();
    }

    @Test
    void autorisationCarteEnAttente_cacheeAuVoyageur() {
        add(BidSupplementRail.STRIPE, BidAmendmentStatus.AUTHORIZATION_PENDING, "2.00", 1);

        assertThat(adapter.view(bid, announcement, travelerId, null).amendments()).isEmpty();
        assertThat(adapter.view(bid, announcement, travelerId, null).moneyEntries()).isEmpty();
        assertThat(adapter.view(bid, announcement, senderId, null).amendments()).hasSize(1);

        stored.get(0).setStatus(BidAmendmentStatus.CANCELLED);
        stored.get(0).setClosedReason(BidAmendmentClosedReason.AUTHORIZATION_NOT_CONFIRMED);
        assertThat(adapter.view(bid, announcement, travelerId, null).amendments()).isEmpty();
    }

    @Test
    void closeSansAcquisition_affichéeSansTransaction() {
        add(BidSupplementRail.CASH, BidAmendmentStatus.DECLINED, "2.00", 3);

        BidAmendmentView v = adapter.view(bid, announcement, senderId, null);

        assertThat(v.amendments()).hasSize(1);
        assertThat(v.moneyEntries()).isEmpty();
        assertThat(v.amendments().get(0).netAmount()).isNull();
    }

    @Test
    void listes_lecturesGroupees_aucuneRequeteParColis() {
        BidAmendmentEntity accepted = add(BidSupplementRail.CASH, BidAmendmentStatus.ACCEPTED, "3.00", 30);
        BidEntity other = new BidEntity();
        setId(other, UUID.randomUUID());
        other.setAnnouncementId(announcement.getId());
        other.setSenderId(senderId);
        other.setStatus(BidStatus.IN_TRANSIT);
        when(amendments.findByBidIdIn(anyList())).thenReturn(List.of(accepted));
        when(items.findByAmendmentIdIn(anyList())).thenReturn(List.of());
        when(supplements.findBidIdsByBidIdInAndStatusIn(anyList(), any())).thenReturn(List.of());
        when(gridItems.findAnnouncementIdsWithItems(anyList())).thenReturn(List.of());

        BidAmendmentSummaryPort.Prefetch prefetch = adapter.prefetch(List.of(bid, other));
        BidAmendmentView v = adapter.view(bid, announcement, senderId, prefetch);
        BidAmendmentView none = adapter.view(other, announcement, senderId, prefetch);

        assertThat(v.amendments()).hasSize(1);
        assertThat(v.amendmentAllowed()).isTrue();
        assertThat(none.amendments()).isEmpty();
        assertThat(none.amendmentAllowed()).isFalse();
        verify(amendments, never()).findByBidIdOrderByCreatedAtDesc(any());
        verify(amendments, never()).findFirstByBidIdAndStatusIn(any(), any());
        verify(amendments, never()).countByBidIdAndAcceptedAtNotNull(any());
        verify(supplements, never()).existsByBidIdAndStatusIn(any(), any());
        verify(gridItems, never()).existsByAnnouncementId(any());
        verify(items, never()).findByAmendmentId(any());
        verify(supplements).findBidIdsByBidIdInAndStatusIn(List.of(bid.getId()),
                com.yadony.api.payments.supplement.BidSupplementStatus.OPEN);
        verify(supplements, never()).findByBidIdIn(any());
    }

    @Test
    void listeVide_aucuneRequete() {
        adapter.prefetch(List.of());

        verify(amendments, never()).findByBidIdIn(any());
        verify(items, never()).findByAmendmentIdIn(any());
        verify(supplements, never()).findBidIdsByBidIdInAndStatusIn(any(), any());
        verify(gridItems, never()).findAnnouncementIdsWithItems(any());
    }
}
