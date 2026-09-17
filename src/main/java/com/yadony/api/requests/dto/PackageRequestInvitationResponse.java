package com.yadony.api.requests.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record PackageRequestInvitationResponse(UUID announcementId, LocalDateTime createdAt) {}
