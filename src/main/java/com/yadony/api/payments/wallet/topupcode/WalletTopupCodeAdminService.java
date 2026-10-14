package com.yadony.api.payments.wallet.topupcode;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Création, consultation et révocation des codes de recharge (admin). */
@Service
@Transactional
public class WalletTopupCodeAdminService {

    /** Même plafond que WalletAdminAdjustmentService.CAP_EUR : 500 € d'équivalent par code. */
    static final BigDecimal CAP_EUR = new BigDecimal("500");
    static final int MAX_QUANTITY = 10_000;
    static final int DEFAULT_VALIDITY_DAYS = 90;
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int MAX_GENERATION_ATTEMPTS = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record CreateCommand(String code, BigDecimal amount, String currency, Integer maxRedemptions,
                                LocalDateTime expiresAt, String note, UUID adminId) {}

    private final WalletTopupCodeRepository codes;
    private final WalletTopupCodeRedemptionRepository redemptions;
    private final ExchangeRateService exchangeRateService;
    private final AuditService auditService;

    public WalletTopupCodeAdminService(WalletTopupCodeRepository codes,
                                       WalletTopupCodeRedemptionRepository redemptions,
                                       ExchangeRateService exchangeRateService,
                                       AuditService auditService) {
        this.codes = codes;
        this.redemptions = redemptions;
        this.exchangeRateService = exchangeRateService;
        this.auditService = auditService;
    }

    public WalletTopupCodeEntity create(CreateCommand command) {
        String code = command.code() == null || command.code().isBlank()
                ? generateCode()
                : WalletTopupCodeRedemptionService.normalize(command.code());
        if (!WalletTopupCodeRedemptionService.CODE_FORMAT.matcher(code).matches()) {
            throw badRequest("topup-code-format-invalid",
                    "Le code doit faire 4 à 32 caractères : lettres, chiffres ou tirets");
        }
        SupportedCurrency currency = SupportedCurrency.fromCode(command.currency() == null ? null
                : command.currency().trim().toUpperCase(java.util.Locale.ROOT));
        if (currency == null) {
            throw badRequest("topup-code-currency-unsupported", "Devise non prise en charge");
        }
        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > currency.minorUnit()) {
            throw badRequest("topup-code-amount-invalid", "Le montant doit être positif, avec au plus "
                    + currency.minorUnit() + " décimale(s) en " + currency.name());
        }
        int quantity = command.maxRedemptions() == null ? 1 : command.maxRedemptions();
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw badRequest("topup-code-quantity-invalid", "La quantité doit être comprise entre 1 et " + MAX_QUANTITY);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expiresAt = command.expiresAt() == null ? now.plusDays(DEFAULT_VALIDITY_DAYS) : command.expiresAt();
        if (!expiresAt.isAfter(now)) {
            throw badRequest("topup-code-expiry-invalid", "La date d'expiration doit être dans le futur");
        }
        if (codes.existsByCode(code)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "topup-code-duplicate", "Conflict",
                    "Un code de recharge existe déjà avec ce code : " + code);
        }
        assertUnderCap(amount, currency.name());

        WalletTopupCodeEntity entity = new WalletTopupCodeEntity();
        entity.setCode(code);
        entity.setAmount(amount);
        entity.setCurrency(currency.name());
        entity.setMaxRedemptions(quantity);
        entity.setExpiresAt(expiresAt);
        entity.setNote(command.note() == null || command.note().isBlank() ? null : command.note().trim());
        entity.setCreatedBy(command.adminId());
        WalletTopupCodeEntity saved = codes.save(entity);

        Map<String, Object> payload = new HashMap<>();
        payload.put("code", saved.getCode());
        payload.put("amount", amount.toPlainString());
        payload.put("currency", currency.name());
        payload.put("maxRedemptions", quantity);
        auditService.log("wallet_topup_code", saved.getId(), "WALLET_TOPUP_CODE_CREATED", command.adminId(), payload);
        return saved;
    }

    /** Code libre, au format YD-XXXX-XXXX, sans caractères ambigus (0/O, 1/I/L). */
    @Transactional(readOnly = true)
    public String generateCode() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = "YD-" + randomBlock() + "-" + randomBlock();
            if (!codes.existsByCode(candidate)) return candidate;
        }
        throw new IllegalStateException("Impossible de générer un code de recharge libre");
    }

    @Transactional(readOnly = true)
    public List<WalletTopupCodeEntity> list() { return codes.findAllByOrderByCreatedAtDesc(); }

    @Transactional(readOnly = true)
    public WalletTopupCodeEntity get(UUID id) {
        return codes.findById(id).orElseThrow(WalletTopupCodeAdminService::notFound);
    }

    @Transactional(readOnly = true)
    public List<WalletTopupCodeRedemptionEntity> redemptions(UUID codeId) {
        get(codeId);
        return redemptions.findByCodeIdOrderByCreatedAtDesc(codeId);
    }

    /** Idempotent : un code déjà révoqué garde son auteur et sa date. Les crédits versés restent acquis. */
    public WalletTopupCodeEntity revoke(UUID id, UUID adminId) {
        WalletTopupCodeEntity code = codes.findByIdForUpdate(id).orElseThrow(WalletTopupCodeAdminService::notFound);
        if (code.getStatus() == WalletTopupCodeStatus.REVOKED) return code;
        code.setStatus(WalletTopupCodeStatus.REVOKED);
        code.setRevokedBy(adminId);
        code.setRevokedAt(LocalDateTime.now(ZoneOffset.UTC));
        WalletTopupCodeEntity saved = codes.save(code);
        auditService.log("wallet_topup_code", id, "WALLET_TOPUP_CODE_REVOKED", adminId,
                Map.of("code", code.getCode(), "redeemedCount", code.getRedeemedCount()));
        return saved;
    }

    private void assertUnderCap(BigDecimal amount, String code) {
        BigDecimal eur = "EUR".equals(code) ? amount : exchangeRateService.toEurPivot(amount, code);
        if (eur.compareTo(CAP_EUR) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-amount-cap",
                    "Unprocessable", "Un code ne peut pas dépasser 500 € d'équivalent",
                    Map.of("capEur", CAP_EUR.toPlainString(), "eurEquivalent", eur.toPlainString(), "currency", code));
        }
    }

    private static String randomBlock() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private static YadonyBusinessException badRequest(String code, String detail) {
        return new YadonyBusinessException(HttpStatus.BAD_REQUEST, code, "Bad Request", detail);
    }

    private static YadonyBusinessException notFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "topup-code-not-found", "Not Found",
                "Code de recharge introuvable");
    }
}
