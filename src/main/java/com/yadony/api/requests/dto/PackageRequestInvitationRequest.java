package com.yadony.api.requests.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PackageRequestInvitationRequest(@NotNull UUID announcementId) {}
