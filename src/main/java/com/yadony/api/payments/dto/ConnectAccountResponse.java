package com.yadony.api.payments.dto;

import com.yadony.api.auth.StripeAccountStatus;

import java.util.Set;

public record ConnectAccountResponse(
        String stripeAccountId,
        StripeAccountStatus stripeAccountStatus,
        Set<String> requirementsCurrentlyDue
) {}
