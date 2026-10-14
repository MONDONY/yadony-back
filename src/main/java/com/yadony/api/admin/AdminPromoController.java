package com.yadony.api.admin;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.promo.PromoCodeEntity;
import com.yadony.api.promo.PromoCodeRepository;
import com.yadony.api.promo.PromoCodeStatus;
import com.yadony.api.promo.PromoCodeTarget;
import com.yadony.api.promo.dto.CreatePromoRequest;
import com.yadony.api.promo.dto.PromoCodeResponse;
import com.yadony.api.promo.dto.UpdatePromoRequest;
import com.yadony.api.promo.dto.UpdatePromoStatusRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/promo-codes")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('PROMO_MANAGE')")
public class AdminPromoController {

    private final PromoCodeRepository promoCodeRepository;
    private final AuditService auditService;

    public AdminPromoController(PromoCodeRepository promoCodeRepository,
                                AuditService auditService) {
        this.promoCodeRepository = promoCodeRepository;
        this.auditService = auditService;
    }

    @PostMapping
    public ResponseEntity<PromoCodeResponse> create(@Valid @RequestBody CreatePromoRequest request) {
        if (promoCodeRepository.findByCode(request.code().toUpperCase()).isPresent()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "promo-code-exists", "Promo Code Exists",
                    "Un code promo avec ce code existe déjà : " + request.code().toUpperCase());
        }

        PromoCodeEntity promo = new PromoCodeEntity();
        promo.setCode(request.code());
        promo.setRate(request.rate());
        promo.setTarget(request.target() != null ? request.target() : PromoCodeTarget.ANY);
        promo.setValidFrom(request.validFrom());
        promo.setValidTo(request.validTo());
        promo.setMaxRedemptions(request.maxRedemptions());
        promo.setPerUserLimit(request.perUserLimit() != null ? request.perUserLimit() : 1);
        promo.setStatus(PromoCodeStatus.ACTIVE);
        PromoCodeEntity saved = promoCodeRepository.save(promo);

        auditService.log("PROMO", saved.getId(), "PROMO_CODE_CREATED", adminId(),
                Map.of("code", saved.getCode(), "rate", saved.getRate().toPlainString()));

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping
    public List<PromoCodeResponse> list() {
        return promoCodeRepository.findAll().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public PromoCodeResponse get(@PathVariable UUID id) {
        return toResponse(findOrThrow(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PromoCodeResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePromoRequest request) {
        PromoCodeEntity promo = findOrThrow(id);
        if (request.rate() != null)           promo.setRate(request.rate());
        if (request.target() != null)         promo.setTarget(request.target());
        if (request.validFrom() != null)      promo.setValidFrom(request.validFrom());
        if (request.validTo() != null)        promo.setValidTo(request.validTo());
        if (request.maxRedemptions() != null) promo.setMaxRedemptions(request.maxRedemptions());
        if (request.perUserLimit() != null)   promo.setPerUserLimit(request.perUserLimit());
        PromoCodeEntity saved = promoCodeRepository.save(promo);
        auditService.log("PROMO", id, "PROMO_CODE_UPDATED", adminId(),
                Map.of("fields", request.toString()));
        return ResponseEntity.ok(toResponse(saved));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<PromoCodeResponse> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePromoStatusRequest request) {
        PromoCodeEntity promo = findOrThrow(id);
        promo.setStatus(request.status());
        PromoCodeEntity saved = promoCodeRepository.save(promo);
        auditService.log("PROMO", id, "PROMO_CODE_STATUS_UPDATED", adminId(),
                Map.of("status", request.status().name()));
        return ResponseEntity.ok(toResponse(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        PromoCodeEntity promo = findOrThrow(id);
        promo.softDelete();
        promoCodeRepository.save(promo);
        auditService.log("PROMO", id, "PROMO_CODE_DELETED", adminId(), Map.of("code", promo.getCode()));
        return ResponseEntity.noContent().build();
    }

    private PromoCodeEntity findOrThrow(UUID id) {
        return promoCodeRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "promo-not-found", "Promo Not Found", "Code promo introuvable"));
    }

    private UUID adminId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof com.yadony.api.auth.UserEntity user) {
            return user.getId();
        }
        return null;
    }

    private PromoCodeResponse toResponse(PromoCodeEntity p) {
        return new PromoCodeResponse(
                p.getId(), p.getCode(), p.getRate(), p.getTarget(),
                p.getValidFrom(), p.getValidTo(), p.getMaxRedemptions(),
                p.getPerUserLimit(), p.getRedeemedCount(), p.getStatus(),
                p.getCreatedAt(), p.getOwnerUserId(), p.getDiscountKind());
    }
}
