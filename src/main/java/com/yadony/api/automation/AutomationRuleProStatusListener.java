package com.yadony.api.automation;

import com.yadony.api.auth.UserProStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Aligne les règles d'automatisation sur le statut PRO du voyageur.
 *
 * <p>Les règles s'exécutent côté serveur et ne s'arrêteraient pas d'elles-mêmes :
 * sans cette désactivation, un voyageur résilié continuerait de bénéficier du
 * moteur d'automatisations.
 *
 * <p>Les règles ne sont jamais supprimées, conformément à la règle du projet
 * interdisant les suppressions physiques, et parce qu'un réabonnement doit
 * restaurer une configuration immédiatement opérationnelle.
 */
@Component
public class AutomationRuleProStatusListener {

    private static final Logger log = LoggerFactory.getLogger(AutomationRuleProStatusListener.class);

    private final AutomationRuleRepository ruleRepository;

    public AutomationRuleProStatusListener(AutomationRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @EventListener
    @Transactional
    public void onUserProStatusChanged(UserProStatusChangedEvent event) {
        if (event.isPro()) {
            restore(event.userId());
        } else {
            suspend(event.userId());
        }
    }

    private void suspend(java.util.UUID travelerId) {
        List<AutomationRuleEntity> rules = ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId);
        int suspended = 0;
        for (AutomationRuleEntity rule : rules) {
            if (rule.isEnabled()) {
                rule.setEnabled(false);
                rule.setDisabledByDowngrade(true);
                suspended++;
            }
        }
        if (suspended > 0) {
            ruleRepository.saveAll(rules);
            log.info("PRO access lost for traveler {} — {} automation rules suspended",
                    travelerId, suspended);
        }
    }

    private void restore(java.util.UUID travelerId) {
        List<AutomationRuleEntity> rules =
                ruleRepository.findByTravelerIdAndDisabledByDowngradeTrue(travelerId);
        if (rules.isEmpty()) {
            return;
        }
        for (AutomationRuleEntity rule : rules) {
            rule.setEnabled(true);
            rule.setDisabledByDowngrade(false);
        }
        ruleRepository.saveAll(rules);
        log.info("PRO access restored for traveler {} — {} automation rules re-enabled",
                travelerId, rules.size());
    }
}
