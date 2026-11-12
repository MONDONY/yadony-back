package com.yadony.api.favorites;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

public enum FavoriteTargetType {
    TRIP,
    PACKAGE_REQUEST;

    /**
     * "trip" → TRIP, "package-request" → PACKAGE_REQUEST.
     * Throws {@link YadonyBusinessException} (400) for any unknown path segment so that
     * the caller gets a scoped RFC 7807 bad-request — without polluting the global
     * {@link com.yadony.api.common.GlobalExceptionHandler} with a catch-all for
     * {@link IllegalArgumentException} (which would silently downgrade internal errors).
     */
    public static FavoriteTargetType fromPath(String path) {
        return switch (path) {
            case "trip" -> TRIP;
            case "package-request" -> PACKAGE_REQUEST;
            default -> throw YadonyBusinessException.localized(HttpStatus.BAD_REQUEST, "favorites/unknown-type", "problem.favorites-unknown-type", path);
        };
    }
}
