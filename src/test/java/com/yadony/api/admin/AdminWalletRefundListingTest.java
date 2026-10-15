package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminWalletRefundRequestResponse;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.wallet.WalletRefundRequestEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestStatus;
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
        var page = new AdminWalletRefundListing(users).describe(new PageImpl<>(List.of()));
        assertThat(page.getContent()).isEmpty();
        verify(users, never()).findAllByIdIncludingDeleted(any());
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
