package com.yadony.api.assistant.adminhelp;

import com.yadony.api.assistant.quota.AssistantUserDailyUsageStore;
import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Questions à l'assistant de la console par administrateur et par jour UTC. Même table que le quota de l'app
 * (V329), sous la clé {@code admin:<id>} qui ne peut pas croiser un uid Firebase. Base injoignable : la question
 * passe (une panne ne prive pas l'équipe d'aide ; le coût reste borné par les limites des fournisseurs).
 */
@Component
public class AdminHelpQuota {

    private static final Logger log = LoggerFactory.getLogger(AdminHelpQuota.class);
    static final String KEY_PREFIX = "admin:";

    private final AssistantUserDailyUsageStore store;
    private final AdminHelpProperties properties;
    private final Clock clock;

    @Autowired
    public AdminHelpQuota(AssistantUserDailyUsageStore store, AdminHelpProperties properties) {
        this(store, properties, Clock.systemUTC());
    }

    AdminHelpQuota(AssistantUserDailyUsageStore store, AdminHelpProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return les questions restantes aujourd'hui, celle-ci déduite ; 429 si le quota est atteint */
    public int consume(UUID adminId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        int quota = properties.dailyQuota();
        int used;
        try {
            used = store.tryConsume(KEY_PREFIX + adminId, today, quota);
        } catch (DataAccessException | TransactionException e) {
            log.warn("Centre d'aide admin : quota injoignable en base, question accordée : {}", e.getClass().getSimpleName());
            return quota;
        }
        if (used == AssistantUserDailyUsageStore.REFUSED) {
            String retryAfter = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "admin-help-quota-exceeded",
                    "problem.admin-help.admin-help-quota-exceeded", Map.of("retryAfter", retryAfter));
        }
        return quota - used;
    }
}
