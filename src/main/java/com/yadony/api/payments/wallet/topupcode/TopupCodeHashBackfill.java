package com.yadony.api.payments.wallet.topupcode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hache les codes de recharge créés avant V360 puis efface leur texte en clair. Tourne une fois
 * les beans créés et AVANT le démarrage du serveur web ({@link SmartInitializingSingleton}) : aucune
 * requête ne peut chercher un ancien code par empreinte avant qu'elle existe. Idempotent : une
 * ligne déjà hachée n'est plus sélectionnée. SQL direct, pour couvrir aussi les lignes
 * soft-deleted (l'index unique de l'empreinte les inclut).
 */
@Component
public class TopupCodeHashBackfill implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(TopupCodeHashBackfill.class);

    private final JdbcTemplate jdbc;
    private final TopupCodeHasher hasher;
    private final TransactionTemplate transaction;

    public TopupCodeHashBackfill(JdbcTemplate jdbc, TopupCodeHasher hasher, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.transaction = new TransactionTemplate(txManager);
    }

    @Override
    public void afterSingletonsInstantiated() {
        Integer hashed = transaction.execute(status -> backfill());
        if (hashed != null && hashed > 0) {
            log.info("Codes de recharge : {} code(s) existant(s) hachés, texte en clair effacé", hashed);
        }
    }

    int backfill() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, code FROM wallet_topup_codes WHERE code_hash IS NULL AND code IS NOT NULL FOR UPDATE");
        for (Map<String, Object> row : rows) {
            String code = WalletTopupCodeRedemptionService.normalize((String) row.get("code"));
            jdbc.update("UPDATE wallet_topup_codes SET code_hash = ?, code_hint = ?, code = NULL WHERE id = ?",
                    hasher.hash(code), TopupCodeHasher.hint(code), (UUID) row.get("id"));
        }
        return rows.size();
    }
}
