package com.yadony.api.payments.pawapay;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

/**
 * Levée quand la signature RFC 9421 d'un callback pawaPay est absente, illisible, expirée ou
 * invalide. Traduite en {@code 401} RFC 7807 (code d'erreur {@code invalid-pawapay-signature})
 * par le {@code GlobalExceptionHandler}.
 */
public class PawapaySignatureException extends YadonyBusinessException {
    public PawapaySignatureException(String detail) {
        super(HttpStatus.UNAUTHORIZED, "invalid-pawapay-signature", "Invalid pawaPay Signature", detail);
    }
}
