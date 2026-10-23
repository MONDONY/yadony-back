package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.tracking.TrackingEventEntity;
import com.yadony.api.tracking.TrackingEventRepository;
import com.yadony.api.tracking.TrackingEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /admin/bids/{id}/photos} de bout en bout (MockMvc + base H2 partagée, données uniques par test). */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminBidPhotosIT — GET /admin/bids/{id}/photos")
class AdminBidPhotosIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired TrackingEventRepository trackingRepository;
    @MockitoBean StorageService storageService;

    private BidEntity bid;

    @BeforeEach
    void setUp() {
        when(storageService.generatePresignedUrl(anyString(), any(Duration.class)))
                .thenAnswer(i -> "https://signed.test/" + i.getArgument(0));
        UserEntity traveler = saveUser();
        UserEntity sender = saveUser();
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Départ");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Arrivée");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("20.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setCurrency("EUR");
        a.setAcceptedPaymentMethods(new HashSet<>(Set.of(PaymentMethod.CASH)));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a = announcementRepository.save(a);
        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(sender.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(BidStatus.IN_TRANSIT);
        b.setCurrency("EUR");
        b.setTrackingNumber("YD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        bid = bidRepository.save(b);
    }

    private UserEntity saveUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        u.setFirstName("Aicha");
        u.setLastName("N" + UUID.randomUUID().toString().substring(0, 6));
        return userRepository.save(u);
    }

    private static UsernamePasswordAuthenticationToken admin(AdminPermission... permissions) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (AdminPermission p : permissions) authorities.add(new SimpleGrantedAuthority(p.name()));
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "p@yadony.test", AdminRole.SUPPORT, false, "uid-p"),
                null, authorities);
    }

    private void scan(TrackingEventType type, LocalDateTime at, String key, String gps) {
        TrackingEventEntity e = new TrackingEventEntity();
        e.setBidId(bid.getId());
        e.setEventType(type);
        e.setScannedAt(at);
        e.setPhotoUrl(key);
        e.setGpsLabel(gps);
        trackingRepository.save(e);
    }

    @Test
    void photos_unknownBid_404ProblemDetail() throws Exception {
        mockMvc.perform(get("/admin/bids/{id}/photos", UUID.randomUUID())
                        .with(authentication(admin(AdminPermission.BID_VIEW))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(endsWith("bid-not-found")));
    }

    @Test
    void photos_withoutBidView_403() throws Exception {
        mockMvc.perform(get("/admin/bids/{id}/photos", bid.getId())
                        .with(authentication(admin(AdminPermission.USER_VIEW))))
                .andExpect(status().isForbidden());
    }

    @Test
    void photos_jsonShape() throws Exception {
        LocalDateTime t = LocalDateTime.of(2026, 10, 6, 18, 0);
        scan(TrackingEventType.TRANSIT, t.plusHours(2), "tracking/" + bid.getId() + "/transit.jpg", null);
        scan(TrackingEventType.DEPART, t, "tracking/" + bid.getId() + "/depart.jpg", "Paris CDG");
        scan(TrackingEventType.ARRIVEE, t.plusHours(4), null, "Dakar");
        bid.setRefusalPhotoUrl("refusals/" + bid.getId() + ".jpg");
        bidRepository.save(bid);

        mockMvc.perform(get("/admin/bids/{id}/photos", bid.getId())
                        .with(authentication(admin(AdminPermission.BID_VIEW))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bidId").value(bid.getId().toString()))
                .andExpect(jsonPath("$.photos.length()").value(3))
                .andExpect(jsonPath("$.photos[0].stage").value("DEPART"))
                .andExpect(jsonPath("$.photos[0].label").value("DEPART"))
                .andExpect(jsonPath("$.photos[0].gpsLabel").value("Paris CDG"))
                .andExpect(jsonPath("$.photos[0].url").value("https://signed.test/tracking/" + bid.getId() + "/depart.jpg"))
                .andExpect(jsonPath("$.photos[0].at").exists())
                .andExpect(jsonPath("$.photos[1].stage").value("TRANSIT"))
                .andExpect(jsonPath("$.photos[1].gpsLabel").doesNotExist())
                .andExpect(jsonPath("$.photos[2].stage").value("REFUSAL"))
                .andExpect(jsonPath("$.photos[2].at").exists());
    }
}
