package com.yadony.api.search.dto;

import com.yadony.api.search.UnresolvedKind;

import java.util.List;

public record UnresolvedItem(UnresolvedKind kind, String phrase, List<String> options) {}
