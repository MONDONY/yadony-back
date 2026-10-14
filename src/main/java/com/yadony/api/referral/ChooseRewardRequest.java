package com.yadony.api.referral;

import jakarta.validation.constraints.NotNull;

/** Corps de {@code POST /me/referral/rewards/{invitationId}/choose}. */
public record ChooseRewardRequest(@NotNull ReferralRewardChoice choice) {}
