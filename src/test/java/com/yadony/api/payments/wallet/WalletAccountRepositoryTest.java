package com.yadony.api.payments.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class WalletAccountRepositoryTest {

    @Autowired WalletAccountRepository repository;

    @Test
    void allowsMultipleCurrencyRowsPerUser() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity eur = new WalletAccountEntity();
        eur.setUserId(userId);
        eur.setCurrency("EUR");
        eur.setBalance(new BigDecimal("10.00"));
        repository.save(eur);

        WalletAccountEntity cad = new WalletAccountEntity();
        cad.setUserId(userId);
        cad.setCurrency("CAD");
        cad.setBalance(new BigDecimal("5.00"));
        repository.save(cad);

        assertThat(repository.findAllByUserId(userId)).hasSize(2);
        assertThat(repository.findByUserIdAndCurrency(userId, "CAD")).isPresent();
    }
}
