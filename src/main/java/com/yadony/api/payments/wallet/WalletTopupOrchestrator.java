package com.yadony.api.payments.wallet;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.CurrencyCatalog;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.wallet.dto.WalletTopupRequest;
import com.yadony.api.payments.wallet.dto.WalletTopupResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class WalletTopupOrchestrator {

    private final CurrencyCatalog currencyCatalog;

    public WalletTopupOrchestrator(CurrencyCatalog currencyCatalog) {
        this.currencyCatalog = currencyCatalog;
    }

    public WalletTopupResponse initiate(UUID userId, WalletTopupRequest request) {
        return switch (request.getPaymentMethod()) {
            case "STRIPE" -> initiateStripe(userId, request);
            case "WAVE", "ORANGE_MONEY" -> throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "mobile-money-topup-retired", "Mobile Money Topup Retired",
                    "Le rechargement par mobile money n'est plus disponible pour le moment. "
                            + "Choisissez Carte bancaire.");
            default -> throw new IllegalArgumentException(
                    "Mode de paiement inconnu : " + request.getPaymentMethod());
        };
    }

    private WalletTopupResponse initiateStripe(UUID userId, WalletTopupRequest request) {
        SupportedCurrency currency = currencyCatalog.resolve(request.getCurrencyCode());
        CurrencyAmount amount = CurrencyAmount.of(request.getAmount(), currency);

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amount.minor())
                .setCurrency(currency.code())
                .putMetadata("wallet_topup", "true")
                .putMetadata("user_id", userId.toString())
                .putMetadata("wallet_currency", currency.code())
                .build();

        try {
            PaymentIntent paymentIntent = PaymentIntent.create(params);
            return new WalletTopupResponse(paymentIntent.getClientSecret(), null);
        } catch (StripeException exception) {
            throw new YadonyBusinessException(
                    HttpStatus.BAD_GATEWAY,
                    "wallet-topup-stripe-error", "Stripe Error",
                    "Impossible de préparer la recharge du wallet. Veuillez réessayer.");
        }
    }
}
