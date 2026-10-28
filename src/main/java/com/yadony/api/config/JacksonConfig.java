package com.yadony.api.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.yadony.api.common.BoundedDecimals;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Project convention: every LocalDateTime in entities/DTOs is UTC. We force the
 * JSON serializer to append the "Z" offset so that consumers (Flutter, web)
 * parse it as UTC instead of as a naive local-zone instant.
 *
 * Without this, Jackson's default LocalDateTimeSerializer emits
 * "2026-05-03T12:00:00", which Dart's DateTime.parse interprets as the device's
 * local zone, shifting every displayed timestamp by the user's UTC offset.
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcLocalDateTimeCustomizer() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(LocalDateTime.class, new UtcLocalDateTimeSerializer());
        module.addDeserializer(BigDecimal.class, new BoundedBigDecimalDeserializer());
        return builder -> builder.modulesToInstall(module);
    }

    /**
     * Refuse un décimal démesuré ({@code 1e10000000}) dès la lecture du corps : sinon le
     * premier {@code setScale} d'un service fige un thread de longues secondes (voir
     * {@link BoundedDecimals}). L'erreur remonte en 400 {@code malformed-request}.
     */
    static final class BoundedBigDecimalDeserializer extends NumberDeserializers.BigDecimalDeserializer {
        @Override
        public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            BigDecimal value = super.deserialize(p, ctxt);
            if (!BoundedDecimals.isBounded(value)) {
                throw ctxt.weirdNumberException(value, BigDecimal.class, "nombre hors limites");
            }
            return value;
        }
    }

    static final class UtcLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(value.atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
    }
}
