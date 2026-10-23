package com.yadony.api.admin.userview;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Litiges et annulations d'un utilisateur sur la fiche admin. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminUserIncidentsIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired CancellationRepository cancellationRepository;
        @Autowired JdbcTemplate jdbc;

    private UserEntity traveler;
    private UserEntity sender;

    @BeforeEach
    void setUp() {
        traveler = saveUser();
        sender = saveUser();
    }

    private UserEntity saveUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        return userRepository.save(u);
    }

    private static UsernamePasswordAuthenticationToken authWith(String... permissions) {
        var all = new ArrayList<SimpleGrantedAuthority>();
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) all.add(new SimpleGrantedAuthority(p));
        var principal = new AdminPrincipal(UUID.randomUUID(), "admin@yadony.com", AdminRole.ADMIN, false, "uid");
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private static UsernamePasswordAuthenticationToken fullAuth() {
        return authWith("USER_VIEW", "BID_VIEW", "DISPUTE_VIEW");
    }

    private AnnouncementEntity announcement(UserEntity owner) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(owner.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("20.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setCurrency("EUR");
        a.setAcceptedPaymentMethods(new HashSet<>(Set.of(PaymentMethod.STRIPE)));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    private BidEntity bid(AnnouncementEntity a, UserEntity from) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(from.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(BidStatus.PAYMENT_ESCROWED);
        b.setCurrency("EUR");
        b.setTrackingNumber("YD-" + UUID.randomUUID().toString().substring(0, 8));
        return bidRepository.save(b);
    }

    private DisputeEntity dispute(BidEntity b, UserEntity sen, UserEntity trav, UserEntity reporter, String status) {
        DisputeEntity d = new DisputeEntity();
        d.setBidId(b.getId());
        d.setSenderId(sen.getId());
        d.setTravelerId(trav.getId());
        d.setReporterId(reporter == null ? null : reporter.getId());
        d.setType(DisputeTypes.SENDER_NO_SHOW_CONTESTED);
        d.setStatus(status);
        return disputeRepository.save(d);
    }

    private CancellationEntity cancellation(BidEntity b, UserEntity by, CancellationScope scope) {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(b.getId());
        c.setCancelledBy(by.getId());
        c.setReason(CancellationReason.SENDER_NO_SHOW.name());
        c.setScope(scope);
        c.setNoShowStatus(CancellationStatus.CONFIRMED);
        return cancellationRepository.save(c);
    }

    private ResultActions overview(UserEntity u, UsernamePasswordAuthenticationToken auth) throws Exception {
        return mockMvc.perform(get("/admin/users/{id}/overview", u.getId()).with(authentication(auth)));
    }

    @Test
    void disputes_listsWhereUserIsSenderTravelerOrReporter() throws Exception {
        UserEntity third = saveUser();
        BidEntity b1 = bid(announcement(traveler), sender);
        DisputeEntity asSender = dispute(b1, sender, traveler, null, DisputeTypes.STATUS_OPEN);
        DisputeEntity asTraveler = dispute(bid(announcement(traveler), third), third, traveler, null,
                DisputeTypes.STATUS_RESOLVED);
        DisputeEntity asReporter = dispute(bid(announcement(third), saveUser()), saveUser(), third, traveler,
                DisputeTypes.STATUS_OPEN);
        dispute(bid(announcement(third), saveUser()), saveUser(), third, null, DisputeTypes.STATUS_OPEN);

        mockMvc.perform(get("/admin/users/{id}/disputes", traveler.getId()).with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[?(@.id=='" + asTraveler.getId() + "')].role").value("TRAVELER"))
                .andExpect(jsonPath("$.content[?(@.id=='" + asReporter.getId() + "')].role").value("REPORTER"))
                .andExpect(jsonPath("$.content[?(@.id=='" + asSender.getId() + "')].role").value("TRAVELER"));
        mockMvc.perform(get("/admin/users/{id}/disputes", sender.getId()).with(authentication(fullAuth())))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].role").value("SENDER"))
                .andExpect(jsonPath("$.content[0].bidId").value(b1.getId().toString()))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"));
    }

    @Test
    void disputes_roleDiffersPerDisputeAndOrderIsNewestFirst() throws Exception {
        UserEntity other = saveUser();
        DisputeEntity older = dispute(bid(announcement(other), traveler), traveler, other, null, DisputeTypes.STATUS_OPEN);
        DisputeEntity newer = dispute(bid(announcement(traveler), other), other, traveler, null, DisputeTypes.STATUS_OPEN);
        jdbc.update("UPDATE disputes SET created_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(2), older.getId());

        mockMvc.perform(get("/admin/users/{id}/disputes", traveler.getId()).with(authentication(fullAuth())))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(newer.getId().toString()))
                .andExpect(jsonPath("$.content[0].role").value("TRAVELER"))
                .andExpect(jsonPath("$.content[1].id").value(older.getId().toString()))
                .andExpect(jsonPath("$.content[1].role").value("SENDER"));
    }

    @Test
    void disputes_isPaginated() throws Exception {
        for (int i = 0; i < 3; i++) {
            dispute(bid(announcement(traveler), sender), sender, traveler, null, DisputeTypes.STATUS_OPEN);
        }
        mockMvc.perform(get("/admin/users/{id}/disputes?page=1&size=2", traveler.getId())
                        .with(authentication(fullAuth())))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/admin/users/{id}/disputes?size=100000", traveler.getId())
                        .with(authentication(fullAuth())))
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void disputes_unknownUser_returns404() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/disputes", UUID.randomUUID()).with(authentication(fullAuth())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/users/{id}/cancellations", UUID.randomUUID()).with(authentication(fullAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellations_includesDeclaredAndSufferedByUser() throws Exception {
        UserEntity other = saveUser();
        BidEntity mine = bid(announcement(other), traveler);
        BidEntity suffered = bid(announcement(traveler), sender);
        BidEntity unrelated = bid(announcement(other), saveUser());
        CancellationEntity declared = cancellation(mine, traveler, CancellationScope.HANDOVER);
        CancellationEntity undergone = cancellation(suffered, sender, CancellationScope.DELIVERY);
        cancellation(unrelated, other, CancellationScope.HANDOVER);

        mockMvc.perform(get("/admin/users/{id}/cancellations", traveler.getId()).with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.id=='" + declared.getId() + "')].declaredByUser").value(true))
                .andExpect(jsonPath("$.content[?(@.id=='" + undergone.getId() + "')].declaredByUser").value(false))
                .andExpect(jsonPath("$.content[?(@.id=='" + undergone.getId() + "')].scope").value("DELIVERY"))
                .andExpect(jsonPath("$.content[?(@.id=='" + undergone.getId() + "')].status").value("CONFIRMED"));
    }

    @Test
    void cancellations_userBothDeclarantAndPartyAppearsOnce() throws Exception {
        cancellation(bid(announcement(traveler), sender), traveler, CancellationScope.HANDOVER);
        mockMvc.perform(get("/admin/users/{id}/cancellations", traveler.getId()).with(authentication(fullAuth())))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void overview_kpis_openAndTotalDisputes() throws Exception {
        dispute(bid(announcement(traveler), sender), sender, traveler, null, DisputeTypes.STATUS_OPEN);
        dispute(bid(announcement(traveler), sender), sender, traveler, null, DisputeTypes.STATUS_OPEN);
        dispute(bid(announcement(traveler), sender), sender, traveler, null, DisputeTypes.STATUS_RESOLVED);

        overview(traveler, fullAuth())
                .andExpect(jsonPath("$.kpis.openDisputes").value(2))
                .andExpect(jsonPath("$.kpis.totalDisputes").value(3));
    }

    @Test
    void overview_kpis_newUserHasZeroDisputes() throws Exception {
        overview(saveUser(), fullAuth())
                .andExpect(jsonPath("$.kpis.openDisputes").value(0))
                .andExpect(jsonPath("$.kpis.totalDisputes").value(0));
    }

    @Test
    void disputes_withoutDisputeView_returns403() throws Exception {
        var auth = authWith("USER_VIEW", "BID_VIEW");
        mockMvc.perform(get("/admin/users/{id}/disputes", traveler.getId()).with(authentication(auth)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/users/{id}/cancellations", traveler.getId()).with(authentication(auth)))
                .andExpect(status().isForbidden());
    }

    @Test
    void overview_withoutDisputeView_omitsDisputeKpis() throws Exception {
        dispute(bid(announcement(traveler), sender), sender, traveler, null, DisputeTypes.STATUS_OPEN);
        overview(traveler, authWith("USER_VIEW", "BID_VIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.openDisputes").doesNotExist())
                .andExpect(jsonPath("$.kpis.totalDisputes").doesNotExist());
    }
}
