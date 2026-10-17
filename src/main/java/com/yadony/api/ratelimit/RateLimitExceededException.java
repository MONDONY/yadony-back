package com.yadony.api.ratelimit;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Une {@link RateLimitRule} refuse la requête : 429 {@code too-many-requests}, avec
 * {@code Retry-After} et {@code retryAfterSeconds} pour que le client sache quand
 * réessayer (GlobalExceptionHandler).
 */
public class RateLimitExceededException extends YadonyBusinessException {

    public static final String CODE = "too-many-requests";

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds, String detail) {
        super(HttpStatus.TOO_MANY_REQUESTS, CODE, "Too Many Requests", detail,
                Map.of("retryAfterSeconds", retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
