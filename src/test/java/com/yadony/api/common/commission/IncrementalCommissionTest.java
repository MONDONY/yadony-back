package com.yadony.api.common.commission;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Commission différentielle partagée par le supplément et la modification d'un colis accepté :
 * l'expéditeur ne paie que {@code C(base + extra) − C(base)}, jamais le minimum de la devise deux fois.
 */
@ExtendWith(MockitoExtension.class)
class IncrementalCommissionTest {

    private static final CommissionGrid GRID = new CommissionGrid(UUID.randomUUID(),
            LocalDateTime.of(2026, 10, 1, 0, 0),
            List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07")),
                    new CommissionTier(new BigDecimal("50"), new BigDecimal("0.06")),
                    new CommissionTier(new BigDecimal("300"), new BigDecimal("0.05"))),
            CommissionGridService.defaultMinimums());

    private static final UUID TRAVELER = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();

    @Mock CommissionGridService gridService;
    @Mock CommissionRateResolver rateResolver;

    private IncrementalCommission incremental;

    @BeforeEach
    void setUp() {
        incremental = new IncrementalCommission(gridService, rateResolver);
    }

    @Test
    void compute_gridBid_isDifferenceOfCommissions() {
        when(gridService.require(GRID.id())).thenReturn(GRID);
        when(rateResolver.reductions(eq(TRAVELER), eq(SENDER), isNull(), isNull(), any()))
                .thenReturn(CommissionReductions.NONE);
        BidEntity bid = gridBid("EUR");

        IncrementalCommission.Result r = incremental.compute(bid, TRAVELER, new BigDecimal("54.00"), new BigDecimal("23.50"));

        BigDecimal expected = CommissionCalculator.compute(new BigDecimal("77.50"), SupportedCurrency.EUR, GRID,
                        CommissionReductions.NONE).commission()
                .subtract(CommissionCalculator.compute(new BigDecimal("54.00"), SupportedCurrency.EUR, GRID,
                        CommissionReductions.NONE).commission());
        assertThat(expected.signum()).isPositive();
        assertThat(r.commission()).isEqualByComparingTo(expected);
        assertThat(r.gridId()).isEqualTo(GRID.id());
        assertThat(r.rateSnapshot()).isEqualByComparingTo(
                CommissionResult.storableRate(expected.divide(new BigDecimal("23.50"), 6, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void compute_minimumNotChargedTwice() {
        when(gridService.require(GRID.id())).thenReturn(GRID);
        when(rateResolver.reductions(any(), any(), isNull(), isNull(), any())).thenReturn(CommissionReductions.NONE);
        BidEntity bid = gridBid("EUR");

        // Base 10 € : 7 % = 0,70 € relevé au minimum de 1,00 €. Extra 1 € : C(11) = 0,77 → toujours 1,00 €.
        IncrementalCommission.Result r = incremental.compute(bid, TRAVELER, new BigDecimal("10"), new BigDecimal("1"));

        BigDecimal minimum = CommissionCalculator.compute(new BigDecimal("10"), SupportedCurrency.EUR, GRID,
                CommissionReductions.NONE).commission();
        assertThat(minimum).isEqualByComparingTo("1.00");
        assertThat(r.commission()).isLessThan(minimum);
        assertThat(r.commission()).isEqualByComparingTo("0.00");
    }

    @Test
    void compute_legacyBid_usesStoredRate() {
        BidEntity bid = bid("EUR");
        bid.setCommissionRate(new BigDecimal("0.12"));

        IncrementalCommission.Result r = incremental.compute(bid, TRAVELER, new BigDecimal("40"), new BigDecimal("10"));

        assertThat(r.commission()).isEqualByComparingTo("1.20");
        assertThat(r.gridId()).isNull();
        assertThat(r.rateSnapshot()).isEqualByComparingTo("0.120000");
        verify(gridService, never()).require(any());
    }

    @Test
    void compute_negotiatedBid_usesFrozenReductionsWithoutResolver() {
        when(gridService.require(GRID.id())).thenReturn(GRID);
        BidEntity bid = gridBid("EUR");
        bid.setNegotiatedNetEur(new BigDecimal("50.00"));
        bid.setCommissionOverrideRate(new BigDecimal("0.03"));

        // C(50) = min(3,50 ; 1,50) = 1,50 ; C(60) = 1,80 → 0,30 €.
        IncrementalCommission.Result r = incremental.compute(bid, TRAVELER, new BigDecimal("50"), new BigDecimal("10"));

        assertThat(r.commission()).isEqualByComparingTo("0.30");
        verify(rateResolver, never()).reductions(any(), any(), any(), any(), any());
    }

    @Test
    void compute_negativeOrZeroExtra_throws() {
        BidEntity bid = gridBid("EUR");

        assertThatThrownBy(() -> incremental.compute(bid, TRAVELER, new BigDecimal("54"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> incremental.compute(bid, TRAVELER, new BigDecimal("54"), new BigDecimal("-3")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> incremental.compute(bid, TRAVELER, new BigDecimal("54"), new BigDecimal("0.004")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BidEntity gridBid(String currency) {
        BidEntity bid = bid(currency);
        bid.setCommissionGridId(GRID.id());
        return bid;
    }

    private static BidEntity bid(String currency) {
        BidEntity bid = new BidEntity();
        setId(bid, UUID.randomUUID());
        bid.setSenderId(SENDER);
        bid.setCurrency(currency);
        return bid;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
