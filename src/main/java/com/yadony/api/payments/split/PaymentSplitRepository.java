package com.yadony.api.payments.split;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentSplitRepository extends JpaRepository<PaymentSplitEntity, UUID> {

    Optional<PaymentSplitEntity> findByPaymentId(UUID paymentId);

    Optional<PaymentSplitEntity> findByDisputeId(UUID disputeId);
}
