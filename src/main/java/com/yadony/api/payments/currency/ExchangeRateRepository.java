package com.yadony.api.payments.currency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRateEntity, String> {

    Optional<ExchangeRateEntity> findByCurrency(String currency);
}
