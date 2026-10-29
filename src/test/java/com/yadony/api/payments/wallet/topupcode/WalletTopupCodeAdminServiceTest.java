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
    final TopupCodeHasher hasher = new TopupCodeHasher("unit-test-key-0123456789abcdef0123456789");
    WalletTopupCodeAdminService service;
    final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WalletTopupCodeAdminService(codes, redemptions, exchangeRateService, auditService, hasher);
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
        when(codes.existsByCodeHash(hasher.hash("DAKAR-2000-LANCEMENT"))).thenReturn(false);
        when(exchangeRateService.toEurPivot(new BigDecimal("2000"), "XOF")).thenReturn(new BigDecimal("3.05"));
        when(codes.saveAndFlush(any())).thenAnswer(i -> {
            WalletTopupCodeEntity e = i.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            return e;
        });

        WalletTopupCodeEntity c = service.create(cmd(" dakar-2000-lancement ", "2000", "xof", 100, null));

        assertThat(c.getIssuedCode()).isEqualTo("DAKAR-2000-LANCEMENT");
        assertThat(c.getCode()).isNull();
        assertThat(c.getCodeHash()).isEqualTo(hasher.hash("DAKAR-2000-LANCEMENT"));
        assertThat(c.getCodeHint()).isEqualTo("MENT");
        assertThat(c.getCurrency()).isEqualTo("XOF");
        assertThat(c.getMaxRedemptions()).isEqualTo(100);
        assertThat(c.getCreatedBy()).isEqualTo(adminId);
        assertThat(c.getExpiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusDays(89));
        assertThat(c.getExpiresAt()).isBefore(LocalDateTime.now(ZoneOffset.UTC).plusDays(91));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> payload =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditService).log(eq("wallet_topup_code"), any(), eq("WALLET_TOPUP_CODE_CREATED"), eq(adminId),
                payload.capture());
        assertThat(payload.getValue()).containsEntry("codeHint", "MENT").doesNotContainKey("code");
        assertThat(payload.getValue().values()).noneMatch(v -> String.valueOf(v).contains("LANCEMENT"));
    }

    @Test
    void create_sansCode_genereUnCodeYD() {
        when(codes.existsByCodeHash(anyString())).thenReturn(false);
        when(codes.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        WalletTopupCodeEntity c = service.create(cmd(null, "10", "EUR", 1, null));
        assertThat(c.getIssuedCode()).matches("YD(-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}){3}");
        assertThat(c.getCodeHash()).isEqualTo(hasher.hash(c.getIssuedCode()));
        assertThat(c.getCodeHint()).isEqualTo(c.getIssuedCode().substring(c.getIssuedCode().length() - 4));
    }

    @Test
    void create_doublon_409() {
        when(codes.existsByCodeHash(hasher.hash("DAKAR-2000-LANCEMENT"))).thenReturn(true);
        assertThatThrownBy(() -> service.create(cmd("DAKAR-2000-LANCEMENT", "10", "EUR", 1, null)))
                .satisfies(t -> assertCode(t, HttpStatus.CONFLICT, "topup-code-duplicate"));
    }

    @Test
    void create_doublonSimultane_contrainteUnique_409() {
        when(codes.existsByCodeHash(hasher.hash("DAKAR-2000-LANCEMENT"))).thenReturn(false);
        when(codes.saveAndFlush(any())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException("uq_wallet_topup_codes_code"));
        assertThatThrownBy(() -> service.create(cmd("DAKAR-2000-LANCEMENT", "10", "EUR", 1, null)))
                .satisfies(t -> assertCode(t, HttpStatus.CONFLICT, "topup-code-duplicate"));
        verify(auditService, org.mockito.Mockito.never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void create_auDelaDe500EurosEquivalent_422() {
        when(codes.existsByCodeHash(hasher.hash("GROS-CODE-0001"))).thenReturn(false);
        when(exchangeRateService.toEurPivot(new BigDecimal("400000"), "XOF")).thenReturn(new BigDecimal("609.80"));
        assertThatThrownBy(() -> service.create(cmd("GROS-CODE-0001", "400000", "XOF", 1, null)))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-amount-cap"));
    }

    @Test
    void create_500EurosPile_accepte() {
        when(codes.existsByCodeHash(hasher.hash("PILE-CODE-0001"))).thenReturn(false);
        when(codes.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.create(cmd("PILE-CODE-0001", "500", "EUR", 1, null)).getAmount()).isEqualByComparingTo("500");
    }

    @Test
    void create_decimalesEnXof_400() {
        assertThatThrownBy(() -> service.create(cmd("CFA1-CODE-0001", "1500.50", "XOF", 1, null)))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "topup-code-amount-invalid"));
    }

    @Test
    void create_deviseInconnue_quantiteHorsBornes_expirationPassee_formatInvalide_400() {
        assertThatThrownBy(() -> service.create(cmd("CODE1-TESTS-01", "10", "JPY", 1, null)))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "topup-code-currency-unsupported"));
        assertThatThrownBy(() -> service.create(cmd("CODE1-TESTS-01", "10", "EUR", 0, null)))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "topup-code-quantity-invalid"));
        assertThatThrownBy(() -> service.create(cmd("CODE1-TESTS-01", "10", "EUR", 10_001, null)))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "topup-code-quantity-invalid"));
        assertThatThrownBy(() -> service.create(cmd("CODE1-TESTS-01", "10", "EUR", 1,
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
        c.setCodeHint("MENT");
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

    @Test
    void create_codeManuelDeMoinsDe10LettresOuChiffres_400() {
        assertThatThrownBy(() -> service.create(cmd("DAKAR-2000", "10", "EUR", 1, null)))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "topup-code-too-short"));
        assertThatThrownBy(() -> service.create(cmd("AB-CD-EF-GH-I", "10", "EUR", 1, null)))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "topup-code-too-short"));
        org.mockito.Mockito.verifyNoInteractions(codes, auditService);
    }

    @Test
    void create_codeManuelDe10LettresOuChiffres_accepte() {
        when(codes.existsByCodeHash(hasher.hash("DAKAR-20001"))).thenReturn(false);
        when(codes.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.create(cmd("dakar-20001", "10", "EUR", 1, null)).getCodeHint()).isEqualTo("01");
    }
}
