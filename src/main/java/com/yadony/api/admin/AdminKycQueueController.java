package com.yadony.api.admin;

import com.yadony.api.kyc.KycAdminReviewService;
import com.yadony.api.kyc.KycRejectionCodes;
import com.yadony.api.kyc.dto.KycQueueItemResponse;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * File de revue des verifications d'identite. Lecture sous {@code USER_KYC} : le support la
 * consulte, seules les decisions ({@code AdminUserKycController}) exigent {@code KYC_DECIDE}.
 */
@RestController
@RequestMapping("/admin/kyc")
@PreAuthorize("hasRole('ADMIN')")
public class AdminKycQueueController {

    private final KycAdminReviewService review;

    public AdminKycQueueController(KycAdminReviewService review) {
        this.review = review;
    }

    /**
     * @param status {@code IN_REVIEW} (defaut), {@code IN_PROGRESS}, {@code NOT_STARTED},
     *               {@code REJECTED}, {@code VERIFIED}
     * @param query  UUID utilisateur, telephone E.164 ou nom
     * @param size   plafonne a 100
     */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_KYC')")
    @GetMapping("/verifications")
    public Page<KycQueueItemResponse> queue(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) VerificationProviderKind provider,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return review.queue(status, provider, query, from, to, page, size);
    }

    /** Catalogue ferme des codes de refus et de revocation, dans l'ordre d'affichage. */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_KYC')")
    @GetMapping("/rejection-codes")
    public List<String> rejectionCodes() {
        return KycRejectionCodes.ALL;
    }
}
