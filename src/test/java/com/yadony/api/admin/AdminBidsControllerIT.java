package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.AdminBidDetailResponse;
import com.yadony.api.admin.dto.AdminBidTimelineResponse;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.util.ReflectionTestUtils.setField;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdminBidsController} : droits (ROLE_ADMIN + BID_VIEW), forme JSON de la fiche colis
 * enrichie (champs ajoutés en fin, jamais la valeur du code de remise) et chronologie.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminBidsControllerIT — /admin/bids")
class AdminBidsControllerIT {

    @Autowired MockMvc mockMvc;

    @MockitoBean BidRepository bidRepo;
    @MockitoBean AnnouncementRepository announcementRepo;
    @MockitoBean AdminBidDetailAssembler assembler;

    private static final UUID BID = UUID.randomUUID();
    private static final UUID ANN = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken as(AdminRole role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (AdminPermission p : role.permissions()) {
            authorities.add(new SimpleGrantedAuthority(p.name()));
        }
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(ADMIN_ID, "a@yadony.test", role, false, "uid-" + role), null, authorities);
    }

    private BidEntity bid() {
        BidEntity b = new BidEntity();
        setField(b, "id", BID);
        setField(b, "createdAt", LocalDateTime.of(2026, 10, 6, 18, 53));
        b.setAnnouncementId(ANN);
        b.setStatus(BidStatus.ACCEPTED);
        b.setCurrency("EUR");
        setField(b, "trackingNumber", "DON-8ANH6EZR");
        setField(b, "confirmationCode", "654321");
        return b;
    }

    @Test
    void detail_sansAuthentification_refuse() throws Exception {
        mockMvc.perform(get("/admin/bids/{id}", BID)).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    void detail_sansBidView_403() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(ADMIN_ID, "a@yadony.test", AdminRole.SUPPORT, false, "uid"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("BID_VIEW_NOT")));
        mockMvc.perform(get("/admin/bids/{id}", BID).with(authentication(auth)))
                .andExpect(status().isForbidden());
    }

    @Test
    void detail_inconnu_404ProblemDetail() throws Exception {
        when(bidRepo.findById(BID)).thenReturn(Optional.empty());
        mockMvc.perform(get("/admin/bids/{id}", BID).with(authentication(as(AdminRole.SUPPORT))))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains("application/problem+json"));
    }

    @Test
    void detail_support_200_champsAjoutesEnFin() throws Exception {
        BidEntity b = bid();
        AnnouncementEntity ann = new AnnouncementEntity();
        setField(ann, "id", ANN);
        ann.setDepartureCity("Paris");
        ann.setArrivalCity("Bamako");
        when(bidRepo.findById(BID)).thenReturn(Optional.of(b));
        when(announcementRepo.findById(ANN)).thenReturn(Optional.of(ann));
        UUID paymentId = UUID.randomUUID();
        var trip = new AdminBidDetailResponse.Trip(ANN, "ACTIVE", "Paris", "Bamako", "FR", "ML",
                LocalDate.of(2026, 10, 12), null, null, LocalDate.of(2026, 10, 13), null, "Europe/Paris",
                "Aéroport CDG", null, "PLANE", new BigDecimal("23"), new BigDecimal("15"), BigDecimal.ZERO, "KG",
                new BigDecimal("10"), null, null, null, 2);
        var traveler = new AdminBidDetailResponse.Party(UUID.randomUUID(), "Moussa Diallo", "moussa", "•••• 5678",
                "ACTIVE", "VERIFIED", "ONBOARDING_COMPLETE", true, "NOT_CONFIGURED", false);
        var money = new AdminBidDetailResponse.Money(paymentId, "ESCROW", "STRIPE", 4000, 480, 0, "EUR",
                Instant.parse("2026-10-06T19:00:00Z"), null, null, false);
        when(assembler.extras(any(), any(), any())).thenReturn(new AdminBidDetailAssembler.Extras(
                trip, null, traveler, new AdminBidDetailResponse.Recipient("Fatou", "•••• 1122"), money,
                new AdminBidDetailResponse.Links(null, null, null, "fs-42", null), true, List.of(), null));

        mockMvc.perform(get("/admin/bids/{id}", BID).with(authentication(as(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(BID.toString()))
                .andExpect(jsonPath("$.corridor").exists())
                .andExpect(jsonPath("$.trackingNumber").value("DON-8ANH6EZR"))
                .andExpect(jsonPath("$.trip.departureDate").value("2026-10-12"))
                .andExpect(jsonPath("$.trip.arrivalCountryCode").value("ML"))
                .andExpect(jsonPath("$.trip.otherBidsCount").value(2))
                .andExpect(jsonPath("$.traveler.stripeConnectUsable").value(true))
                .andExpect(jsonPath("$.traveler.phoneMasked").value("•••• 5678"))
                .andExpect(jsonPath("$.recipient.phoneMasked").value("•••• 1122"))
                .andExpect(jsonPath("$.money.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.money.commissionCents").value(480))
                .andExpect(jsonPath("$.links.conversationId").value("fs-42"))
                .andExpect(jsonPath("$.confirmationCodePresent").value(true))
                // Jamais la valeur du code de remise.
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("654321"));
    }

    @Test
    void timeline_support_200_entreesAvecAuteur() throws Exception {
        when(bidRepo.findById(BID)).thenReturn(Optional.of(bid()));
        when(assembler.timeline(any(), any())).thenReturn(List.of(new AdminBidTimelineResponse.Entry(
                LocalDateTime.of(2026, 10, 6, 18, 54, 1), "EVENT", "PRESENCE_CONFIRMED", null, null, null, null,
                "AUDIT", "USER", "Moussa Diallo")));

        mockMvc.perform(get("/admin/bids/{id}/timeline", BID).with(authentication(as(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bidId").value(BID.toString()))
                .andExpect(jsonPath("$.entries[0].label").value("PRESENCE_CONFIRMED"))
                .andExpect(jsonPath("$.entries[0].kind").value("EVENT"))
                .andExpect(jsonPath("$.entries[0].source").value("AUDIT"))
                .andExpect(jsonPath("$.entries[0].actorKind").value("USER"))
                .andExpect(jsonPath("$.entries[0].actorLabel").value("Moussa Diallo"));
    }

    @Test
    void annonces_parIdentifiant_200() throws Exception {
        AnnouncementEntity ann = new AnnouncementEntity();
        setField(ann, "id", ANN);
        ann.setStatus(AnnouncementStatus.ACTIVE);
        ann.setDepartureCity("Paris");
        ann.setArrivalCity("Bamako");
        ann.setDepartureDate(LocalDate.of(2026, 10, 12));
        when(announcementRepo.findById(ANN)).thenReturn(Optional.of(ann));

        mockMvc.perform(get("/admin/announcements").param("id", ANN.toString())
                        .with(authentication(as(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ANN.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void detail_nonAdmin_403() throws Exception {
        var user = new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("BID_VIEW")));
        mockMvc.perform(get("/admin/bids/{id}", BID).with(authentication(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/bids/{id}/timeline", BID).with(authentication(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    void timeline_sansAuthentification_refuse() throws Exception {
        mockMvc.perform(get("/admin/bids/{id}/timeline", BID)).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    /** BID_VIEW seul (surcharge qui retire le reste) : l'assembleur reçoit des droits vides. */
    @Test
    void detail_bidViewSeul_droitsFinsTransmisVides() throws Exception {
        when(bidRepo.findById(BID)).thenReturn(Optional.of(bid()));
        when(assembler.extras(any(), any(), any())).thenReturn(new AdminBidDetailAssembler.Extras(
                null, null, null, null, null, null, false, List.of(), null));
        when(assembler.timeline(any(), any())).thenReturn(List.of());
        var auth = new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(ADMIN_ID, "a@yadony.test", AdminRole.SUPPORT, false, "uid"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("BID_VIEW")));

        mockMvc.perform(get("/admin/bids/{id}", BID).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.money").doesNotExist());
        mockMvc.perform(get("/admin/bids/{id}/timeline", BID).with(authentication(auth)))
                .andExpect(status().isOk());

        var none = new AdminBidDetailAssembler.Access(false, false, false, false, false);
        org.mockito.Mockito.verify(assembler).extras(any(), any(), org.mockito.ArgumentMatchers.eq(none));
        org.mockito.Mockito.verify(assembler).timeline(any(), org.mockito.ArgumentMatchers.eq(none));
    }

    @Test
    void detail_support_droitsFinsDuRole() throws Exception {
        when(bidRepo.findById(BID)).thenReturn(Optional.of(bid()));
        when(assembler.extras(any(), any(), any())).thenReturn(new AdminBidDetailAssembler.Extras(
                null, null, null, null, null, null, false, List.of(), null));

        mockMvc.perform(get("/admin/bids/{id}", BID).with(authentication(as(AdminRole.SUPPORT))))
                .andExpect(status().isOk());

        // SUPPORT : paiement, utilisateurs, litiges, modération, mais pas AUDIT_VIEW (PAYMENT_VIEW suffit).
        org.mockito.Mockito.verify(assembler).extras(any(), any(),
                org.mockito.ArgumentMatchers.eq(new AdminBidDetailAssembler.Access(true, true, true, true, true)));
    }
}
