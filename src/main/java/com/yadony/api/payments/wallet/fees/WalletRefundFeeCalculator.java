package com.yadony.api.payments.wallet.fees;

import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.wallet.WalletRefundAllocation.RefundableTopup;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Frais retenus sur une recharge remboursée : nuls tant qu'elle a été partiellement
 * dépensée (le principe posé lot 2, tâche 1 est qu'on ne rembourse jamais une recharge
 * entamée), sinon les frais réels du rail d'origine, plafonnés au montant restant pour
 * qu'un frais mal renseigné ne fasse jamais passer le net en négatif.
 *
 * <p>Pur : aucune dépendance Spring, aucun appel réseau. {@link FeeSources} isole les
 * deux rails (Stripe, pawaPay) pour que ce calcul reste testable sans mock statique ;
 * les implémentations réelles ({@link StripeFeeSource}, {@link PawapayFeeTable}) et
 * leur branchement dans l'allocateur arrivent en tâche 3.
 */
public final class WalletRefundFeeCalculator {

    private WalletRefundFeeCalculator() {
    }

    /** Source des frais réels par rail, injectée pour isoler le calcul pur des appels externes. */
    public interface FeeSources {
        BigDecimal stripeFee(String paymentIntentId, BigDecimal amount, String currency);

        BigDecimal pawapayFee(String provider, BigDecimal amount, String currency);
    }

    /**
     * Frais retenus sur {@code topup} lors de son remboursement, dans {@code currency}
     * (la devise du wallet, pas forcément celle du rail sous-jacent).
     */
    public static BigDecimal feeFor(RefundableTopup topup, String currency, FeeSources sources) {
        if (topup.original().compareTo(topup.remaining()) != 0) {
            return BigDecimal.ZERO;
        }
        int scale = SupportedCurrency.fromCodeOrDefault(currency).minorUnit();
        BigDecimal raw = switch (topup.rail()) {
            case STRIPE -> sources.stripeFee(topup.paymentIntentId(), topup.remaining(), currency);
            case PAWAPAY -> sources.pawapayFee(topup.provider(), topup.remaining(), currency);
        };
        return raw.setScale(scale, RoundingMode.HALF_UP).min(topup.remaining());
    }
}
