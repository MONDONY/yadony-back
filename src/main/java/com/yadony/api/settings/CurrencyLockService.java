package com.yadony.api.settings;

import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Un utilisateur est figé dans sa devise uniquement tant qu'il détient un solde non
 * nul dans une devise quelconque : seul l'argent réellement détenu fige le choix, pas
 * l'historique. Un solde retombé à zéro rouvre le choix, c'est le comportement voulu.
 * Aucune colonne dédiée : l'état se dérive, pour ne pas dupliquer une source de vérité.
 */
@Component
public class CurrencyLockService {

    private final WalletAccountRepository walletAccountRepository;

    public CurrencyLockService(WalletAccountRepository walletAccountRepository) {
        this.walletAccountRepository = walletAccountRepository;
    }

    public boolean isLocked(UUID userId) {
        return walletAccountRepository.findAllByUserId(userId).stream()
                .map(WalletAccountEntity::getBalance)
                .anyMatch(balance -> balance != null && balance.compareTo(BigDecimal.ZERO) > 0);
    }
}
