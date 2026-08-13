package com.yadony.api.common;

import io.sentry.Sentry;
import io.sentry.ScopeCallback;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("GlobalExceptionHandler — tests unitaires")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Nested
    @DisplayName("handleValidation()")
    class ValidationTests {

        @Test
        @DisplayName("erreurs de validation → 422 avec violations")
        void handleValidation_returns422WithViolations() {
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            BindingResult bindingResult = mock(BindingResult.class);
            FieldError fieldError = new FieldError("obj", "phoneNumber", "Le numéro est invalide");

            when(ex.getBindingResult()).thenReturn(bindingResult);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

            ResponseEntity<ProblemDetail> response = handler.handleValidation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            ProblemDetail body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getTitle()).isEqualTo("Validation Error");
            assertThat(body.getType().toString()).contains("validation");
            @SuppressWarnings("unchecked")
            var violations = (java.util.Map<String, String>) body.getProperties().get("violations");
            assertThat(violations).containsKey("phoneNumber");
            assertThat(violations.get("phoneNumber")).isEqualTo("Le numéro est invalide");
        }

        @Test
        @DisplayName("plusieurs erreurs sur le même champ → premier message conservé")
        void handleValidation_multipleSameField_keepFirstMessage() {
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            BindingResult bindingResult = mock(BindingResult.class);
            FieldError error1 = new FieldError("obj", "email", "Email invalide");
            FieldError error2 = new FieldError("obj", "email", "Email trop long");

            when(ex.getBindingResult()).thenReturn(bindingResult);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(error1, error2));

            ResponseEntity<ProblemDetail> response = handler.handleValidation(ex);

            @SuppressWarnings("unchecked")
            var violations = (java.util.Map<String, String>) response.getBody().getProperties().get("violations");
            assertThat(violations).hasSize(1);
            assertThat(violations.get("email")).isEqualTo("Email invalide");
        }
    }

    @Nested
    @DisplayName("handleConstraintViolation()")
    class ConstraintViolationTests {

        @Test
        @DisplayName("constraint violation → 422 avec message")
        void handleConstraintViolation_returns422() {
            @SuppressWarnings("unchecked")
            Set<ConstraintViolation<?>> violations = Set.of();
            ConstraintViolationException ex = new ConstraintViolationException("Constraint failed", violations);

            ResponseEntity<ProblemDetail> response = handler.handleConstraintViolation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().getType().toString()).contains("validation");
        }
    }

    @Nested
    @DisplayName("handleAuthentication()")
    class AuthenticationTests {

        @Test
        @DisplayName("AuthenticationException → 401 UNAUTHORIZED")
        void handleAuthentication_returns401() {
            AuthenticationException ex = mock(AuthenticationException.class);
            when(ex.getMessage()).thenReturn("Token invalide");

            ResponseEntity<ProblemDetail> response = handler.handleAuthentication(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().getTitle()).isEqualTo("Unauthorized");
            assertThat(response.getBody().getType().toString()).contains("unauthorized");
        }
    }

    @Nested
    @DisplayName("handleAccessDenied()")
    class AccessDeniedTests {

        @Test
        @DisplayName("AccessDeniedException → 403 FORBIDDEN")
        void handleAccessDenied_returns403() {
            AccessDeniedException ex = new AccessDeniedException("Accès refusé");

            ResponseEntity<ProblemDetail> response = handler.handleAccessDenied(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().getTitle()).isEqualTo("Forbidden");
            assertThat(response.getBody().getType().toString()).contains("forbidden");
        }
    }

    @Nested
    @DisplayName("handleNotFound()")
    class NotFoundTests {

        @Test
        @DisplayName("YadonyNotFoundException → 404 NOT_FOUND")
        void handleNotFound_returns404() {
            YadonyNotFoundException ex = new YadonyNotFoundException("Ressource introuvable");

            ResponseEntity<ProblemDetail> response = handler.handleNotFound(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().getTitle()).isEqualTo("Not Found");
            assertThat(response.getBody().getType().toString()).contains("not-found");
        }
    }

    @Nested
    @DisplayName("handleBusiness()")
    class BusinessTests {

        @Test
        @DisplayName("YadonyBusinessException 409 → 409 CONFLICT avec errorCode dans type")
        void handleBusiness_returns409WithTypeUri() {
            YadonyBusinessException ex = new YadonyBusinessException(
                    HttpStatus.CONFLICT, "phone-already-exists",
                    "Phone Already Exists", "Ce numéro existe déjà");

            ResponseEntity<ProblemDetail> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getTitle()).isEqualTo("Phone Already Exists");
            assertThat(response.getBody().getType().toString()).contains("phone-already-exists");
            assertThat(response.getBody().getDetail()).isEqualTo("Ce numéro existe déjà");
        }

        @Test
        @DisplayName("YadonyBusinessException 403 → 403 FORBIDDEN")
        void handleBusiness_403_returnsForbidden() {
            YadonyBusinessException ex = new YadonyBusinessException(
                    HttpStatus.FORBIDDEN, "forbidden-role", "Forbidden Role", "Rôle interdit");

            ResponseEntity<ProblemDetail> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("YadonyBusinessException 422 → 422 UNPROCESSABLE_ENTITY")
        void handleBusiness_422_returnsUnprocessable() {
            YadonyBusinessException ex = new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "value-exceeds-limit",
                    "Value Exceeds Limit", "Valeur maximum : 500 €");

            ResponseEntity<ProblemDetail> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("YadonyBusinessException 500 → 500 INTERNAL_SERVER_ERROR")
        void handleBusiness_500_returnsInternalServerError() {
            YadonyBusinessException ex = new YadonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "firebase-delete-failed",
                    "Firebase Delete Failed", "Erreur Firebase");

            ResponseEntity<ProblemDetail> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("handleOptimisticLock()")
    class OptimisticLockTests {

        @Test
        @DisplayName("ObjectOptimisticLockingFailureException → 409 CONFLICT (concurrent-update)")
        void handleOptimisticLock_returns409() {
            var ex = new org.springframework.orm.ObjectOptimisticLockingFailureException(
                    "NegotiationThreadEntity", java.util.UUID.randomUUID());

            ResponseEntity<ProblemDetail> response = handler.handleOptimisticLock(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getType().toString()).contains("concurrent-update");
        }
    }

    @Nested
    @DisplayName("handleGeneric()")
    class GenericExceptionTests {

        @Test
        @DisplayName("exception inattendue → 500 + Sentry capturé")
        void handleGeneric_returns500AndCapturesToSentry() {
            RuntimeException ex = new RuntimeException("Erreur inattendue");

            try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
                sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

                ResponseEntity<ProblemDetail> response = handler.handleGeneric(ex);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(response.getBody().getTitle()).isEqualTo("Internal Server Error");
                assertThat(response.getBody().getDetail()).isEqualTo("An unexpected error occurred");
                assertThat(response.getBody().getType().toString()).contains("internal-error");

                sentryMock.verify(() -> Sentry.withScope(any(ScopeCallback.class)));
            }
        }

        @Test
        @DisplayName("NullPointerException → 500 toujours retourné")
        void handleGeneric_nullPointer_returns500() {
            NullPointerException npe = new NullPointerException("null ref");

            try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
                sentryMock.when(() -> Sentry.captureException(any())).thenAnswer(inv -> null);

                ResponseEntity<ProblemDetail> response = handler.handleGeneric(npe);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
    }

    @Nested
    @DisplayName("handleTypeMismatch() — erreurs client Spring MVC (régression back-monkey)")
    class TypeMismatchTests {

        @Test
        @DisplayName("MethodArgumentTypeMismatchException → 400 avec le nom du paramètre")
        void handleTypeMismatch_returns400WithParameter() {
            MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
            when(ex.getName()).thenReturn("id");
            when(ex.getMessage()).thenReturn("Failed to convert 'abc' to UUID");

            ResponseEntity<ProblemDetail> response = handler.handleTypeMismatch(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ProblemDetail body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getTitle()).isEqualTo("Bad Request");
            assertThat(body.getType().toString()).contains("bad-parameter");
            assertThat(body.getDetail()).contains("id");
            assertThat(body.getProperties().get("parameter")).isEqualTo("id");
        }
    }

    @Nested
    @DisplayName("handleMissingInput()")
    class MissingInputTests {

        @Test
        @DisplayName("MissingServletRequestParameterException → 400")
        void handleMissingParameter_returns400() {
            MissingServletRequestParameterException ex =
                    new MissingServletRequestParameterException("page", "int");

            ResponseEntity<ProblemDetail> response = handler.handleMissingInput(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getType().toString()).contains("bad-request");
        }

        @Test
        @DisplayName("MissingRequestHeaderException → 400")
        void handleMissingHeader_returns400() {
            MissingRequestHeaderException ex = mock(MissingRequestHeaderException.class);
            when(ex.getMessage()).thenReturn("Required header 'Authorization' is not present");

            ResponseEntity<ProblemDetail> response = handler.handleMissingInput(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getTitle()).isEqualTo("Bad Request");
        }
    }

    @Nested
    @DisplayName("handleMethodNotSupported()")
    class MethodNotSupportedTests {

        @Test
        @DisplayName("HttpRequestMethodNotSupportedException → 405 + en-tête Allow")
        void handleMethodNotSupported_returns405WithAllow() {
            HttpRequestMethodNotSupportedException ex =
                    new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST"));

            ResponseEntity<ProblemDetail> response = handler.handleMethodNotSupported(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(response.getBody().getType().toString()).contains("method-not-allowed");
            assertThat(response.getHeaders().getAllow()).contains(HttpMethod.GET, HttpMethod.POST);
        }

        @Test
        @DisplayName("sans méthodes supportées → 405 sans Allow")
        void handleMethodNotSupported_noSupported_returns405() {
            HttpRequestMethodNotSupportedException ex =
                    new HttpRequestMethodNotSupportedException("DELETE");

            ResponseEntity<ProblemDetail> response = handler.handleMethodNotSupported(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }
    }

    @Nested
    @DisplayName("handleMediaTypeNotSupported()")
    class MediaTypeNotSupportedTests {

        @Test
        @DisplayName("HttpMediaTypeNotSupportedException → 415")
        void handleMediaTypeNotSupported_returns415() {
            HttpMediaTypeNotSupportedException ex =
                    new HttpMediaTypeNotSupportedException("Content type 'text/plain' not supported");

            ResponseEntity<ProblemDetail> response = handler.handleMediaTypeNotSupported(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(response.getBody().getType().toString()).contains("unsupported-media-type");
        }
    }

    @Nested
    @DisplayName("handleNoResourceFound()")
    class NoResourceFoundTests {

        @Test
        @DisplayName("NoResourceFoundException → 404")
        void handleNoResourceFound_returns404() {
            NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/garbage/path");

            ResponseEntity<ProblemDetail> response = handler.handleNoResourceFound(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().getTitle()).isEqualTo("Not Found");
            assertThat(response.getBody().getType().toString()).contains("not-found");
        }
    }

    @Nested
    @DisplayName("handleMultipart()")
    class MultipartTests {

        @Test
        @DisplayName("MultipartException (upload non multipart) → 400")
        void handleMultipart_returns400() {
            MultipartException ex = new MultipartException(
                    "Current request is not a multipart request");

            ResponseEntity<ProblemDetail> response = handler.handleMultipart(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Bad Request");
            assertThat(response.getBody().getType().toString()).contains("bad-multipart");
        }
    }
}
