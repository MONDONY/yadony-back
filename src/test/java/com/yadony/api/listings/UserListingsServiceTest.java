package com.yadony.api.listings;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.listings.dto.UserListingItem;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementSearchMapper;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.requests.dto.PackageRequestSearchResponse;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.requests.service.PackageRequestSearchMapper;
import com.yadony.api.requests.service.ViewerPaymentCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserListingsServiceTest {

    @Mock AnnouncementRepository announcementRepository;
    @Mock PackageRequestRepository packageRequestRepository;
    @Mock AnnouncementSearchMapper announcementSearchMapper;
    @Mock PackageRequestSearchMapper packageRequestSearchMapper;
    @Mock UserRepository userRepository;
    @Mock BlockVisibility blockVisibility;

    UserListingsService service;
    UUID ownerId;
    UUID viewerId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new UserListingsService(announcementRepository, packageRequestRepository,
                announcementSearchMapper, packageRequestSearchMapper, userRepository, blockVisibility);
        ownerId = UUID.randomUUID();
        viewerId = UUID.randomUUID();
        when(announcementRepository.findByTravelerIdAndStatus(eq(ownerId), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(packageRequestRepository.findOpenBySenderId(eq(ownerId), any(), any())).thenReturn(List.of());
    }

    private AnnouncementEntity trip(LocalDate departure) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureDate(departure);
        a.setStatus(AnnouncementStatus.ACTIVE);
        return a;
    }

    private AnnouncementSearchResponse tripResponse(LocalDateTime createdAt) {
        AnnouncementSearchResponse r = mock(AnnouncementSearchResponse.class);
        when(r.createdAt()).thenReturn(createdAt);
        return r;
    }

    private PackageRequestEntity request(LocalDate desiredDate, LocalDateTime publishedAt) {
        PackageRequestEntity p = new PackageRequestEntity();
        p.setDesiredDate(desiredDate);
        p.setDisclaimerSignedAt(publishedAt);
        return p;
    }

    @Test
    void blockedOwner_isHidden_andNothingIsRead() {
        doThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND, "not-found", "Not Found", "Ressource introuvable"))
                .when(blockVisibility).assertVisible(viewerId, ownerId);

        assertThatThrownBy(() -> service.openListings(ownerId, viewerId))
                .isInstanceOf(YadonyBusinessException.class);

        verify(announcementRepository, never()).findByTravelerIdAndStatus(any(), any(), any());
        verify(packageRequestRepository, never()).findOpenBySenderId(any(), any(), any());
    }

    @Test
    void mixesTripsAndRequests_newestPublishedFirst() {
        AnnouncementEntity trip = trip(LocalDate.now(ZoneOffset.UTC).plusDays(5));
        when(announcementRepository.findByTravelerIdAndStatus(eq(ownerId), eq(AnnouncementStatus.ACTIVE), any()))
                .thenReturn(new PageImpl<>(List.of(trip)));
        AnnouncementSearchResponse tripDto = tripResponse(LocalDateTime.of(2026, 10, 20, 9, 0));
        when(announcementSearchMapper.toSearchResponseList(List.of(trip), Set.of())).thenReturn(List.of(tripDto));

        PackageRequestEntity req = request(LocalDate.now(ZoneOffset.UTC).plusDays(10),
                LocalDateTime.of(2026, 10, 21, 18, 0));
        when(packageRequestRepository.findOpenBySenderId(eq(ownerId), any(), any())).thenReturn(List.of(req));
        PackageRequestSearchResponse reqDto = mock(PackageRequestSearchResponse.class);
        when(packageRequestSearchMapper.toSearchResponseList(
                eq(List.of(req)), eq(Set.of()), any(ViewerPaymentCapabilities.class))).thenReturn(List.of(reqDto));

        List<UserListingItem> items = service.openListings(ownerId, viewerId);

        assertThat(items).extracting(UserListingItem::kind).containsExactly("REQUEST", "TRIP");
        assertThat(items.get(0).request()).isSameAs(reqDto);
        assertThat(items.get(0).trip()).isNull();
        assertThat(items.get(1).trip()).isSameAs(tripDto);
        assertThat(items.get(1).request()).isNull();
    }

    @Test
    void tripsAlreadyDeparted_orPastHandoverDeadline_areDropped() {
        AnnouncementEntity departed = trip(LocalDate.now(ZoneOffset.UTC).minusDays(1));
        AnnouncementEntity closed = trip(LocalDate.now(ZoneOffset.UTC).plusDays(3));
        closed.setHandoverDeadline(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        when(announcementRepository.findByTravelerIdAndStatus(eq(ownerId), eq(AnnouncementStatus.ACTIVE), any()))
                .thenReturn(new PageImpl<>(List.of(departed, closed)));
        when(announcementSearchMapper.toSearchResponseList(List.of(), Set.of())).thenReturn(List.of());

        assertThat(service.openListings(ownerId, viewerId)).isEmpty();
    }

    @Test
    void requestsWithPastDesiredDate_areDropped_flexibleOnesStay() {
        PackageRequestEntity expired = request(LocalDate.now(ZoneOffset.UTC).minusDays(1),
                LocalDateTime.of(2026, 10, 1, 8, 0));
        PackageRequestEntity flexible = request(null, LocalDateTime.of(2026, 10, 2, 8, 0));
        when(packageRequestRepository.findOpenBySenderId(eq(ownerId), any(), any()))
                .thenReturn(List.of(expired, flexible));
        PackageRequestSearchResponse flexDto = mock(PackageRequestSearchResponse.class);
        when(packageRequestSearchMapper.toSearchResponseList(
                eq(List.of(flexible)), eq(Set.of()), any(ViewerPaymentCapabilities.class)))
                .thenReturn(List.of(flexDto));

        List<UserListingItem> items = service.openListings(ownerId, viewerId);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).request()).isSameAs(flexDto);
    }

    @Test
    void anonymousViewer_getsNoPaymentCapabilities() {
        PackageRequestEntity req = request(null, LocalDateTime.of(2026, 10, 2, 8, 0));
        when(packageRequestRepository.findOpenBySenderId(eq(ownerId), any(), any())).thenReturn(List.of(req));
        when(packageRequestSearchMapper.toSearchResponseList(
                eq(List.of(req)), eq(Set.of()), eq(ViewerPaymentCapabilities.NONE)))
                .thenReturn(List.of(mock(PackageRequestSearchResponse.class)));

        assertThat(service.openListings(ownerId, null)).hasSize(1);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void knownViewer_isLookedUpForPaymentCapabilities() {
        PackageRequestEntity req = request(null, LocalDateTime.of(2026, 10, 2, 8, 0));
        when(packageRequestRepository.findOpenBySenderId(eq(ownerId), any(), any())).thenReturn(List.of(req));
        when(userRepository.findById(viewerId)).thenReturn(Optional.empty());
        when(packageRequestSearchMapper.toSearchResponseList(any(), any(), any()))
                .thenReturn(List.of(mock(PackageRequestSearchResponse.class)));

        service.openListings(ownerId, viewerId);

        verify(userRepository).findById(viewerId);
    }

    @Test
    void publishedAt_fallsBackToCreatedAt_whenNeverSigned() {
        PackageRequestEntity req = new PackageRequestEntity();
        LocalDateTime created = LocalDateTime.of(2026, 9, 1, 12, 0);
        ReflectionTestUtils.setField(req, "createdAt", created);

        assertThat(UserListingsService.publishedAt(req)).isEqualTo(created);

        req.setDisclaimerSignedAt(LocalDateTime.of(2026, 10, 5, 12, 0));
        assertThat(UserListingsService.publishedAt(req)).isEqualTo(LocalDateTime.of(2026, 10, 5, 12, 0));
    }
}
