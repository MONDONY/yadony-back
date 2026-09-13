package com.yadony.api.matching.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.matching.dto.RevenueDetailsDto.RevenueLine;
import com.yadony.api.payments.PaymentRail;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RevenueDetailsDtoTest {

    private static RevenueLine line(String currency, String date, String amount, RevenueRail rail) {
        return new RevenueLine(currency, new RevenueItemDto(
                UUID.randomUUID(), "Paris", "Dakar", LocalDate.parse(date),
                new BigDecimal("2.00"), rail, new BigDecimal(amount)));
    }

    @Test
    void groups_lines_by_currency_in_alphabetical_order_with_subtotals() {
        RevenueDetailsDto dto = RevenueDetailsDto.of("30d", List.of(
                line("XOF", "2026-09-09", "120000", RevenueRail.MOBILE_MONEY),
                line("EUR", "2026-09-12", "480.00", RevenueRail.CARD),
                line("EUR", "2026-09-05", "330.00", RevenueRail.CARD),
                line("XOF", "2026-08-30", "75000", RevenueRail.CASH)));

        assertThat(dto.period()).isEqualTo("30d");
        assertThat(dto.deliveries()).isEqualTo(4);
        assertThat(dto.groups()).extracting(RevenueGroupDto::currency).containsExactly("EUR", "XOF");
        assertThat(dto.groups().get(0).total()).isEqualByComparingTo("810.00");
        assertThat(dto.groups().get(0).deliveries()).isEqualTo(2);
        assertThat(dto.groups().get(1).total()).isEqualByComparingTo("195000");
        assertThat(dto.groups().get(1).deliveries()).isEqualTo(2);
    }

    @Test
    void sorts_items_by_date_desc_then_amount_desc() {
        RevenueDetailsDto dto = RevenueDetailsDto.of("30d", List.of(
                line("EUR", "2026-09-05", "330.00", RevenueRail.CARD),
                line("EUR", "2026-09-12", "220.00", RevenueRail.CASH),
                line("EUR", "2026-09-12", "480.00", RevenueRail.CARD)));

        assertThat(dto.groups().get(0).items())
                .extracting(RevenueItemDto::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("480.00"), new BigDecimal("220.00"), new BigDecimal("330.00"));
    }

    @Test
    void rounds_totals_and_amounts_to_the_currency_minor_unit() {
        RevenueDetailsDto dto = RevenueDetailsDto.of("7d", List.of(
                line("EUR", "2026-09-12", "10.005", RevenueRail.CARD),
                line("XOF", "2026-09-12", "1000.4", RevenueRail.CASH)));

        assertThat(dto.groups().get(0).total()).isEqualTo(new BigDecimal("10.01"));
        assertThat(dto.groups().get(0).items().get(0).amount()).isEqualTo(new BigDecimal("10.01"));
        assertThat(dto.groups().get(1).total()).isEqualTo(new BigDecimal("1000"));
        assertThat(dto.groups().get(1).items().get(0).amount()).isEqualTo(new BigDecimal("1000"));
    }

    @Test
    void is_empty_without_lines() {
        RevenueDetailsDto dto = RevenueDetailsDto.of("12m", List.of());

        assertThat(dto.deliveries()).isZero();
        assertThat(dto.groups()).isEmpty();
    }

    @Test
    void item_from_payment_uses_the_trip_date_and_maps_the_rail() {
        UUID tripId = UUID.randomUUID();
        PaymentLineRow row = new PaymentLineRow(tripId, "Paris", "Dakar",
                LocalDate.parse("2026-09-12"), LocalDateTime.parse("2026-09-14T10:15:00"),
                new BigDecimal("4.00"), PaymentRail.PAWAPAY, "XOF", new BigDecimal("120000"));

        RevenueItemDto item = RevenueItemDto.fromPayment(row);

        assertThat(item.tripId()).isEqualTo(tripId);
        assertThat(item.date()).isEqualTo(LocalDate.parse("2026-09-12"));
        assertThat(item.rail()).isEqualTo(RevenueRail.MOBILE_MONEY);
        assertThat(item.amount()).isEqualByComparingTo("120000");
    }

    @Test
    void item_from_payment_falls_back_on_the_payment_date_without_trip() {
        PaymentLineRow row = new PaymentLineRow(null, "Lyon", "Abidjan", null,
                LocalDateTime.parse("2026-09-14T10:15:00"),
                new BigDecimal("2.50"), PaymentRail.STRIPE, "EUR", new BigDecimal("176.00"));

        RevenueItemDto item = RevenueItemDto.fromPayment(row);

        assertThat(item.tripId()).isNull();
        assertThat(item.date()).isEqualTo(LocalDate.parse("2026-09-14"));
        assertThat(item.rail()).isEqualTo(RevenueRail.CARD);
    }

    @Test
    void item_from_cash_is_a_cash_rail_on_the_trip_date() {
        CashLineRow row = new CashLineRow(UUID.randomUUID(), "Paris", "Dakar",
                LocalDate.parse("2026-09-12"), new BigDecimal("2.00"), "EUR", new BigDecimal("220.00"));

        RevenueItemDto item = RevenueItemDto.fromCash(row);

        assertThat(item.rail()).isEqualTo(RevenueRail.CASH);
        assertThat(item.date()).isEqualTo(LocalDate.parse("2026-09-12"));
        assertThat(item.weightKg()).isEqualByComparingTo("2.00");
    }
}
