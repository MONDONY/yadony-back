package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.supplement.dto.AdminSupplementDecisionResponse;
import com.yadony.api.payments.supplement.dto.AdminSupplementRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BidSupplementAdminServiceTest {

    private final BidSupplementRepository repository = mock(BidSupplementRepository.class);
    private final BidSupplementCardPayouts cardPayouts = mock(BidSupplementCardPayouts.class);
    private final BidSupplementCardRefunds cardRefunds = mock(BidSupplementCardRefunds.class);
    private final BidSupplementMobileMoneyPayouts mmPayouts = mock(BidSupplementMobileMoneyPayouts.class);
    private final BidSupplementMobileMoneyRefunds mmRefunds = mock(BidSupplementMobileMoneyRefunds.class);
    private final AuditService audit = mock(AuditService.class);
    private final AdminAlertEscalator alerts = mock(AdminAlertEscalator.class);
    private final StorageService storage = mock(StorageService.class);
    private final BidSupplementAdminService service = new BidSupplementAdminService(repository, cardPayouts,
            cardRefunds, mmPayouts, mmRefunds, audit, alerts, storage);
    private final UUID adminId = UUID.randomUUID();

    private BidSupplementEntity supplement(BidSupplementRail rail, BidSupplementStatus status) {
        BidSupplementEntity s = new BidSupplementEntity();
        BidSupplementPricingTest.setId(s, UUID.randomUUID());
        s.setBidId(UUID.randomUUID());
        s.setTravelerId(UUID.randomUUID());
        s.setSenderId(UUID.randomUUID());
        s.setRail(rail);
        s.setStatus(status);
        s.setCurrency("EUR");
        s.setNetAmount(new BigDecimal("20.00"));
        s.setCommissionAmount(new BigDecimal("1.10"));
        s.setGrossAmount(new BigDecimal("21.10"));
        s.setReasonCode(BidSupplementReason.GRID_ITEM);
        s.setReasonText("texte libre du voyageur");
        s.setPhotoKey("tracking/" + s.getBidId() + "/1_supplement.jpg");
        s.setExpiresAt(LocalDateTime.of(2026, 10, 19, 12, 0));
        s.setPaidAt(LocalDateTime.of(2026, 10, 19, 11, 0));
        s.setStripePaymentIntentId(rail == BidSupplementRail.STRIPE ? "pi_1" : null);
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void liste_montantsCompletsEtReferences_sansLeMotifLibre_photoPresignee() {
        BidSupplementEntity s = supplement(BidSupplementRail.STRIPE, BidSupplementStatus.PAID);
        BidSupplementEntity purged = supplement(BidSupplementRail.CASH, BidSupplementStatus.DECLINED);
        setPhotoPurgedAt(purged, LocalDateTime.now());
        purged.setCommissionStatus(CommissionStatus.REFUNDED);
        purged.setCommissionChargedVia(CommissionChargedVia.WALLET);
        when(repository.findByBidIdOrderByCreatedAtDesc(s.getBidId())).thenReturn(List.of(s, purged));
        when(storage.generatePresignedUrl(eq(s.getPhotoKey()), any())).thenReturn("https://r2/photo");

        List<AdminSupplementRow> rows = service.listForBid(s.getBidId());

        assertThat(rows).hasSize(2);
        AdminSupplementRow row = rows.get(0);
        assertThat(row.netAmount()).isEqualByComparingTo("20.00");
        assertThat(row.commissionAmount()).isEqualByComparingTo("1.10");
        assertThat(row.grossAmount()).isEqualByComparingTo("21.10");
        assertThat(row.photoUrl()).isEqualTo("https://r2/photo");
        assertThat(row.paidAt()).isNotNull();
        assertThat(row.stripePaymentIntentId()).isEqualTo("pi_1");
        assertThat(row.toString()).doesNotContain("texte libre du voyageur");
        assertThat(rows.get(1).photoUrl()).isNull();
        assertThat(rows.get(1).commissionStatus()).isEqualTo("REFUNDED");
        assertThat(rows.get(1).commissionChargedVia()).isEqualTo("WALLET");
    }

    @Test
    void verser_carte_payeAuditeEtCloLAlerteNonSolde() {
        BidSupplementEntity s = supplement(BidSupplementRail.STRIPE, BidSupplementStatus.PAID);
        when(cardPayouts.payout(s.getId())).thenAnswer(inv -> {
            s.setStatus(BidSupplementStatus.PAID_OUT);
            return true;
        });

        AdminSupplementDecisionResponse r = service.payout(s.getId(), adminId, "Partage du litige : part voyageur");

        assertThat(r.acted()).isTrue();
        assertThat(r.supplement().status()).isEqualTo("PAID_OUT");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("BID_SUPPLEMENT"), eq(s.getId()), eq("BID_SUPPLEMENT_ADMIN_PAYOUT_DECIDED"),
                eq(adminId), payload.capture());
        assertThat(payload.getValue()).containsEntry("adminReason", "Partage du litige : part voyageur")
                .containsEntry("status", "PAID");
        verify(alerts).resolveOpen("SUPP_UNSETTLED_" + s.getId());
    }

    @Test
    void verser_retenuParLesGardes_alerteGardee() {
        BidSupplementEntity s = supplement(BidSupplementRail.STRIPE, BidSupplementStatus.PAID);
        when(cardPayouts.payout(s.getId())).thenReturn(false);

        assertThat(service.payout(s.getId(), adminId, "relance").acted()).isFalse();
        verify(alerts, never()).resolveOpen(anyString());
    }

    @Test
    void verser_relanceDUnVersementEnAttente_carteEtMobileMoney() {
        BidSupplementEntity card = supplement(BidSupplementRail.STRIPE, BidSupplementStatus.PAYOUT_PENDING);
        BidSupplementEntity mm = supplement(BidSupplementRail.PAWAPAY, BidSupplementStatus.PAYOUT_PENDING);
        BidSupplementEntity mmPaid = supplement(BidSupplementRail.PAWAPAY, BidSupplementStatus.PAID);
        when(cardPayouts.resume(card.getId())).thenReturn(true);
        when(mmPayouts.resume(mm.getId())).thenReturn(true);
        when(mmPayouts.payout(mmPaid.getId())).thenReturn(true);

        assertThat(service.payout(card.getId(), adminId, "relance").acted()).isTrue();
        assertThat(service.payout(mm.getId(), adminId, "relance").acted()).isTrue();
        assertThat(service.payout(mmPaid.getId(), adminId, "décision").acted()).isTrue();
        verify(cardPayouts, never()).payout(any());
    }

    @Test
    void verser_statutNonPayable_409() {
        BidSupplementEntity s = supplement(BidSupplementRail.STRIPE, BidSupplementStatus.REFUNDED);

        YadonyBusinessException e = catchThrowableOfType(YadonyBusinessException.class,
                () -> service.payout(s.getId(), adminId, "x"));

        assertThat(e.getErrorCode()).isEqualTo("supplement-not-payable");
        verifyNoInteractions(cardPayouts, audit);
    }

    @Test
    void rembourser_carteEtMobileMoney_payeOuEnAttente() {
        BidSupplementEntity card = supplement(BidSupplementRail.STRIPE, BidSupplementStatus.PAID);
        BidSupplementEntity mm = supplement(BidSupplementRail.PAWAPAY, BidSupplementStatus.REFUND_PENDING);
        when(cardRefunds.refund(card.getId())).thenReturn(true);
        when(mmRefunds.refund(mm.getId())).thenReturn(false);

        assertThat(service.refund(card.getId(), adminId, "Partage : part expéditeur").acted()).isTrue();
        assertThat(service.refund(mm.getId(), adminId, "relance").acted()).isFalse();

        verify(audit).log(eq("BID_SUPPLEMENT"), eq(card.getId()), eq("BID_SUPPLEMENT_ADMIN_REFUND_DECIDED"),
                eq(adminId), any());
        verify(alerts).resolveOpen("SUPP_UNSETTLED_" + card.getId());
        verify(alerts, never()).resolveOpen("SUPP_UNSETTLED_" + mm.getId());
    }

    @Test
    void rembourser_apresVersement_409SansInversion() {
        for (BidSupplementStatus status : List.of(BidSupplementStatus.PAYOUT_PENDING, BidSupplementStatus.PAID_OUT)) {
            BidSupplementEntity s = supplement(BidSupplementRail.STRIPE, status);
            YadonyBusinessException e = catchThrowableOfType(YadonyBusinessException.class,
                    () -> service.refund(s.getId(), adminId, "x"));
            assertThat(e.getErrorCode()).isEqualTo("supplement-already-paid-out");
        }
        BidSupplementEntity declined = supplement(BidSupplementRail.PAWAPAY, BidSupplementStatus.DECLINED);
        assertThat(catchThrowableOfType(YadonyBusinessException.class,
                () -> service.refund(declined.getId(), adminId, "x")).getErrorCode())
                .isEqualTo("supplement-not-refundable");
        verifyNoInteractions(cardRefunds, mmRefunds, audit);
    }

    @Test
    void especes_rienAVerserNiARembourser_422() {
        BidSupplementEntity s = supplement(BidSupplementRail.CASH, BidSupplementStatus.PAID);

        assertThat(catchThrowableOfType(YadonyBusinessException.class,
                () -> service.payout(s.getId(), adminId, "x")).getErrorCode())
                .isEqualTo("supplement-cash-no-money-held");
        assertThat(catchThrowableOfType(YadonyBusinessException.class,
                () -> service.refund(s.getId(), adminId, "x")).getErrorCode())
                .isEqualTo("supplement-cash-no-money-held");
    }

    @Test
    void supplementInconnu_404() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(catchThrowableOfType(YadonyBusinessException.class,
                () -> service.payout(unknown, adminId, "x")).getErrorCode()).isEqualTo("supplement-not-found");
    }

    private static void setPhotoPurgedAt(BidSupplementEntity s, LocalDateTime at) {
        try {
            java.lang.reflect.Field f = BidSupplementEntity.class.getDeclaredField("photoPurgedAt");
            f.setAccessible(true);
            f.set(s, at);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
