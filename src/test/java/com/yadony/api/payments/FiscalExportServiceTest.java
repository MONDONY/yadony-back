package com.yadony.api.payments;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.supplement.BidSupplementEntity;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.BidSupplementRepository;
import com.yadony.api.payments.supplement.BidSupplementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FiscalExportServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidSupplementRepository supplementRepository;

    @InjectMocks private FiscalExportService service;

    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private UserEntity traveler;

    @BeforeEach
    void setup() throws Exception {
        traveler = new UserEntity();
        setField(traveler, "id", TRAVELER_ID);
        traveler.setFirstName("Kofi");
        traveler.setLastName("Mensah");
        setField(traveler, "country", "FR");
    }

    @Nested
    @DisplayName("generateCsv — summary")
    class SummaryCsv {

        @Test
        void containsHeaderAndTotals() {
            PaymentEntity p = buildPayment(BigDecimal.valueOf(100), BigDecimal.valueOf(12));
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of(p));

            byte[] csv = service.generateCsv(traveler, 2025, "summary");
            String content = new String(csv);

            assertThat(content).contains("Année").contains("Revenu brut").contains("Revenu net");
            assertThat(content).contains("2025");
            assertThat(content).contains("100.00");
            assertThat(content).contains("88.00"); // net = 100 - 12
        }
    }

    @Nested
    @DisplayName("generateCsv — transactions")
    class TransactionsCsv {

        @Test
        void containsTransactionRow() {
            PaymentEntity p = buildPayment(BigDecimal.valueOf(50), BigDecimal.valueOf(6));
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of(p));
            // bidId is null → resolveCorridorForPayment returns "—" without calling bidRepository

            byte[] csv = service.generateCsv(traveler, 2025, "transactions");
            String content = new String(csv);

            assertThat(content).contains("Date").contains("Trajet").contains("50.00").contains("44.00");
        }

        @Test
        void returnsJustHeaderOnNoPayments() {
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of());

            byte[] csv = service.generateCsv(traveler, 2025, "transactions");
            String content = new String(csv);

            assertThat(content).contains("Date,Trajet");
            assertThat(content.lines().count()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("generateCsv — dac7")
    class Dac7Csv {

        @Test
        void containsDac7Fields() {
            PaymentEntity p = buildPayment(BigDecimal.valueOf(200), BigDecimal.valueOf(24));
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of(p));

            byte[] csv = service.generateCsv(traveler, 2025, "dac7");
            String content = new String(csv);

            assertThat(content).contains("DAC7");
            assertThat(content).contains("Kofi");
            assertThat(content).contains("200.00");
            assertThat(content).contains("176.00");
        }
    }

    @Nested
    @DisplayName("generateHtml — pdf")
    class GenerateHtml {

        @Test
        void returnsSummaryHtml() {
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of());

            byte[] html = service.generateHtml(traveler, 2025, "summary");
            String content = new String(html);

            assertThat(content).contains("<!DOCTYPE html>");
            assertThat(content).contains("2025");
            assertThat(content).contains("Kofi Mensah");
        }

        @Test
        void returnsTransactionsHtml() {
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of());

            byte[] html = service.generateHtml(traveler, 2025, "transactions");
            String content = new String(html);

            assertThat(content).contains("Transactions 2025");
        }

        @Test
        void returnsDac7Html() {
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of());

            byte[] html = service.generateHtml(traveler, 2025, "dac7");
            String content = new String(html);

            assertThat(content).contains("DAC7");
            assertThat(content).contains("Kofi Mensah");
        }
    }

    @Nested
    @DisplayName("Supplément de prix versé (B5)")
    class Supplements {

        @Test
        void resume_supplementVerseCompteDansLeBrutEtLaCommission() {
            PaymentEntity p = buildPayment(BigDecimal.valueOf(100), BigDecimal.valueOf(12));
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any())).thenReturn(List.of(p));
            when(supplementRepository.findPaidOutByTravelerBetween(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of(paidOut(new BigDecimal("21.10"), new BigDecimal("1.10"), null)));

            String content = new String(service.generateCsv(traveler, 2025, "summary"));

            // brut 100 + 21,10 ; commission 12 + 1,10 ; net 108
            assertThat(content).contains("121.10").contains("13.10").contains("108.00");
        }

        @Test
        void dac7_brutEtCommissionAvecSupplement_nombreDeTransactionsInchange() {
            PaymentEntity p = buildPayment(BigDecimal.valueOf(200), BigDecimal.valueOf(24));
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any())).thenReturn(List.of(p));
            when(supplementRepository.findPaidOutByTravelerBetween(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of(paidOut(new BigDecimal("21.10"), new BigDecimal("1.10"), null)));

            String csv = new String(service.generateCsv(traveler, 2025, "dac7"));
            String html = new String(service.generateHtml(traveler, 2025, "dac7"));

            assertThat(csv).contains("Revenu brut total (€),221.10").contains("Commissions retenues (€),25.10")
                    .contains("Revenu net total (€),196.00").contains("Nombre de transactions,1");
            assertThat(html).contains("221.10 €").contains("25.10 €").contains("<td>1</td>");
        }

        @Test
        void transactions_ligneDuSupplementAvecSonTrajet() throws Exception {
            UUID bidId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            AnnouncementEntity announcement = new AnnouncementEntity();
            announcement.setDepartureCity("Paris");
            announcement.setArrivalCity("Dakar");
            when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any())).thenReturn(List.of());
            when(supplementRepository.findPaidOutByTravelerBetween(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of(paidOut(new BigDecimal("21.10"), new BigDecimal("1.10"), bidId)));

            String csv = new String(service.generateCsv(traveler, 2025, "transactions"));
            String html = new String(service.generateHtml(traveler, 2025, "transactions"));

            assertThat(csv).contains("2025-06-01,Paris → Dakar (supplément),21.10,1.10,20.00");
            assertThat(html).contains("Paris → Dakar (supplément)").contains("<td>20.00</td>");
        }

        private BidSupplementEntity paidOut(BigDecimal gross, BigDecimal commission, UUID bidId) {
            BidSupplementEntity s = new BidSupplementEntity();
            s.setBidId(bidId);
            s.setRail(BidSupplementRail.STRIPE);
            s.setStatus(BidSupplementStatus.PAID_OUT);
            s.setCurrency("EUR");
            s.setGrossAmount(gross);
            s.setCommissionAmount(commission);
            s.setNetAmount(gross.subtract(commission));
            s.setPaidOutAt(LocalDateTime.of(2025, 6, 1, 12, 0));
            return s;
        }
    }

    // ---- Helpers ----

    private PaymentEntity buildPayment(BigDecimal amount, BigDecimal commission) {
        PaymentEntity p = new PaymentEntity();
        try {
            setField(p, "id", UUID.randomUUID());
            setField(p, "amount", amount);
            setField(p, "commissionAmount", commission);
            setField(p, "status", PaymentStatus.RELEASED);
            setField(p, "createdAt", LocalDateTime.now());
        } catch (Exception ignored) {}
        return p;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
