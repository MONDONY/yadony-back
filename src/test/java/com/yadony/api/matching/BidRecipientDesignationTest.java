package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.RecipientAccount;
import com.yadony.api.common.RecipientAccountResolver;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Désignation du destinataire d'un colis")
class BidRecipientDesignationTest {

    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private RecipientAccountResolver resolver;
    private AuditService audit;
    private BidRecipientDesignation designation;
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        resolver = mock(RecipientAccountResolver.class);
        audit = mock(AuditService.class);
        designation = new BidRecipientDesignation(resolver, audit);
        bid = new BidEntity();
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status,
                                      String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getErrorCode()).isEqualTo(code);
        });
    }

    @Test
    void nameAndPhoneOnly_unchanged() {
        designation.apply(bid, senderId, travelerId, "Awa", "+221771234567", null);

        assertThat(bid.getRecipientName()).isEqualTo("Awa");
        assertThat(bid.getRecipientPhone()).isEqualTo("+221771234567");
        assertThat(bid.getRecipientUserId()).isNull();
        verifyNoInteractions(resolver);
    }

    @Test
    void usernameOnly_setsAccount_nameWithAt_phoneNull() {
        when(resolver.resolve(senderId, "awa.diallo")).thenReturn(new RecipientAccount(accountId, "awa.diallo"));

        designation.apply(bid, senderId, travelerId, null, null, "awa.diallo");

        assertThat(bid.getRecipientUserId()).isEqualTo(accountId);
        assertThat(bid.getRecipientName()).isEqualTo("@awa.diallo");
        assertThat(bid.getRecipientPhone()).isNull();
    }

    @Test
    void blankFieldsAlongsideUsername_areIgnored() {
        when(resolver.resolve(senderId, "awa.diallo")).thenReturn(new RecipientAccount(accountId, "awa.diallo"));

        designation.apply(bid, senderId, travelerId, " ", "", "awa.diallo");

        assertThat(bid.getRecipientUserId()).isEqualTo(accountId);
    }

    @Test
    void usernameWithNameOrPhone_conflict_noWrite() {
        assertProblem(() -> designation.apply(bid, senderId, travelerId, "Awa", null, "awa.diallo"),
                HttpStatus.UNPROCESSABLE_ENTITY, "recipient-mode-conflict");
        assertProblem(() -> designation.apply(bid, senderId, travelerId, null, "+221771234567", "awa.diallo"),
                HttpStatus.UNPROCESSABLE_ENTITY, "recipient-mode-conflict");

        assertThat(bid.getRecipientUserId()).isNull();
        assertThat(bid.getRecipientName()).isNull();
        verifyNoInteractions(resolver);
    }

    @Test
    void usernameOfTheTraveler_refused() {
        when(resolver.resolve(senderId, "voyageur")).thenReturn(new RecipientAccount(travelerId, "voyageur"));

        assertProblem(() -> designation.apply(bid, senderId, travelerId, null, null, "voyageur"),
                HttpStatus.UNPROCESSABLE_ENTITY, "recipient-is-traveler");
        assertThat(bid.getRecipientUserId()).isNull();
    }

    @Test
    void resolverFailure_propagates_noWrite() {
        when(resolver.resolve(any(), any())).thenThrow(YadonyBusinessException.localized(
                HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));

        assertProblem(() -> designation.apply(bid, senderId, travelerId, null, null, "inconnu"),
                HttpStatus.NOT_FOUND, "user-not-found");
        assertThat(bid.getRecipientUserId()).isNull();
    }

    @Test
    void noRecipientAtAll_leavesBidWithoutRecipient() {
        designation.apply(bid, senderId, travelerId, null, null, null);

        assertThat(bid.getRecipientName()).isNull();
        assertThat(bid.getRecipientPhone()).isNull();
        assertThat(bid.getRecipientUserId()).isNull();
    }

    @Test
    void requireRecipient_acceptsNamePhoneOrUsername_refusesTheRest() {
        designation.requireRecipient("Awa", "+221771234567", null);
        designation.requireRecipient(null, null, "awa.diallo");

        assertProblem(() -> designation.requireRecipient(null, null, null),
                HttpStatus.UNPROCESSABLE_ENTITY, "recipient-required");
        assertProblem(() -> designation.requireRecipient("Awa", " ", null),
                HttpStatus.UNPROCESSABLE_ENTITY, "recipient-required");
    }

    @Test
    void audit_logsAccountId_neverPhone_onlyWhenDesignatedByUsername() {
        designation.audit(bid, senderId);
        verify(audit, never()).log(any(), any(), any(), any(), any());

        bid.setRecipientUserId(accountId);
        designation.audit(bid, senderId);
        verify(audit).log("BID", bid.getId(), "RECIPIENT_DESIGNATED_BY_USERNAME", senderId,
                Map.of("recipientUserId", accountId.toString()));
    }
}
