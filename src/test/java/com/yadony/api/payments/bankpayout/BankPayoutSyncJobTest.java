package com.yadony.api.payments.bankpayout;

import com.stripe.exception.ApiConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankPayoutSyncJobTest {

    static final Instant NOW = Instant.parse("2026-10-18T20:00:00Z");
    static final String ACCOUNT = "acct_1";

    @Mock BankPayoutTrackingService service;
    @Mock BankPayoutTrackingRepository repository;
    @Mock BankPayoutStripeGateway gateway;
    @Mock NamedParameterJdbcTemplate jdbc;

    BankPayoutSyncJob job;

    @BeforeEach
    void setUp() {
        job = new BankPayoutSyncJob(service, repository, gateway, jdbc, true, 60, 100, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.findIncomplete(any(), any())).thenReturn(List.of());
        when(repository.findAccountsAwaitingBank(any())).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private void untracked(List<Object[]> rows) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn((List) rows);
    }

    @Test
    void opensTrackingForTransferredPaymentsWithoutRow() {
        UUID paymentId = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        untracked(List.<Object[]>of(new Object[]{paymentId, "tr_3URtaA3LeHyrKJsF0kdqgrIH"},
                new Object[]{other.toString(), "tr_2"}));
        BankPayoutTrackingEntity row = new BankPayoutTrackingEntity();
        when(service.openRow(paymentId, null, "tr_3URtaA3LeHyrKJsF0kdqgrIH", null)).thenReturn(row);
        when(service.openRow(other, null, "tr_2", null)).thenThrow(new IllegalStateException("x"));

        BankPayoutSyncJob.Report report = job.run();

        assertThat(report.opened()).isEqualTo(1);
        verify(service).enrichQuietly(row);
    }

    @Test
    void completesIncompleteRows() {
        untracked(List.of());
        BankPayoutTrackingEntity done = new BankPayoutTrackingEntity();
        BankPayoutTrackingEntity stillMissing = new BankPayoutTrackingEntity();
        when(repository.findIncomplete(any(), any())).thenReturn(List.of(done, stillMissing));
        org.mockito.Mockito.doAnswer(i -> {
            ((BankPayoutTrackingEntity) i.getArgument(0)).setAvailableOn(LocalDate.of(2026, 10, 22));
            return null;
        }).when(service).enrichQuietly(done);

        assertThat(job.run().completed()).isEqualTo(1);
    }

    @Test
    void resyncsPayouts_oldestFirst_skippingAlreadyAppliedOnes() throws Exception {
        untracked(List.of());
        when(repository.findAccountsAwaitingBank(any())).thenReturn(List.of(ACCOUNT));
        when(repository.findOldestAwaitingBank(eq(ACCOUNT), any()))
                .thenReturn(Optional.of(LocalDateTime.of(2026, 10, 18, 18, 20)));
        PayoutSnapshot newer = new PayoutSnapshot("po_new", "in_transit", LocalDate.of(2026, 10, 24), 500L, "eur",
                null, true, 300L);
        PayoutSnapshot older = new PayoutSnapshot("po_old", "failed", null, 500L, "eur", "account_closed", true, 100L);
        PayoutSnapshot done = new PayoutSnapshot("po_done", "paid", LocalDate.of(2026, 10, 10), 500L, "eur", null,
                true, 50L);
        when(gateway.recentPayouts(eq(ACCOUNT), anyLong())).thenReturn(List.of(newer, older, done));
        BankPayoutTrackingEntity paidRow = new BankPayoutTrackingEntity();
        paidRow.setPayoutStatus("paid");
        when(repository.findByConnectedAccountIdAndPayoutIdIn(ACCOUNT, List.of("po_done"))).thenReturn(List.of(paidRow));
        when(repository.findByConnectedAccountIdAndPayoutIdIn(ACCOUNT, List.of("po_old"))).thenReturn(List.of());
        when(service.applyPayout(eq(ACCOUNT), any(), eq(NOW), any())).thenReturn(1);

        assertThat(job.run().payoutsApplied()).isEqualTo(2);

        InOrder order = inOrder(service);
        order.verify(service).applyPayout(ACCOUNT, older, NOW, NOW);
        order.verify(service).applyPayout(ACCOUNT, newer, NOW, Instant.parse("2026-10-24T00:00:00Z"));
        verify(service, never()).applyPayout(eq(ACCOUNT), eq(done), any(), any());
        long expectedGte = LocalDateTime.of(2026, 10, 18, 18, 20).toEpochSecond(ZoneOffset.UTC)
                - BankPayoutSyncJob.PAYOUT_LOOKBACK_MARGIN_SECONDS;
        verify(gateway).recentPayouts(ACCOUNT, expectedGte);
    }

    @Test
    void stripeDownForOneAccount_doesNotStopTheRun() throws Exception {
        untracked(List.of());
        when(repository.findAccountsAwaitingBank(any())).thenReturn(List.of("acct_a", "acct_b"));
        when(repository.findOldestAwaitingBank(anyString(), any())).thenReturn(Optional.empty());
        when(gateway.recentPayouts(eq("acct_a"), anyLong())).thenThrow(new ApiConnectionException("down"));
        when(gateway.recentPayouts(eq("acct_b"), anyLong())).thenReturn(List.of());

        assertThat(job.run().payoutsApplied()).isZero();
        verify(gateway).recentPayouts(eq("acct_b"), anyLong());
    }

    @Test
    void scheduledRun_respectsFlag() {
        BankPayoutSyncJob disabled = new BankPayoutSyncJob(service, repository, gateway, jdbc, false, 60, 100,
                Clock.fixed(NOW, ZoneOffset.UTC));
        disabled.scheduledRun();
        verify(repository, never()).findAccountsAwaitingBank(any());

        untracked(List.of());
        job.scheduledRun();
        verify(repository).findAccountsAwaitingBank(any());
        verify(service, never()).openRow(any(), isNull(), anyString(), isNull());
        verify(repository, never()).findByConnectedAccountIdAndPayoutIdIn(anyString(), anyCollection());
    }
}
