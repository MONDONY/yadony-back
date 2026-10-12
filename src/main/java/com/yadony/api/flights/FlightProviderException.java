package com.yadony.api.flights;

/**
 * Travelpayouts injoignable ou réponse inexploitable. Reste interne au package :
 * les services la traduisent en {@code ProblemDetail} 502 ({@link FlightErrors}).
 */
public class FlightProviderException extends RuntimeException {

    public FlightProviderException(String message) {
        super(message);
    }

    public FlightProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
