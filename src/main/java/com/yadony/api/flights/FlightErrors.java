package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

/** Erreurs métier du package vols, toutes servies en RFC 7807 par {@code GlobalExceptionHandler}. */
final class FlightErrors {

    private FlightErrors() {
    }

    /** Token Travelpayouts absent de la configuration : la fonctionnalité est coupée, pas cassée. */
    static YadonyBusinessException unavailable() {
        return new YadonyBusinessException(HttpStatus.SERVICE_UNAVAILABLE, "flights-unavailable",
                "Flights Unavailable", "La recherche de vols est momentanément indisponible.");
    }

    static YadonyBusinessException providerError() {
        return new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "flights-provider-error",
                "Flights Provider Error", "Le service de prix des vols ne répond pas. Réessayez dans un instant.");
    }

    static YadonyBusinessException invalid(String code, String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, "Invalid Flight Query", detail);
    }
}
