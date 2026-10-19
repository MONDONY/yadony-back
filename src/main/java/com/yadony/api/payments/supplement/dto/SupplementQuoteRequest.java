package com.yadony.api.payments.supplement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record SupplementQuoteRequest(@NotNull @Positive BigDecimal netAmount) {}
