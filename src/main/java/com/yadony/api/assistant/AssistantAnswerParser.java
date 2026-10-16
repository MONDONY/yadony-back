package com.yadony.api.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.assistant.dto.ParsedAnswer;
import com.yadony.api.assistant.llm.LlmCallException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Lit le JSON du modèle ; tout écart lève INVALID_OUTPUT pour déclencher la bascule. */
@Component
public class AssistantAnswerParser {

    private final ObjectMapper objectMapper;

    public AssistantAnswerParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedAnswer parse(String content) {
        String json = content.strip();
        if (json.startsWith("```")) {
            int firstLine = json.indexOf('\n');
            int fence = json.lastIndexOf("```");
            json = firstLine > 0 && fence > firstLine ? json.substring(firstLine + 1, fence).strip() : json;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw invalid("pas du JSON");
        }
        if (node == null || !node.isObject()) throw invalid("pas un objet JSON");
        JsonNode answer = node.get("answer");
        JsonNode answered = node.get("answered");
        if (answer == null || !answer.isTextual() || answer.asText().isBlank()) throw invalid("answer manquant");
        if (answered == null || !answered.isBoolean()) throw invalid("answered manquant");
        List<String> routes = new ArrayList<>();
        JsonNode rs = node.get("routes");
        if (rs != null && rs.isArray()) {
            for (JsonNode r : rs) if (r.isTextual()) routes.add(r.asText());
        }
        return new ParsedAnswer(answer.asText().strip(), answered.asBoolean(), List.copyOf(routes));
    }

    private static LlmCallException invalid(String reason) {
        return new LlmCallException(LlmCallException.INVALID_OUTPUT, "Réponse du modèle inexploitable : " + reason);
    }
}
