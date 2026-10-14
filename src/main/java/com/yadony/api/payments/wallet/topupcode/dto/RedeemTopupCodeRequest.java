package com.yadony.api.payments.wallet.topupcode.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RedeemTopupCodeRequest(@NotBlank @Size(max = 64) String code) {}
