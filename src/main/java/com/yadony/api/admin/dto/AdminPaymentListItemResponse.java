package com.yadony.api.admin.dto;

import com.yadony.api.payments.PaymentEntity;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminPaymentListItemResponse(
        UUID id,
        UUID bidId,
        String status,
        String method,
        long amountCents,
        long commissionCents,
        LocalDateTime createdAt
) {
    public static AdminPaymentListItemResponse from(PaymentEntity p) {
        return new AdminPaymentListItemResponse(
                p.getId(),
                p.getBidId(),
                p.getStatus().name(),
                p.getRail().name(),
                AdminWalletResponse.toCents(p.getAmount()),
                AdminWalletResponse.toCents(p.getCommissionAmount()),
                p.getCreatedAt()
        );
    }
}
