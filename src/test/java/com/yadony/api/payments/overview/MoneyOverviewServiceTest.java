package com.yadony.api.payments.overview;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.overview.dto.MoneyItemDto;
import com.yadony.api.payments.overview.dto.MoneyOverviewResponse;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MoneyOverviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String UID = "uid-traveler";

    private final MoneyOverviewReadModel readModel = mock(MoneyOverviewReadModel.class);
    private final WalletAccountRepository wallets = mock(WalletAccountRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final MoneyOverviewService service =
            new MoneyOverviewService(readModel, wallets, users, Clock.fixed(NOW, ZoneOffset.UTC));

    private UUID userId;
    private UserEntity counterparty;

    @BeforeEach
    void setUp() {
        UserEntity me = new UserEntity();
        userId = UUID.randomUUID();
        ReflectionTestUtils.setField(me, "id", userId);
        when(users.findByFirebaseUid(UID)).thenReturn(Optional.of(me));
        counterparty = new UserEntity();
        ReflectionTestUtils.setField(counterparty, "id", UUID.randomUUID());
        counterparty.setFirstName("Aminata");
        counterparty.setLastName("Diallo");
        when(users.findAllById(anyIterable())).thenReturn(List.of(counterparty));
    }

    private MoneyRow payment(MoneyRole role, String paymentStatus, String bidStatus, String amount,
                             String commission, String currency, OffsetDateTime releasedAt, OffsetDateTime holdUntil) {
        return new MoneyRow(role, UUID.randomUUID(), bidStatus, "STRIPE", currency, "DON-TEST0001", new BigDecimal("10"), null,
                UUID.randomUUID(), "Paris", "Dakar", LocalDate.of(2026, 10, 20), counterparty.getId(),
                UUID.randomUUID(), paymentStatus, "STRIPE", new BigDecimal(amount), new BigDecimal(commission),
                null, currency, false, null, releasedAt, releasedAt, 0, 0, holdUntil, 0, 0);
    }

    @Test
    void unknownUser_is404() {
        when(users.findByFirebaseUid("nobody")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.overview("nobody"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void readsOnlyTheCallersRows_sinceTheRecentWindow() {
        when(readModel.findRows(any(), any())).thenReturn(List.of());
        MoneyOverviewResponse r = service.overview(UID);
        verify(readModel).findRows(eq(userId), eq(OffsetDateTime.of(2026, 9, 10, 12, 0, 0, 0, ZoneOffset.UTC)));
        assertThat(r.recentWindowDays()).isEqualTo(30);
        assertThat(r.generatedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(r.traveler().items()).isEmpty();
        assertThat(r.traveler().totals()).isEmpty();
        assertThat(r.sender().items()).isEmpty();
        verify(users, never()).findAllById(anyIterable());
    }

    @Test
    void walletBalances_perCurrency_sortedAndUppercased() {
        when(readModel.findRows(any(), any())).thenReturn(List.of());
        WalletAccountEntity xof = new WalletAccountEntity();
        xof.setCurrency("xof");
        xof.setBalance(new BigDecimal("5000"));
        WalletAccountEntity eur = new WalletAccountEntity();
        eur.setCurrency("EUR");
        eur.setBalance(new BigDecimal("12.50"));
        when(wallets.findAllByUserId(userId)).thenReturn(List.of(xof, eur));

        MoneyOverviewResponse r = service.overview(UID);
        assertThat(r.wallet()).extracting(w -> w.currency()).containsExactly("EUR", "XOF");
        assertThat(r.wallet().get(0).balance()).isEqualByComparingTo("12.50");
    }

    @Test
    void travelerTotals_perCurrency_neverMixed_andItemsOrdered() {
        OffsetDateTime hold = OffsetDateTime.of(2026, 10, 15, 9, 0, 0, 0, ZoneOffset.UTC);
        when(readModel.findRows(any(), any())).thenReturn(List.of(
                payment(MoneyRole.TRAVELER, "RELEASED", "COMPLETED", "50.00", "6.00", "EUR",
                        OffsetDateTime.of(2026, 10, 1, 8, 0, 0, 0, ZoneOffset.UTC), null),
                payment(MoneyRole.TRAVELER, "ESCROW", "ACCEPTED", "100.00", "12.00", "EUR", null, null),
                payment(MoneyRole.TRAVELER, "ESCROW", "ARRIVED", "20000", "2400", "XOF", null, hold),
                payment(MoneyRole.TRAVELER, "REFUNDED", "CANCELLED", "30.00", "3.60", "EUR", null, null)));

        MoneyOverviewResponse r = service.overview(UID);
        List<MoneyItemDto> items = r.traveler().items();
        assertThat(items).extracting(MoneyItemDto::state).containsExactly(
                MoneyState.RELEASE_SCHEDULED, MoneyState.ESCROWED, MoneyState.RELEASED_RECENTLY);
        assertThat(items.get(0).releaseAt()).isEqualTo(hold);
        assertThat(items.get(0).counterpartyName()).isEqualTo("Aminata D.");
        assertThat(items.get(0).channel()).isEqualTo(MoneyChannel.CARD);
        assertThat(items.get(0).bidStatus()).isEqualTo("ARRIVED");
        assertThat(items.get(0).cashCommissionStatus()).isNull();

        assertThat(r.traveler().totals()).hasSize(2);
        assertThat(r.traveler().totals().get(0).currency()).isEqualTo("EUR");
        assertThat(r.traveler().totals().get(0).upcoming()).isEqualByComparingTo("88.00");
        assertThat(r.traveler().totals().get(0).releasedRecently()).isEqualByComparingTo("44.00");
        assertThat(r.traveler().totals().get(1).currency()).isEqualTo("XOF");
        assertThat(r.traveler().totals().get(1).upcoming()).isEqualByComparingTo("17600");
        assertThat(r.traveler().totals().get(1).releasedRecently()).isEqualByComparingTo("0");
        assertThat(r.sender().items()).isEmpty();
    }

    @Test
    void senderTotals_blockedRefundPendingAndRefunded() {
        MoneyRow refundPending = new MoneyRow(MoneyRole.SENDER, UUID.randomUUID(), "CANCELLED", "MOBILE_MONEY", "XOF",
                "DON-TEST0002", new BigDecimal("10"), null, UUID.randomUUID(), "Paris", "Abidjan", LocalDate.of(2026, 10, 12),
                counterparty.getId(), UUID.randomUUID(), "ESCROW", "PAWAPAY", new BigDecimal("15000"),
                new BigDecimal("1800"), null, "XOF", false, null, null, null, 0, 0, null, 0, 1);
        when(readModel.findRows(any(), any())).thenReturn(List.of(
                payment(MoneyRole.SENDER, "ESCROW", "IN_TRANSIT", "100.00", "12.00", "EUR", null, null),
                payment(MoneyRole.SENDER, "REFUNDED", "CANCELLED", "40.00", "4.80", "EUR",
                        OffsetDateTime.of(2026, 10, 2, 8, 0, 0, 0, ZoneOffset.UTC), null),
                payment(MoneyRole.SENDER, "RELEASED", "COMPLETED", "70.00", "8.40", "EUR",
                        OffsetDateTime.of(2026, 10, 3, 8, 0, 0, 0, ZoneOffset.UTC), null),
                refundPending));

        MoneyOverviewResponse r = service.overview(UID);
        assertThat(r.sender().items()).extracting(MoneyItemDto::state).containsExactly(
                MoneyState.AWAITING_DELIVERY_CONFIRMATION, MoneyState.REFUND_PENDING, MoneyState.REFUNDED_RECENTLY);
        assertThat(r.sender().items().get(1).channel()).isEqualTo(MoneyChannel.MOBILE_MONEY);
        assertThat(r.sender().totals()).hasSize(2);
        assertThat(r.sender().totals().get(0).currency()).isEqualTo("EUR");
        assertThat(r.sender().totals().get(0).blocked()).isEqualByComparingTo("100.00");
        assertThat(r.sender().totals().get(0).refundedRecently()).isEqualByComparingTo("40.00");
        assertThat(r.sender().totals().get(0).refundPending()).isEqualByComparingTo("0");
        assertThat(r.sender().totals().get(1).refundPending()).isEqualByComparingTo("15000");
    }

    @Test
    void cashParcel_listedWithoutAmount_andExcludedFromTotals() {
        MoneyRow cash = new MoneyRow(MoneyRole.TRAVELER, UUID.randomUUID(), "ACCEPTED", "CASH", "EUR",
                "DON-CASH0001", new BigDecimal("10"), "CHARGED", UUID.randomUUID(), "Lyon", "Bamako", LocalDate.of(2026, 10, 25),
                null, null, null, null, null, null, null, null, false,
                null, null, null, 0, 0, null, 0, 0);
        when(readModel.findRows(any(), any())).thenReturn(List.of(cash));

        MoneyOverviewResponse r = service.overview(UID);
        assertThat(r.traveler().items()).singleElement().satisfies(i -> {
            assertThat(i.state()).isEqualTo(MoneyState.CASH);
            assertThat(i.channel()).isEqualTo(MoneyChannel.CASH);
            assertThat(i.amount()).isNull();
            assertThat(i.counterpartyName()).isNull();
            assertThat(i.cashCommissionStatus()).isEqualTo("CHARGED");
            assertThat(i.bidStatus()).isEqualTo("ACCEPTED");
            assertThat(i.weightKg()).isEqualByComparingTo("10");
        });
        assertThat(r.traveler().totals()).isEmpty();
        verify(users, never()).findAllById(anyIterable());
    }

    @Test
    void counterpartyWithoutAnyName_isOmitted() {
        UserEntity anonymous = new UserEntity();
        ReflectionTestUtils.setField(anonymous, "id", counterparty.getId());
        when(users.findAllById(anyIterable())).thenReturn(List.of(anonymous));
        when(readModel.findRows(any(), any())).thenReturn(List.of(
                payment(MoneyRole.TRAVELER, "ESCROW", "ACCEPTED", "10.00", "1.20", "EUR", null, null)));
        assertThat(service.overview(UID).traveler().items().get(0).counterpartyName()).isNull();
    }
}
