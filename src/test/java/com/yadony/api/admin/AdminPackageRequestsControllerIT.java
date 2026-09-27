package com.yadony.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.AdminPackageRequestDetailResponse;
import com.yadony.api.admin.dto.AdminPackageRequestListItemResponse;
import com.yadony.api.admin.dto.RemovePackageRequestRequest;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementRemovalReason;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.service.PackageRequestModerationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdminPackageRequestsController} : permissions (BID_VIEW en lecture, CONTENT_REMOVE
 * pour retirer/restaurer), statuts HTTP métier et forme JSON. Les permissions sont celles
 * que {@code AdminRole} attribue réellement : SUPPORT a BID_VIEW mais pas CONTENT_REMOVE.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminPackageRequestsControllerIT — /admin/package-requests")
class AdminPackageRequestsControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean AdminPackageRequestQueryService queryService;
    @MockitoBean PackageRequestModerationService moderationService;

    private static final UUID ID = UUID.randomUUID();
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

    private static AdminPackageRequestDetailResponse detail(PackageRequestStatus status) {
        return new AdminPackageRequestDetailResponse(ID, UUID.randomUUID(), "Awa Ndiaye", "Paris", "Dakar",
                LocalDate.of(2026, 10, 1), 2, new BigDecimal("5.00"), ParcelSize.SMALL, TransportMode.PLANE,
                status, "EUR", new BigDecimal("40.00"), LocalDateTime.of(2026, 9, 1, 10, 0), 1, 1,
                "Vêtements", "CLOTHES", "Plateau", "Almadies", null, null, null,
                Set.of(PaymentMethod.CASH), true, null, List.of(new AdminPackageRequestDetailResponse.Photo("https://r2.test/signed?x=1")),
                List.of(new AdminPackageRequestDetailResponse.Negotiation(UUID.randomUUID(), UUID.randomUUID(),
                        "Moussa Diop", NegotiationThreadStatus.OPEN, new BigDecimal("35.00"), "EUR",
                        LocalDateTime.of(2026, 9, 2, 9, 0))),
                List.of(new AdminPackageRequestDetailResponse.Report(UUID.randomUUID(), UUID.randomUUID(),
                        "Fatou Sow", "SCAM_ATTEMPT", "frauduleuse", "OPEN", LocalDateTime.of(2026, 9, 3, 8, 0))),
                true, null, false);
    }

    private String removeBody(AnnouncementRemovalReason reason, String note) throws Exception {
        return objectMapper.writeValueAsString(new RemovePackageRequestRequest(reason, note));
    }

    // ── Permissions ─────────────────────────────────────────────────────────

    @Test
    void permissionsReelles_supportLitMaisNeRetirePas() {
        assertThat(AdminRole.SUPPORT.permissions()).contains(AdminPermission.BID_VIEW)
                .doesNotContain(AdminPermission.CONTENT_REMOVE);
        assertThat(AdminRole.ADMIN.permissions()).contains(AdminPermission.BID_VIEW, AdminPermission.CONTENT_REMOVE);
    }

    @Test
    void list_sansAuthentification_refuse() throws Exception {
        mockMvc.perform(get("/admin/package-requests")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    void list_sansBidView_403() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(ADMIN_ID, "a@yadony.test", AdminRole.SUPPORT, false, "uid"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        mockMvc.perform(get("/admin/package-requests").with(authentication(auth)))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_support_200_formeJsonEtParametres() throws Exception {
        var item = new AdminPackageRequestListItemResponse(ID, UUID.randomUUID(), "Awa Ndiaye", "Paris", "Dakar",
                LocalDate.of(2026, 10, 1), new BigDecimal("5.00"), ParcelSize.SMALL, TransportMode.PLANE,
                PackageRequestStatus.REMOVED_BY_ADMIN, "EUR", new BigDecimal("40.00"),
                LocalDateTime.of(2026, 9, 1, 10, 0), 3, 0);
        when(queryService.list(eq(PackageRequestStatus.REMOVED_BY_ADMIN), eq("dakar"), eq(true),
                eq("2026-09-01"), eq("2026-09-30"), eq(1), eq(50)))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(1, 50), 51));

        mockMvc.perform(get("/admin/package-requests")
                        .param("status", "REMOVED_BY_ADMIN").param("query", "dakar").param("reportedOnly", "true")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .param("page", "1").param("size", "50")
                        .with(authentication(as(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.content[0].senderName").value("Awa Ndiaye"))
                .andExpect(jsonPath("$.content[0].departureCity").value("Paris"))
                .andExpect(jsonPath("$.content[0].desiredDate").value("2026-10-01"))
                .andExpect(jsonPath("$.content[0].weightKg").value(5.00))
                .andExpect(jsonPath("$.content[0].parcelSize").value("SMALL"))
                .andExpect(jsonPath("$.content[0].transportMode").value("PLANE"))
                .andExpect(jsonPath("$.content[0].status").value("REMOVED_BY_ADMIN"))
                .andExpect(jsonPath("$.content[0].currency").value("EUR"))
                .andExpect(jsonPath("$.content[0].targetPrice").value(40.00))
                .andExpect(jsonPath("$.content[0].reportCount").value(3))
                .andExpect(jsonPath("$.content[0].openNegotiationCount").value(0))
                .andExpect(jsonPath("$.totalElements").value(51));
    }

    @Test
    void removeBlockedReason_slugEnMinuscules() throws Exception {
        var d = detail(PackageRequestStatus.NEGOTIATING);
        var blocked = new AdminPackageRequestDetailResponse(d.id(), d.senderId(), d.senderName(), d.departureCity(),
                d.arrivalCity(), d.desiredDate(), d.dateToleranceDays(), d.weightKg(), d.parcelSize(),
                d.transportMode(), d.status(), d.currency(), d.targetPrice(), d.createdAt(), d.reportCount(),
                d.openNegotiationCount(), d.description(), d.contentCategory(), d.pickupNeighborhood(),
                d.deliveryNeighborhood(), null, null, null, d.acceptedPaymentMethods(), d.negotiable(),
                null, d.photos(), d.negotiations(), d.reports(), false,
                PackageRequestModerationService.HAS_ACTIVE_SHIPMENT, false);
        when(queryService.detail(ID)).thenReturn(blocked);

        mockMvc.perform(get("/admin/package-requests/{id}", ID).with(authentication(as(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canRemove").value(false))
                .andExpect(jsonPath("$.removeBlockedReason").value("package-request-has-active-shipment"));
    }

    @Test
    void list_parametresParDefaut() throws Exception {
        when(queryService.list(isNull(), isNull(), eq(false), isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/admin/package-requests").with(authentication(as(AdminRole.ADMIN))))
                .andExpect(status().isOk());
        verify(queryService).list(isNull(), isNull(), eq(false), isNull(), isNull(), eq(0), eq(20));
    }

    @Test
    void list_statutInconnu_400() throws Exception {
        mockMvc.perform(get("/admin/package-requests").param("status", "NOPE")
                        .with(authentication(as(AdminRole.ADMIN))))
                .andExpect(status().isBadRequest());
        verify(queryService, never()).list(any(), any(), anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void detail_support_200_formeJson() throws Exception {
        when(queryService.detail(ID)).thenReturn(detail(PackageRequestStatus.OPEN));

        mockMvc.perform(get("/admin/package-requests/{id}", ID).with(authentication(as(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.description").value("Vêtements"))
                .andExpect(jsonPath("$.contentCategory").value("CLOTHES"))
                .andExpect(jsonPath("$.pickupNeighborhood").value("Plateau"))
                .andExpect(jsonPath("$.acceptedPaymentMethods[0]").value("CASH"))
                .andExpect(jsonPath("$.negotiable").value(true))
                .andExpect(jsonPath("$.statusBeforeRemoval").doesNotExist())
                .andExpect(jsonPath("$.photos[0].url").value("https://r2.test/signed?x=1"))
                .andExpect(jsonPath("$.negotiations[0].travelerName").value("Moussa Diop"))
                .andExpect(jsonPath("$.negotiations[0].status").value("OPEN"))
                .andExpect(jsonPath("$.negotiations[0].lastPrice").value(35.00))
                .andExpect(jsonPath("$.reports[0].reporterName").value("Fatou Sow"))
                .andExpect(jsonPath("$.reports[0].reason").value("SCAM_ATTEMPT"))
                .andExpect(jsonPath("$.reports[0].details").value("frauduleuse"))
                .andExpect(jsonPath("$.canRemove").value(true))
                .andExpect(jsonPath("$.removeBlockedReason").doesNotExist())
                .andExpect(jsonPath("$.canRestore").value(false));
    }

    @Test
    void detail_introuvable_404ProblemDetail() throws Exception {
        when(queryService.detail(ID)).thenThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "package-request-not-found", "Package Request Not Found", "Demande d'envoi introuvable"));

        mockMvc.perform(get("/admin/package-requests/{id}", ID).with(authentication(as(AdminRole.ADMIN))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("package-request-not-found"));
    }

    // ── Retrait ─────────────────────────────────────────────────────────────

    @Test
    void remove_support_403() throws Exception {
        mockMvc.perform(post("/admin/package-requests/{id}/remove", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(removeBody(AnnouncementRemovalReason.SUSPECTED_FRAUD, "ticket #4821"))
                        .with(authentication(as(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());
        verify(moderationService, never()).removeByAdmin(any(), any(), any(), any());
    }

    @Test
    void remove_admin_200_passeLAdminActeurEtRendLaFiche() throws Exception {
        when(queryService.detail(ID)).thenReturn(detail(PackageRequestStatus.REMOVED_BY_ADMIN));

        mockMvc.perform(post("/admin/package-requests/{id}/remove", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(removeBody(AnnouncementRemovalReason.SUSPECTED_FRAUD, "ticket #4821"))
                        .with(authentication(as(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED_BY_ADMIN"));
        verify(moderationService).removeByAdmin(ID, ADMIN_ID, AnnouncementRemovalReason.SUSPECTED_FRAUD, "ticket #4821");
    }

    @Test
    void remove_motifAbsent_422() throws Exception {
        mockMvc.perform(post("/admin/package-requests/{id}/remove", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internalNote\":\"x\"}")
                        .with(authentication(as(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity());
        verify(moderationService, never()).removeByAdmin(any(), any(), any(), any());
    }

    @Test
    void remove_conflit_409AvecCode() throws Exception {
        when(moderationService.removeByAdmin(eq(ID), eq(ADMIN_ID), any(), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT,
                        PackageRequestModerationService.HAS_ACTIVE_SHIPMENT, "Package Request Not Removable",
                        "Un envoi est engagé sur cette demande. Traitez-le par un litige."));

        mockMvc.perform(post("/admin/package-requests/{id}/remove", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(removeBody(AnnouncementRemovalReason.OTHER, null))
                        .with(authentication(as(AdminRole.ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("package-request-has-active-shipment"));
    }

    // ── Restauration ────────────────────────────────────────────────────────

    @Test
    void restore_support_403() throws Exception {
        mockMvc.perform(post("/admin/package-requests/{id}/restore", ID)
                        .with(authentication(as(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());
        verify(moderationService, never()).restoreByAdmin(any(), any());
    }

    @Test
    void restore_admin_200() throws Exception {
        when(queryService.detail(ID)).thenReturn(detail(PackageRequestStatus.OPEN));

        mockMvc.perform(post("/admin/package-requests/{id}/restore", ID)
                        .with(authentication(as(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        verify(moderationService).restoreByAdmin(ID, ADMIN_ID);
    }

    @Test
    void restore_nonRetiree_409() throws Exception {
        when(moderationService.restoreByAdmin(ID, ADMIN_ID)).thenThrow(new YadonyBusinessException(
                HttpStatus.CONFLICT, "package-request-not-removed", "Package Request Not Removed", "Non retirée"));

        mockMvc.perform(post("/admin/package-requests/{id}/restore", ID)
                        .with(authentication(as(AdminRole.ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("package-request-not-removed"));
    }
}
