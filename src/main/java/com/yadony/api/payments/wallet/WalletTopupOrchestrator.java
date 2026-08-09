package com.yadony.api.payments.wallet;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.dto.WalletTopupRequest;
import com.yadony.api.payments.wallet.dto.WalletTopupResponse;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.CurrencyCatalog;
import com.yadony.api.payments.currency.ExchangeRateProperties;
import com.yadony.api.payments.currency.FxRateService;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class WalletTopupOrchestrator {

    private CurrencyCatalog currencyCatalog = new CurrencyCatalog();
    private FxRateService fxRateService = new FxRateService(
            (source, target) -> { throw new IllegalStateException("FX provider not configured"); },
            new ExchangeRateProperties(new BigDecimal("655.957"), new BigDecimal("655.957"), 300),
            Caffeine.newBuilder().build());

    @Autowired
    public void configureCurrency(CurrencyCatalog currencyCatalog, FxRateService fxRateService) {
        this.currencyCatalog = currencyCatalog;
        this.fxRateService = fxRateService;
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
        try {
            SupportedCurrency currency = currencyCatalog.resolve(null, request.getCurrencyCode());
            CurrencyAmount localAmount = CurrencyAmount.of(request.getAmount(), currency);
            CurrencyAmount walletCredit = fxRateService.convertToEur(request.getAmount(), currency);
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(localAmount.minor())
                .setCurrency(currency.code())
                .putMetadata("wallet_topup", "true")
                .putMetadata("user_id", userId.toString())
                .putMetadata("wallet_currency", "eur")
                .putMetadata("wallet_source_currency", currency.code())
                .putMetadata("wallet_credit_eur", walletCredit.major().toPlainString())
                .build();
            PaymentIntent pi = PaymentIntent.create(params);
            return new WalletTopupResponse(pi.getClientSecret(), null);
        } catch (Exception e) {
            throw new RuntimeException("Erreur Stripe topup", e);
        }
    }
}
