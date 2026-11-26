package com.yadony.api.matching.reception;

import com.yadony.api.auth.DeclaredPhoneHasher;
import com.yadony.api.auth.DeclaredPhoneLevel;
import com.yadony.api.auth.DeclaredPhoneResolver;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.PhoneLookupService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.RecipientTrust;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rattrapage avec le VRAI {@link PhoneLookupService} : un numéro « Vérifié par Yadony » contesté
 * (propriétaire SMS ailleurs, deux comptes validés) ne rattache aucun colis, comme le chemin aller.
 */
@DisplayName("ReceptionLinker#catchUp : précédence du numéro validé par Yadony")
class ReceptionCatchUpPrecedenceTest {

    private static final String PHONE = "+224621234567";

    private final DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("cle-de-test");
    private final FirebaseContactService firebase = mock(FirebaseContactService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final BidRecipientLinkRepository links = mock(BidRecipientLinkRepository.class);
    private final BlockVisibility block = mock(BlockVisibility.class);
    private ReceptionLinker linker;
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        PhoneLookupService lookup = new PhoneLookupService(firebase, users, hasher, new DeclaredPhoneResolver(hasher));
        linker = new ReceptionLinker(mock(BidRepository.class), mock(AnnouncementRepository.class), users, links,
                firebase, block, mock(NotificationDispatcher.class), mock(AuditService.class),
                mock(RecipientTrust.class), mock(DesignatedRecipientNaming.class), lookup);
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setSenderId(UUID.randomUUID());
        bid.setTrackingToken("t");
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setRecipientPhone(PHONE);
        when(links.findCatchUpCandidates(any(), any(), any())).thenReturn(List.of(bid));
        when(links.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private UserEntity yadony(String uid) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirebaseUid(uid);
        u.setDeclaredPhoneHash(hasher.hash(PHONE));
        u.setDeclaredPhoneLevel(DeclaredPhoneLevel.YADONY_VERIFIED);
        return u;
    }

    private void claimsFor(String... uids) {
        when(firebase.getContact(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new FirebaseContactService.Contact(null, null, PHONE));
        when(firebase.getContacts(any())).thenAnswer(inv -> {
            Map<String, FirebaseContactService.Contact> out = new HashMap<>();
            for (String uid : uids) {
                out.put(uid, new FirebaseContactService.Contact(null, null, PHONE));
            }
            return out;
        });
    }

    @Test
    void soleYadonyAccount_linksTheParcel() {
        UserEntity me = yadony("uid-a");
        when(firebase.findPhoneOwner(PHONE)).thenReturn(FirebaseContactService.PhoneOwnerLookup.notFound());
        when(users.findTop3ByDeclaredPhoneHashAndDeclaredPhoneLevel(hasher.hash(PHONE), DeclaredPhoneLevel.YADONY_VERIFIED))
                .thenReturn(List.of(me));
        claimsFor("uid-a");

        assertThat(linker.catchUp(me)).isEqualTo(1);
    }

    @Test
    void twoYadonyAccountsOnSameNumber_linkNothing() {
        UserEntity a = yadony("uid-a");
        UserEntity b = yadony("uid-b");
        when(firebase.findPhoneOwner(PHONE)).thenReturn(FirebaseContactService.PhoneOwnerLookup.notFound());
        when(users.findTop3ByDeclaredPhoneHashAndDeclaredPhoneLevel(hasher.hash(PHONE), DeclaredPhoneLevel.YADONY_VERIFIED))
                .thenReturn(List.of(a, b));
        claimsFor("uid-a", "uid-b");

        assertThat(linker.catchUp(a)).isZero();
        assertThat(linker.catchUp(b)).isZero();
        verify(links, never()).save(any());
    }

    @Test
    void smsOwnerElsewhere_linksNothingForTheYadonyAccount() {
        UserEntity me = yadony("uid-a");
        UserEntity smsOwner = new UserEntity();
        ReflectionTestUtils.setField(smsOwner, "id", UUID.randomUUID());
        smsOwner.setFirebaseUid("uid-sms");
        when(firebase.findPhoneOwner(PHONE)).thenReturn(FirebaseContactService.PhoneOwnerLookup.found("uid-sms"));
        when(users.findByFirebaseUid("uid-sms")).thenReturn(Optional.of(smsOwner));
        claimsFor("uid-a");

        assertThat(linker.catchUp(me)).isZero();
        verify(links, never()).save(any());
    }

    @Test
    void trustedRecipientOnAlreadyHandedOverParcel_publishesLinkConfirmedEvent() {
        UserEntity me = yadony("uid-a");
        bid.setStatus(BidStatus.HANDED_OVER);
        RecipientTrust trust = mock(RecipientTrust.class);
        when(trust.isTrusted(any(), any())).thenReturn(true);
        linker = new ReceptionLinker(mock(BidRepository.class), mock(AnnouncementRepository.class), users, links,
                firebase, block, mock(NotificationDispatcher.class), mock(AuditService.class),
                trust, mock(DesignatedRecipientNaming.class),
                new PhoneLookupService(firebase, users, hasher, new DeclaredPhoneResolver(hasher)));
        List<Object> published = new java.util.ArrayList<>();
        linker.setEventPublisher(published::add);
        when(firebase.findPhoneOwner(PHONE)).thenReturn(FirebaseContactService.PhoneOwnerLookup.notFound());
        when(users.findTop3ByDeclaredPhoneHashAndDeclaredPhoneLevel(hasher.hash(PHONE), DeclaredPhoneLevel.YADONY_VERIFIED))
                .thenReturn(List.of(me));
        claimsFor("uid-a");

        assertThat(linker.catchUp(me)).isEqualTo(1);

        assertThat(published).containsExactly(
                new com.yadony.api.matching.events.RecipientLinkConfirmedEvent(bid.getId()));
    }
}
