package com.yadony.api.payments.bankpayout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.events.BankPayoutFailedEvent;
import com.yadony.api.payments.events.BankPayoutPaidEvent;
import com.yadony.api.payments.events.StripeTransferRecordedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankPayoutTrackingServiceTest {

    static final String ACCOUNT = "acct_1URsjrKQWSCn1fJ6";
    static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    @Mock BankPayoutTrackingRepository repository;
    @Mock BankPayoutStripeGateway gateway;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher events;
    @Mock AdminAlertService adminAlert;
    @Mock UserRepository userRepository;

    BankPayoutTrackingService service;
    final List<BankPayoutTrackingEntity> rows = new ArrayList<>();
    final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BankPayoutTrackingService(repository, gateway, auditService, events, adminAlert, userRepository,
                new ObjectMapper(), TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.findByConnectedAccountIdAndDestinationPaymentIdIn(anyString(), anyCollection()))
                .thenAnswer(i -> rows.stream()
                        .filter(r -> ((java.util.Collection<?>) i.getArgument(1)).contains(r.getDestinationPaymentId()))
                        .toList());
        when(repository.findByConnectedAccountIdAndPayoutIdIn(anyString(), anyCollection()))
                .thenAnswer(i -> rows.stream()
                        .filter(r -> r.getPayoutId() != null
                                && ((java.util.Collection<?>) i.getArgument(1)).contains(r.getPayoutId()))
                        .toList());
    }

    private BankPayoutTrackingEntity row(String py) {
        BankPayoutTrackingEntity row = new BankPayoutTrackingEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
        row.setPaymentId(UUID.randomUUID());
        row.setTravelerId(travelerId);
        row.setTransferId("tr_" + py);
        row.setConnectedAccountId(ACCOUNT);
        row.setDestinationPaymentId(py);
        row.setAvailableOn(LocalDate.of(2026, 10, 22));
        rows.add(row);
        return row;
    }

    private static Event payoutEvent(String type, String id, String status, long created, String failureCode,
                                     String account) {
        String accountJson = account == null ? "" : ",\"account\":\"" + account + "\"";
        String failure = failureCode == null ? "null" : "\"" + failureCode + "\"";
        return ApiResource.GSON.fromJson("{\"id\":\"evt_" + type + created + "\",\"object\":\"event\",\"type\":\""
                + type + "\",\"created\":" + created + accountJson + ",\"data\":{\"object\":{\"id\":\"" + id
                + "\",\"object\":\"payout\",\"status\":\"" + status + "\",\"amount\":500,\"currency\":\"eur\","
                + "\"arrival_date\":1792800000,\"automatic\":true,\"created\":1792700000,\"failure_code\":"
                + failure + "}}}", Event.class);
    }

    private void payoutContains(String payoutId, BankPayoutStripeGateway.BalanceTxnRef... refs) throws Exception {
        when(gateway.payoutBalanceTransactions(ACCOUNT, payoutId)).thenReturn(List.of(refs));
    }

    private static BankPayoutStripeGateway.BalanceTxnRef payment(String py) {
        return new BankPayoutStripeGateway.BalanceTxnRef("payment", py);
    }

    // ── Transfer ────────────────────────────────────────────────────────────

    @Test
    void recordTransfer_opensRow_thenReadsDestinationPaymentAndAvailability() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(repository.findByPaymentId(paymentId)).thenReturn(Optional.empty());
        when(gateway.retrieveTransfer("tr_1")).thenReturn(
                new BankPayoutStripeGateway.TransferInfo("tr_1", ACCOUNT, "py_1"));
        when(gateway.availableOn(ACCOUNT, "py_1")).thenReturn(Optional.of(LocalDate.of(2026, 10, 22)));

        service.recordTransfer(new StripeTransferRecordedEvent(paymentId, UUID.randomUUID(), travelerId, "tr_1", ACCOUNT));

        ArgumentCaptor<BankPayoutTrackingEntity> saved = ArgumentCaptor.forClass(BankPayoutTrackingEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        BankPayoutTrackingEntity row = saved.getValue();
        assertThat(row.getPaymentId()).isEqualTo(paymentId);
        assertThat(row.getTravelerId()).isEqualTo(travelerId);
        assertThat(row.getDestinationPaymentId()).isEqualTo("py_1");
        assertThat(row.getAvailableOn()).isEqualTo(LocalDate.of(2026, 10, 22));
        verify(repository).save(row);
    }

    @Test
    void recordTransfer_existingRow_isReusedAndOnlyCompleted() throws Exception {
        BankPayoutTrackingEntity existing = row("py_9");
        existing.setAvailableOn(null);
        when(repository.findByPaymentId(existing.getPaymentId())).thenReturn(Optional.of(existing));
        when(gateway.availableOn(ACCOUNT, "py_9")).thenReturn(Optional.of(LocalDate.of(2026, 10, 25)));

        service.recordTransfer(new StripeTransferRecordedEvent(existing.getPaymentId(), null, travelerId, "tr_9", ACCOUNT));

        verify(repository, never()).saveAndFlush(any());
        verify(gateway, never()).retrieveTransfer(anyString());
        assertThat(existing.getAvailableOn()).isEqualTo(LocalDate.of(2026, 10, 25));
    }

    @Test
    void recordTransfer_incompleteEvent_isIgnored() {
        service.recordTransfer(new StripeTransferRecordedEvent(null, null, null, "tr", ACCOUNT));
        service.recordTransfer(new StripeTransferRecordedEvent(UUID.randomUUID(), null, null, null, ACCOUNT));
        verify(repository, never()).findByPaymentId(any());
    }

    @Test
    void recordTransfer_stripeDown_keepsRowWithoutDates() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(repository.findByPaymentId(paymentId)).thenReturn(Optional.empty());
        when(gateway.retrieveTransfer("tr_1")).thenThrow(new ApiConnectionException("down"));

        service.recordTransfer(new StripeTransferRecordedEvent(paymentId, null, travelerId, "tr_1", ACCOUNT));

        verify(repository).saveAndFlush(any());
        verify(repository, never()).save(any());
    }

    @Test
    void openRow_withoutAccount_readsTransfer_andResolvesTraveler() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UserEntity user = mock(UserEntity.class);
        when(user.getId()).thenReturn(travelerId);
        when(repository.findByPaymentId(paymentId)).thenReturn(Optional.empty());
        when(gateway.retrieveTransfer("tr_2")).thenReturn(
                new BankPayoutStripeGateway.TransferInfo("tr_2", ACCOUNT, "py_2"));
        when(userRepository.findByStripeAccountId(ACCOUNT)).thenReturn(Optional.of(user));

        BankPayoutTrackingEntity row = service.openRow(paymentId, null, "tr_2", null);

        assertThat(row.getConnectedAccountId()).isEqualTo(ACCOUNT);
        assertThat(row.getDestinationPaymentId()).isEqualTo("py_2");
        assertThat(row.getTravelerId()).isEqualTo(travelerId);
    }

    @Test
    void openRow_withoutAccount_unreadableTransfer_returnsNull() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(repository.findByPaymentId(paymentId)).thenReturn(Optional.empty());
        when(gateway.retrieveTransfer("tr_3")).thenThrow(new ApiConnectionException("down"));
        assertThat(service.openRow(paymentId, null, "tr_3", null)).isNull();

        when(gateway.retrieveTransfer("tr_4")).thenReturn(new BankPayoutStripeGateway.TransferInfo("tr_4", null, null));
        assertThat(service.openRow(paymentId, null, "tr_4", null)).isNull();
    }

    @Test
    void openRow_concurrentInsert_returnsTheOtherRow() {
        UUID paymentId = UUID.randomUUID();
        BankPayoutTrackingEntity other = new BankPayoutTrackingEntity();
        when(repository.findByPaymentId(paymentId)).thenReturn(Optional.empty(), Optional.of(other));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq"));

        assertThat(service.openRow(paymentId, travelerId, "tr_5", ACCOUNT)).isSameAs(other);
    }

    // ── Payouts ─────────────────────────────────────────────────────────────

    @Test
    void payoutPaid_mapsDestinationPayment_marksPaid_auditsAndNotifiesOnce() throws Exception {
        BankPayoutTrackingEntity row = row("py_1URyguKQWSCn1fJ6jemyuBTm");
        payoutContains("po_1", payment("py_1URyguKQWSCn1fJ6jemyuBTm"),
                new BankPayoutStripeGateway.BalanceTxnRef("payout", "po_1"));
        Event paid = payoutEvent("payout.paid", "po_1", "paid", 1761300000L, null, ACCOUNT);

        assertThat(service.handlePayoutEvent(paid)).isTrue();

        assertThat(row.getPayoutId()).isEqualTo("po_1");
        assertThat(row.getPayoutStatus()).isEqualTo("paid");
        assertThat(row.getPaidAt()).isEqualTo(Instant.ofEpochSecond(1761300000L));
        assertThat(row.getArrivalDate()).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(row.getNotifiedKey()).isEqualTo("po_1:paid");
        verify(auditService).log(eq("PAYMENT"), eq(row.getPaymentId()), eq("BANK_PAYOUT_PAID"), isNull(), anyMap());
        ArgumentCaptor<BankPayoutPaidEvent> published = ArgumentCaptor.forClass(BankPayoutPaidEvent.class);
        verify(events).publishEvent(published.capture());
        BankPayoutPaidEvent event = published.getValue();
        assertThat(event.travelerId()).isEqualTo(travelerId);
        assertThat(event.amount()).isEqualByComparingTo("5.00");
        assertThat(event.currency()).isEqualTo("EUR");

        // Rejeu du même événement : rien ne change, ni push ni audit.
        service.handlePayoutEvent(paid);
        verify(events, times(1)).publishEvent(any(Object.class));
        verify(auditService, times(1)).log(anyString(), any(), anyString(), any(), anyMap());
    }

    @Test
    void outOfOrder_createdAfterPaid_doesNotGoBack() throws Exception {
        BankPayoutTrackingEntity row = row("py_a");
        payoutContains("po_2", payment("py_a"));
        service.handlePayoutEvent(payoutEvent("payout.paid", "po_2", "paid", 2000L, null, ACCOUNT));
        service.handlePayoutEvent(payoutEvent("payout.created", "po_2", "pending", 1000L, null, ACCOUNT));
        // Même seconde, statut moins avancé : ignoré aussi.
        service.handlePayoutEvent(payoutEvent("payout.updated", "po_2", "in_transit", 2000L, null, ACCOUNT));

        assertThat(row.getPayoutStatus()).isEqualTo("paid");
        verify(events, times(1)).publishEvent(any(BankPayoutPaidEvent.class));
    }

    @Test
    void createdThenPaid_goesInTransitThenPaid() throws Exception {
        BankPayoutTrackingEntity row = row("py_b");
        payoutContains("po_3", payment("py_b"));
        service.handlePayoutEvent(payoutEvent("payout.created", "po_3", "pending", 1000L, null, ACCOUNT));
        assertThat(BankPayoutStatus.derive(row.getPayoutStatus(), row.getAvailableOn(), LocalDate.of(2026, 10, 24)))
                .isEqualTo(BankPayoutStatus.IN_TRANSIT);
        assertThat(row.getPaidAt()).isNull();
        verify(events, never()).publishEvent(any(Object.class));

        service.handlePayoutEvent(payoutEvent("payout.paid", "po_3", "paid", 3000L, null, ACCOUNT));
        assertThat(row.getPayoutStatus()).isEqualTo("paid");
        verify(events).publishEvent(any(BankPayoutPaidEvent.class));
    }

    @Test
    void canceled_returnsToAvailable() throws Exception {
        BankPayoutTrackingEntity row = row("py_c");
        payoutContains("po_4", payment("py_c"));
        service.handlePayoutEvent(payoutEvent("payout.created", "po_4", "pending", 1000L, null, ACCOUNT));
        service.handlePayoutEvent(payoutEvent("payout.canceled", "po_4", "canceled", 2000L, null, ACCOUNT));

        assertThat(row.getPayoutStatus()).isEqualTo("canceled");
        assertThat(BankPayoutStatus.derive(row.getPayoutStatus(), row.getAvailableOn(), LocalDate.of(2026, 10, 24)))
                .isEqualTo(BankPayoutStatus.AVAILABLE);
        verify(events, never()).publishEvent(any(Object.class));
        verify(adminAlert, never()).raise(anyString(), anyString(), anyMap());
    }

    @Test
    void failed_setsFailureCode_alertsAdmin_notifiesOnce_thenNextPayoutTakesOver() throws Exception {
        BankPayoutTrackingEntity row = row("py_d");
        payoutContains("po_5", payment("py_d"));
        Event failed = payoutEvent("payout.failed", "po_5", "failed", 2000L, "account_closed", ACCOUNT);

        service.handlePayoutEvent(failed);
        service.handlePayoutEvent(failed);

        assertThat(row.getPayoutStatus()).isEqualTo("failed");
        assertThat(row.getFailureCode()).isEqualTo("account_closed");
        verify(adminAlert, times(1)).raise(eq("STRIPE_PAYOUT_FAILED"), anyString(), anyMap());
        ArgumentCaptor<BankPayoutFailedEvent> event = ArgumentCaptor.forClass(BankPayoutFailedEvent.class);
        verify(events, times(1)).publishEvent(event.capture());
        assertThat(event.getValue().failureCode()).isEqualTo("account_closed");
        verify(auditService).log(eq("PAYMENT"), eq(row.getPaymentId()), eq("BANK_PAYOUT_FAILED"), isNull(), anyMap());

        // Nouveau payout après mise à jour de l'IBAN : il contient la payout_failure de po_5.
        payoutContains("po_6", new BankPayoutStripeGateway.BalanceTxnRef("payout_failure", "po_5"));
        service.handlePayoutEvent(payoutEvent("payout.paid", "po_6", "paid", 1500L, null, ACCOUNT));

        assertThat(row.getPayoutId()).isEqualTo("po_6");
        assertThat(row.getPayoutStatus()).isEqualTo("paid");
        assertThat(row.getFailureCode()).isNull();
        verify(events).publishEvent(any(BankPayoutPaidEvent.class));
    }

    @Test
    void platformPayout_isNotOurs() {
        assertThat(service.handlePayoutEvent(payoutEvent("payout.paid", "po_p", "paid", 1L, null, null))).isFalse();
        verify(repository, never()).findByConnectedAccountIdAndPayoutIdIn(anyString(), anyCollection());
    }

    @Test
    void unreadablePayload_isAcknowledged() {
        Event broken = ApiResource.GSON.fromJson("{\"id\":\"evt\",\"object\":\"event\",\"type\":\"payout.paid\","
                + "\"account\":\"" + ACCOUNT + "\",\"data\":{\"object\":{\"object\":\"payout\"}}}", Event.class);
        assertThat(service.handlePayoutEvent(broken)).isTrue();
    }

    @Test
    void stripeUnavailable_throwsSoThatInboxRetries() throws Exception {
        when(gateway.payoutBalanceTransactions(ACCOUNT, "po_7")).thenThrow(new ApiConnectionException("down"));
        assertThatThrownBy(() -> service.handlePayoutEvent(
                payoutEvent("payout.paid", "po_7", "paid", 1L, null, ACCOUNT)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedPayoutWithoutTrackedParcel_stillAlertsAdmin() throws Exception {
        payoutContains("po_8", payment("py_unknown"));
        service.handlePayoutEvent(payoutEvent("payout.failed", "po_8", "failed", 1L, "no_account", ACCOUNT));
        verify(adminAlert).raise(eq("STRIPE_PAYOUT_FAILED"), anyString(), anyMap());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void manualPayout_isIgnored() throws Exception {
        row("py_e");
        PayoutSnapshot manual = new PayoutSnapshot("po_9", "paid", null, 500L, "eur", null, false, 1L);
        assertThat(service.applyPayout(ACCOUNT, manual, NOW, NOW)).isZero();
        verify(gateway, never()).payoutBalanceTransactions(anyString(), anyString());
        PayoutSnapshot noStatus = new PayoutSnapshot("po_9", null, null, 500L, "eur", null, true, 1L);
        assertThat(service.applyPayout(ACCOUNT, noStatus, NOW, NOW)).isZero();
    }

    @Test
    void oldPayoutFoundByResync_isRecorded_butNotAnnounced() throws Exception {
        BankPayoutTrackingEntity row = row("py_f");
        payoutContains("po_10", payment("py_f"));
        PayoutSnapshot old = new PayoutSnapshot("po_10", "paid", LocalDate.of(2026, 10, 1), 500L, "eur", null, true, 1L);
        Instant paidAt = Instant.parse("2026-10-01T00:00:00Z");

        assertThat(service.applyPayout(ACCOUNT, old, NOW, paidAt)).isEqualTo(1);

        assertThat(row.getPayoutStatus()).isEqualTo("paid");
        assertThat(row.getPaidAt()).isEqualTo(paidAt);
        assertThat(row.getNotifiedKey()).isEqualTo("po_10:paid");
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void resyncOfSamePaidPayout_keepsFirstPaidAt() throws Exception {
        BankPayoutTrackingEntity row = row("py_g");
        payoutContains("po_11", payment("py_g"));
        service.handlePayoutEvent(payoutEvent("payout.paid", "po_11", "paid", 1000L, null, ACCOUNT));
        Instant first = row.getPaidAt();

        PayoutSnapshot again = new PayoutSnapshot("po_11", "paid", LocalDate.of(2026, 10, 24), 500L, "eur", null, true, 1L);
        assertThat(service.applyPayout(ACCOUNT, again, NOW, NOW)).isZero();
        assertThat(row.getPaidAt()).isEqualTo(first);
    }

    @Test
    void travelerUnknownOnRow_isResolvedFromConnectedAccount() throws Exception {
        BankPayoutTrackingEntity row = row("py_h");
        row.setTravelerId(null);
        UserEntity user = mock(UserEntity.class);
        when(user.getId()).thenReturn(travelerId);
        when(userRepository.findByStripeAccountId(ACCOUNT)).thenReturn(Optional.of(user));
        payoutContains("po_12", payment("py_h"));

        service.handlePayoutEvent(payoutEvent("payout.paid", "po_12", "paid", 1000L, null, ACCOUNT));

        ArgumentCaptor<BankPayoutPaidEvent> event = ArgumentCaptor.forClass(BankPayoutPaidEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().travelerId()).isEqualTo(travelerId);
    }

    @Test
    void rowsWithoutDestinationPayment_areCompletedBeforeMapping() throws Exception {
        BankPayoutTrackingEntity pending = row(null);
        when(repository.findByConnectedAccountIdAndDestinationPaymentIdIsNull(ACCOUNT)).thenReturn(List.of(pending));
        when(gateway.retrieveTransfer(pending.getTransferId())).thenReturn(
                new BankPayoutStripeGateway.TransferInfo(pending.getTransferId(), ACCOUNT, "py_late"));
        when(gateway.availableOn(ACCOUNT, "py_late")).thenReturn(Optional.empty());
        payoutContains("po_13", payment("py_late"));

        service.handlePayoutEvent(payoutEvent("payout.paid", "po_13", "paid", 1000L, null, ACCOUNT));

        assertThat(pending.getDestinationPaymentId()).isEqualTo("py_late");
        assertThat(pending.getPayoutStatus()).isEqualTo("paid");
    }

    @Test
    void anotherValidPayoutSeenLater_isNotOverriddenByOlderInfo() {
        BankPayoutTrackingEntity row = new BankPayoutTrackingEntity();
        row.setPayoutId("po_new");
        row.setPayoutStatus("in_transit");
        row.setPayoutEventAt(Instant.ofEpochSecond(5000));
        PayoutSnapshot older = new PayoutSnapshot("po_old", "pending", null, 1L, "eur", null, true, 1L);

        assertThat(BankPayoutTrackingService.apply(row, older, Instant.ofEpochSecond(4000), NOW)).isFalse();
        assertThat(row.getPayoutId()).isEqualTo("po_new");
    }

    @Test
    void helpers() {
        assertThat(BankPayoutTrackingService.rank(null)).isZero();
        assertThat(BankPayoutTrackingService.rank("weird")).isZero();
        assertThat(BankPayoutTrackingService.rank("pending")).isLessThan(BankPayoutTrackingService.rank("in_transit"));
        assertThat(BankPayoutTrackingService.amount(null, "EUR")).isNull();
        assertThat(BankPayoutTrackingService.amount(500L, "XOF")).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(BankPayoutTrackingService.amount(500L, "ZZZ")).isEqualByComparingTo("5.00");
        assertThat(BankPayoutTrackingService.amount(500L, null)).isEqualByComparingTo("5.00");
        assertThat(service.today()).isEqualTo(LocalDate.of(2026, 10, 24));
    }

    @Test
    void parsePayout_readsStripeFields() throws Exception {
        PayoutSnapshot snapshot = BankPayoutTrackingService.parsePayout(new ObjectMapper().readTree(
                "{\"id\":\"po_x\",\"status\":\"in_transit\",\"arrival_date\":1792800000,\"amount\":1234,"
                        + "\"currency\":\"eur\",\"failure_code\":null,\"automatic\":true,\"created\":42}"));
        assertThat(snapshot).isEqualTo(new PayoutSnapshot("po_x", "in_transit", LocalDate.of(2026, 10, 24), 1234L,
                "eur", null, true, 42L));
        PayoutSnapshot bare = BankPayoutTrackingService.parsePayout(new ObjectMapper().readTree("{\"id\":\"po_y\"}"));
        assertThat(bare.arrivalDate()).isNull();
        assertThat(bare.automatic()).isNull();
        assertThatThrownBy(() -> BankPayoutTrackingService.parsePayout(new ObjectMapper().readTree("{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noTrackedRow_changesNothing() throws Exception {
        payoutContains("po_14", payment("py_nobody"), new BankPayoutStripeGateway.BalanceTxnRef("payment", null));
        PayoutSnapshot snapshot = new PayoutSnapshot("po_14", "paid", null, 1L, "eur", null, true, 1L);
        assertThat(service.applyPayout(ACCOUNT, snapshot, NOW, NOW)).isZero();
        verify(repository, never()).save(any());
        assertThat(Map.of()).isEmpty();
    }
}
