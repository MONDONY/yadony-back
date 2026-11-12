package com.yadony.api.payments.wallet.topupcode;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.dao.DataIntegrityViolationException;
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
    /**
     * Code saisi par l'admin : au moins 10 lettres ou chiffres (les tirets ne comptent pas),
     * pour qu'il ne se devine pas en quelques milliers d'essais (décision du propriétaire, 28/10).
     */
    static final int MANUAL_CODE_MIN_ALPHANUMERIC = 10;
    private static final int MAX_GENERATION_ATTEMPTS = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record CreateCommand(String code, BigDecimal amount, String currency, Integer maxRedemptions,
                                LocalDateTime expiresAt, String note, UUID adminId) {}

    private final WalletTopupCodeRepository codes;
    private final WalletTopupCodeRedemptionRepository redemptions;
    private final ExchangeRateService exchangeRateService;
    private final AuditService auditService;
    private final TopupCodeHasher hasher;

    public WalletTopupCodeAdminService(WalletTopupCodeRepository codes,
                                       WalletTopupCodeRedemptionRepository redemptions,
                                       ExchangeRateService exchangeRateService,
                                       AuditService auditService,
                                       TopupCodeHasher hasher) {
        this.codes = codes;
        this.redemptions = redemptions;
        this.exchangeRateService = exchangeRateService;
        this.auditService = auditService;
        this.hasher = hasher;
    }

    /**
     * Le code créé porte son texte en clair dans {@link WalletTopupCodeEntity#getIssuedCode()},
     * jamais persisté : c'est la seule fois où l'admin peut le copier.
     */

    public WalletTopupCodeEntity create(CreateCommand command) {
        boolean manual = command.code() != null && !command.code().isBlank();
        String code = manual ? WalletTopupCodeRedemptionService.normalize(command.code()) : generateCode();
        if (!WalletTopupCodeRedemptionService.CODE_FORMAT.matcher(code).matches()) {
            throw badRequest("topup-code-format-invalid", "problem.topup-code-format-invalid");
        }
        if (manual && code.replace("-", "").length() < MANUAL_CODE_MIN_ALPHANUMERIC) {
            throw badRequest("topup-code-too-short", "problem.topup-code-too-short", String.valueOf(MANUAL_CODE_MIN_ALPHANUMERIC));
        }
        String codeHash = hasher.hash(code);
        SupportedCurrency currency = SupportedCurrency.fromCode(command.currency() == null ? null
                : command.currency().trim().toUpperCase(java.util.Locale.ROOT));
        if (currency == null) {
            throw badRequest("topup-code-currency-unsupported", "problem.topup-code-currency-unsupported");
        }
        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > currency.minorUnit()) {
            throw badRequest("topup-code-amount-invalid", "problem.topup-code-amount-invalid", String.valueOf(currency.minorUnit()), String.valueOf(currency.name()));
        }
        int quantity = command.maxRedemptions() == null ? 1 : command.maxRedemptions();
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw badRequest("topup-code-quantity-invalid", "problem.topup-code-quantity-invalid", String.valueOf(MAX_QUANTITY));
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expiresAt = command.expiresAt() == null ? now.plusDays(DEFAULT_VALIDITY_DAYS) : command.expiresAt();
        if (!expiresAt.isAfter(now)) {
            throw badRequest("topup-code-expiry-invalid", "problem.topup-code-expiry-invalid");
        }
        if (codes.existsByCodeHash(codeHash)) {
            throw duplicate();
        }
        assertUnderCap(amount, currency.name());

        WalletTopupCodeEntity entity = new WalletTopupCodeEntity();
        entity.setCodeHash(codeHash);
        entity.setCodeHint(TopupCodeHasher.hint(code));
        entity.setAmount(amount);
        entity.setCurrency(currency.name());
        entity.setMaxRedemptions(quantity);
        entity.setExpiresAt(expiresAt);
        entity.setNote(command.note() == null || command.note().isBlank() ? null : command.note().trim());
        entity.setCreatedBy(command.adminId());
        WalletTopupCodeEntity saved;
        try {
            // Flush immédiat : deux créations simultanées du même code se départagent sur
            // uq_wallet_topup_codes_code ici, en 409, au lieu d'un 500 au commit.
            saved = codes.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw duplicate();
        }
        saved.setIssuedCode(code);

        // Jamais le code en clair : audit_log est immuable et lisible par tout admin d'audit.
        Map<String, Object> payload = new HashMap<>();
        payload.put("codeHint", saved.getCodeHint());
        payload.put("amount", amount.toPlainString());
        payload.put("currency", currency.name());
        payload.put("maxRedemptions", quantity);
        auditService.log("wallet_topup_code", saved.getId(), "WALLET_TOPUP_CODE_CREATED", command.adminId(), payload);
        return saved;
    }

    /**
     * Code libre, au format YD-XXXX-XXXX-XXXX (12 caractères tirés au hasard sur 31, environ 59
     * bits), sans caractères ambigus (0/O, 1/I/L).
     */
    @Transactional(readOnly = true)
    public String generateCode() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = "YD-" + randomBlock() + "-" + randomBlock() + "-" + randomBlock();
            if (!codes.existsByCodeHash(hasher.hash(candidate))) return candidate;
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
                Map.of("codeHint", String.valueOf(code.getCodeHint()), "redeemedCount", code.getRedeemedCount()));
        return saved;
    }

    private void assertUnderCap(BigDecimal amount, String code) {
        BigDecimal eur = "EUR".equals(code) ? amount : exchangeRateService.toEurPivot(amount, code);
        if (eur.compareTo(CAP_EUR) > 0) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-amount-cap", "problem.topup-code-amount-cap", Map.of("capEur", CAP_EUR.toPlainString(), "eurEquivalent", eur.toPlainString(), "currency", code));
        }
    }

    private static String randomBlock() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private static YadonyBusinessException badRequest(String code, String key, Object... args) {
        return YadonyBusinessException.localized(HttpStatus.BAD_REQUEST, code, key, args);
    }

    private static YadonyBusinessException duplicate() {
        return YadonyBusinessException.localized(HttpStatus.CONFLICT, "topup-code-duplicate", "problem.topup-code-duplicate");
    }

    private static YadonyBusinessException notFound() {
        return YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "topup-code-not-found", "problem.topup-code-not-found");
    }
}
