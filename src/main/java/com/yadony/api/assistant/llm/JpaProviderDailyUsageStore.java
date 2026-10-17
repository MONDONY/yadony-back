package com.yadony.api.assistant.llm;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compteurs en base. Les écritures ont leur propre transaction, courte ({@code REQUIRES_NEW}) : le verrou
 * de ligne est rendu tout de suite, jamais gardé pendant l'appel au fournisseur, et le compteur ne
 * disparaît pas si la transaction d'un appelant est annulée.
 */
@Component
public class JpaProviderDailyUsageStore implements ProviderDailyUsageStore {

    private final ProviderDailyUsageRepository repository;

    public JpaProviderDailyUsageStore(ProviderDailyUsageRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryReserve(UUID providerId, LocalDate day, int limit) {
        repository.insertIfAbsent(providerId, day);
        return repository.incrementRequestsBelow(providerId, day, limit) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void addSuccess(UUID providerId, LocalDate day) {
        // La ligne existe déjà (réservée le même jour), sauf si minuit UTC est passé pendant l'appel.
        repository.insertIfAbsent(providerId, day);
        repository.incrementSuccesses(providerId, day);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, DailyCount> read(Collection<UUID> providerIds, LocalDate day) {
        if (providerIds.isEmpty()) return Map.of();
        return repository.findCounts(providerIds, day).stream()
                .collect(Collectors.toMap(DailyCount::providerId, Function.identity()));
    }
}
