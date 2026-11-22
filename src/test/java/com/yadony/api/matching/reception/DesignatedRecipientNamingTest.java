package com.yadony.api.matching.reception;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DesignatedRecipientNamingTest {

    @Mock FirebaseContactService firebaseContact;
    @Mock BidRepository bidRepository;

    private UserEntity account(boolean hidePhone) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirebaseUid("uid-a");
        u.setFirstName("Binta");
        u.setLastName("Sow");
        u.setHidePhoneNumber(hidePhone);
        return u;
    }

    private BidEntity designated(UserEntity a) {
        BidEntity b = new BidEntity();
        b.setRecipientUserId(a.getId());
        b.setRecipientName("@binta.sow");
        return b;
    }

    private DesignatedRecipientNaming naming() {
        return new DesignatedRecipientNaming(firebaseContact, bidRepository);
    }

    @Test
    void apply_visiblePhone_setsPublicNameAndPhone() {
        UserEntity a = account(false);
        BidEntity b = designated(a);
        when(firebaseContact.getContact("uid-a"))
                .thenReturn(new FirebaseContactService.Contact("+221 78 111 22 33", null));

        naming().apply(b, a);

        assertThat(b.getRecipientName()).isEqualTo(a.publicDisplayName());
        assertThat(b.getRecipientPhone()).isEqualTo("+221781112233");
        verify(bidRepository).save(b);
    }

    @Test
    void apply_declaredPhoneOnly_isCopiedWhenNotHidden() {
        UserEntity a = account(false);
        BidEntity b = designated(a);
        when(firebaseContact.getContact("uid-a"))
                .thenReturn(new FirebaseContactService.Contact(null, null, "+225 07 11 22 33 44"));

        naming().apply(b, a);

        assertThat(b.getRecipientName()).isEqualTo(a.publicDisplayName());
        assertThat(b.getRecipientPhone()).isEqualTo("+2250711223344");
        verify(bidRepository).save(b);
    }

    @Test
    void apply_verifiedPhoneWinsOverDeclaredPhone() {
        UserEntity a = account(false);
        BidEntity b = designated(a);
        when(firebaseContact.getContact("uid-a"))
                .thenReturn(new FirebaseContactService.Contact("+221 78 111 22 33", null, "+225 07 11 22 33 44"));

        naming().apply(b, a);

        assertThat(b.getRecipientPhone()).isEqualTo("+221781112233");
    }

    @Test
    void apply_declaredPhone_isNeverCopiedWhenHidden() {
        UserEntity a = account(true);
        BidEntity b = designated(a);

        naming().apply(b, a);

        assertThat(b.getRecipientPhone()).isNull();
        verify(firebaseContact, never()).getContact(any());
    }

    @Test
    void apply_noPhoneAtAll_leavesPhoneEmpty() {
        UserEntity a = account(false);
        BidEntity b = designated(a);
        when(firebaseContact.getContact("uid-a")).thenReturn(FirebaseContactService.Contact.EMPTY);

        naming().apply(b, a);

        assertThat(b.getRecipientPhone()).isNull();
        assertThat(b.getRecipientName()).isEqualTo(a.publicDisplayName());
    }

    @Test
    void apply_hiddenPhone_neverReadsNorCopiesPhone() {
        UserEntity a = account(true);
        BidEntity b = designated(a);

        naming().apply(b, a);

        assertThat(b.getRecipientName()).isEqualTo(a.publicDisplayName());
        assertThat(b.getRecipientPhone()).isNull();
        verify(firebaseContact, never()).getContact(any());
    }

    @Test
    void apply_otherAccountOrNumberParcel_untouched() {
        UserEntity a = account(false);
        BidEntity byNumber = new BidEntity();
        byNumber.setRecipientName("Fatou");
        BidEntity other = designated(account(false));

        naming().apply(byNumber, a);
        naming().apply(other, a);

        assertThat(byNumber.getRecipientName()).isEqualTo("Fatou");
        assertThat(other.getRecipientName()).isEqualTo("@binta.sow");
        verify(bidRepository, never()).save(any());
    }
}
