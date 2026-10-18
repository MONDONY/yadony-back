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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @DisplayName("validateAndGetDiscount()")
    class ValidateTests {

        @Test
        void valid_code_returns_rate() {
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(activePromo(new BigDecimal("0.06"))));
            when(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(any(), any())).thenReturn(0L);

            PromoDiscount discount = service.validateAndGetDiscount("WELCOME10", userId, PromoCodeTarget.SENDER);

            assertThat(discount.kind()).isEqualTo(PromoDiscountKind.COMMISSION_POINTS);
            assertThat(discount.value()).isEqualByComparingTo("0.06");
        }

        @Test
        void unknown_code_throws_promo_not_found() {
            when(promoCodeRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.validateAndGetDiscount("UNKNOWN", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-not-found"));
        }

        @Test
        void disabled_code_throws_promo_expired() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setStatus(PromoCodeStatus.DISABLED);
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetDiscount("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-expired"));
        }

        @Test
        void expired_validTo_throws_promo_expired() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setValidTo(LocalDateTime.now().minusDays(1));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetDiscount("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-expired"));
        }

        @Test
        void future_validFrom_throws_promo_expired() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setValidFrom(LocalDateTime.now().plusDays(1));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetDiscount("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-expired"));
        }

        @Test
        void max_redemptions_reached_throws_promo_limit_reached() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setMaxRedemptions(100);
            p.setRedeemedCount(100);
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetDiscount("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-limit-reached"));
        }

        @Test
        void per_user_limit_exceeded_throws_promo_limit_reached() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(any(), eq(userId))).thenReturn(1L); // already used once, limit = 1

            assertThatThrownBy(() -> service.validateAndGetDiscount("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-limit-reached"));
        }

        @Test
        void traveler_target_rejects_sender() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setTarget(PromoCodeTarget.TRAVELER);
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetDiscount("WELCOME10", userId, PromoCodeTarget.SENDER))
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
            when(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(any(), any())).thenReturn(0L);

            assertThat(service.validateAndGetDiscount("WELCOME10", userId, PromoCodeTarget.SENDER).value()).isEqualByComparingTo("0.06");
            assertThat(service.validateAndGetDiscount("WELCOME10", userId, PromoCodeTarget.TRAVELER).value()).isEqualByComparingTo("0.06");
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
            when(redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(promoId, bidId)).thenReturn(false);
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
            when(redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(promoId, bidId)).thenReturn(true);

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
            when(redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(promoId, bidId)).thenReturn(false);
            when(promoCodeRepository.findByIdForUpdate(promoId)).thenReturn(Optional.of(p));
            return p;
        }

        @Test
        void per_user_limit_rechecked_under_lock_throws_and_writes_nothing() {
            PromoCodeEntity p = lockedPromo(1, null);
            when(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(p.getId(), userId)).thenReturn(1L);

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
            // Absent avant le verrou, présent (validé par une transaction concurrente) après.
            when(redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(p.getId(), bidId))
                    .thenReturn(false, true);
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
            when(redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(p.getId(), bidId)).thenReturn(false);
            when(promoCodeRepository.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));
            when(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(p.getId(), userId)).thenReturn(1L);
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
    @DisplayName("codes personnels (parrainage)")
    class PersonalCodeTests {

        private PromoCodeEntity personalPromo(UUID owner) {
            PromoCodeEntity p = activePromo(new BigDecimal("0.050"));
            p.setCode("MERCI-ABOU-7K2F");
            p.setOwnerUserId(owner);
            p.setDiscountKind(PromoDiscountKind.PRICE_PERCENT);
            p.setTarget(PromoCodeTarget.SENDER);
            p.setMaxRedemptions(1);
            return p;
        }

        @Test
        void owner_gets_a_price_percent_discount() {
            when(promoCodeRepository.findByCode("MERCI-ABOU-7K2F")).thenReturn(Optional.of(personalPromo(userId)));
            when(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(any(), any())).thenReturn(0L);

            PromoDiscount discount = service.validateAndGetDiscount("merci-abou-7k2f", userId, PromoCodeTarget.SENDER);

            assertThat(discount.kind()).isEqualTo(PromoDiscountKind.PRICE_PERCENT);
            assertThat(discount.value()).isEqualByComparingTo("0.05");
        }

        @Test
        void another_user_is_refused_with_promo_not_owner() {
            when(promoCodeRepository.findByCode("MERCI-ABOU-7K2F"))
                    .thenReturn(Optional.of(personalPromo(UUID.randomUUID())));

            assertThatThrownBy(() -> service.validateAndGetDiscount("MERCI-ABOU-7K2F", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getErrorCode()).isEqualTo("promo-not-owner");
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    });
        }

        @Test
        void single_use_once_redeemed() {
            PromoCodeEntity p = personalPromo(userId);
            p.setRedeemedCount(1);
            when(promoCodeRepository.findByCode("MERCI-ABOU-7K2F")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetDiscount("MERCI-ABOU-7K2F", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("promo-limit-reached"));
        }

        @Test
        void lock_and_redeem_refuse_another_user() {
            when(promoCodeRepository.findByCode("MERCI-ABOU-7K2F"))
                    .thenReturn(Optional.of(personalPromo(UUID.randomUUID())));

            assertThatThrownBy(() -> service.lockForRedemption("MERCI-ABOU-7K2F", userId, bidId))
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("promo-not-owner"));
            assertThatThrownBy(() -> service.redeem("MERCI-ABOU-7K2F", userId, bidId, new BigDecimal("0.064")))
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("promo-not-owner"));
            verify(promoCodeRepository, never()).findByIdForUpdate(any());
        }

        @Test
        void createPersonalCode_builds_a_single_use_owned_sender_code() {
            when(promoCodeRepository.existsByCodeIncludingDeleted(any())).thenReturn(true, false);
            when(promoCodeRepository.save(any())).thenAnswer(inv -> {
                PromoCodeEntity e = inv.getArgument(0);
                ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
                return e;
            });
            LocalDateTime validTo = LocalDateTime.now(ZoneOffset.UTC).plusMonths(6);

            PromoCodeEntity created = service.createPersonalCode(userId, "MERCI-ABOU", new BigDecimal("0.05"), validTo);

            assertThat(created.getCode()).matches("MERCI-ABOU-[A-HJ-NP-Z2-9]{4}");
            assertThat(created.getOwnerUserId()).isEqualTo(userId);
            assertThat(created.getDiscountKind()).isEqualTo(PromoDiscountKind.PRICE_PERCENT);
            assertThat(created.getRate()).isEqualByComparingTo("0.05");
            assertThat(created.getTarget()).isEqualTo(PromoCodeTarget.SENDER);
            assertThat(created.getMaxRedemptions()).isEqualTo(1);
            assertThat(created.getPerUserLimit()).isEqualTo(1);
            assertThat(created.getValidTo()).isEqualTo(validTo);
            assertThat(created.getStatus()).isEqualTo(PromoCodeStatus.ACTIVE);
            verify(promoCodeRepository, times(2)).existsByCodeIncludingDeleted(any());
            verify(auditService).log(eq("PROMO"), eq(created.getId()), eq("PROMO_PERSONAL_CODE_CREATED"),
                    eq(userId), any());
        }

        @Test
        void createPersonalCode_gives_up_after_repeated_collisions() {
            when(promoCodeRepository.existsByCodeIncludingDeleted(any())).thenReturn(true);

            assertThatThrownBy(() -> service.createPersonalCode(userId, "MERCI-ABOU", new BigDecimal("0.05"),
                    LocalDateTime.now(ZoneOffset.UTC).plusMonths(6)))
                    .isInstanceOf(IllegalStateException.class);
            verify(promoCodeRepository, never()).save(any());
        }

        @Test
        void findAllByIds_skips_the_query_when_empty() {
            assertThat(service.findAllByIds(List.of())).isEmpty();
            verifyNoInteractions(promoCodeRepository);
        }
    }

    @Nested
    @DisplayName("PromoDiscount.applyTo()")
    class DiscountTests {

        @Test
        void commission_points_subtract_with_floor() {
            assertThat(PromoDiscount.points(new BigDecimal("0.02")).applyTo(new BigDecimal("0.05")))
                    .isEqualByComparingTo("0.03");
            assertThat(PromoDiscount.points(new BigDecimal("0.08")).applyTo(new BigDecimal("0.05")))
                    .isEqualByComparingTo("0");
        }

        @Test
        void price_percent_keeps_paid_price_at_95_percent_when_commission_allows() {
            BigDecimal base = new BigDecimal("0.120");
            BigDecimal rate = PromoDiscount.pricePercent(new BigDecimal("0.050")).applyTo(base);
            BigDecimal net = new BigDecimal("100.00");
            // prix sans code 112,00 ; avec code 106,40 = 112 × 0,95 ; net voyageur inchangé.
            assertThat(net.multiply(BigDecimal.ONE.add(rate))).isEqualByComparingTo("106.40");
            assertThat(rate.scale()).isLessThanOrEqualTo(3);
        }

        @Test
        void price_percent_never_makes_commission_negative() {
            assertThat(PromoDiscount.pricePercent(new BigDecimal("0.050")).applyTo(new BigDecimal("0.050")))
                    .isEqualByComparingTo("0");
            assertThat(PromoDiscount.pricePercent(new BigDecimal("0.050")).applyTo(BigDecimal.ZERO))
                    .isEqualByComparingTo("0");
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
            when(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(p.getId(), userId)).thenReturn(1L);

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
            when(redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(p.getId(), bidId)).thenReturn(true);

            assertThat(service.lockForRedemption("WELCOME10", userId, bidId)).isEqualTo(p.getId());
            verify(redemptionRepository, never()).countByPromoCodeIdAndUserIdAndReleasedAtIsNull(any(), any());
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

    @Nested
    @DisplayName("releaseForBid()")
    class ReleaseTests {

        private PromoRedemptionEntity activeRedemption(PromoCodeEntity p) {
            PromoRedemptionEntity r = new PromoRedemptionEntity();
            ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            r.setPromoCodeId(p.getId());
            r.setUserId(userId);
            r.setBidId(bidId);
            r.setAppliedRate(new BigDecimal("0.06"));
            r.setRedeemedAt(LocalDateTime.now());
            return r;
        }

        @Test
        void releases_active_redemption_decrements_counter_under_lock_and_audits() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            PromoRedemptionEntity r = activeRedemption(p);
            when(redemptionRepository.findByBidIdAndReleasedAtIsNull(bidId)).thenReturn(List.of(r));
            when(promoCodeRepository.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));
            when(redemptionRepository.markReleasedIfActive(eq(r.getId()), any(OffsetDateTime.class), eq("BID_CANCELLED")))
                    .thenReturn(1);
            when(promoCodeRepository.findRedeemedCountById(p.getId())).thenReturn(4);

            int released = service.releaseForBid(bidId, "BID_CANCELLED");

            assertThat(released).isEqualTo(1);
            assertThat(p.getRedeemedCount()).isEqualTo(3);
            var order = inOrder(promoCodeRepository, redemptionRepository);
            order.verify(promoCodeRepository).findByIdForUpdate(p.getId());
            order.verify(redemptionRepository).markReleasedIfActive(eq(r.getId()), any(), eq("BID_CANCELLED"));
            order.verify(promoCodeRepository).save(p);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> details = ArgumentCaptor.forClass(java.util.Map.class);
            verify(auditService).log(eq("PROMO"), eq(p.getId()), eq("PROMO_CODE_RELEASED"), eq(userId), details.capture());
            assertThat(details.getValue())
                    .containsEntry("bidId", bidId.toString())
                    .containsEntry("reason", "BID_CANCELLED")
                    .containsEntry("redemptionId", r.getId().toString())
                    .doesNotContainKey("counterFloor");
        }

        @Test
        void replayed_or_concurrent_release_does_not_decrement_twice() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            PromoRedemptionEntity r = activeRedemption(p);
            when(redemptionRepository.findByBidIdAndReleasedAtIsNull(bidId)).thenReturn(List.of(r));
            when(promoCodeRepository.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));
            // Une autre transaction a libéré la ligne entre la lecture et le verrou.
            when(redemptionRepository.markReleasedIfActive(any(), any(), any())).thenReturn(0);

            assertThat(service.releaseForBid(bidId, "BID_CANCELLED")).isZero();

            verify(promoCodeRepository, never()).findRedeemedCountById(any());
            verify(promoCodeRepository, never()).save(any());
            verifyNoInteractions(auditService);
        }

        @Test
        void no_active_redemption_is_a_no_op() {
            when(redemptionRepository.findByBidIdAndReleasedAtIsNull(bidId)).thenReturn(List.of());

            assertThat(service.releaseForBid(bidId, "TRIP_CANCELLED")).isZero();

            verifyNoInteractions(promoCodeRepository, auditService);
        }

        @Test
        void counter_never_goes_below_zero() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            PromoRedemptionEntity r = activeRedemption(p);
            when(redemptionRepository.findByBidIdAndReleasedAtIsNull(bidId)).thenReturn(List.of(r));
            when(promoCodeRepository.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));
            when(redemptionRepository.markReleasedIfActive(any(), any(), any())).thenReturn(1);
            when(promoCodeRepository.findRedeemedCountById(p.getId())).thenReturn(0);

            service.releaseForBid(bidId, "BID_CANCELLED");

            assertThat(p.getRedeemedCount()).isZero();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> details = ArgumentCaptor.forClass(java.util.Map.class);
            verify(auditService).log(any(), any(), eq("PROMO_CODE_RELEASED"), any(), details.capture());
            assertThat(details.getValue()).containsEntry("counterFloor", true);
        }

        @Test
        void deleted_promo_code_is_skipped_without_writing() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            PromoRedemptionEntity r = activeRedemption(p);
            when(redemptionRepository.findByBidIdAndReleasedAtIsNull(bidId)).thenReturn(List.of(r));
            when(promoCodeRepository.findByIdForUpdate(p.getId())).thenReturn(Optional.empty());

            assertThat(service.releaseForBid(bidId, "BID_CANCELLED")).isZero();

            verify(redemptionRepository, never()).markReleasedIfActive(any(), any(), any());
            verifyNoInteractions(auditService);
        }

        @Test
        void per_user_limit_is_available_again_once_released() {
            // Limite = 1 : les comptages ne retiennent que les rachats actifs (released_at IS NULL) ;
            // une fois le rachat du bid annulé libéré, le comptage repasse à 0.
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(p.getId(), userId))
                    .thenReturn(1L, 0L);

            assertThatThrownBy(() -> service.validateAndGetDiscount("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(YadonyBusinessException.class);
            assertThat(service.validateAndGetDiscount("WELCOME10", userId, PromoCodeTarget.SENDER).value())
                    .isEqualByComparingTo("0.06");
        }

        @Test
        void redeeming_again_on_same_bid_reactivates_released_row() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            PromoRedemptionEntity released = activeRedemption(p);
            released.setReleasedAt(OffsetDateTime.now(ZoneOffset.UTC));
            released.setReleaseReason("BID_CANCELLED");
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(p.getId(), bidId)).thenReturn(false);
            when(promoCodeRepository.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));
            when(redemptionRepository.findByPromoCodeIdAndBidId(p.getId(), bidId)).thenReturn(Optional.of(released));
            when(promoCodeRepository.findRedeemedCountById(p.getId())).thenReturn(0);
            when(redemptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PromoRedemptionEntity result = service.redeem("WELCOME10", userId, bidId, new BigDecimal("0.06"));

            assertThat(result).isSameAs(released);
            assertThat(result.isReleased()).isFalse();
            assertThat(result.getReleaseReason()).isNull();
            assertThat(p.getRedeemedCount()).isEqualTo(1);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> details = ArgumentCaptor.forClass(java.util.Map.class);
            verify(auditService).log(eq("PROMO"), eq(p.getId()), eq("PROMO_CODE_REDEEMED"), eq(userId), details.capture());
            assertThat(details.getValue()).containsEntry("reactivated", true);
        }

        @Test
        void lock_for_redemption_checks_limits_when_only_a_released_row_exists() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(promoCodeRepository.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));
            when(redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(p.getId(), bidId)).thenReturn(false);
            when(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(p.getId(), userId)).thenReturn(1L);

            assertThatThrownBy(() -> service.lockForRedemption("WELCOME10", userId, bidId))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("promo-limit-reached"));
        }
    }

    @Nested
    @DisplayName("discountOfRedeemed() — remise d'un code déjà racheté, sans contrôle")
    class DiscountOfRedeemedTests {

        @Test
        @DisplayName("code désactivé, expiré et à sa limite : la remise est rendue quand même, rien n'est écrit")
        void discountOfRedeemed_ignoresLimitsAndDates() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.02"));
            p.setDiscountKind(PromoDiscountKind.COMMISSION_POINTS);
            p.setStatus(PromoCodeStatus.DISABLED);
            p.setValidTo(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
            p.setMaxRedemptions(1);
            p.setRedeemedCount(1);
            when(promoCodeRepository.findById(p.getId())).thenReturn(Optional.of(p));

            assertThat(service.discountOfRedeemed(p.getId()))
                    .contains(PromoDiscount.points(new BigDecimal("0.02")));
            verifyNoInteractions(redemptionRepository, auditService);
            verify(promoCodeRepository, never()).save(any());
        }

        @Test
        @DisplayName("code introuvable : vide")
        void discountOfRedeemed_unknown_empty() {
            UUID id = UUID.randomUUID();
            when(promoCodeRepository.findById(id)).thenReturn(Optional.empty());

            assertThat(service.discountOfRedeemed(id)).isEmpty();
        }
    }
}
