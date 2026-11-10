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

    @Converter
    static class Platforms extends JsonList<VideoModalPlatform> {
        Platforms() {
            super(new TypeReference<>() { });
        }
    }

    @Converter
    static class Situations extends JsonList<VideoModalSituation> {
        Situations() {
            super(new TypeReference<>() { });
        }
    }

    @Converter
    static class Corridors extends JsonList<VideoModalCorridor> {
        Corridors() {
            super(new TypeReference<>() { });
        }
    }

    /** Seuils des moments ; colonne vide = seuils par défaut. */
    @Converter
    static class TriggerOptions implements AttributeConverter<VideoModalTriggerOptions, String> {

        @Override
        public String convertToDatabaseColumn(VideoModalTriggerOptions value) {
            try {
                return MAPPER.writeValueAsString(value == null ? VideoModalTriggerOptions.DEFAULTS : value);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Seuils non sérialisables", e);
            }
        }

        @Override
        public VideoModalTriggerOptions convertToEntityAttribute(String json) {
            if (json == null || json.isBlank()) return VideoModalTriggerOptions.DEFAULTS;
            try {
                return MAPPER.readValue(json, VideoModalTriggerOptions.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Colonne JSON illisible", e);
            }
        }
    }

    /** Action d'un bouton INFO ; colonne vide = pas d'action. */
    @Converter
    static class Action implements AttributeConverter<VideoModalAction, String> {

        @Override
        public String convertToDatabaseColumn(VideoModalAction value) {
            if (value == null) return null;
            try {
                return MAPPER.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Action non sérialisable", e);
            }
        }

        @Override
        public VideoModalAction convertToEntityAttribute(String json) {
            if (json == null || json.isBlank()) return null;
            try {
                return MAPPER.readValue(json, VideoModalAction.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Colonne JSON illisible", e);
            }
        }
    }
}
