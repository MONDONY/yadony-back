package com.yadony.api.requests.service;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementRemovalReason;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.event.PackageRequestRemovedByAdminEvent;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackageRequestModerationServiceTest {

    @Mock private PackageRequestRepository requestRepository;
    @Mock private NegotiationThreadRepository threadRepository;
    @Mock private PackageRequestService packageRequestService;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PackageRequestModerationService service;

    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PackageRequestModerationService(requestRepository, threadRepository,
                packageRequestService, auditService, eventPublisher);
        lenient().when(requestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static PackageRequestEntity request(PackageRequestStatus status) {
        PackageRequestEntity e = new PackageRequestEntity();
        ReflectionTestUtils.setField(e, "id", REQUEST_ID);
        e.setSenderId(SENDER_ID);
        e.setStatus(status);
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDesiredDate(LocalDate.now(ZoneOffset.UTC).plusDays(10));
        e.setDateToleranceDays((short) 2);
        return e;
    }

    private static NegotiationThreadEntity thread(NegotiationThreadStatus status) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setPackageRequestId(REQUEST_ID);
        t.setTravelerId(UUID.randomUUID());
        t.setStatus(status);
        return t;
    }

    private void givenLocked(PackageRequestEntity e, NegotiationThreadEntity... threads) {
        when(requestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(e));
        lenient().when(threadRepository.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of(threads));
    }

    private static String code(Throwable t) {
        return ((YadonyBusinessException) t).getErrorCode();
    }

    // ── Classement des fils de négociation ──────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = NegotiationThreadStatus.class, names = {"ACCEPTED", "AWAITING_DEPOSIT"})
    void blocksRemoval_filsQuiOntEngageDeLArgent(NegotiationThreadStatus status) {
        assertThat(PackageRequestModerationService.blocksRemoval(thread(status))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = NegotiationThreadStatus.class,
            names = {"OPEN", "AWAITING_TRIP", "AWAITING_PAYMENT", "AWAITING_COMMISSION",
                     "REJECTED", "CANCELLED", "AUTO_REJECTED", "EXPIRED"})
    void blocksRemoval_filsSansArgentEngage(NegotiationThreadStatus status) {
        assertThat(PackageRequestModerationService.blocksRemoval(thread(status))).isFalse();
    }

    @Test
    void blocksRemoval_commissionEnVolOuReglee_bloque() {
        NegotiationThreadEntity viaCard = thread(NegotiationThreadStatus.AWAITING_COMMISSION);
        viaCard.setCommissionPaymentIntentId("pi_123");
        NegotiationThreadEntity viaWallet = thread(NegotiationThreadStatus.AWAITING_COMMISSION);
        viaWallet.setCommissionChargedVia("WALLET");

        assertThat(PackageRequestModerationService.blocksRemoval(viaCard)).isTrue();
        assertThat(PackageRequestModerationService.blocksRemoval(viaWallet)).isTrue();
    }

    @Test
    void blocksRemoval_colisMaterialise_bloqueQuelQueSoitLeStatut() {
        NegotiationThreadEntity t = thread(NegotiationThreadStatus.CANCELLED);
        t.setMaterializedBidId(UUID.randomUUID());

        assertThat(PackageRequestModerationService.blocksRemoval(t)).isTrue();
    }

    @Test
    void removalBlockedReason_selonLeStatutDeLaDemande() {
        assertThat(service.removalBlockedReason(request(PackageRequestStatus.OPEN), List.of())).isNull();
        assertThat(service.removalBlockedReason(request(PackageRequestStatus.EXPIRED), List.of())).isNull();
        assertThat(service.removalBlockedReason(request(PackageRequestStatus.REMOVED_BY_ADMIN), List.of()))
                .isEqualTo("package-request-already-removed");
        assertThat(service.removalBlockedReason(request(PackageRequestStatus.DRAFT), List.of()))
                .isEqualTo("package-request-draft");
        assertThat(service.removalBlockedReason(request(PackageRequestStatus.COMPLETED), List.of()))
                .isEqualTo("package-request-completed");
        assertThat(service.removalBlockedReason(request(PackageRequestStatus.ACCEPTED), List.of()))
                .isEqualTo("package-request-has-active-shipment");
        assertThat(service.removalBlockedReason(request(PackageRequestStatus.NEGOTIATING),
                List.of(thread(NegotiationThreadStatus.OPEN), thread(NegotiationThreadStatus.AWAITING_DEPOSIT))))
                .isEqualTo("package-request-has-active-shipment");
    }

    // ── Retrait ─────────────────────────────────────────────────────────────

    @Test
    void remove_introuvable_404() {
        when(requestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeByAdmin(REQUEST_ID, ADMIN_ID, AnnouncementRemovalReason.OTHER, null))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(t -> assertThat(((YadonyBusinessException) t).getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .satisfies(t -> assertThat(code(t)).isEqualTo("package-request-not-found"));
    }

    @Test
    void remove_dejaRetiree_409() {
        givenLocked(request(PackageRequestStatus.REMOVED_BY_ADMIN));

        assertThatThrownBy(() -> service.removeByAdmin(REQUEST_ID, ADMIN_ID, AnnouncementRemovalReason.OTHER, null))
                .satisfies(t -> assertThat(((YadonyBusinessException) t).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .satisfies(t -> assertThat(code(t)).isEqualTo("package-request-already-removed"));
        verify(packageRequestService, never()).terminateActiveNegotiations(any(), any(), any(), any(), any());
    }

    @Test
    void remove_filAvecArgentEngage_409SansRienToucher() {
        PackageRequestEntity e = request(PackageRequestStatus.NEGOTIATING);
        givenLocked(e, thread(NegotiationThreadStatus.ACCEPTED));

        assertThatThrownBy(() -> service.removeByAdmin(REQUEST_ID, ADMIN_ID, AnnouncementRemovalReason.OTHER, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("package-request-has-active-shipment"))
                .hasMessageContaining("litige");
        assertThat(e.getStatus()).isEqualTo(PackageRequestStatus.NEGOTIATING);
        verify(requestRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void remove_annuleLesFilsParLeCheminExistant_memoriseLeStatut_auditeEtNotifieSansLaNote() {
        PackageRequestEntity e = request(PackageRequestStatus.NEGOTIATING);
        givenLocked(e, thread(NegotiationThreadStatus.OPEN), thread(NegotiationThreadStatus.AWAITING_PAYMENT));
        when(packageRequestService.terminateActiveNegotiations(REQUEST_ID, ADMIN_ID,
                PackageRequestModerationService.ADMIN_ACTOR_NAME, NegotiationThreadStatus.CANCELLED,
                "request-removed-by-admin")).thenReturn(2);

        PackageRequestEntity removed = service.removeByAdmin(REQUEST_ID, ADMIN_ID,
                AnnouncementRemovalReason.PROHIBITED_ITEM, "signalé par Awa, ticket #4821");

        assertThat(removed.getStatus()).isEqualTo(PackageRequestStatus.REMOVED_BY_ADMIN);
        assertThat(removed.getStatusBeforeRemoval()).isEqualTo(PackageRequestStatus.NEGOTIATING);
        verify(requestRepository).save(e);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("PACKAGE_REQUEST"), eq(REQUEST_ID), eq("PACKAGE_REQUEST_REMOVED_BY_ADMIN"),
                eq(ADMIN_ID), audit.capture());
        assertThat(audit.getValue())
                .containsEntry("publicReason", "PROHIBITED_ITEM")
                .containsEntry("internalNote", "signalé par Awa, ticket #4821")
                .containsEntry("cancelledNegotiations", "2")
                .containsEntry("previousStatus", "NEGOTIATING");

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(
                new PackageRequestRemovedByAdminEvent(REQUEST_ID, SENDER_ID, "PROHIBITED_ITEM"));
        assertThat(event.getValue().toString()).doesNotContain("4821");
    }

    @Test
    void remove_noteInterneAbsente_auditeUneChaineVide() {
        givenLocked(request(PackageRequestStatus.EXPIRED));

        service.removeByAdmin(REQUEST_ID, ADMIN_ID, AnnouncementRemovalReason.DUPLICATE, null);

        verify(auditService).log(eq("PACKAGE_REQUEST"), eq(REQUEST_ID), eq("PACKAGE_REQUEST_REMOVED_BY_ADMIN"),
                eq(ADMIN_ID), eq(Map.of("publicReason", "DUPLICATE", "internalNote", "",
                        "cancelledNegotiations", "0", "previousStatus", "EXPIRED")));
    }

    // ── Restauration ────────────────────────────────────────────────────────

    @Test
    void restore_introuvable_404() {
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restoreByAdmin(REQUEST_ID, ADMIN_ID))
                .satisfies(t -> assertThat(code(t)).isEqualTo("package-request-not-found"));
    }

    @Test
    void restore_nonRetiree_409() {
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request(PackageRequestStatus.OPEN)));

        assertThatThrownBy(() -> service.restoreByAdmin(REQUEST_ID, ADMIN_ID))
                .satisfies(t -> assertThat(((YadonyBusinessException) t).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .satisfies(t -> assertThat(code(t)).isEqualTo("package-request-not-removed"));
    }

    @Test
    void restore_rendLeStatutDOrigineEtAudite() {
        PackageRequestEntity e = request(PackageRequestStatus.REMOVED_BY_ADMIN);
        e.setStatusBeforeRemoval(PackageRequestStatus.OPEN);
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(e));

        PackageRequestEntity restored = service.restoreByAdmin(REQUEST_ID, ADMIN_ID);

        assertThat(restored.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
        assertThat(restored.getStatusBeforeRemoval()).isNull();
        verify(auditService).log("PACKAGE_REQUEST", REQUEST_ID, "PACKAGE_REQUEST_RESTORED_BY_ADMIN", ADMIN_ID,
                Map.of("restoredStatus", "OPEN"));
    }

    @Test
    void restore_negotiatingSansFilActif_redevientOpen() {
        PackageRequestEntity e = request(PackageRequestStatus.REMOVED_BY_ADMIN);
        e.setStatusBeforeRemoval(PackageRequestStatus.NEGOTIATING);
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(e));
        when(threadRepository.findByPackageRequestId(REQUEST_ID))
                .thenReturn(List.of(thread(NegotiationThreadStatus.CANCELLED)));

        assertThat(service.restoreByAdmin(REQUEST_ID, ADMIN_ID).getStatus()).isEqualTo(PackageRequestStatus.OPEN);
    }

    @Test
    void restore_negotiatingAvecFilActif_resteNegotiating() {
        PackageRequestEntity e = request(PackageRequestStatus.REMOVED_BY_ADMIN);
        e.setStatusBeforeRemoval(PackageRequestStatus.NEGOTIATING);
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(e));
        when(threadRepository.findByPackageRequestId(REQUEST_ID))
                .thenReturn(List.of(thread(NegotiationThreadStatus.OPEN)));

        assertThat(service.restoreByAdmin(REQUEST_ID, ADMIN_ID).getStatus())
                .isEqualTo(PackageRequestStatus.NEGOTIATING);
    }

    @Test
    void restore_dateSouhaiteePassee_expire() {
        PackageRequestEntity e = request(PackageRequestStatus.REMOVED_BY_ADMIN);
        e.setStatusBeforeRemoval(PackageRequestStatus.OPEN);
        e.setDesiredDate(LocalDate.now(ZoneOffset.UTC).minusDays(1));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(e));

        assertThat(service.restoreByAdmin(REQUEST_ID, ADMIN_ID).getStatus()).isEqualTo(PackageRequestStatus.EXPIRED);
        verify(auditService).log(eq("PACKAGE_REQUEST"), eq(REQUEST_ID), eq("PACKAGE_REQUEST_RESTORED_BY_ADMIN"),
                eq(ADMIN_ID), eq(Map.of("restoredStatus", "EXPIRED")));
    }

    @Test
    void restore_dateDuJour_resteOuverte() {
        PackageRequestEntity e = request(PackageRequestStatus.REMOVED_BY_ADMIN);
        e.setStatusBeforeRemoval(PackageRequestStatus.OPEN);
        e.setDesiredDate(LocalDate.now(ZoneOffset.UTC));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(e));

        assertThat(service.restoreByAdmin(REQUEST_ID, ADMIN_ID).getStatus()).isEqualTo(PackageRequestStatus.OPEN);
    }

    @Test
    void restore_statutDOrigineInconnu_retombeSurOpen() {
        PackageRequestEntity e = request(PackageRequestStatus.REMOVED_BY_ADMIN);
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(e));

        assertThat(service.restoreByAdmin(REQUEST_ID, ADMIN_ID).getStatus()).isEqualTo(PackageRequestStatus.OPEN);
    }

    @Test
    void restore_expireeAvantLeRetrait_resteExpiree() {
        PackageRequestEntity e = request(PackageRequestStatus.REMOVED_BY_ADMIN);
        e.setStatusBeforeRemoval(PackageRequestStatus.EXPIRED);
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(e));

        assertThat(service.restoreByAdmin(REQUEST_ID, ADMIN_ID).getStatus()).isEqualTo(PackageRequestStatus.EXPIRED);
        verify(auditService).log(eq("PACKAGE_REQUEST"), eq(REQUEST_ID), eq("PACKAGE_REQUEST_RESTORED_BY_ADMIN"),
                eq(ADMIN_ID), anyMap());
    }
}
