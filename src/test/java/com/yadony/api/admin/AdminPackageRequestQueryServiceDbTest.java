package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminPackageRequestDetailResponse;
import com.yadony.api.admin.dto.AdminPackageRequestListItemResponse;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.requests.dto.PackageRequestPhotoResponse;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.requests.service.PackageRequestModerationService;
import com.yadony.api.requests.service.PackageRequestPhotoService;
import com.yadony.api.requests.service.PackageRequestService;
import com.yadony.api.requests.specification.PackageRequestSpecifications;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Liste et fiche admin des demandes d'envoi, traduites en SQL par Hibernate (H2, profil
 * test) : filtres, sous-requêtes (signalements, expéditeur), compteurs, et non-régression de
 * la recherche publique qui ne doit jamais montrer une demande retirée.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({AdminPackageRequestQueryService.class, PackageRequestModerationService.class})
class AdminPackageRequestQueryServiceDbTest {

    @Autowired AdminPackageRequestQueryService service;
    @Autowired PackageRequestRepository requestRepository;
    @Autowired TestEntityManager em;

    @MockitoBean FirebaseContactService firebaseContactService;
    @MockitoBean PackageRequestPhotoService photoService;
    @MockitoBean StorageService storageService;
    @MockitoBean PackageRequestService packageRequestService;
    @MockitoBean AuditService auditService;

    private UserEntity awa;
    private UserEntity moussa;

    @BeforeEach
    void setUp() {
        awa = user("Awa", "Ndiaye", "uid-awa");
        moussa = user("Moussa", "Diop", "uid-moussa");
        when(firebaseContactService.findUidByPhone(any())).thenReturn(Optional.empty());
        when(photoService.activePhotos(any())).thenReturn(List.of());
    }

    private UserEntity user(String first, String last, String uid) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setUsername("u" + uid.replace("-", ""));
        u.setFirstName(first);
        u.setLastName(last);
        return em.persistAndFlush(u);
    }

    private PackageRequestEntity request(UUID senderId, String from, String to, PackageRequestStatus status) {
        PackageRequestEntity e = new PackageRequestEntity();
        e.setSenderId(senderId);
        e.setDepartureCity(from);
        e.setArrivalCity(to);
        e.setDesiredDate(LocalDate.now(ZoneOffset.UTC).plusDays(7));
        e.setDateToleranceDays((short) 2);
        e.setWeightKg(new BigDecimal("5.00"));
        e.setParcelSize(ParcelSize.SMALL);
        e.setTransportMode(TransportMode.PLANE);
        e.setContentCategory("vetements");
        e.setDescription("Vêtements pour la famille");
        e.setTargetPriceEur(new BigDecimal("40.00"));
        e.setStatus(status);
        return requestRepository.saveAndFlush(e);
    }

    private NegotiationThreadEntity thread(UUID requestId, UUID travelerId, NegotiationThreadStatus status) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(requestId);
        t.setTravelerId(travelerId);
        t.setTravelerTravelDate(LocalDate.now().plusDays(7));
        t.setTravelerAvailableKg(new BigDecimal("10.00"));
        t.setStatus(status);
        t.setCurrentPriceEur(new BigDecimal("35.00"));
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        return em.persistAndFlush(t);
    }

    private ReportEntity report(UUID requestId, UUID reporterId, boolean deleted) {
        ReportEntity r = new ReportEntity();
        r.setTargetType(ReportTargetType.PACKAGE_REQUEST);
        r.setTargetId(requestId);
        r.setReporterId(reporterId);
        r.setReason(ReportReason.SCAM_ATTEMPT);
        r.setDescription("frauduleuse");
        r.setStatus(ReportStatus.OPEN);
        if (deleted) {
            r.softDelete();
        }
        return em.persistAndFlush(r);
    }

    private static List<UUID> ids(Page<AdminPackageRequestListItemResponse> page) {
        return page.getContent().stream().map(AdminPackageRequestListItemResponse::id).toList();
    }

    @Test
    void list_filtresStatutVilleNomIdEtSignales() {
        PackageRequestEntity dakar = request(awa.getId(), "Paris", "Dakar", PackageRequestStatus.OPEN);
        PackageRequestEntity abidjan = request(moussa.getId(), "Lyon", "Abidjan", PackageRequestStatus.REMOVED_BY_ADMIN);
        PackageRequestEntity bamako = request(moussa.getId(), "Marseille", "Bamako", PackageRequestStatus.NEGOTIATING);
        report(abidjan.getId(), awa.getId(), false);
        report(bamako.getId(), awa.getId(), true); // supprimé : ne compte pas

        assertThat(service.list(null, null, false, null, null, 0, 20).getTotalElements()).isEqualTo(3);
        assertThat(ids(service.list(PackageRequestStatus.REMOVED_BY_ADMIN, null, false, null, null, 0, 20)))
                .containsExactly(abidjan.getId());
        assertThat(ids(service.list(null, "DAKAR", false, null, null, 0, 20))).containsExactly(dakar.getId());
        assertThat(ids(service.list(null, "moussa di", false, null, null, 0, 20)))
                .containsExactlyInAnyOrder(abidjan.getId(), bamako.getId());
        assertThat(ids(service.list(null, dakar.getId().toString(), false, null, null, 0, 20)))
                .containsExactly(dakar.getId());
        assertThat(ids(service.list(null, "  ", true, null, null, 0, 20))).containsExactly(abidjan.getId());
    }

    @Test
    void list_rechercheParTelephone_resolueParFirebase() {
        PackageRequestEntity dakar = request(awa.getId(), "Paris", "Dakar", PackageRequestStatus.OPEN);
        request(moussa.getId(), "Lyon", "Abidjan", PackageRequestStatus.OPEN);
        when(firebaseContactService.findUidByPhone("+33612345678")).thenReturn(Optional.of("uid-awa"));

        assertThat(ids(service.list(null, "+33612345678", false, null, null, 0, 20))).containsExactly(dakar.getId());
    }

    @Test
    void list_ligneComplete_compteursEtTriDecroissant() {
        PackageRequestEntity first = request(awa.getId(), "Paris", "Dakar", PackageRequestStatus.NEGOTIATING);
        PackageRequestEntity second = request(awa.getId(), "Paris", "Douala", PackageRequestStatus.OPEN);
        thread(first.getId(), moussa.getId(), NegotiationThreadStatus.OPEN);
        thread(first.getId(), moussa.getId(), NegotiationThreadStatus.CANCELLED);
        report(first.getId(), moussa.getId(), false);
        report(first.getId(), UUID.randomUUID(), false);

        Page<AdminPackageRequestListItemResponse> page = service.list(null, null, false, null, null, 0, 20);

        assertThat(ids(page)).containsExactly(second.getId(), first.getId());
        AdminPackageRequestListItemResponse row = page.getContent().get(1);
        assertThat(row.senderId()).isEqualTo(awa.getId());
        assertThat(row.senderName()).isEqualTo("Awa Ndiaye");
        assertThat(row.departureCity()).isEqualTo("Paris");
        assertThat(row.arrivalCity()).isEqualTo("Dakar");
        assertThat(row.weightKg()).isEqualByComparingTo("5");
        assertThat(row.parcelSize()).isEqualTo(ParcelSize.SMALL);
        assertThat(row.transportMode()).isEqualTo(TransportMode.PLANE);
        assertThat(row.status()).isEqualTo(PackageRequestStatus.NEGOTIATING);
        assertThat(row.currency()).isEqualTo("EUR");
        assertThat(row.targetPrice()).isEqualByComparingTo("40");
        assertThat(row.createdAt()).isNotNull();
        assertThat(row.reportCount()).isEqualTo(2);
        assertThat(row.openNegotiationCount()).isEqualTo(1);
    }

    @Test
    void list_bornesDeDatesEtPagination() {
        request(awa.getId(), "Paris", "Dakar", PackageRequestStatus.OPEN);
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();

        assertThat(service.list(null, null, false, today, today, 0, 20).getTotalElements()).isEqualTo(1);
        assertThat(service.list(null, null, false, tomorrow, null, 0, 20).getTotalElements()).isZero();
        assertThat(service.list(null, null, false, null, "2000-01-01T00:00:00Z", 0, 20).getTotalElements()).isZero();
        assertThat(service.list(null, null, false, "2000-01-01T00:00:00", null, -3, 1000).getSize())
                .isEqualTo(AdminPackageRequestQueryService.MAX_PAGE_SIZE);
        assertThatThrownBy(() -> service.list(null, null, false, "hier", null, 0, 20))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(t -> assertThat(((YadonyBusinessException) t).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void detail_ficheComplete_negociationsSignalementsEtVerdicts() {
        PackageRequestEntity r = request(awa.getId(), "Paris", "Dakar", PackageRequestStatus.NEGOTIATING);
        r.setPickupNeighborhood("18e");
        r.setDeliveryNeighborhood("Plateau");
        requestRepository.saveAndFlush(r);
        NegotiationThreadEntity t = thread(r.getId(), moussa.getId(), NegotiationThreadStatus.OPEN);
        report(r.getId(), moussa.getId(), false);
        when(photoService.activePhotos(r.getId()))
                .thenReturn(List.of(new PackageRequestPhotoResponse(UUID.randomUUID(), "k", "https://signed/1")));

        AdminPackageRequestDetailResponse d = service.detail(r.getId());

        assertThat(d.senderName()).isEqualTo("Awa Ndiaye");
        assertThat(d.description()).isEqualTo("Vêtements pour la famille");
        assertThat(d.contentCategory()).isEqualTo("vetements");
        assertThat(d.pickupNeighborhood()).isEqualTo("18e");
        assertThat(d.deliveryNeighborhood()).isEqualTo("Plateau");
        assertThat(d.photos()).containsExactly(new AdminPackageRequestDetailResponse.Photo("https://signed/1"));
        assertThat(d.negotiations()).singleElement().satisfies(n -> {
            assertThat(n.id()).isEqualTo(t.getId());
            assertThat(n.travelerName()).isEqualTo("Moussa Diop");
            assertThat(n.status()).isEqualTo(NegotiationThreadStatus.OPEN);
            assertThat(n.lastPrice()).isEqualByComparingTo("35");
        });
        assertThat(d.reports()).singleElement().satisfies(rep -> {
            assertThat(rep.reporterName()).isEqualTo("Moussa Diop");
            assertThat(rep.reason()).isEqualTo("SCAM_ATTEMPT");
            assertThat(rep.details()).isEqualTo("frauduleuse");
        });
        assertThat(d.reportCount()).isEqualTo(1);
        assertThat(d.openNegotiationCount()).isEqualTo(1);
        assertThat(d.canRemove()).isTrue();
        assertThat(d.removeBlockedReason()).isNull();
        assertThat(d.canRestore()).isFalse();
    }

    @Test
    void detail_retiree_restaurableEtPhotoLegacyPresignee() {
        PackageRequestEntity r = request(awa.getId(), "Paris", "Dakar", PackageRequestStatus.REMOVED_BY_ADMIN);
        r.setStatusBeforeRemoval(PackageRequestStatus.OPEN);
        r.setPhotoUrl("package-requests/legacy.jpg");
        requestRepository.saveAndFlush(r);
        when(storageService.generatePresignedUrl(any(), any())).thenReturn("https://signed/legacy");

        AdminPackageRequestDetailResponse d = service.detail(r.getId());

        assertThat(d.status()).isEqualTo(PackageRequestStatus.REMOVED_BY_ADMIN);
        assertThat(d.statusBeforeRemoval()).isEqualTo(PackageRequestStatus.OPEN);
        assertThat(d.photos()).containsExactly(new AdminPackageRequestDetailResponse.Photo("https://signed/legacy"));
        assertThat(d.canRemove()).isFalse();
        assertThat(d.removeBlockedReason()).isEqualTo("package-request-already-removed");
        assertThat(d.canRestore()).isTrue();
    }

    @Test
    void detail_filPaye_retraitBloque() {
        PackageRequestEntity r = request(awa.getId(), "Paris", "Dakar", PackageRequestStatus.NEGOTIATING);
        thread(r.getId(), moussa.getId(), NegotiationThreadStatus.AWAITING_DEPOSIT);

        AdminPackageRequestDetailResponse d = service.detail(r.getId());

        assertThat(d.canRemove()).isFalse();
        assertThat(d.removeBlockedReason()).isEqualTo("package-request-has-active-shipment");
    }

    @Test
    void detail_introuvable_404() {
        assertThatThrownBy(() -> service.detail(UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(t -> assertThat(((YadonyBusinessException) t).getErrorCode())
                        .isEqualTo("package-request-not-found"));
    }

    @Test
    void rechercheePublique_nExposeJamaisUneDemandeRetiree() {
        PackageRequestEntity open = request(awa.getId(), "Paris", "Dakar", PackageRequestStatus.OPEN);
        request(awa.getId(), "Paris", "Dakar", PackageRequestStatus.REMOVED_BY_ADMIN);

        assertThat(requestRepository.findAll(PackageRequestSpecifications.openOnly()))
                .extracting(PackageRequestEntity::getId).containsExactly(open.getId());
        assertThat(requestRepository.findOpenOrNegotiatingByCorridor("Paris", "Dakar"))
                .extracting(PackageRequestEntity::getId).containsExactly(open.getId());
        assertThat(requestRepository.findExpired(LocalDate.now(ZoneOffset.UTC).plusDays(30)))
                .extracting(PackageRequestEntity::getId).containsExactly(open.getId());
    }
}
