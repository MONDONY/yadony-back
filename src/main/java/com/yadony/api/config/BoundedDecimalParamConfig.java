package com.yadony.api.config;

import com.yadony.api.common.BoundedDecimals;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.math.BigDecimal;

/**
 * Même garde que {@code JacksonConfig.BoundedBigDecimalDeserializer}, pour les
 * {@code @RequestParam BigDecimal} (ex. {@code ?weight=1e10000000} sur l'estimation de prix,
 * publique). Un convertisseur String → BigDecimal direct passe avant la fabrique générique
 * String → Number de Spring. Le refus remonte en 400 via {@code handleTypeMismatch}.
 */
@Configuration
public class BoundedDecimalParamConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new BoundedBigDecimalConverter());
    }

    static final class BoundedBigDecimalConverter implements Converter<String, BigDecimal> {
        @Override
        public BigDecimal convert(String source) {
            String text = source.trim();
            if (text.isEmpty()) {
                return null;
            }
            BigDecimal value = new BigDecimal(text);
            if (!BoundedDecimals.isBounded(value)) {
                throw new IllegalArgumentException("nombre hors limites");
            }
            return value;
        }
    }
}
