package com.yadony.api.payments;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refus définitif (rien n'a été créé chez Stripe, on peut revenir en arrière) ou issue inconnue (on rejoue, jamais de
 * retour en arrière). Chaque type est épinglé : retirer un type d'une des deux listes fait rougir sa ligne.
 */
class StripeFailuresTest {

    static Stream<Arguments> erreurs() {
        return Stream.of(
                Arguments.of(new InvalidRequestException("x", null, "r", null, 400, null), true),
                Arguments.of(new CardException("x", "r", null, null, null, null, 402, null), true),
                Arguments.of(new AuthenticationException("x", "r", null, 401), true),
                Arguments.of(new PermissionException("x", "r", null, 403), true),
                Arguments.of(new RateLimitException("x", null, "r", null, 429, null), false),
                Arguments.of(new IdempotencyException("x", "r", null, 400), false),
                Arguments.of(new ApiConnectionException("x"), false),
                Arguments.of(new ApiException("x", "r", null, 500, null), false));
    }

    @ParameterizedTest(name = "{0} → refus définitif : {1}")
    @MethodSource("erreurs")
    void classement(StripeException e, boolean definitive) {
        assertThat(StripeFailures.definitiveRefusal(e)).isEqualTo(definitive);
    }
}
