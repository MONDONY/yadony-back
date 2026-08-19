package com.yadony.api.search.dto;

public record RecognizedField(String field, String value, int[] span, double confidence) {}
