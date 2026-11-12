package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

/** Erreurs métier du package vols, toutes servies en RFC 7807 par {@code GlobalExceptionHandler}. */
final class FlightErrors {

    private FlightErrors() {
    }

    /** Token Travelpayouts absent de la configuration : la fonctionnalité est coupée, pas cassée. */
    static YadonyBusinessException unavailable() {
        return YadonyBusinessException.localized(HttpStatus.SERVICE_UNAVAILABLE, "flights-unavailable", "problem.flights-unavailable");
    }

    static YadonyBusinessException providerError() {
        return YadonyBusinessException.localized(HttpStatus.BAD_GATEWAY, "flights-provider-error", "problem.flights-provider-error");
    }

    /** Alerte absente ou à un autre utilisateur : même réponse, on ne révèle pas son existence. */
    static YadonyBusinessException alertNotFound() {
        return YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "flight-alert-not-found", "problem.flight-alert-not-found");
    }

    static YadonyBusinessException invalid(String code, String messageKey, Object... args) {
        return YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, code, messageKey, args);
    }
}
