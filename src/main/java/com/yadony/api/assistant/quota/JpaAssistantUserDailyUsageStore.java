package com.yadony.api.assistant.quota;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Questions du jour en base. Transaction courte et propre ({@code REQUIRES_NEW}) : le verrou de ligne est
 * rendu tout de suite, jamais gardé pendant l'appel au fournisseur, et la question comptée ne disparaît
 * pas si la transaction d'un appelant est annulée.
 */
@Component
public class JpaAssistantUserDailyUsageStore implements AssistantUserDailyUsageStore {

    private final AssistantUserDailyUsageRepository repository;

    public JpaAssistantUserDailyUsageStore(AssistantUserDailyUsageRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int tryConsume(String uid, LocalDate day, int limit) {
        repository.insertIfAbsent(uid, day);
        if (repository.incrementBelow(uid, day, limit) != 1) return REFUSED;
        // Relu sous le verrou de ligne pris par l'UPDATE : c'est exactement le compte de cette question.
        return repository.findQuestions(uid, day).orElseThrow();
    }
}
