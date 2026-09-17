package com.yadony.api.payments.pawapay;

import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave renvoie le client sur une URL HTTPS (jamais un schéma applicatif). Cette page ne
 * décide de rien : l'état vient du callback. Elle redirige vers l'écran d'attente, qui
 * relit le statut. Même leçon que {@code StripeOnboardingRedirectController}.
 */
@RestController
@RequestMapping("/pawapay/return")
public class PawapayReturnController {

    private final PawapayProperties props;

    public PawapayReturnController(PawapayProperties props) {
        this.props = props;
    }

    @GetMapping("/{bidId}")
    public ResponseEntity<Void> back(@PathVariable String bidId) {
        String location;
        try {
            location = String.format(props.deepLinkAwaiting(), UUID.fromString(bidId));
        } catch (IllegalArgumentException e) {
            location = "yadony://";
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    /**
     * Recharge du portefeuille : l'écran de destination est le portefeuille lui-même, qui
     * relit le statut de la recharge. Aucun identifiant dans l'URL — l'id de l'opération
     * n'existe qu'après la soumission, qui a justement besoin de cette URL ; le
     * {@code userId} passé en paramètre par pawaPay n'est pas lu ici (cette page ne décide
     * de rien et ne doit rien refléter dans {@code Location}).
     */
    @GetMapping("/wallet-topup")
    public ResponseEntity<Void> backWalletTopup() {
        String deepLink = props.deepLinkWallet();
        String location = deepLink != null && !deepLink.isBlank() ? deepLink : "yadony://";
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    @GetMapping("/thread/{threadId}")
    public ResponseEntity<Void> backThread(@PathVariable String threadId) {
        String location;
        try {
            location = String.format(props.deepLinkAwaitingThread(), UUID.fromString(threadId));
        } catch (IllegalArgumentException e) {
            location = "yadony://";
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }
}
