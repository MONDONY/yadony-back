package com.yadony.api.common;

import com.yadony.api.payments.cash.exception.CommissionChargeFailedException;
import com.yadony.api.payments.cash.exception.CommissionMethodMissingException;
import com.yadony.api.payments.cash.exception.InvalidPaymentMethodForAnnouncementException;
import com.yadony.api.payments.exceptions.TravelerNotEligibleForPaymentException;
import io.sentry.Sentry;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpMethod;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String BASE_TYPE = "https://yadony.app/errors/";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed");
        problem.setType(URI.create(BASE_TYPE + "validation"));
        problem.setTitle("Validation Error");

        Map<String, String> violations = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        f -> f.getDefaultMessage() != null ? f.getDefaultMessage() : "invalid",
                        (a, b) -> a
                ));
        problem.setProperty("violations", violations);
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create(BASE_TYPE + "validation"));
        problem.setTitle("Constraint Violation");
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setType(URI.create(BASE_TYPE + "unauthorized"));
        problem.setTitle("Unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Access denied");
        problem.setType(URI.create(BASE_TYPE + "forbidden"));
        problem.setTitle("Forbidden");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(YadonyNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(YadonyNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create(BASE_TYPE + "not-found"));
        problem.setTitle("Not Found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(YadonyBusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(YadonyBusinessException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ex.getStatus(), ex.getMessage());
        problem.setType(URI.create(BASE_TYPE + ex.getErrorCode()));
        problem.setTitle(ex.getTitle());
        problem.setProperty("code", ex.getErrorCode());
        ex.getProperties().forEach(problem::setProperty);
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        String reason = ex.getReason();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ex.getStatusCode(), reason != null ? reason : ex.getMessage());

        if (reason != null && reason.contains("/")) {
            // Structured error code like "request/expired" or "negotiation/duplicate-thread"
            problem.setType(URI.create(BASE_TYPE + reason));
            problem.setProperty("code", reason);
            String[] parts = reason.split("/", 2);
            String title = parts.length > 1
                    ? capitalize(parts[1].replace("-", " "))
                    : capitalize(parts[0].replace("-", " "));
            problem.setTitle(title);
        } else {
            problem.setType(URI.create(BASE_TYPE + "generic"));
            problem.setTitle(reason != null ? reason : ex.getStatusCode().toString());
        }

        return ResponseEntity.status(ex.getStatusCode()).body(problem);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(HttpMessageNotReadableException ex) {
        log.error("HttpMessageNotReadableException: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Malformed request payload");
        problem.setType(URI.create(BASE_TYPE + "malformed-request"));
        problem.setTitle("Bad Request");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(TravelerNotEligibleForPaymentException.class)
    public ResponseEntity<ProblemDetail> handleTravelerNotEligible(TravelerNotEligibleForPaymentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create(BASE_TYPE + "traveler-not-eligible"));
        problem.setTitle("Traveler Not Eligible");
        problem.setProperty("code", "traveler-not-eligible");
        problem.setProperty("travelerId", ex.getTravelerId().toString());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(CommissionMethodMissingException.class)
    public ProblemDetail handleCommissionMethodMissing(CommissionMethodMissingException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create(BASE_TYPE + "commission-method-missing"));
        pd.setTitle("Méthode de commission requise");
        pd.setProperty("code", "commission-method-missing");
        return pd;
    }

    @ExceptionHandler(InvalidPaymentMethodForAnnouncementException.class)
    public ProblemDetail handleInvalidPaymentMethod(InvalidPaymentMethodForAnnouncementException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create(BASE_TYPE + "invalid-payment-method-for-announcement"));
        pd.setTitle("Mode de paiement non autorisé");
        pd.setProperty("code", "invalid-payment-method-for-announcement");
        return pd;
    }

    @ExceptionHandler(CommissionChargeFailedException.class)
    public ProblemDetail handleCommissionChargeFailed(CommissionChargeFailedException ex) {
        // Distinct code from "negotiation/commission-charge-failed" (thrown by
        // NegotiationService.finalizeInternal for the negotiation checkout flow) —
        // this one covers the classic bid flow's async commission retry failures.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
        pd.setType(URI.create(BASE_TYPE + "commission-charge-failed"));
        pd.setTitle("Débit de la commission refusé");
        pd.setProperty("code", "commission-charge-failed");
        return pd;
    }

    /**
     * Optimistic-lock conflict (e.g. two concurrent finalizes of the same negotiation
     * thread — /checkout vs Stripe webhook). The loser maps to 409 instead of 500 so
     * the client can simply re-read the now-finalized resource.
     */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(
            org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "La ressource a été modifiée simultanément, réessayez");
        problem.setType(URI.create(BASE_TYPE + "concurrent-update"));
        problem.setTitle("Concurrent Update");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Client supplied a path/query param of the wrong type (e.g. a non-UUID where a
     * UUID is expected, or text where a number is expected). Client error → 400, not
     * a fall-through to the catch-all 500. Logged at WARN (no Sentry): it is a bad
     * request, not a server bug.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch on parameter '{}': {}", ex.getName(), ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'");
        problem.setType(URI.create(BASE_TYPE + "bad-parameter"));
        problem.setTitle("Bad Request");
        problem.setProperty("parameter", ex.getName());
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Required request header or query parameter missing. Client error → 400, not 500.
     */
    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> handleMissingInput(Exception ex) {
        // Le message complet (qui peut nommer l'en-tête Authorization) reste côté
        // log ; le corps renvoyé au client reste générique pour ne rien divulguer.
        log.warn("Missing required input: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Paramètre ou en-tête de requête requis manquant");
        problem.setType(URI.create(BASE_TYPE + "bad-request"));
        problem.setTitle("Bad Request");
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * HTTP method not supported by the matched route (e.g. DELETE on a GET-only
     * endpoint). Client error → 405 with an {@code Allow} header, not 500.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not supported: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
        problem.setType(URI.create(BASE_TYPE + "method-not-allowed"));
        problem.setTitle("Method Not Allowed");
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null) {
            builder.allow(ex.getSupportedHttpMethods().toArray(new HttpMethod[0]));
        }
        return builder.body(problem);
    }

    /**
     * Request Content-Type not supported by the endpoint. Client error → 415, not 500.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("Unsupported media type: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
        problem.setType(URI.create(BASE_TYPE + "unsupported-media-type"));
        problem.setTitle("Unsupported Media Type");
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(problem);
    }

    /**
     * No handler/static resource matched the request path. Client error → 404, not a
     * fall-through to the catch-all 500. Not logged: unknown paths are noise.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFound(NoResourceFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "No endpoint matches this path");
        problem.setType(URI.create(BASE_TYPE + "not-found"));
        problem.setTitle("Not Found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * Requête multipart invalide : Content-Type non multipart sur un endpoint
     * d'upload, ou part de fichier requise absente. Erreur client → 400, pas 500.
     */
    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ProblemDetail> handleMultipart(Exception ex) {
        log.warn("Multipart/upload invalide: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Requête multipart invalide ou fichier manquant");
        problem.setType(URI.create(BASE_TYPE + "bad-multipart"));
        problem.setTitle("Bad Request");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex) {
        String requestId = MDC.get("requestId");
        log.error("Unexpected error requestId={}", requestId, ex);
        Sentry.withScope(scope -> {
            if (requestId != null && !requestId.isBlank()) {
                scope.setTag("request_id", requestId);
            }
            Sentry.captureException(ex);
        });
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setType(URI.create(BASE_TYPE + "internal-error"));
        problem.setTitle("Internal Server Error");
        if (requestId != null && !requestId.isBlank()) {
            problem.setProperty("requestId", requestId);
        }
        return ResponseEntity.internalServerError().body(problem);
    }
}
