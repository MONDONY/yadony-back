package com.yadony.api.assistant.llm;

public record LlmResult<T>(String slug, T value) {
}
