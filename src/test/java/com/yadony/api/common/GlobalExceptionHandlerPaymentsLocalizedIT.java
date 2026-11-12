package com.yadony.api.common;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayerRefusals;
import com.yadony.api.payments.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paquet 4a du défaut n° 9 : refus de paiement, portefeuille, mobile money et code de recharge rendus
 * dans la langue de l'app (DispatcherServlet + GlobalExceptionHandler + MessagesResolver). Les
 * refus du portefeuille et du mobile money passent par les vraies fabriques du code de production.
 */
class GlobalExceptionHandlerPaymentsLocalizedIT {

    @RestController
    static class ThrowingController {
        @GetMapping("/t/bid-not-payable")
        void bidNotPayable() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "bid-not-payable",
                    "problem.bid-not-payable", "CANCELLED");
        }

        @GetMapping("/t/stripe-error")
        void stripeError() {
            throw YadonyBusinessException.localized(HttpStatus.BAD_GATEWAY, "stripe-error", "problem.stripe-error");
        }

        @GetMapping("/t/wallet-dispute")
        void walletDispute() {
            throw WalletService.disputeHeld();
        }

        @GetMapping("/t/wallet-balance-empty")
        void walletBalanceEmpty() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-balance-empty",
                    "problem.wallet-balance-empty.no-balance");
        }

        @GetMapping("/t/mm-invalid-phone")
        void mobileMoneyInvalidPhone() {
            throw MobileMoneyPayerRefusals.invalidPhone();
        }

        @GetMapping("/t/mm-amount")
        void mobileMoneyAmount() {
            throw MobileMoneyPayerRefusals.amountOutOfRange("Wave");
        }

        @GetMapping("/t/mm-currency")
        void mobileMoneyCurrency() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-currency-mismatch",
                    "problem.mobile-money-currency-mismatch.several", "XOF, XAF", "EUR");
        }

        @GetMapping("/t/promo-expired")
        void promoExpired() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-expired",
                    "problem.topup-code.expired");
        }

        @GetMapping("/t/promo-too-many")
        void promoTooMany() {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "topup-code-too-many-attempts",
                    "problem.topup-code.too-many-attempts.account");
        }

        @GetMapping("/t/topup-above-max")
        void topupAboveMax() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-topup-above-max",
                    "problem.wallet-topup-above-max", "500 EUR");
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler(TestMessages.resolver()))
                .build();
    }

    private static MockHttpServletRequestBuilder getJson(String url, String acceptLanguage) {
        MockHttpServletRequestBuilder request = get(url).accept(MediaType.APPLICATION_JSON);
        return acceptLanguage == null ? request : request.header("Accept-Language", acceptLanguage);
    }

    private void expect(String url, int httpStatus, String code, String fr, String en) throws Exception {
        for (String header : new String[]{null, "fr", "fr-FR,fr;q=0.9"}) {
            mvc.perform(getJson(url, header))
                    .andExpect(status().is(httpStatus))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.detail").value(fr));
        }
        mvc.perform(getJson(url, "en"))
                .andExpect(status().is(httpStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").value(en));
    }

    @Test
    @DisplayName("réservation non payable : statut injecté, FR par défaut, EN avec Accept-Language")
    void bidNotPayable() throws Exception {
        expect("/t/bid-not-payable", 422, "bid-not-payable",
                "Cette demande ne peut plus être payée (statut : CANCELLED)",
                "This request can no longer be paid (status: CANCELLED)");
    }

    @Test
    @DisplayName("paiement refusé par Stripe : titre inchangé, détail traduit")
    void stripeError() throws Exception {
        expect("/t/stripe-error", 502, "stripe-error",
                "Impossible de vérifier le paiement auprès de Stripe",
                "Unable to verify the payment with Stripe");
        mvc.perform(getJson("/t/stripe-error", "en")).andExpect(jsonPath("$.title").value("Stripe Error"));
    }

    @Test
    @DisplayName("portefeuille bloqué par un litige et solde vide : FR et EN")
    void wallet() throws Exception {
        expect("/t/wallet-dispute", 422, "wallet-dispute-hold",
                "Solde bloqué : une recharge de cette devise fait l'objet d'une contestation bancaire. Contactez le support.",
                "Balance blocked: a top-up in this currency is under a bank dispute. Contact support.");
        expect("/t/wallet-balance-empty", 422, "wallet-balance-empty",
                "Aucun solde à rembourser", "No balance to refund");
        expect("/t/topup-above-max", 422, "wallet-topup-above-max",
                "Une recharge par carte est limitée à 500 EUR.", "A card top-up is limited to 500 EUR.");
    }

    @Test
    @DisplayName("mobile money : numéro invalide, montant hors limites et devise de versement")
    void mobileMoney() throws Exception {
        expect("/t/mm-invalid-phone", 422, "mobile-money-payer-unsupported",
                "Numéro de téléphone invalide.", "Invalid phone number.");
        expect("/t/mm-amount", 422, "mobile-money-payer-unsupported",
                "Montant hors des limites de Wave.", "Amount outside the limits of Wave.");
        expect("/t/mm-currency", 422, "mobile-money-currency-mismatch",
                "Vos comptes de versement sont en XOF, XAF, ce trajet est en EUR.",
                "Your payout accounts are in XOF, XAF, this trip is in EUR.");
    }

    @Test
    @DisplayName("code de recharge expiré ou trop d'essais : FR et EN")
    void topupCode() throws Exception {
        expect("/t/promo-expired", 422, "topup-code-expired", "Ce code de recharge a expiré",
                "This top-up code has expired");
        expect("/t/promo-too-many", 429, "topup-code-too-many-attempts",
                "Trop d'essais de code. Réessaie dans une heure.", "Too many code attempts. Try again in an hour.");
    }
}
