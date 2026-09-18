package com.yadony.api.payments.wallet.fees;

import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.wallet.WalletRefundAllocation.RefundableTopup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Frais retenus sur une recharge remboursée : nuls tant qu'elle a été partiellement
 * dépensée (le principe posé lot 2, tâche 1 est qu'on ne rembourse jamais une recharge
 * entamée), sinon les frais réels du rail d'origine, plafonnés au montant restant pour
 * qu'un frais mal renseigné ne fasse jamais passer le net en négatif.
 *
 * <p>Pur : aucune dépendance Spring, aucun appel réseau. {@link FeeSources} isole les
 * deux rails (Stripe, pawaPay) pour que ce calcul reste testable sans mock statique ; les
 * implémentations réelles ({@link StripeFeeSource}, {@link PawapayFeeTable}) sont branchées
 * par {@code WalletSelfRefundService#load} sur {@code WalletRefundAllocator#allocate}.
 */
public final class WalletRefundFeeCalculator {

    private WalletRefundFeeCalculator() {
    }

    /**
     * Source des frais réels par rail, injectée pour isoler le calcul pur des appels externes.
     *
     * <p><b>Contrat :</b> les deux méthodes rendent TOUJOURS un montant, jamais {@code null} —
     * un frais inconnu ou illisible se rend {@code BigDecimal.ZERO} (on ne retient alors rien),
     * jamais {@code null}. {@link #feeFor} le vérifie explicitement plutôt que de laisser un
     * {@code NullPointerException} opaque remonter d'un {@code setScale}.
     */
    public interface FeeSources {
        /** @return le frais Stripe de ce paiement, jamais {@code null} ({@code ZERO} si inconnu). */
        BigDecimal stripeFee(String paymentIntentId, BigDecimal amount, String currency);

        /** @return le frais pawaPay de cet opérateur, jamais {@code null} ({@code ZERO} si inconnu). */
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
        Objects.requireNonNull(raw, () -> "FeeSources." + topup.rail()
                + " a rendu null : un frais inconnu se rend BigDecimal.ZERO, jamais null");
        return raw.setScale(scale, RoundingMode.HALF_UP).min(topup.remaining());
    }
}
