package com.yadony.api.messaging.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Ouverture d'une conversation de questions sur une annonce. */
public record InquiryRequest(@NotNull UUID announcementId) {}
