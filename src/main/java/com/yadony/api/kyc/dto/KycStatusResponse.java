package com.yadony.api.kyc.dto;

public record KycStatusResponse(
        String kycStatus,
        String verificationStatus,
        String rejectionReason,
        String rejectionCode
) {}
