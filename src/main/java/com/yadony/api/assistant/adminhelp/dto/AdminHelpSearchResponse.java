package com.yadony.api.assistant.adminhelp.dto;

import java.util.List;

public record AdminHelpSearchResponse(String query, List<AdminHelpSearchResult> results) {
}
