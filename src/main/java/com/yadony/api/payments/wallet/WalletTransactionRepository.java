package com.yadony.api.payments.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransactionEntity, UUID> {

    // Liste et non Page : l'écran portefeuille n'affiche pas le total, et une Page ajoutait un
    // COUNT(*) de tout l'historique de l'utilisateur à chaque GET /wallet/balance.
    List<WalletTransactionEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<WalletTransactionEntity> findByIdempotencyKey(String idempotencyKey);

    // Journal admin des mouvements (cf. WalletAdminAdjustmentService#transactions) : le tri
    // vient du Pageable, un filtre absent choisit la variante sans ce critère.
    Page<WalletTransactionEntity> findByUserId(UUID userId, Pageable pageable);

    Page<WalletTransactionEntity> findByUserIdAndCurrency(UUID userId, String currency, Pageable pageable);

    Page<WalletTransactionEntity> findByUserIdAndType(UUID userId, WalletTransactionType type, Pageable pageable);

    Page<WalletTransactionEntity> findByUserIdAndCurrencyAndType(UUID userId, String currency,
                                                                 WalletTransactionType type, Pageable pageable);

    boolean existsByUserIdAndBidIdAndType(UUID userId, UUID bidId, WalletTransactionType type);

    boolean existsByUserId(UUID userId);


    List<WalletTransactionEntity> findAllByUserIdAndBidIdAndType(UUID userId, UUID bidId, WalletTransactionType type);

    /**
     * Lignes d'un débit rattaché à une référence de paiement plutôt qu'à un bid — la
     * commission espèces d'un fil de négociation est débitée avec {@code payment_ref} =
     * id du fil et {@code bid_id} NULL ({@code WalletCommissionCollector#executeForNegotiation}).
     */
    List<WalletTransactionEntity> findAllByUserIdAndPaymentRefAndType(UUID userId, String paymentRef,
                                                                     WalletTransactionType type);

    List<WalletTransactionEntity> findByUserIdAndCurrencyOrderByCreatedAtAsc(UUID userId, String currency);
}
