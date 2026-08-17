package com.yadony.api.admin;

import com.yadony.api.admin.dto.*;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.*;
import com.yadony.api.tracking.TrackingEventRepository;
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
    @Mock TrackingEventRepository trackingRepo;
    @Mock UserRepository userRepo;

    private AdminBidsController controller() {
        return new AdminBidsController(bidRepo, announcementRepo, trackingRepo, userRepo);
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

    @Test
    void getBid_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(bidRepo.findById(id)).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(
            com.yadony.api.common.YadonyBusinessException.class,
            () -> controller().getBid(id)
        );
    }

    @Test
    void getTimeline_returnsEntriesFromTrackingEvents() {
        UUID bidId = UUID.randomUUID();
        BidEntity bid = new BidEntity();
        when(bidRepo.findById(bidId)).thenReturn(Optional.of(bid));
        when(trackingRepo.findByBidIdOrderByScannedAtAsc(bidId)).thenReturn(List.of());
        ResponseEntity<AdminBidTimelineResponse> resp = controller().getTimeline(bidId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().bidId()).isEqualTo(bidId);
    }

    @Test
    void listAnnouncements_returnsPage() {
        Page<AnnouncementEntity> page = new PageImpl<>(List.of());
        when(announcementRepo.findAll(any(Pageable.class))).thenReturn(page);
        // empty page → travelerIds empty → loadUserNames short-circuits, no repo call needed
        ResponseEntity<?> resp = controller().listAnnouncements(0, 20);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }
}
