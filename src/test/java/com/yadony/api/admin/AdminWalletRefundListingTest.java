package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminWalletRefundRequestResponse;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.wallet.WalletRefundItemStatus;
import com.yadony.api.payments.wallet.WalletRefundRequestEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestItemEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestItemRepository;
import com.yadony.api.payments.wallet.WalletRefundRequestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminWalletRefundListingTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 14, 18, 29, 41);

    @Test
    void requestAtDeletion_isAccountDeletion_requestLongBefore_isWallet() {
        UserEntity user = new UserEntity();
        user.setDeletedAt(T);

        assertThat(AdminWalletRefundListing.origin(request(T.minusSeconds(2)), user))
                .isEqualTo(AdminWalletRefundRequestResponse.ORIGIN_ACCOUNT_DELETION);
        assertThat(AdminWalletRefundListing.origin(request(T.plusDays(2)), user))
                .isEqualTo(AdminWalletRefundRequestResponse.ORIGIN_ACCOUNT_DELETION);
        assertThat(AdminWalletRefundListing.origin(request(T.minusDays(3)), user))
                .isEqualTo(AdminWalletRefundRequestResponse.ORIGIN_WALLET);
    }

    @Test
    void deletionRequestWins_overFinalization_forTheThirtyDayFlow() {
        UserEntity user = new UserEntity();
        user.setDeletionRequestedAt(T.toInstant(ZoneOffset.UTC));
        user.setDeletedAt(T.plusDays(30));

        assertThat(AdminWalletRefundListing.origin(request(T.plusSeconds(1)), user))
                .isEqualTo(AdminWalletRefundRequestResponse.ORIGIN_ACCOUNT_DELETION);
    }

    @Test
    void activeOrUnknownUser_isWallet_andNameFallsBackToNull() {
        UserEntity active = new UserEntity();
        assertThat(AdminWalletRefundListing.origin(request(T), active))
                .isEqualTo(AdminWalletRefundRequestResponse.ORIGIN_WALLET);
        assertThat(AdminWalletRefundListing.origin(request(T), null))
                .isEqualTo(AdminWalletRefundRequestResponse.ORIGIN_WALLET);
        assertThat(AdminWalletRefundListing.displayName(null)).isNull();
        assertThat(AdminWalletRefundListing.displayName(active)).isNull();
        active.setFirstName("Awa");
        assertThat(AdminWalletRefundListing.displayName(active)).isEqualTo("Awa");
    }

    @Test
    void emptyPage_readsNoUser() {
        UserRepository users = mock(UserRepository.class);
        WalletRefundRequestItemRepository items = mock(WalletRefundRequestItemRepository.class);
        var page = new AdminWalletRefundListing(users, items).describe(new PageImpl<>(List.of()));
        assertThat(page.getContent()).isEmpty();
        verify(users, never()).findAllByIdIncludingDeleted(any());
        verify(items, never()).findByRefundRequestIdIn(any());
    }

    @Test
    @DisplayName("frais = somme des items hors FAILED, nul sans item ; montant versé et note repris")
    void describe_sumsFeesOfNonFailedItems_andCarriesPaidAmountAndNote() {
        UserRepository users = mock(UserRepository.class);
        WalletRefundRequestItemRepository items = mock(WalletRefundRequestItemRepository.class);
        WalletRefundRequestEntity withItems = request(T);
        withId(withItems);
        withItems.setCurrency("XOF");
        withItems.setAmount(new BigDecimal("500"));
        withItems.setStatus(WalletRefundRequestStatus.RESOLVED);
        withItems.setPaidAmount(new BigDecimal("487"));
        withItems.setResolutionNote("Versé par Wave, frais opérateur");
        WalletRefundRequestEntity withoutItems = request(T);
        withId(withoutItems);
        when(users.findAllByIdIncludingDeleted(any())).thenReturn(List.of());
        when(items.findByRefundRequestIdIn(any())).thenReturn(List.of(
                item(withItems.getId(), "10", WalletRefundItemStatus.REFUNDED),
                item(withItems.getId(), "3", WalletRefundItemStatus.PROCESSING),
                item(withItems.getId(), "5", WalletRefundItemStatus.FAILED),
                item(withItems.getId(), null, WalletRefundItemStatus.PENDING)));

        var page = new AdminWalletRefundListing(users, items)
                .describe(new PageImpl<>(List.of(withItems, withoutItems)));

        AdminWalletRefundRequestResponse first = page.getContent().get(0);
        assertThat(first.feeAmount()).isEqualByComparingTo("13");
        assertThat(first.paidAmount()).isEqualByComparingTo("487");
        assertThat(first.resolutionNote()).isEqualTo("Versé par Wave, frais opérateur");
        AdminWalletRefundRequestResponse second = page.getContent().get(1);
        assertThat(second.feeAmount()).isNull();
        assertThat(second.paidAmount()).isNull();
        assertThat(second.resolutionNote()).isNull();
    }

    private static WalletRefundRequestItemEntity item(UUID requestId, String fee, WalletRefundItemStatus status) {
        WalletRefundRequestItemEntity i = new WalletRefundRequestItemEntity();
        i.setRefundRequestId(requestId);
        i.setAmount(new BigDecimal("100"));
        i.setFeeAmount(fee == null ? null : new BigDecimal(fee));
        i.setStatus(status);
        return i;
    }

    private static void withId(WalletRefundRequestEntity entity) {
        try {
            var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static WalletRefundRequestEntity request(LocalDateTime requestedAt) {
        WalletRefundRequestEntity r = new WalletRefundRequestEntity();
        r.setUserId(UUID.randomUUID());
        r.setCurrency("EUR");
        r.setAmount(new BigDecimal("9.05"));
        r.setStatus(WalletRefundRequestStatus.REFUNDED);
        r.setRequestedAt(requestedAt);
        return r;
    }

}
