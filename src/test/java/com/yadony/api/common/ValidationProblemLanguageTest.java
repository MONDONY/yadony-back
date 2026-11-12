package com.yadony.api.common;

import com.yadony.api.common.i18n.TestMessages;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Retest R6 : titre et détail des 422 de validation suivent Accept-Language ; sans en-tête, anglais comme avant. */
class ValidationProblemLanguageTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(TestMessages.resolver());

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void requestWith(String acceptLanguage) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (acceptLanguage != null) {
            request.addHeader("Accept-Language", acceptLanguage);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @SuppressWarnings("unchecked")
    private ProblemDetail validation() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult binding = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(binding);
        when(binding.getFieldErrors()).thenReturn(List.of(new FieldError("o", "email", "invalide")));
        ProblemDetail body = handler.handleValidation(ex).getBody();
        assertThat(body.getStatus()).isEqualTo(422);
        assertThat(body.getType().toString()).endsWith("/validation");
        assertThat(body.getProperties()).containsKey("violations");
        assertThat((Map<String, Object>) body.getProperties().get("violations")).containsKey("email");
        return body;
    }

    @Test
    void francaisAvecAcceptLanguageFr() {
        requestWith("fr");
        ProblemDetail body = validation();
        assertThat(body.getTitle()).isEqualTo("Validation échouée");
        assertThat(body.getDetail()).isEqualTo("Certaines données sont invalides");
    }

    @Test
    void anglaisAvecAcceptLanguageEn() {
        requestWith("en");
        ProblemDetail body = validation();
        assertThat(body.getTitle()).isEqualTo("Validation Error");
        assertThat(body.getDetail()).isEqualTo("Validation failed");
    }

    @Test
    void anglaisSansAcceptLanguage() {
        requestWith(null);
        assertThat(validation().getDetail()).isEqualTo("Validation failed");
    }

    @Test
    void anglaisHorsRequeteHttp() {
        assertThat(validation().getDetail()).isEqualTo("Validation failed");
    }

    @Test
    void contraintesLocaliseesEnFrancais() {
        requestWith("fr");
        ProblemDetail body = handler.handleConstraintViolation(
                new ConstraintViolationException("x", Set.of())).getBody();
        assertThat(body.getStatus()).isEqualTo(422);
        assertThat(body.getTitle()).isEqualTo("Violation de contrainte");
        assertThat(body.getDetail()).isEqualTo("Paramètres de requête invalides");
    }

    @Test
    void contraintesSansEnteteGardentLeComportementActuel() {
        requestWith(null);
        ProblemDetail body = handler.handleConstraintViolation(
                new ConstraintViolationException("x", Set.of())).getBody();
        assertThat(body.getTitle()).isEqualTo("Constraint Violation");
        assertThat(body.getDetail()).isEqualTo("Paramètres de requête invalides");
    }

    @Test
    void contraintesEnAnglais() {
        requestWith("en");
        ProblemDetail body = handler.handleConstraintViolation(
                new ConstraintViolationException("x", Set.of())).getBody();
        assertThat(body.getDetail()).isEqualTo("Invalid request parameters");
    }
}
