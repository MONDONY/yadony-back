package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.PriceGridItemRequest;
import com.yadony.api.matching.dto.PriceGridItemResponse;
import com.yadony.api.matching.dto.PriceGridReorderRequest;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/travelers/me/price-grid")
public class PriceGridController {

    private final PriceGridService priceGridService;
    private final UserRepository userRepository;

    public PriceGridController(PriceGridService priceGridService,
                               UserRepository userRepository) {
        this.priceGridService = priceGridService;
        this.userRepository = userRepository;
    }

    // ── GET /travelers/me/price-grid ──────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasRole('TRAVELER') or hasRole('ADMIN')")
    public ResponseEntity<List<PriceGridItemResponse>> getMyPriceGrid() {
        UUID travelerId = resolveUserId();
        return ResponseEntity.ok(priceGridService.getItems(travelerId));
    }

    // ── POST /travelers/me/price-grid/items ───────────────────────────────────

    @PostMapping("/items")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<PriceGridItemResponse> addItem(
            @Valid @RequestBody PriceGridItemRequest request) {
        UUID travelerId = resolveUserId();
        PriceGridItemResponse created = priceGridService.addItem(travelerId, request, travelerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── PUT /travelers/me/price-grid/items/{itemId} ───────────────────────────

    @PutMapping("/items/{itemId}")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<PriceGridItemResponse> updateItem(
            @PathVariable UUID itemId,
            @Valid @RequestBody PriceGridItemRequest request) {
        UUID travelerId = resolveUserId();
        PriceGridItemResponse updated = priceGridService.updateItem(travelerId, itemId, request, travelerId);
        return ResponseEntity.ok(updated);
    }

    // ── DELETE /travelers/me/price-grid/items/{itemId} ────────────────────────

    @DeleteMapping("/items/{itemId}")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<Void> deleteItem(@PathVariable UUID itemId) {
        UUID travelerId = resolveUserId();
        priceGridService.deleteItem(travelerId, itemId, travelerId);
        return ResponseEntity.noContent().build();
    }

    // ── PUT /travelers/me/price-grid/reorder ──────────────────────────────────

    @PutMapping("/reorder")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<List<PriceGridItemResponse>> reorder(
            @Valid @RequestBody PriceGridReorderRequest request) {
        UUID travelerId = resolveUserId();
        return ResponseEntity.ok(priceGridService.reorder(travelerId, request.orderedIds()));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private UUID resolveUserId() {
        String firebaseUid = requireFirebaseUid();
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        return user.getId();
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthorized", "problem.auth.token-required");
        }
        return (String) auth.getPrincipal();
    }
}
