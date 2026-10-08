package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminDisputeDetailResponse;
import com.yadony.api.admin.dto.AdminDisputeListItemResponse;
import com.yadony.api.admin.dto.AdminGuaranteeFundRequest;
import com.yadony.api.admin.dto.AdminResolveDisputeRequest;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.currency.SupportedCurrency;
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
import org.springframework.security.core.Authentication;
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
    private final BidRepository bidRepo;
    private final com.yadony.api.payments.split.PaymentSplitService splitService;
    private final org.springframework.transaction.support.TransactionTemplate decisionTransaction;

    public AdminDisputesController(DisputeRepository disputeRepo,
                                   CancellationRepository cancellationRepo,
                                   AuditService auditService,
                                   UserRepository userRepo,
                                   ApplicationEventPublisher eventPublisher,
                                   BidRepository bidRepo,
                                   com.yadony.api.payments.split.PaymentSplitService splitService,
                                   org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.disputeRepo = disputeRepo;
        this.cancellationRepo = cancellationRepo;
        this.auditService = auditService;
        this.userRepo = userRepo;
        this.eventPublisher = eventPublisher;
        this.bidRepo = bidRepo;
        this.splitService = splitService;
        this.decisionTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
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

    /**
     * Résout un litige. Avec {@code senderRefundAmount} + {@code travelerPayoutAmount}
     * (FLUTTER-E2), le séquestre du colis est en plus partagé : la décision, le claim du
     * paiement et la ligne de partage sont commités ensemble, puis les étapes Stripe s'exécutent.
     * Un échec Stripe n'annule pas la décision : la réponse porte {@code split.status} et
     * {@code split.lastError}, et {@code POST /admin/disputes/{id}/split/retry} reprend.
     * Partager déplace de l'argent dans les deux sens : il faut aussi PAYMENT_RELEASE et
     * PAYMENT_REFUND.
     */
    @PreAuthorize("hasAuthority('DISPUTE_RESOLVE')")
    @PostMapping("/admin/disputes/{id}/resolve")
    public ResponseEntity<AdminDisputeDetailResponse> resolveDispute(
            @PathVariable UUID id,
            @RequestBody AdminResolveDisputeRequest request,
            Authentication authentication) {

        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        if (request.hasSplit()) {
            requireMoneyAuthorities(authentication);
        }
        DisputeEntity entity = decisionTransaction.execute(status -> {
            DisputeEntity d = findDisputeOrThrow(id);
            requireNotResolved(d);
            com.yadony.api.payments.split.PaymentSplitService.SplitPlan plan = request.hasSplit()
                    ? splitService.plan(d.getBidId(), request.senderRefundAmount(), request.travelerPayoutAmount())
                    : null;
            d.setStatus("RESOLVED");
            d.setResolutionType(request.resolution());
            d.setResolutionNote(request.note());
            d.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
            if (plan != null) {
                d.setSenderRefundAmount(plan.senderRefund());
                d.setTravelerPayoutAmount(plan.travelerPayout());
                d.setSplitCurrency(plan.currency());
            }
            disputeRepo.save(d);
            resolveLinkedCancellation(d);
            if (plan != null) {
                splitService.claim(plan, d.getId(), adminId);
            }

            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("resolution", Objects.toString(request.resolution(), ""));
            payload.put("note", Objects.toString(request.note(), ""));
            if (plan != null) {
                payload.put("senderRefundAmount", plan.senderRefund().toPlainString());
                payload.put("travelerPayoutAmount", plan.travelerPayout().toPlainString());
                payload.put("currency", plan.currency());
            }
            auditService.log("DISPUTE", d.getId(), "RESOLVE", adminId, payload);
            eventPublisher.publishEvent(new DisputeResolvedEvent(
                    id, d.getBidId(), d.getSenderId(), d.getTravelerId(), request.resolution()));
            return d;
        });

        splitService.findForDispute(id).ifPresent(split -> splitService.execute(split.getId()));
        return ResponseEntity.ok(toDisputeDetail(entity, usersOf(entity)));
    }

    /** Montants répartissables du colis d'un litige (formulaire de partage). */
    @PreAuthorize("hasAuthority('DISPUTE_VIEW')")
    @GetMapping("/admin/disputes/{id}/split-options")
    public ResponseEntity<com.yadony.api.admin.dto.AdminDisputeSplitOptionsResponse> splitOptions(@PathVariable UUID id) {
        DisputeEntity entity = findDisputeOrThrow(id);
        var a = splitService.availability(entity.getBidId());
        return ResponseEntity.ok(new com.yadony.api.admin.dto.AdminDisputeSplitOptionsResponse(
                a.splittable() && !"RESOLVED".equals(entity.getStatus()),
                "RESOLVED".equals(entity.getStatus()) ? "dispute-already-resolved" : a.reasonCode(),
                a.currency(), a.amount(), a.commission(), a.refunded(), a.netAvailable(), a.rail(), a.paymentStatus()));
    }

    /** Reprend un partage interrompu (échec Stripe entre le remboursement et le transfert). */
    @PreAuthorize("hasAuthority('DISPUTE_RESOLVE')")
    @PostMapping("/admin/disputes/{id}/split/retry")
    public ResponseEntity<AdminDisputeDetailResponse> retrySplit(@PathVariable UUID id, Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        requireMoneyAuthorities(authentication);
        DisputeEntity entity = findDisputeOrThrow(id);
        var split = splitService.findForDispute(id)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "split-not-found",
                        "Not Found", "Aucun partage pour ce litige"));
        if (split.getStatus() == com.yadony.api.payments.split.PaymentSplitStatus.COMPLETED) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "split-already-completed",
                    "Split Already Completed", "Ce partage est déjà entièrement exécuté");
        }
        auditService.log("DISPUTE", id, "SPLIT_RETRY", adminId,
                Map.of("splitId", split.getId().toString(), "status", split.getStatus().name()));
        splitService.execute(split.getId());
        return ResponseEntity.ok(toDisputeDetail(entity, usersOf(entity)));
    }

    private static void requireMoneyAuthorities(Authentication authentication) {
        java.util.Set<String> granted = authentication == null ? Set.of()
                : authentication.getAuthorities().stream()
                        .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());
        if (!granted.contains("PAYMENT_RELEASE") || !granted.contains("PAYMENT_REFUND")) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "split-permission-required",
                    "Permission Required",
                    "Partager un paiement exige les droits de libération et de remboursement");
        }
    }

    private Map<UUID, UserEntity> usersOf(DisputeEntity entity) {
        Set<UUID> ids = new HashSet<>();
        if (entity.getSenderId() != null) ids.add(entity.getSenderId());
        if (entity.getTravelerId() != null) ids.add(entity.getTravelerId());
        return userRepo.findAllById(ids).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
    }

    @PreAuthorize("hasAuthority('DISPUTE_RESOLVE')")
    @PostMapping("/admin/disputes/{id}/guarantee-fund")
    @Transactional
    public ResponseEntity<AdminDisputeDetailResponse> payGuaranteeFund(
            @PathVariable UUID id,
            @RequestBody AdminGuaranteeFundRequest request,
            Authentication authentication) {

        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        DisputeEntity entity = findDisputeOrThrow(id);
        requireNotResolved(entity);
        // Un versement sans bénéficiaire était enregistré tel quel : personne à payer, et le
        // montant restait invisible de l'export comptable. Le back-office envoyait un champ vide.
        if (request.beneficiaryUserId() == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "guarantee-beneficiary-required", "Guarantee Beneficiary Required",
                    "Indiquez à qui verser le fonds de garantie");
        }
        if (request.amountCents() <= 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "guarantee-amount-invalid", "Guarantee Amount Invalid",
                    "Le montant du fonds de garantie doit être positif");
        }
        String currency = resolveGuaranteeCurrency(entity, request.currency());
        entity.setStatus("RESOLVED");
        entity.setResolutionType("GUARANTEE_PAID");
        entity.setResolutionNote(request.reason());
        entity.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setBeneficiaryUserId(request.beneficiaryUserId());
        entity.setGuaranteeAmountCents((long) request.amountCents());
        entity.setGuaranteeCurrency(currency);
        disputeRepo.save(entity);
        resolveLinkedCancellation(entity);

        auditService.log("DISPUTE", entity.getId(), "GUARANTEE_FUND", adminId,
                Map.of("amountCents", request.amountCents(),
                       "currency", currency,
                       "beneficiaryUserId", request.beneficiaryUserId().toString(),
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

    /**
     * La devise du versement est celle du bid du litige : un fonds de garantie saisi « en
     * euros » sur un colis en francs CFA versait 655 fois trop peu, ou l'inverse. Une devise
     * explicite doit lui correspondre ; sans bid, elle est obligatoire.
     */
    private String resolveGuaranteeCurrency(DisputeEntity entity, String requested) {
        String bidCurrency = entity.getBidId() != null
                ? bidRepo.findById(entity.getBidId()).map(b -> b.getCurrency()).orElse(null)
                : null;
        String normalizedRequested = requested != null && !requested.isBlank()
                ? requested.trim().toUpperCase(java.util.Locale.ROOT) : null;
        if (normalizedRequested != null && SupportedCurrency.fromCode(normalizedRequested) == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "currency-unsupported", "Currency Unsupported",
                    "Cette devise n'est pas prise en charge par yadony.");
        }
        if (bidCurrency != null) {
            String normalizedBid = bidCurrency.toUpperCase(java.util.Locale.ROOT);
            if (normalizedRequested != null && !normalizedRequested.equals(normalizedBid)) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "guarantee-currency-mismatch", "Guarantee Currency Mismatch",
                        "Le fonds de garantie se verse dans la devise du colis (" + normalizedBid + ")");
            }
            return normalizedBid;
        }
        if (normalizedRequested == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "guarantee-currency-required", "Guarantee Currency Required",
                    "Indiquez la devise du fonds de garantie");
        }
        return normalizedRequested;
    }

    private void requireNotResolved(DisputeEntity entity) {
        if ("RESOLVED".equals(entity.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "dispute-already-resolved", "Dispute Already Resolved",
                    "Ce litige est déjà résolu");
        }
    }

    // La file des no-shows (GET /admin/cancellations) et leur arbitrage vivent dans
    // cancellation/AdminNoShowController : la logique d'annulation reste dans cancellation/.

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
                d.getBeneficiaryUserId(),
                d.getGuaranteeAmountCents(),
                d.getGuaranteeCurrency(),
                d.getSenderId(),
                d.getTravelerId(),
                bidCurrencyOf(d),
                d.getSenderRefundAmount(),
                d.getTravelerPayoutAmount(),
                d.getSplitCurrency(),
                splitOf(d));
    }

    private com.yadony.api.admin.dto.AdminDisputeSplitResponse splitOf(DisputeEntity d) {
        if (d.getId() == null) return null;
        return splitService.findForDispute(d.getId())
                .map(s -> new com.yadony.api.admin.dto.AdminDisputeSplitResponse(
                        s.getId(), s.getSenderRefundAmount(), s.getTravelerPayoutAmount(), s.getCurrency(),
                        s.getMode().name(), s.getStatus().name(), s.getAttempts(), s.getLastError(),
                        s.getStripeRefundId(), s.getStripeTransferId(), s.getCompletedAt()))
                .orElse(null);
    }

    private String bidCurrencyOf(DisputeEntity d) {
        if (d.getBidId() == null) {
            return null;
        }
        return bidRepo.findById(d.getBidId())
                .map(b -> b.getCurrency() != null ? b.getCurrency().toUpperCase(java.util.Locale.ROOT) : null)
                .orElse(null);
    }

}
