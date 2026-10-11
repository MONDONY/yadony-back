package com.yadony.api.payments;

import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.voucher.CommissionVoucherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Net versé au voyageur, calcul UNIQUE pour la livraison, le colis non réclamé, le rattrapage et la
 * libération forcée admin : montant − commission + part de commission rendue par le bon de
 * parrainage du voyageur. La consommation du bon est idempotente par colis : deux chemins (ou une
 * reprise) qui versent le même paiement calculent exactement le même montant, donc les mêmes
 * paramètres de Transfer.
 */
@Component
public class ReleaseNetCalculator {

    private static final Logger log = LoggerFactory.getLogger(ReleaseNetCalculator.class);

    private final CommissionVoucherService voucherService;

    public ReleaseNetCalculator(CommissionVoucherService voucherService) {
        this.voucherService = voucherService;
    }

    /** Net en devise du paiement (2 décimales), bon de parrainage consommé. */
    public BigDecimal net(PaymentEntity payment, UUID travelerId, UUID bidId) {
        return payment.getAmount().subtract(payment.getCommissionAmount())
                .add(travelerVoucherTopUp(travelerId, bidId, payment.getCommissionAmount()));
    }

    /** Net carte en unités mineures de la devise du paiement (EUR par défaut). */
    public CurrencyAmount cardNet(PaymentEntity payment, UUID travelerId, UUID bidId) {
        SupportedCurrency currency = SupportedCurrency.fromCode(payment.getCurrency());
        return CurrencyAmount.of(net(payment, travelerId, bidId), currency != null ? currency : SupportedCurrency.EUR);
    }

    /**
     * Part de {@code commissionAmount} rendue au voyageur s'il détient un bon actif (consommé dans
     * le même geste) — {@code commissionAmount × (1 − facteur)}. Best-effort : un souci côté bon ne
     * bloque jamais le versement.
     */
    public BigDecimal travelerVoucherTopUp(UUID travelerId, UUID bidId, BigDecimal commissionAmount) {
        if (voucherService == null) {
            return BigDecimal.ZERO;
        }
        try {
            return voucherService.consume(travelerId, bidId)
                    .map(v -> commissionAmount.multiply(BigDecimal.ONE.subtract(v.getFactor()))
                            .setScale(2, RoundingMode.HALF_UP))
                    .orElse(BigDecimal.ZERO);
        } catch (Exception ex) {
            log.error("Échec consommation bon parrainage voyageur={} bid={}: {}", travelerId, bidId, ex.toString());
            return BigDecimal.ZERO;
        }
    }
}
