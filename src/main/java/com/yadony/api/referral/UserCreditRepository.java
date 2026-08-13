package com.yadony.api.referral;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserCreditRepository extends JpaRepository<UserCreditEntity, UUID> {

    List<UserCreditEntity> findByUserId(UUID userId);

    @Query("SELECT COALESCE(SUM(c.amountCents), 0) FROM UserCreditEntity c WHERE c.userId = :userId")
    int sumAmountCentsByUserId(@Param("userId") UUID userId);

    /**
     * Cumul des crédits d'une seule devise. Sommer toutes devises confondues
     * additionnerait des dollars et des euros pour produire un total qu'aucun
     * symbole ne peut légender honnêtement.
     */
    @Query("SELECT COALESCE(SUM(c.amountCents), 0) FROM UserCreditEntity c "
            + "WHERE c.userId = :userId AND UPPER(c.currency) = UPPER(:currency)")
    int sumAmountCentsByUserIdAndCurrency(@Param("userId") UUID userId,
                                          @Param("currency") String currency);
}
