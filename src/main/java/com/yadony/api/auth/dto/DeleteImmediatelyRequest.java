package com.yadony.api.auth.dto;

import jakarta.validation.constraints.AssertTrue;

public record DeleteImmediatelyRequest(
        @AssertTrue(message = "{validation.account.delete.confirm}")
        boolean confirmationAcknowledged
) {}
