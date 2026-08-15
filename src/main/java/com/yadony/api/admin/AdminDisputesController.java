package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminCancellationResponse;
import com.yadony.api.admin.dto.AdminDisputeDetailResponse;
import com.yadony.api.admin.dto.AdminDisputeListItemResponse;
import com.yadony.api.admin.dto.AdminGuaranteeFundRequest;
import com.yadony.api.admin.dto.AdminResolveDisputeRequest;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.disputes.events.DisputeResolvedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.yadony.api.auth.UserEntity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminDisputesController {

    private final DisputeRepository disputeRepo;
    private final CancellationRepository cancellationRepo;
    private final AuditService auditService;
    private final UserRepository userRepo;
    private final ApplicationEventPublisher eventPublisher;

    public AdminDisputesController(DisputeRepository disputeRepo,
                                   CancellationRepository cancellationRepo,
                                   AuditService auditService,
                                   UserRepository userRepo,
                                   ApplicationEventPublisher eventPublisher) {
        this.disputeRepo = disputeRepo;
        this.cancellationRepo = cancellationRepo;
        this.auditService = auditService;
        this.userRepo = userRepo;
        this.eventPublisher = eventPublisher;
    }

    // -------------------------------------------------------------------------
    // Disputes
    // -------------------------------------------------------------------------

    @PreAuthorize("hasAuthority('DISPUTE_VIEW')")
    @GetMapping("/admin/disputes")
    public ResponseEntity<Page<AdminDisputeListItemResponse>> listDisputes(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<DisputeEntity> disputes = disputeRepo
                .findAdminFiltered(status, PageRequest.of(page, size, Sort.by("createdAt").descending()));

        Set<UUID> userIds = new HashSet<>();
        for (DisputeEntity d : disputes.getContent()) {
            if (d.getSenderId() != null) userIds.add(d.getSenderId());
            if (d.getTravelerId() != null) userIds.add(d.getTravelerId());
        }
        Map<UUID, UserEntity> usersById = userRepo.findAllById(userIds).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));

        Page<AdminDisputeListItemResponse> result = disputes.map(d -> toDisputeListItem(d, usersById));
        return ResponseEntity.ok(result);
    }

    @PreAuthorize("hasAuthority('DISPUTE_VIEW')")
    @GetMapping("/admin/disputes/{id}")
    public ResponseEntity<AdminDisputeDetailResponse> getDispute(@PathVariable UUID id) {
        DisputeEntity entity = findDisputeOrThrow(id);
        Set<UUID> ids = new HashSet<>();
        if (entity.getSenderId() != null) ids.add(entity.getSenderId());
        if (entity.getTravelerId() != null) ids.add(entity.getTravelerId());
        Map<UUID, UserEntity> usersById = userRepo.findAllById(ids).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
        return ResponseEntity.ok(toDisputeDetail(entity, usersById));
    }

    @PreAuthorize("hasAuthority('DISPUTE_RESOLVE')")
    @PostMapping("/admin/disputes/{id}/resolve")
    @Transactional
    public ResponseEntity<AdminDisputeDetailResponse> resolveDispute(
            @PathVariable UUID id,
            @RequestBody AdminResolveDisputeRequest request) {

        DisputeEntity entity = findDisputeOrThrow(id);
        requireNotResolved(entity);
        entity.setStatus("RESOLVED");
        entity.setResolutionType(request.resolution());
        entity.setResolutionNote(request.note());
        entity.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        disputeRepo.save(entity);
        resolveLinkedCancellation(entity);

        auditService.log("DISPUTE", entity.getId(), "RESOLVE", null,
                Map.of("resolution", Objects.toString(request.resolution(), ""),
                       "note", Objects.toString(request.note(), "")));
        eventPublisher.publishEvent(new DisputeResolvedEvent(
                id, entity.getBidId(), entity.getSenderId(), entity.getTravelerId(),
                request.resolution()));

        Set<UUID> resolveIds = new HashSet<>();
        if (entity.getSenderId() != null) resolveIds.add(entity.getSenderId());
        if (entity.getTravelerId() != null) resolveIds.add(entity.getTravelerId());
        Map<UUID, UserEntity> resolveUsers = userRepo.findAllById(resolveIds).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
        return ResponseEntity.ok(toDisputeDetail(entity, resolveUsers));
    }

    @PreAuthorize("hasAuthority('DISPUTE_RESOLVE')")
    @PostMapping("/admin/disputes/{id}/guarantee-fund")
    @Transactional
    public ResponseEntity<AdminDisputeDetailResponse> payGuaranteeFund(
            @PathVariable UUID id,
            @RequestBody AdminGuaranteeFundRequest request) {

        DisputeEntity entity = findDisputeOrThrow(id);
        requireNotResolved(entity);
        entity.setStatus("RESOLVED");
        entity.setResolutionType("GUARANTEE_PAID");
        entity.setResolutionNote(request.reason());
        entity.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setBeneficiaryUserId(request.beneficiaryUserId());
        entity.setGuaranteeAmountCents((long) request.amountCents());
        disputeRepo.save(entity);
        resolveLinkedCancellation(entity);

        auditService.log("DISPUTE", entity.getId(), "GUARANTEE_FUND", null,
                Map.of("amountCents", request.amountCents(),
                       "beneficiaryUserId", Objects.toString(request.beneficiaryUserId() != null ? request.beneficiaryUserId().toString() : null, ""),
                       "reason", Objects.toString(request.reason(), "")));
        eventPublisher.publishEvent(new DisputeResolvedEvent(
                id, entity.getBidId(), entity.getSenderId(), entity.getTravelerId(),
                "GUARANTEE_PAID"));

        Set<UUID> gfIds = new HashSet<>();
        if (entity.getSenderId() != null) gfIds.add(entity.getSenderId());
        if (entity.getTravelerId() != null) gfIds.add(entity.getTravelerId());
        Map<UUID, UserEntity> gfUsers = userRepo.findAllById(gfIds).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
        return ResponseEntity.ok(toDisputeDetail(entity, gfUsers));
    }

    /** À la résolution d'un litige, transitionne l'annulation liée (si encore
     *  active) vers un statut terminal RESOLVED — sans quoi la bannière app
     *  reste bloquée sur PENDING_CONFIRMATION/CONTESTED indéfiniment. Le
     *  scope (HANDOVER/DELIVERY) est déduit du type de litige. */
    private void resolveLinkedCancellation(DisputeEntity entity) {
        if (entity.getBidId() == null) return;
        boolean isHandover = DisputeTypes.isHandover(entity.getType());
        Optional<CancellationEntity> cancellation = isHandover
                ? cancellationRepo.findByBidId(entity.getBidId())
                : cancellationRepo.findByBidIdAndScope(entity.getBidId(), CancellationScope.DELIVERY);
        cancellation.ifPresent(c -> {
            if (c.getNoShowStatus() == CancellationStatus.PENDING_CONFIRMATION
                    || c.getNoShowStatus() == CancellationStatus.CONTESTED) {
                c.setNoShowStatus(CancellationStatus.RESOLVED);
                cancellationRepo.save(c);
            }
        });
    }

    private void requireNotResolved(DisputeEntity entity) {
        if ("RESOLVED".equals(entity.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "dispute-already-resolved", "Dispute Already Resolved",
                    "Ce litige est déjà résolu");
        }
    }

    // -------------------------------------------------------------------------
    // Cancellations
    // -------------------------------------------------------------------------

    @PreAuthorize("hasAuthority('DISPUTE_VIEW')")
    @GetMapping("/admin/cancellations")
    public ResponseEntity<Page<AdminCancellationResponse>> listCancellations(
            @RequestParam(required = false) CancellationStatus noShowStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<AdminCancellationResponse> result = cancellationRepo
                .findAdminFiltered(noShowStatus, PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(this::toCancellationResponse);
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private DisputeEntity findDisputeOrThrow(UUID id) {
        return disputeRepo.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "dispute-not-found", "Not Found", "Litige introuvable"));
    }

    private String userName(UUID userId, Map<UUID, UserEntity> users) {
        if (userId == null) return null;
        UserEntity u = users.get(userId);
        if (u == null) return null;
        return MatchingTextUtil.buildName(u);
    }

    private AdminDisputeListItemResponse toDisputeListItem(DisputeEntity d, Map<UUID, UserEntity> users) {
        return new AdminDisputeListItemResponse(
                d.getId(),
                d.getBidId(),
                d.getType(),
                d.getStatus(),
                userName(d.getSenderId(), users),
                userName(d.getTravelerId(), users),
                d.isRefundFrozen(),
                d.getCreatedAt());
    }

    private AdminDisputeDetailResponse toDisputeDetail(DisputeEntity d, Map<UUID, UserEntity> users) {
        return new AdminDisputeDetailResponse(
                d.getId(),
                d.getBidId(),
                d.getType(),
                d.getStatus(),
                userName(d.getSenderId(), users),
                userName(d.getTravelerId(), users),
                d.isRefundFrozen(),
                d.getCreatedAt(),
                d.getResolutionType(),
                d.getResolvedAt(),
                d.getResolutionNote(),
                d.getBeneficiaryUserId());
    }

    private AdminCancellationResponse toCancellationResponse(CancellationEntity e) {
        return new AdminCancellationResponse(
                e.getId(),
                e.getBidId(),
                e.getCancelledBy(),
                e.getReason(),
                e.getNoShowStatus() != null ? e.getNoShowStatus().name() : null,
                e.getContestationDeadline(),
                e.getCreatedAt());
    }
}
