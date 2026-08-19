package com.yadony.api.settings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Un utilisateur est figé dans son pays, et donc dans la devise qui en dérive, dès
 * qu'un compte Stripe Connect existe pour lui ou dès le premier mouvement d'argent
 * réel : portefeuille non vide dans une devise quelconque, transaction portefeuille
 * déjà passée, ou envoi engagé au-delà de la simple mise en relation. Aucune colonne
 * dédiée : l'état se dérive, pour ne pas dupliquer une source de vérité.
 */
@Component
public class CountryLockService {

    /**
     * Statuts au-delà desquels l'expéditeur s'est engagé sur un envoi (paiement
     * escrow posé ou remise déjà entamée) — pas les statuts encore réversibles
     * sans conséquence financière (brouillon, négociation, terminaux).
     */
    private static final List<BidStatus> COMMITTED_BID_STATUSES = List.of(
            BidStatus.PAYMENT_ESCROWED,
            BidStatus.ACCEPTED,
            BidStatus.HANDED_OVER,
            BidStatus.IN_TRANSIT,
            BidStatus.ARRIVED);

    private final UserRepository userRepository;
    private final WalletAccountRepository walletAccountRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final BidRepository bidRepository;

    public CountryLockService(UserRepository userRepository,
                               WalletAccountRepository walletAccountRepository,
                               WalletTransactionRepository walletTransactionRepository,
                               BidRepository bidRepository) {
        this.userRepository = userRepository;
        this.walletAccountRepository = walletAccountRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.bidRepository = bidRepository;
    }

    public boolean isLocked(UUID userId) {
        return hasStripeAccount(userId)
                || hasNonZeroWalletBalance(userId)
                || walletTransactionRepository.existsByUserId(userId)
                || bidRepository.existsBySenderIdAndStatusIn(userId, COMMITTED_BID_STATUSES);
    }

    /**
     * Le pays d'un compte Connect est immuable chez Stripe : une fois le compte créé,
     * changer de pays côté yadony produirait une divergence irrattrapable.
     */
    private boolean hasStripeAccount(UUID userId) {
        return userRepository.findById(userId)
                .map(UserEntity::getStripeAccountId)
                .filter(id -> !id.isBlank())
                .isPresent();
    }

    private boolean hasNonZeroWalletBalance(UUID userId) {
        return walletAccountRepository.findAllByUserId(userId).stream()
                .map(WalletAccountEntity::getBalance)
                .anyMatch(balance -> balance != null && balance.compareTo(BigDecimal.ZERO) > 0);
    }
}
