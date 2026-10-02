package com.yadony.api.activation.dto;

import com.yadony.api.activation.IntentSource;
import com.yadony.api.activation.UserIntent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Corps de PUT /users/me/intent. destinationCountry absent = « Autre ». */
public record DeclareIntentRequest(
        @NotNull UserIntent intent,
        @Pattern(regexp = "[A-Za-z]{2}") String destinationCountry,
        @NotNull IntentSource source) {}
