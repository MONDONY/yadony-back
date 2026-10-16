package com.yadony.api.assistant.provider.dto;

public record ProviderTestResponse(boolean ok, Integer status, long latencyMs, String sample, String error) {
}
