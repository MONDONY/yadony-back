package com.yadony.api.calls.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminCallResponse(UUID id, UUID conversationId, UUID callerId, UUID calleeId, String status,
                                OffsetDateTime startedAt, OffsetDateTime endedAt, Integer durationSeconds,
                                LocalDateTime createdAt) {}
