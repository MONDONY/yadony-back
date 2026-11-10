package com.yadony.api.videomodal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/** Listes d'une campagne stockées en JSON dans une colonne TEXT. */
final class VideoModalJsonConverters {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VideoModalJsonConverters() {
    }

    abstract static class JsonList<T> implements AttributeConverter<List<T>, String> {

        private final TypeReference<List<T>> type;

        JsonList(TypeReference<List<T>> type) {
            this.type = type;
        }

        @Override
        public String convertToDatabaseColumn(List<T> value) {
            try {
                return MAPPER.writeValueAsString(value == null ? List.of() : value);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Liste non sérialisable", e);
            }
        }

        @Override
        public List<T> convertToEntityAttribute(String json) {
            if (json == null || json.isBlank()) return new ArrayList<>();
            try {
                return new ArrayList<>(MAPPER.readValue(json, type));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Colonne JSON illisible", e);
            }
        }
    }

    @Converter
    static class Highlights extends JsonList<VideoModalHighlight> {
        Highlights() {
            super(new TypeReference<>() { });
        }
    }

    @Converter
    static class Triggers extends JsonList<VideoModalTrigger> {
        Triggers() {
            super(new TypeReference<>() { });
        }
    }
}
