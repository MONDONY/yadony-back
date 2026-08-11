package com.yadony.api.kyc;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.util.UUID;

@Entity
@Table(name = "kyc_verifications", schema = "kyc_schema")
@Where(clause = "deleted_at IS NULL")
public class KycVerificationEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "stripe_verification_session_id", length = 255)
    private String stripeVerificationSessionId;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private KycVerificationStatus status = KycVerificationStatus.PENDING;

    @Column(name = "rejection_reason", length = 512)
    private String rejectionReason;

    @Column(name = "rejection_code", length = 64)
    private String rejectionCode;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getStripeVerificationSessionId() { return stripeVerificationSessionId; }
    public void setStripeVerificationSessionId(String id) { this.stripeVerificationSessionId = id; }

    public KycVerificationStatus getStatus() { return status; }
    public void setStatus(KycVerificationStatus status) { this.status = status; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public String getRejectionCode() { return rejectionCode; }
    public void setRejectionCode(String rejectionCode) { this.rejectionCode = rejectionCode; }
}
