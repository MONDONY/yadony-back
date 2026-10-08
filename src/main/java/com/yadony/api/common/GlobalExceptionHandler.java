package com.yadony.api.common;

import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.payments.cash.exception.CommissionChargeFailedException;
import com.yadony.api.payments.cash.exception.CommissionMethodMissingException;
import com.yadony.api.payments.cash.exception.InvalidPaymentMethodForAnnouncementException;
import com.yadony.api.payments.exceptions.TravelerNotEligibleForPaymentException;
import io.sentry.Sentry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
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
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.sql.SQLTransientConnectionException;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String BASE_TYPE = "https://yadony.app/errors/";

    private final MessagesResolver messagesResolver;

    public GlobalExceptionHandler(MessagesResolver messagesResolver) {
        this.messagesResolver = messagesResolver;
    }

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

    /**
     * Le message Jakarta brut ({@code sendOtp.phoneNumber: must match ...}) expose le nom de
     * la méthode et du paramètre côté serveur. Le client reçoit un détail générique et une
     * carte {@code violations} champ → message (dernier segment du chemin seulement), même
     * forme que {@link #handleValidation}. Le message complet reste dans les logs.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("Constraint violation: {}", ex.getMessage());
        Map<String, String> violations = ex.getConstraintViolations() == null ? Map.of()
                : ex.getConstraintViolations().stream()
                        .collect(Collectors.toMap(
                                v -> leafOf(v.getPropertyPath() == null ? "" : v.getPropertyPath().toString()),
                                v -> v.getMessage() == null ? "invalide" : v.getMessage(),
                                (first, second) -> first));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, "Paramètres de requête invalides");
        problem.setType(URI.create(BASE_TYPE + "validation"));
        problem.setTitle("Constraint Violation");
        problem.setProperty("violations", violations);
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /** {@code sendOtp.request.phoneNumber} → {@code phoneNumber}. */
    private static String leafOf(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        String leaf = dot >= 0 ? propertyPath.substring(dot + 1) : propertyPath;
        return leaf.isBlank() ? "request" : leaf;
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
        pd.setTitle(messagesResolver.forRequest().get("problem.commission-method-missing.title"));
        pd.setProperty("code", "commission-method-missing");
        return pd;
    }

    @ExceptionHandler(InvalidPaymentMethodForAnnouncementException.class)
    public ProblemDetail handleInvalidPaymentMethod(InvalidPaymentMethodForAnnouncementException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create(BASE_TYPE + "invalid-payment-method-for-announcement"));
        pd.setTitle(messagesResolver.forRequest().get("problem.invalid-payment-method-for-announcement.title"));
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
        pd.setTitle(messagesResolver.forRequest().get("problem.commission-charge-failed.title"));
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
                HttpStatus.CONFLICT, messagesResolver.forRequest().get("problem.concurrent-update.detail"));
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
                HttpStatus.BAD_REQUEST, messagesResolver.forRequest().get("problem.missing-input.detail"));
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
                HttpStatus.BAD_REQUEST, messagesResolver.forRequest().get("problem.bad-multipart.detail"));
        problem.setType(URI.create(BASE_TYPE + "bad-multipart"));
        problem.setTitle("Bad Request");
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Le client a fermé la connexion pendant l'écriture de la réponse (Prometheus qui coupe
     * un scrape, mobile qui perd le réseau) : Tomcat lève {@code ClientAbortException}, Spring
     * l'enveloppe en {@code AsyncRequestNotUsableException}. Il n'y a plus personne à qui
     * répondre et rien à corriger côté serveur : ni 500, ni événement Sentry
     * (YADONY-BACK-STAGING-6). Le handler ne rend rien : la réponse est inutilisable.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class,
            org.apache.catalina.connector.ClientAbortException.class})
    public void handleClientAbort(Exception ex) {
        log.debug("Client disconnected before the response was written: {}", ex.getMessage());
    }

    /** SQLState PostgreSQL d'une violation de contrainte CHECK. */
    private static final String SQLSTATE_CHECK_VIOLATION = "23514";

    /**
     * Filet pour une contrainte CHECK de la base plus stricte que la validation de l'API
     * (STAGING-M : {@code chk_pkg_req_weight} à 30 kg quand le DTO acceptait 32). Le client
     * reçoit un 422 lisible au lieu d'un 500, sans le SQL ni le nom de la contrainte ; l'écart
     * reste un bug serveur, donc journalisé en ERROR et capturé dans Sentry comme le 500.
     *
     * <p>Les autres violations d'intégrité (unique 23505, clé étrangère…) sont traitées au cas
     * par cas par les services ; celles qui remontent jusqu'ici gardent le 500 de
     * {@link #handleGeneric} pour ne masquer aucun bug.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(
            org.springframework.dao.DataIntegrityViolationException ex) {
        String constraint = checkConstraintName(ex);
        if (constraint == null) {
            return handleGeneric(ex);
        }
        String requestId = MDC.get(RequestCorrelationFilter.MDC_KEY);
        log.error("Check constraint violated constraint={} requestId={}", constraint, requestId, ex);
        Sentry.withScope(scope -> {
            scope.setTag("db_constraint", constraint);
            if (requestId != null) {
                scope.setTag("request_id", requestId);
            }
            Sentry.captureException(ex);
        });
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                messagesResolver.forRequest().get("problem.constraint-violation.detail"));
        problem.setType(URI.create(BASE_TYPE + "constraint-violation"));
        problem.setTitle("Constraint Violation");
        problem.setProperty("code", "constraint-violation");
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * Nom de la contrainte CHECK violée (ou {@code "check"} si seul le SQLState 23514 la
     * révèle), {@code null} si la violation n'est pas une contrainte CHECK.
     */
    static String checkConstraintName(Throwable ex) {
        boolean checkState = false;
        String name = null;
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException hibernate
                    && hibernate.getConstraintName() != null) {
                name = hibernate.getConstraintName();
            }
            if (current instanceof java.sql.SQLException sql
                    && SQLSTATE_CHECK_VIOLATION.equals(sql.getSQLState())) {
                checkState = true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        if (name != null && name.startsWith("chk_")) {
            return name;
        }
        if (checkState) {
            return name != null ? name : "check";
        }
        return null;
    }

    /**
     * Pool de connexions épuisé : Hikari n'a pas fourni de connexion dans le délai
     * {@code connection-timeout}. C'est une surcharge passagère, pas un bug : 503 avec
     * {@code Retry-After}, un WARN dans les logs et pas d'événement Sentry (test de charge
     * k6 du 07/10 sur staging : 187 requêtes en attente sur un pool de 10, des centaines
     * de 500 envoyés à Sentry). Toute autre panne d'accès à la base garde le 500.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex) {
        if (!isConnectionPoolTimeout(ex)) {
            return handleGeneric(ex);
        }
        String requestId = MDC.get(RequestCorrelationFilter.MDC_KEY);
        log.warn("Connection pool exhausted requestId={}: {}", requestId, ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                messagesResolver.forRequest().get("problem.service-busy.detail"));
        problem.setType(URI.create(BASE_TYPE + "service-busy"));
        problem.setTitle("Service Busy");
        problem.setProperty("code", "service-busy");
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "2")
                .body(problem);
    }

    private static boolean isConnectionPoolTimeout(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex) {
        // L'identifiant de corrélation (RequestCorrelationFilter) relie la ligne de log,
        // l'événement Sentry et la réponse reçue par le client : un ticket support qui
        // cite le X-Request-Id mène à la trace complète sans fouiller par l'heure.
        String requestId = MDC.get(RequestCorrelationFilter.MDC_KEY);
        log.error("Unexpected error requestId={}", requestId, ex);
        Sentry.withScope(scope -> {
            if (requestId != null) {
                scope.setTag("request_id", requestId);
            }
            Sentry.captureException(ex);
        });
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setType(URI.create(BASE_TYPE + "internal-error"));
        problem.setTitle("Internal Server Error");
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return ResponseEntity.internalServerError().body(problem);
    }
}
