package com.yadony.api.admin;

import com.yadony.api.admin.dto.*;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBidsControllerTest {

    @Mock BidRepository bidRepo;
    @Mock AnnouncementRepository announcementRepo;
    @Mock AdminBidDetailAssembler assembler;
    @Mock UserRepository userRepo;
    @Mock com.yadony.api.matching.BidGridItemRepository bidGridItemRepo;

    private AdminBidsController controller() {
        return new AdminBidsController(bidRepo, announcementRepo, userRepo, bidGridItemRepo, assembler);
    }

    @Test
    void listBids_returnsPage() {
        Page<BidEntity> page = new PageImpl<>(List.of());
        when(bidRepo.findAdminFiltered(isNull(), isNull(), isNull(), isNull(), isNull(), any())).thenReturn(page);
        // empty page → annIds is empty → findAllById called with empty collection
        when(announcementRepo.findAllById(any())).thenReturn(List.of());
        // userRepo.findAllById not called — empty page → no userIds to resolve
        ResponseEntity<?> resp = controller().listBids(null, null, null, null, null, 0, 20);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void list_exposesCommissionStatus_soUnsettledCashBidsAreVisible() {
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(com.yadony.api.payments.cash.PaymentMethod.CASH);
        bid.setCommissionStatus(com.yadony.api.payments.cash.CommissionStatus.PENDING);
        Page<BidEntity> page = new PageImpl<>(List.of(bid));
        when(bidRepo.findAdminFiltered(isNull(), isNull(), isNull(), isNull(), isNull(), any())).thenReturn(page);
        when(announcementRepo.findAllById(any())).thenReturn(List.of());

        ResponseEntity<Page<AdminBidListItemResponse>> resp = controller().listBids(null, null, null, null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getContent().get(0).commissionStatus()).isEqualTo("PENDING");
    }

    // netEur est dans la devise du bid, que le back-office affichait toujours en euros.
    @Test
    void list_exposesTheBidCurrency_nextToNetEur() {
        BidEntity bid = new BidEntity();
        bid.setCurrency("xof");
        bid.setNegotiatedNetEur(new java.math.BigDecimal("6000.00"));
        Page<BidEntity> page = new PageImpl<>(List.of(bid));
        when(bidRepo.findAdminFiltered(isNull(), isNull(), isNull(), isNull(), isNull(), any())).thenReturn(page);
        when(announcementRepo.findAllById(any())).thenReturn(List.of());

        ResponseEntity<Page<AdminBidListItemResponse>> resp = controller().listBids(null, null, null, null, null, 0, 20);

        assertThat(resp.getBody().getContent().get(0).currency()).isEqualTo("XOF");
        assertThat(resp.getBody().getContent().get(0).netEur()).isEqualByComparingTo("6000.00");
    }

    /**
     * Une demande directe n'a pas d'accord négocié : son net vient du barème de
     * l'annonce (poids × prix au kilo) et des articles de grille, dans la devise du
     * bid. Le back-office affichait un tiret.
     */
    @Test
    void list_computesTheNetOfADirectRequestFromTheAnnouncementAndGrid() {
        UUID annId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        BidEntity bid = new BidEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setAnnouncementId(annId);
        bid.setCurrency("XOF");
        bid.setWeightKg(new java.math.BigDecimal("3"));
        AnnouncementEntity ann = new AnnouncementEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(ann, "id", annId);
        ann.setPricePerKg(new java.math.BigDecimal("5000"));
        com.yadony.api.matching.BidGridItemEntity item = new com.yadony.api.matching.BidGridItemEntity();
        item.setBidId(bidId);
        item.setUnitPriceNetSnapshot(new java.math.BigDecimal("2500"));
        item.setQuantity(2);
        when(bidRepo.findAdminFiltered(isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(bid)));
        when(announcementRepo.findAllById(any())).thenReturn(List.of(ann));
        when(bidGridItemRepo.findByBidIdIn(any())).thenReturn(List.of(item));

        ResponseEntity<Page<AdminBidListItemResponse>> resp = controller().listBids(null, null, null, null, null, 0, 20);

        // 3 kg × 5 000 + 2 × 2 500 = 20 000 F CFA
        assertThat(resp.getBody().getContent().get(0).netEur()).isEqualByComparingTo("20000");
    }

    @Test
    void list_leavesTheNetEmptyWhenNothingIsPriced() {
        BidEntity bid = new BidEntity();
        bid.setCurrency("EUR");
        when(bidRepo.findAdminFiltered(isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(bid)));
        when(announcementRepo.findAllById(any())).thenReturn(List.of());

        ResponseEntity<Page<AdminBidListItemResponse>> resp = controller().listBids(null, null, null, null, null, 0, 20);

        assertThat(resp.getBody().getContent().get(0).netEur()).isNull();
    }

    @Test
    void getBid_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(bidRepo.findById(id)).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(
            com.yadony.api.common.YadonyBusinessException.class,
            () -> controller().getBid(id, null)
        );
    }

    @Test
    void getTimeline_returnsTheAssembledEntries() {
        UUID bidId = UUID.randomUUID();
        BidEntity bid = new BidEntity();
        when(bidRepo.findById(bidId)).thenReturn(Optional.of(bid));
        var entry = new AdminBidTimelineResponse.Entry(java.time.LocalDateTime.of(2026, 10, 6, 18, 54),
                "EVENT", "PRESENCE_CONFIRMED", null, null, null, null, "AUDIT", "USER", "Awa Ndiaye");
        when(assembler.timeline(eq(bid), any())).thenReturn(List.of(entry));
        ResponseEntity<AdminBidTimelineResponse> resp = controller().getTimeline(bidId, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().bidId()).isEqualTo(bidId);
        assertThat(resp.getBody().entries()).containsExactly(entry);
    }

    @Test
    void getTimeline_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(bidRepo.findById(id)).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(
            com.yadony.api.common.YadonyBusinessException.class,
            () -> controller().getTimeline(id, null));
    }

    @Test
    void getBid_appendsTheAssembledContextAtTheEnd() {
        UUID bidId = UUID.randomUUID();
        UUID annId = UUID.randomUUID();
        BidEntity bid = new BidEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setAnnouncementId(annId);
        bid.setCurrency("EUR");
        bid.setStatus(BidStatus.ACCEPTED);
        AnnouncementEntity ann = new AnnouncementEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(ann, "id", annId);
        ann.setDepartureCity("Paris");
        ann.setArrivalCity("Bamako");
        when(bidRepo.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepo.findById(annId)).thenReturn(Optional.of(ann));
        var links = new AdminBidDetailResponse.Links(null, null, null, "fs-1", null);
        when(assembler.extras(eq(bid), eq(ann), any())).thenReturn(new AdminBidDetailAssembler.Extras(
                null, null, null, null, null, links, true, List.of("https://r2.test/p?sig=1"), null));

        AdminBidDetailResponse body = controller().getBid(bidId, null).getBody();

        assertThat(body.status()).isEqualTo("ACCEPTED");
        assertThat(body.links().conversationId()).isEqualTo("fs-1");
        assertThat(body.confirmationCodePresent()).isTrue();
        assertThat(body.photoUrls()).containsExactly("https://r2.test/p?sig=1");
    }

    @Test
    void listAnnouncements_byId_returnsOnlyThatAnnouncement() {
        UUID id = UUID.randomUUID();
        AnnouncementEntity ann = new AnnouncementEntity();
        ann.setStatus(AnnouncementStatus.ACTIVE);
        when(announcementRepo.findById(id)).thenReturn(Optional.of(ann));

        Page<AdminAnnouncementListItemResponse> page = controller().listAnnouncements(id, 0, 20).getBody();

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void listAnnouncements_byUnknownId_returnsAnEmptyPage() {
        UUID id = UUID.randomUUID();
        when(announcementRepo.findById(id)).thenReturn(Optional.empty());

        Page<AdminAnnouncementListItemResponse> page = controller().listAnnouncements(id, 0, 20).getBody();

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void listAnnouncements_exposesTheAnnouncementCurrency() {
        AnnouncementEntity ann = new AnnouncementEntity();
        ann.setStatus(AnnouncementStatus.ACTIVE);
        ann.setCurrency("xof");
        ann.setPricePerKg(new java.math.BigDecimal("2000.00"));
        Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));
        when(announcementRepo.findAll(any(Pageable.class))).thenReturn(page);
        // Annonce sans voyageur : aucun nom à charger, loadUserNames court-circuite.

        ResponseEntity<Page<AdminAnnouncementListItemResponse>> resp = controller().listAnnouncements(null, 0, 20);

        assertThat(resp.getBody().getContent().get(0).currency()).isEqualTo("XOF");
    }

    @Test
    void listAnnouncements_returnsPage() {
        Page<AnnouncementEntity> page = new PageImpl<>(List.of());
        when(announcementRepo.findAll(any(Pageable.class))).thenReturn(page);
        // empty page → travelerIds empty → loadUserNames short-circuits, no repo call needed
        ResponseEntity<?> resp = controller().listAnnouncements(null, 0, 20);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void listAnnouncements_exposesTripGroupAndLegCount() {
        UUID group = UUID.randomUUID();
        AnnouncementEntity leg1 = new AnnouncementEntity();
        leg1.setStatus(AnnouncementStatus.ACTIVE);
        leg1.setTripGroupId(group);
        leg1.setTripLegIndex(1);
        AnnouncementEntity leg2 = new AnnouncementEntity();
        leg2.setStatus(AnnouncementStatus.ACTIVE);
        leg2.setTripGroupId(group);
        leg2.setTripLegIndex(2);
        AnnouncementEntity single = new AnnouncementEntity();
        single.setStatus(AnnouncementStatus.ACTIVE);
        when(announcementRepo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(leg1, single)));
        when(announcementRepo.findByTripGroupIdIn(java.util.Set.of(group))).thenReturn(List.of(leg1, leg2));

        var content = controller().listAnnouncements(null, 0, 20).getBody().getContent();

        assertThat(content.get(0).tripGroupId()).isEqualTo(group);
        assertThat(content.get(0).tripLegIndex()).isEqualTo(1);
        assertThat(content.get(0).tripLegCount()).isEqualTo(2);
        assertThat(content.get(1).tripGroupId()).isNull();
        assertThat(content.get(1).tripLegIndex()).isNull();
        assertThat(content.get(1).tripLegCount()).isNull();
    }
}
