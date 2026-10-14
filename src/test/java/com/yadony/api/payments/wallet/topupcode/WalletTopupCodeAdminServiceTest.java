package com.yadony.api.payments.wallet.topupcode;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ExchangeRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletTopupCodeAdminServiceTest {

    @Mock WalletTopupCodeRepository codes;
    @Mock WalletTopupCodeRedemptionRepository redemptions;
    @Mock ExchangeRateService exchangeRateService;
    @Mock AuditService auditService;
    WalletTopupCodeAdminService service;
    final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WalletTopupCodeAdminService(codes, redemptions, exchangeRateService, auditService);
    }

    private WalletTopupCodeAdminService.CreateCommand cmd(String code, String amount, String currency,
                                                          Integer qty, LocalDateTime expiresAt) {
        return new WalletTopupCodeAdminService.CreateCommand(code, new BigDecimal(amount), currency, qty,
                expiresAt, "Lancement Dakar", adminId);
    }

    private static void assertCode(Throwable t, HttpStatus status, String code) {
        YadonyBusinessException e = (YadonyBusinessException) t;
        assertThat(e.getStatus()).isEqualTo(status);
        assertThat(e.getErrorCode()).isEqualTo(code);
    }

    @Test
    void create_codeLibre_normalise_expirationParDefaut90Jours_etTrace() {
        when(codes.existsByCode("DAKAR2000")).thenReturn(false);
        when(exchangeRateService.toEurPivot(new BigDecimal("2000"), "XOF")).thenReturn(new BigDecimal("3.05"));
        when(codes.save(any())).thenAnswer(i -> {
            WalletTopupCodeEntity e = i.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            return e;
        });

        WalletTopupCodeEntity c = service.create(cmd(" dakar2000 ", "2000", "xof", 100, null));

        assertThat(c.getCode()).isEqualTo("DAKAR2000");
        assertThat(c.getCurrency()).isEqualTo("XOF");
        assertThat(c.getMaxRedemptions()).isEqualTo(100);
        assertThat(c.getCreatedBy()).isEqualTo(adminId);
        assertThat(c.getExpiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusDays(89));
        assertThat(c.getExpiresAt()).isBefore(LocalDateTime.now(ZoneOffset.UTC).plusDays(91));
        verify(auditService).log(eq("wallet_topup_code"), any(), eq("WALLET_TOPUP_CODE_CREATED"), eq(adminId), any());
    }

    @Test
    void create_sansCode_genereUnCodeYD() {
        when(codes.existsByCode(anyString())).thenReturn(false);
        when(codes.save(any())).thenAnswer(i -> i.getArgument(0));
        WalletTopupCodeEntity c = service.create(cmd(null, "10", "EUR", 1, null));
        assertThat(c.getCode()).matches("YD-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}");
    }

    @Test
    void create_doublon_409() {
        when(codes.existsByCode("DAKAR2000")).thenReturn(true);
        assertThatThrownBy(() -> service.create(cmd("DAKAR2000", "10", "EUR", 1, null)))
                .satisfies(t -> assertCode(t, HttpStatus.CONFLICT, "topup-code-duplicate"));
    }

    @Test
    void create_auDelaDe500EurosEquivalent_422() {
        when(codes.existsByCode("GROS")).thenReturn(false);
        when(exchangeRateService.toEurPivot(new BigDecimal("400000"), "XOF")).thenReturn(new BigDecimal("609.80"));
        assertThatThrownBy(() -> service.create(cmd("GROS", "400000", "XOF", 1, null)))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-amount-cap"));
    }

    @Test
    void create_500EurosPile_accepte() {
        when(codes.existsByCode("PILE")).thenReturn(false);
        when(codes.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.create(cmd("PILE", "500", "EUR", 1, null)).getAmount()).isEqualByComparingTo("500");
    }

    @Test
    void create_decimalesEnXof_400() {
        assertThatThrownBy(() -> service.create(cmd("CFA1", "1500.50", "XOF", 1, null)))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "topup-code-amount-invalid"));
    }

    @Test
    void create_deviseInconnue_quantiteHorsBornes_expirationPassee_formatInvalide_400() {
        assertThatThrownBy(() -> service.create(cmd("CODE1", "10", "JPY", 1, null)))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "topup-code-currency-unsupported"));
        assertThatThrownBy(() -> service.create(cmd("CODE1", "10", "EUR", 0, null)))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "topup-code-quantity-invalid"));
        assertThatThrownBy(() -> service.create(cmd("CODE1", "10", "EUR", 10_001, null)))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "topup-code-quantity-invalid"));
        assertThatThrownBy(() -> service.create(cmd("CODE1", "10", "EUR", 1,
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1))))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "topup-code-expiry-invalid"));
        assertThatThrownBy(() -> service.create(cmd("A!", "10", "EUR", 1, null)))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "topup-code-format-invalid"));
    }

    @Test
    void revoke_poseStatutAuteurEtDate_idempotent() {
        UUID id = UUID.randomUUID();
        WalletTopupCodeEntity c = new WalletTopupCodeEntity();
        ReflectionTestUtils.setField(c, "id", id);
        c.setCode("DAKAR2000");
        when(codes.findByIdForUpdate(id)).thenReturn(Optional.of(c));
        when(codes.save(any())).thenAnswer(i -> i.getArgument(0));

        service.revoke(id, adminId);
        LocalDateTime first = c.getRevokedAt();
        service.revoke(id, adminId);

        assertThat(c.getStatus()).isEqualTo(WalletTopupCodeStatus.REVOKED);
        assertThat(c.getRevokedBy()).isEqualTo(adminId);
        assertThat(c.getRevokedAt()).isEqualTo(first);
        verify(auditService).log(eq("wallet_topup_code"), eq(id), eq("WALLET_TOPUP_CODE_REVOKED"), eq(adminId), any());
    }

    @Test
    void get_inconnu_404() {
        UUID id = UUID.randomUUID();
        when(codes.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id))
                .satisfies(t -> assertCode(t, HttpStatus.NOT_FOUND, "topup-code-not-found"));
    }
}
