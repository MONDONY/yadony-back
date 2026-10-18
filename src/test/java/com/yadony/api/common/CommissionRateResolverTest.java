package com.yadony.api.common;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.config.PlatformSettingsTestFactory;
import com.yadony.api.promo.PromoCodeTarget;
import com.yadony.api.promo.PromoDiscount;
import com.yadony.api.promo.PromoService;
import com.yadony.api.voucher.CommissionVoucherEntity;
import com.yadony.api.voucher.CommissionVoucherService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommissionRateResolverTest {

    @Mock UserRepository userRepository;
    @Mock PromoService promoService;
    @Mock CommissionVoucherService voucherService;

    private CommissionRateResolver resolver() {
        return new CommissionRateResolver(userRepository,
                PlatformSettingsTestFactory.withCommissionRate(new BigDecimal("0.12")),
                promoService, voucherService);
    }

    private CommissionVoucherEntity activeVoucher(BigDecimal factor) {
        CommissionVoucherEntity v = mock(CommissionVoucherEntity.class);
        lenient().when(v.getFactor()).thenReturn(factor);
        return v;
    }

    private UserEntity withOverride(BigDecimal rate) {
        UserEntity u = new UserEntity();
        u.setCommissionRateOverride(rate);
        return u;
    }

    @Test
    void global_rate_when_no_override() {
        UUID t = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(null)));
        assertThat(resolver().resolve(t)).isEqualByComparingTo("0.12");
    }

    @Test
    void traveler_override_applies_when_lower_than_global() {
        UUID t = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.08"))));
        assertThat(resolver().resolve(t)).isEqualByComparingTo("0.08");
    }

    @Test
    void takes_most_favorable_min_of_traveler_and_sender() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.10"))));
        when(userRepository.findById(s)).thenReturn(Optional.of(withOverride(new BigDecimal("0.06"))));
        assertThat(resolver().resolve(t, s)).isEqualByComparingTo("0.06");
    }

    @Test
    void override_above_global_never_increases_rate() {
        UUID t = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.20"))));
        // min(global 0.12, override 0.20) = 0.12 — un override plus élevé ne pénalise pas.
        assertThat(resolver().resolve(t)).isEqualByComparingTo("0.12");
    }

    @Test
    void null_sender_is_ignored() {
        UUID t = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.09"))));
        assertThat(resolver().resolve(t, null)).isEqualByComparingTo("0.09");
    }

    @Test
    void global_rate_when_user_not_found() {
        UUID t = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.empty());
        assertThat(resolver().resolve(t)).isEqualByComparingTo("0.12");
    }

    // ── Phase 2 : code promo ─────────────────────────────────────────────────

    @Test
    void promo_subtracts_points_from_base_rate() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        // Base = min(global 0.12, override voyageur 0.10) = 0.10 ; promo 0.06 retranché → 0.04.
        lenient().when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.10"))));
        lenient().when(userRepository.findById(s)).thenReturn(Optional.of(withOverride(null)));
        when(promoService.validateAndGetDiscount(eq("PROMO6"), eq(s), any())).thenReturn(PromoDiscount.points(new BigDecimal("0.06")));

        assertThat(resolver().resolve(t, s, "PROMO6")).isEqualByComparingTo("0.04");
    }

    @Test
    void promo_equal_to_base_rate_zeroes_commission() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        // Régression WELCOME05 : promo à 0.12 = taux global 0.12 → commission nulle,
        // pas un taux inchangé (l'ancien comportement écrasait par la même valeur : 0 remise).
        lenient().when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(null)));
        lenient().when(userRepository.findById(s)).thenReturn(Optional.of(withOverride(null)));
        when(promoService.validateAndGetDiscount(eq("WELCOME12"), eq(s), any())).thenReturn(PromoDiscount.points(new BigDecimal("0.12")));

        assertThat(resolver().resolve(t, s, "WELCOME12")).isEqualByComparingTo("0.00");
    }

    @Test
    void promo_above_base_rate_floors_at_zero_never_negative() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        // Promo 0.20 > taux global 0.12 → plancher 0, jamais de commission négative.
        lenient().when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(null)));
        lenient().when(userRepository.findById(s)).thenReturn(Optional.of(withOverride(null)));
        when(promoService.validateAndGetDiscount(eq("BIGPROMO"), eq(s), any())).thenReturn(PromoDiscount.points(new BigDecimal("0.20")));

        assertThat(resolver().resolve(t, s, "BIGPROMO")).isEqualByComparingTo("0.00");
    }

    @Test
    void pricePercent_promo_converts_to_rate_points_on_paid_price() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        // Base 0.12 : prix = net × 1.12. 5 % du prix = 0.05 × 1.12 = 0.056 point de taux
        // → taux 0.064, prix = net × 1.064 = net × 1.12 × 0.95.
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        when(promoService.validateAndGetDiscount(eq("MERCI-ABOU-7K2F"), eq(s), any()))
                .thenReturn(PromoDiscount.pricePercent(new BigDecimal("0.050")));

        assertThat(resolver().resolve(t, s, "MERCI-ABOU-7K2F")).isEqualByComparingTo("0.064");
    }

    @Test
    void pricePercent_promo_is_capped_at_commission() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        // Base 0.05 (override) : 5 % du prix = 0.0525 > 0.05 → commission nulle, jamais négative,
        // le net du voyageur n'est pas entamé.
        lenient().when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.05"))));
        lenient().when(userRepository.findById(s)).thenReturn(Optional.of(withOverride(null)));
        when(promoService.validateAndGetDiscount(eq("MERCI-ABOU-7K2F"), eq(s), any()))
                .thenReturn(PromoDiscount.pricePercent(new BigDecimal("0.050")));

        assertThat(resolver().resolve(t, s, "MERCI-ABOU-7K2F")).isEqualByComparingTo("0");
    }

    @Test
    void invalid_promo_throws_and_does_not_fall_through() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        when(promoService.validateAndGetDiscount(any(), any(), any()))
                .thenThrow(new com.yadony.api.common.YadonyBusinessException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "promo-not-found", "Promo Not Found", "Code introuvable"));

        assertThatThrownBy(() -> resolver().resolve(t, s, "BADCODE"))
                .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class)
                .satisfies(e -> assertThat(((com.yadony.api.common.YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("promo-not-found"));
    }

    @Test
    void null_promo_falls_back_to_phase1_logic() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.08"))));
        when(userRepository.findById(s)).thenReturn(Optional.of(withOverride(null)));

        // null promoCode → resolve(t, s) → min(0.08, null, 0.12) = 0.08
        assertThat(resolver().resolve(t, s, null)).isEqualByComparingTo("0.08");
    }

    private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable>
            assertThatThrownBy(org.assertj.core.api.ThrowableAssert.ThrowingCallable code) {
        return org.assertj.core.api.Assertions.assertThatThrownBy(code);
    }

    // ── Phase 3 : bon de réduction (lot 3) — multiplicatif, après le promo ────

    @Test
    void voucher_multipliesRateAfterPromo() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        // Base = min(global 0.12, aucun override) = 0.12 ; bon × 0.5 → 0.06.
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        CommissionVoucherEntity voucher = activeVoucher(new BigDecimal("0.50"));
        when(voucherService.peekActive(s)).thenReturn(Optional.of(voucher));

        assertThat(resolver().resolve(t, s)).isEqualByComparingTo("0.06");
    }

    @Test
    void voucher_appliesAfterPromoSubtraction_multiplicativeOnRemainder() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        // Base 0.12 - promo 0.02 = 0.10 ; bon × 0.5 → 0.05 (pas 0.12×0.5 - 0.02).
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        when(promoService.validateAndGetDiscount(eq("SMALL2"), eq(s), any())).thenReturn(PromoDiscount.points(new BigDecimal("0.02")));
        CommissionVoucherEntity voucher = activeVoucher(new BigDecimal("0.50"));
        when(voucherService.peekActive(s)).thenReturn(Optional.of(voucher));

        assertThat(resolver().resolve(t, s, "SMALL2")).isEqualByComparingTo("0.05");
    }

    @Test
    void noVoucher_rateUnchanged() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        when(voucherService.peekActive(s)).thenReturn(Optional.empty());

        assertThat(resolver().resolve(t, s)).isEqualByComparingTo("0.12");
    }

    @Test
    void voucher_neverConsultedWhenSenderUnknown() {
        UUID t = UUID.randomUUID();
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));

        assertThat(resolver().resolve(t)).isEqualByComparingTo("0.12");
        org.mockito.Mockito.verifyNoInteractions(voucherService);
    }

    // ── reductions : réductions personnelles exposées séparément du taux ─────

    @Test
    void reductions_minOfOverrides() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.10"))));
        when(userRepository.findById(s)).thenReturn(Optional.of(withOverride(new BigDecimal("0.06"))));

        CommissionReductions r = resolver().reductions(t, s, null, null, null);

        assertThat(r.overrideRate()).isEqualByComparingTo("0.06");
        assertThat(r.promo()).isNull();
        assertThat(r.voucherFactor()).isNull();
    }

    @Test
    void reductions_promoValidated_returnsDiscount() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        PromoDiscount discount = PromoDiscount.pricePercent(new BigDecimal("0.050"));
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        when(promoService.validateAndGetDiscount("MERCI-ABOU-7K2F", s, PromoCodeTarget.SENDER)).thenReturn(discount);

        CommissionReductions r = resolver().reductions(t, s, "MERCI-ABOU-7K2F", s, null);

        assertThat(r.promo()).isEqualTo(discount);
        assertThat(r.overrideRate()).isNull();
    }

    @Test
    void reductions_invalidPromo_throws() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        when(promoService.validateAndGetDiscount(any(), any(), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "promo-not-found", "Promo Not Found", "Code introuvable"));

        assertThatThrownBy(() -> resolver().reductions(t, s, "BADCODE", s, null))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("promo-not-found"));
    }

    @Test
    void reductions_withReference_usesPeekForReference() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        UUID reference = UUID.randomUUID();
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        CommissionVoucherEntity voucher = activeVoucher(new BigDecimal("0.50"));
        when(voucherService.peekForReference(s, reference)).thenReturn(Optional.of(voucher));

        CommissionReductions r = resolver().reductions(t, s, null, null, reference);

        assertThat(r.voucherFactor()).isEqualByComparingTo("0.50");
        org.mockito.Mockito.verify(voucherService, org.mockito.Mockito.never()).peekActive(any());
    }

    @Test
    void reductions_withoutReference_usesPeekActive() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        CommissionVoucherEntity voucher = activeVoucher(new BigDecimal("0.50"));
        when(voucherService.peekActive(s)).thenReturn(Optional.of(voucher));

        assertThat(resolver().reductions(t, s, null, null, null).voucherFactor()).isEqualByComparingTo("0.50");
    }

    @Test
    void reductions_noneConfigured_allNull() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        when(voucherService.peekActive(s)).thenReturn(Optional.empty());

        assertThat(resolver().reductions(t, s, null, null, null)).isEqualTo(CommissionReductions.NONE);
    }

    @Test
    void reductions_unknownSender_neverConsultsVoucherNorPromo() {
        UUID t = UUID.randomUUID();
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));

        assertThat(resolver().reductions(t, null, "CODE", null, null)).isEqualTo(CommissionReductions.NONE);
        org.mockito.Mockito.verifyNoInteractions(voucherService, promoService);
    }

    // ── reductionsWithPromoFallback : règle de repli commune aux trois rails ──

    @Test
    void reductionsWithPromoFallback_blankCode_readsReductionsWithoutValidating() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        UUID reference = UUID.randomUUID();
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        when(voucherService.peekForReference(s, reference)).thenReturn(Optional.empty());

        assertThat(resolver().reductionsWithPromoFallback(t, s, "  ", reference)).isEqualTo(CommissionReductions.NONE);
        assertThat(resolver().reductionsWithPromoFallback(t, s, null, reference)).isEqualTo(CommissionReductions.NONE);
        verifyNoInteractions(promoService);
    }

    @Test
    void reductionsWithPromoFallback_validCode_isValidatedForTheSender() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        UUID reference = UUID.randomUUID();
        PromoDiscount discount = PromoDiscount.points(new BigDecimal("0.05"));
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        when(promoService.validateAndGetDiscount("WELCOME05", s, PromoCodeTarget.SENDER)).thenReturn(discount);
        CommissionVoucherEntity voucher = activeVoucher(new BigDecimal("0.50"));
        when(voucherService.peekForReference(s, reference)).thenReturn(Optional.of(voucher));

        CommissionReductions r = resolver().reductionsWithPromoFallback(t, s, "WELCOME05", reference);

        assertThat(r.promo()).isEqualTo(discount);
        assertThat(r.voucherFactor()).isEqualByComparingTo("0.50");
    }

    @Test
    void reductionsWithPromoFallback_invalidCode_fallsBackWithoutCode() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        UUID reference = UUID.randomUUID();
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.04"))));
        lenient().when(userRepository.findById(s)).thenReturn(Optional.of(withOverride(null)));
        when(promoService.validateAndGetDiscount("EXPIRED", s, PromoCodeTarget.SENDER))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "promo-expired", "Promo Expired", "Ce code est expiré."));
        CommissionVoucherEntity voucher = activeVoucher(new BigDecimal("0.50"));
        when(voucherService.peekForReference(s, reference)).thenReturn(Optional.of(voucher));

        CommissionReductions r = resolver().reductionsWithPromoFallback(t, s, "EXPIRED", reference);

        assertThat(r.promo()).as("code invalide : aucune remise").isNull();
        assertThat(r.overrideRate()).as("taux spécial conservé").isEqualByComparingTo("0.04");
        assertThat(r.voucherFactor()).as("bon conservé").isEqualByComparingTo("0.50");
    }

    // ── reductionsForBid : règle unique d'un bid à grille, commune à l'affichage et aux rails ──

    @Test
    void reductionsForBid_redeemedCode_readsRedeemedDiscountWithoutValidating() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        UUID promoCodeId = UUID.randomUUID();
        PromoDiscount discount = PromoDiscount.points(new BigDecimal("0.05"));
        when(userRepository.findById(t)).thenReturn(Optional.of(withOverride(new BigDecimal("0.04"))));
        lenient().when(userRepository.findById(s)).thenReturn(Optional.of(withOverride(null)));
        CommissionVoucherEntity voucher = activeVoucher(new BigDecimal("0.50"));
        when(voucherService.peekForReference(s, bidId)).thenReturn(Optional.of(voucher));
        when(promoService.discountOfRedeemed(promoCodeId)).thenReturn(Optional.of(discount));

        CommissionReductions r = resolver().reductionsForBid(t, s, "ONCE", promoCodeId, bidId);

        assertThat(r.promo()).isEqualTo(discount);
        assertThat(r.overrideRate()).isEqualByComparingTo("0.04");
        assertThat(r.voucherFactor()).isEqualByComparingTo("0.50");
        // Revalidé, un code à usage unique échouerait sur son propre rachat.
        verify(promoService, never()).validateAndGetDiscount(any(), any(), any());
    }

    @Test
    void reductionsForBid_redeemedCodeGone_noDiscount() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        UUID promoCodeId = UUID.randomUUID();
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        when(voucherService.peekForReference(s, bidId)).thenReturn(Optional.empty());
        when(promoService.discountOfRedeemed(promoCodeId)).thenReturn(Optional.empty());

        CommissionReductions r = resolver().reductionsForBid(t, s, "DELETED", promoCodeId, bidId);

        assertThat(r.promo()).isNull();
        verify(promoService, never()).validateAndGetDiscount(any(), any(), any());
    }

    @Test
    void reductionsForBid_noRedeemedCode_fallsBackLikeTheRails() {
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(withOverride(null)));
        when(voucherService.peekForReference(s, bidId)).thenReturn(Optional.empty());
        when(promoService.validateAndGetDiscount("EXPIRED", s, PromoCodeTarget.SENDER))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "promo-expired", "Promo Expired", "Ce code est expiré."));

        CommissionReductions r = resolver().reductionsForBid(t, s, "EXPIRED", null, bidId);

        assertThat(r).isEqualTo(CommissionReductions.NONE);
        verify(promoService, never()).discountOfRedeemed(any());
    }

    @Test
    void frozen_keepsOverrideAndVoucher_neverAPromo() {
        CommissionReductions r = CommissionReductions.frozen(new BigDecimal("0.04"), new BigDecimal("0.5"));

        assertThat(r).isEqualTo(new CommissionReductions(new BigDecimal("0.04"), null, new BigDecimal("0.5")));
    }
}
