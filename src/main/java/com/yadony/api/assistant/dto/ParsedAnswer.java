package com.yadony.api.assistant.dto;

import java.util.List;

public record ParsedAnswer(String answer, boolean answered, List<String> routes) {
}
