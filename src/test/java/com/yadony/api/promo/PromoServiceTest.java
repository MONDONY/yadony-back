package com.yadony.api.promo;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromoServiceTest {

    @Mock PromoCodeRepository promoCodeRepository;
    @Mock PromoRedemptionRepository redemptionRepository;
    @Mock AuditService auditService;

    PromoService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PromoService(promoCodeRepository, redemptionRepository, auditService);
    }

    private PromoCodeEntity activePromo(BigDecimal rate) {
        PromoCodeEntity p = new PromoCodeEntity();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setCode("WELCOME10");
        p.setRate(rate);
        p.setTarget(PromoCodeTarget.ANY);
        p.setStatus(PromoCodeStatus.ACTIVE);
        p.setPerUserLimit(1);
        return p;
    }

    @Nested
    @DisplayName("validateAndGetRate()")
    class ValidateTests {

        @Test
        void valid_code_returns_rate() {
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(activePromo(new BigDecimal("0.06"))));
            when(redemptionRepository.countByPromoCodeIdAndUserId(any(), any())).thenReturn(0L);

            BigDecimal rate = service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER);

            assertThat(rate).isEqualByComparingTo("0.06");
        }

        @Test
        void unknown_code_throws_promo_not_found() {
            when(promoCodeRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.validateAndGetRate("UNKNOWN", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-not-found"));
        }

        @Test
        void disabled_code_throws_promo_expired() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setStatus(PromoCodeStatus.DISABLED);
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-expired"));
        }

        @Test
        void expired_validTo_throws_promo_expired() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setValidTo(LocalDateTime.now().minusDays(1));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-expired"));
        }

        @Test
        void future_validFrom_throws_promo_expired() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setValidFrom(LocalDateTime.now().plusDays(1));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-expired"));
        }

        @Test
        void max_redemptions_reached_throws_promo_limit_reached() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setMaxRedemptions(100);
            p.setRedeemedCount(100);
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-limit-reached"));
        }

        @Test
        void per_user_limit_exceeded_throws_promo_limit_reached() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(redemptionRepository.countByPromoCodeIdAndUserId(any(), eq(userId))).thenReturn(1L); // already used once, limit = 1

            assertThatThrownBy(() -> service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-limit-reached"));
        }

        @Test
        void traveler_target_rejects_sender() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setTarget(PromoCodeTarget.TRAVELER);
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-not-eligible");
                        assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    });
        }

        @Test
        void any_target_accepts_sender_and_traveler() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setTarget(PromoCodeTarget.ANY);
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(redemptionRepository.countByPromoCodeIdAndUserId(any(), any())).thenReturn(0L);

            assertThat(service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER)).isEqualByComparingTo("0.06");
            assertThat(service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.TRAVELER)).isEqualByComparingTo("0.06");
        }
    }

    @Nested
    @DisplayName("redeem()")
    class RedeemTests {

        @Test
        void first_redemption_increments_count_and_saves() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            UUID promoId = (UUID) ReflectionTestUtils.getField(p, "id");
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(redemptionRepository.existsByPromoCodeIdAndBidId(promoId, bidId)).thenReturn(false);
            when(promoCodeRepository.findByIdForUpdate(promoId)).thenReturn(Optional.of(p));
            when(promoCodeRepository.save(p)).thenReturn(p);
            when(redemptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.redeem("WELCOME10", userId, bidId, new BigDecimal("0.06"));

            assertThat(p.getRedeemedCount()).isEqualTo(1);
            verify(redemptionRepository).save(any(PromoRedemptionEntity.class));
            verify(auditService).log(eq("PROMO"), any(), eq("PROMO_CODE_REDEEMED"), eq(userId), any());
        }

        @Test
        void idempotent_skip_if_already_redeemed_for_bid() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            UUID promoId = (UUID) ReflectionTestUtils.getField(p, "id");
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(redemptionRepository.existsByPromoCodeIdAndBidId(promoId, bidId)).thenReturn(true);

            PromoRedemptionEntity existing = new PromoRedemptionEntity();
            when(redemptionRepository.findByPromoCodeIdAndBidId(promoId, bidId)).thenReturn(Optional.of(existing));

            PromoRedemptionEntity result = service.redeem("WELCOME10", userId, bidId, new BigDecimal("0.06"));

            assertThat(result).isSameAs(existing);
            verify(promoCodeRepository, never()).findByIdForUpdate(any());
            verify(redemptionRepository, never()).save(any());
        }

        private PromoCodeEntity lockedPromo(int perUserLimit, Integer maxRedemptions) {
            PromoCodeEntity p = activePromo(new BigDecimal("0.05"));
            p.setPerUserLimit(perUserLimit);
            p.setMaxRedemptions(maxRedemptions);
            UUID promoId = p.getId();
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(redemptionRepository.existsByPromoCodeIdAndBidId(promoId, bidId)).thenReturn(false);
            when(promoCodeRepository.findByIdForUpdate(promoId)).thenReturn(Optional.of(p));
            return p;
        }

        @Test
        void per_user_limit_rechecked_under_lock_throws_and_writes_nothing() {
            PromoCodeEntity p = lockedPromo(1, null);
            when(redemptionRepository.countByPromoCodeIdAndUserId(p.getId(), userId)).thenReturn(1L);

            assertThatThrownBy(() -> service.redeem("WELCOME10", userId, bidId, new BigDecimal("0.05")))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException y = (YadonyBusinessException) e;
                        assertThat(y.getErrorCode()).isEqualTo("promo-limit-reached");
                        assertThat(y.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    });

            verify(promoCodeRepository).findByIdForUpdate(p.getId());
            verify(promoCodeRepository, never()).save(any());
            verify(redemptionRepository, never()).save(any());
            verifyNoInteractions(auditService);
        }

        @Test
        void max_redemptions_rechecked_under_lock_with_fresh_count_not_stale_entity() {
            // L'entité gérée (chargée avant le verrou) dit 3 ; la base, relue sous verrou, dit 10.
            PromoCodeEntity p = lockedPromo(5, 10);
            p.setRedeemedCount(3);
            when(promoCodeRepository.findRedeemedCountById(p.getId())).thenReturn(10);

            assertThatThrownBy(() -> service.redeem("WELCOME10", userId, bidId, new BigDecimal("0.05")))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("promo-limit-reached"));

            verify(redemptionRepository, never()).save(any());
        }

        @Test
        void counter_incremented_from_fresh_count_read_under_lock() {
            PromoCodeEntity p = lockedPromo(5, 100);
            p.setRedeemedCount(3);
            when(promoCodeRepository.findRedeemedCountById(p.getId())).thenReturn(7);
            when(redemptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.redeem("WELCOME10", userId, bidId, new BigDecimal("0.05"));

            assertThat(p.getRedeemedCount()).isEqualTo(8);
        }

        @Test
        void concurrent_redemption_of_same_bid_seen_after_lock_is_idempotent() {
            PromoCodeEntity p = lockedPromo(1, null);
            PromoRedemptionEntity concurrent = new PromoRedemptionEntity();
            when(redemptionRepository.findByPromoCodeIdAndBidId(p.getId(), bidId)).thenReturn(Optional.of(concurrent));

            PromoRedemptionEntity result = service.redeem("WELCOME10", userId, bidId, new BigDecimal("0.05"));

            assertThat(result).isSameAs(concurrent);
            verify(redemptionRepository, never()).save(any());
            verify(promoCodeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("recordGrantedRedemption()")
    class RecordGrantedTests {

        @Test
        void over_limit_is_recorded_and_audited_instead_of_thrown() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.05"));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(redemptionRepository.existsByPromoCodeIdAndBidId(p.getId(), bidId)).thenReturn(false);
            when(promoCodeRepository.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));
            when(redemptionRepository.countByPromoCodeIdAndUserId(p.getId(), userId)).thenReturn(1L);
            when(promoCodeRepository.findRedeemedCountById(p.getId())).thenReturn(1);
            when(redemptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PromoRedemptionEntity saved = service.recordGrantedRedemption("WELCOME10", userId, bidId, new BigDecimal("0.07"));

            assertThat(saved).isNotNull();
            assertThat(saved.getBidId()).isEqualTo(bidId);
            assertThat(p.getRedeemedCount()).isEqualTo(2);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> details = ArgumentCaptor.forClass(java.util.Map.class);
            verify(auditService).log(eq("PROMO"), eq(p.getId()), eq("PROMO_CODE_REDEEMED"), eq(userId), details.capture());
            assertThat(details.getValue()).containsEntry("overLimit", "per_user_limit");
        }

        @Test
        void unknown_code_returns_null_without_throwing() {
            when(promoCodeRepository.findByCode("GONE")).thenReturn(Optional.empty());

            assertThat(service.recordGrantedRedemption("gone", userId, bidId, new BigDecimal("0.07"))).isNull();
            verify(redemptionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("lockForRedemption()")
    class LockForRedemptionTests {

        @Test
        void within_limits_locks_and_returns_id_without_writing() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.05"));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(promoCodeRepository.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));

            assertThat(service.lockForRedemption("welcome10", userId, bidId)).isEqualTo(p.getId());

            verify(promoCodeRepository).findByIdForUpdate(p.getId());
            verify(promoCodeRepository, never()).save(any());
            verify(redemptionRepository, never()).save(any());
        }

        @Test
        void per_user_limit_reached_throws() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.05"));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(promoCodeRepository.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));
            when(redemptionRepository.countByPromoCodeIdAndUserId(p.getId(), userId)).thenReturn(1L);

            assertThatThrownBy(() -> service.lockForRedemption("WELCOME10", userId, bidId))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("promo-limit-reached"));
        }

        @Test
        void global_limit_reached_throws() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.05"));
            p.setMaxRedemptions(2);
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(promoCodeRepository.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));
            when(promoCodeRepository.findRedeemedCountById(p.getId())).thenReturn(2);

            assertThatThrownBy(() -> service.lockForRedemption("WELCOME10", userId, bidId))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(e.getMessage())
                            .contains("nombre maximum d'utilisations"));
        }

        @Test
        void already_redeemed_for_this_bid_skips_limit_check() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.05"));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(promoCodeRepository.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));
            when(redemptionRepository.existsByPromoCodeIdAndBidId(p.getId(), bidId)).thenReturn(true);

            assertThat(service.lockForRedemption("WELCOME10", userId, bidId)).isEqualTo(p.getId());
            verify(redemptionRepository, never()).countByPromoCodeIdAndUserId(any(), any());
        }

        @Test
        void unknown_code_throws_promo_not_found() {
            when(promoCodeRepository.findByCode("NOPE")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.lockForRedemption("nope", userId, bidId))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("promo-not-found"));
        }
    }
}
