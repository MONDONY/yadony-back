package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /admin/bids} de bout en bout (MockMvc + base H2 partagée) : paramètres, nouveaux champs,
 * alerte la plus urgente, droits. Chaque test se restreint à ses propres colis (annonce ou jeton unique).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminBidsListIT — GET /admin/bids")
class AdminBidsListIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired DisputeRepository disputeRepository;

    private UserEntity traveler;
    private UserEntity sender;
    private AnnouncementEntity trip;
    private String token;

    @BeforeEach
    void setUp() {
        token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        traveler = saveUser("Mamadou", "Trav" + token);
        sender = saveUser("Aicha", "Send" + token);
        trip = announcement();
    }

    // ---------------------------------------------------------------- helpers

    private static UsernamePasswordAuthenticationToken as(AdminRole role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (AdminPermission p : role.permissions()) {
            authorities.add(new SimpleGrantedAuthority(p.name()));
        }
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "a@yadony.test", role, false, "uid-" + role), null, authorities);
    }

    /** Authentification avec exactement ces droits (plus ROLE_ADMIN). */
    private static UsernamePasswordAuthenticationToken authWith(AdminPermission... permissions) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (AdminPermission p : permissions) {
            authorities.add(new SimpleGrantedAuthority(p.name()));
        }
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "s@yadony.test", AdminRole.SUPPORT, false, "uid-s"),
                null, authorities);
    }

    private UserEntity saveUser(String firstName, String lastName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        return userRepository.save(u);
    }

    private AnnouncementEntity announcement() {
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
        return announcementRepository.save(a);
    }

    private BidEntity bid(BidStatus status) {
        return bid(status, b -> { });
    }

    private BidEntity bid(BidStatus status, Consumer<BidEntity> customize) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(trip.getId());
        b.setSenderId(sender.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(status);
        b.setCurrency("EUR");
        b.setTrackingNumber("YD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        customize.accept(b);
        return bidRepository.save(b);
    }

    private void openDispute(BidEntity b) {
        DisputeEntity d = new DisputeEntity();
        d.setBidId(b.getId());
        d.setStatus("OPEN");
        disputeRepository.save(d);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder list(
            UsernamePasswordAuthenticationToken auth, String... params) {
        var req = get("/admin/bids").with(authentication(auth)).param("announcementId", trip.getId().toString());
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return req;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void list_withoutBidView_403() throws Exception {
        mockMvc.perform(get("/admin/bids").with(authentication(authWith(AdminPermission.USER_VIEW))))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_returnsNewFields() throws Exception {
        BidEntity named = bid(BidStatus.ACCEPTED, b -> b.setRecipientName("Awa Ndiaye"));
        mockMvc.perform(list(as(AdminRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(named.getId().toString()))
                .andExpect(jsonPath("$.content[0].senderId").value(sender.getId().toString()))
                .andExpect(jsonPath("$.content[0].travelerId").value(traveler.getId().toString()))
                .andExpect(jsonPath("$.content[0].recipientName").value("Awa Ndiaye"))
                .andExpect(jsonPath("$.content[0].departureDate").value(trip.getDepartureDate().toString()))
                .andExpect(jsonPath("$.content[0].updatedAt").exists())
                .andExpect(jsonPath("$.content[0].alert").doesNotExist());
    }

    @Test
    void list_recipientNameAbsentWhenNotFilled() throws Exception {
        bid(BidStatus.ACCEPTED);
        mockMvc.perform(list(as(AdminRole.ADMIN)))
                .andExpect(jsonPath("$.content[0].recipientName").doesNotExist());
    }

    @Test
    void list_alertField_mostUrgentOnly() throws Exception {
        BidEntity b = bid(BidStatus.NO_SHOW);
        openDispute(b);
        mockMvc.perform(list(as(AdminRole.ADMIN)))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].alert").value("DISPUTE_OPEN"));
    }

    @Test
    void list_viewTodo_ordersByUrgency() throws Exception {
        BidEntity noShow = bid(BidStatus.NO_SHOW);
        BidEntity disputed = bid(BidStatus.ACCEPTED);
        openDispute(disputed);
        bid(BidStatus.ACCEPTED); // sans alerte : hors de « À traiter »
        mockMvc.perform(list(as(AdminRole.ADMIN), "view", "TODO"))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(disputed.getId().toString()))
                .andExpect(jsonPath("$.content[0].alert").value("DISPUTE_OPEN"))
                .andExpect(jsonPath("$.content[1].id").value(noShow.getId().toString()))
                .andExpect(jsonPath("$.content[1].alert").value("NO_SHOW"));
    }

    @Test
    void list_query_twoWords() throws Exception {
        BidEntity b = bid(BidStatus.ACCEPTED);
        mockMvc.perform(list(as(AdminRole.ADMIN), "query", "Aicha Send" + token))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(b.getId().toString()));
        mockMvc.perform(list(as(AdminRole.ADMIN), "query", "Aicha Absent" + token))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void list_support_cannotSearchByRecipientPhone() throws Exception {
        String phone = "+22177" + token.replaceAll("[^0-9]", "").concat("0000000").substring(0, 7);
        BidEntity b = bid(BidStatus.ACCEPTED, x -> x.setRecipientPhone(phone));
        mockMvc.perform(list(authWith(AdminPermission.BID_VIEW), "query", phone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        // Avec le droit USER_VIEW, la même saisie trouve le colis : le droit seul fait la différence.
        mockMvc.perform(list(as(AdminRole.ADMIN), "query", phone))
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.hasItem(b.getId().toString())));
    }

    @Test
    void list_admin_canSearchByRecipientPhone_andPhoneStaysMaskedInDetail() throws Exception {
        String digits = "77" + token.replaceAll("[^0-9]", "").concat("0000000").substring(0, 7);
        String phone = "+221" + digits;
        BidEntity b = bid(BidStatus.ACCEPTED, x -> x.setRecipientPhone(phone));
        mockMvc.perform(list(as(AdminRole.ADMIN), "query", phone))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(b.getId().toString()));
        var body = mockMvc.perform(get("/admin/bids/" + b.getId()).with(authentication(as(AdminRole.ADMIN))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain(digits);
    }

    @Test
    void list_unknownView_400() throws Exception {
        mockMvc.perform(list(as(AdminRole.ADMIN), "view", "NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void list_unknownScopePaymentMethodAlert_400() throws Exception {
        mockMvc.perform(list(as(AdminRole.ADMIN), "scope", "NOPE")).andExpect(status().isBadRequest());
        mockMvc.perform(list(as(AdminRole.ADMIN), "paymentMethod", "NOPE")).andExpect(status().isBadRequest());
        mockMvc.perform(list(as(AdminRole.ADMIN), "alert", "NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void list_defaultExcludesNegotiation_butExplicitStatusHonored() throws Exception {
        BidEntity thread = bid(BidStatus.NEGOTIATING);
        mockMvc.perform(list(as(AdminRole.ADMIN))).andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(list(as(AdminRole.ADMIN), "status", "NEGOTIATING"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(thread.getId().toString()));
    }

    @Test
    void list_oldClientParams_stillWork() throws Exception {
        BidEntity b = bid(BidStatus.ACCEPTED);
        mockMvc.perform(list(as(AdminRole.ADMIN), "status", "ACCEPTED", "query", "Send" + token,
                        "dateFrom", "2000-01-01T00:00:00", "dateTo", "2999-01-01T00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(b.getId().toString()));
    }
}
