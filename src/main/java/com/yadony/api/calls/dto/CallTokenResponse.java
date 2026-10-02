package com.yadony.api.calls.dto;

import java.time.Instant;

public record CallTokenResponse(String apiKey, String userId, String token, Instant expiresAt) {}
