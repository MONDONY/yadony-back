package com.yadony.api.assistant.formdraft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.assistant.llm.LlmCallException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lit le JSON du modèle ; tout écart lève INVALID_OUTPUT pour déclencher la bascule de fournisseur. */
@Component
public class FormDraftParser {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public FormDraftParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ModelOutput parse(String content) {
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
        JsonNode draft = node.get("draft");
        if (draft == null || !draft.isObject()) throw invalid("draft manquant");
        JsonNode confirm = node.get("needsConfirmation");
        JsonNode question = node.get("question");
        String q = question != null && question.isTextual() && !question.asText().isBlank()
                ? question.asText().strip() : null;
        List<String> replies = new ArrayList<>();
        JsonNode rs = node.get("quickReplies");
        if (rs != null && rs.isArray()) {
            for (JsonNode r : rs) if (r.isTextual()) replies.add(r.asText());
        }
        return new ModelOutput(objectMapper.convertValue(draft, MAP),
                confirm != null && confirm.isBoolean() && confirm.asBoolean(), q, List.copyOf(replies));
    }

    private static LlmCallException invalid(String reason) {
        return new LlmCallException(LlmCallException.INVALID_OUTPUT, "Réponse du modèle inexploitable : " + reason);
    }
}
