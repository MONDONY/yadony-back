package com.yadony.api.addressbook.invitation;

import com.yadony.api.addressbook.invitation.dto.CreateRecipientInvitationRequest;
import com.yadony.api.addressbook.invitation.dto.IncomingInvitationDto;
import com.yadony.api.addressbook.invitation.dto.InvitationSentResponse;
import com.yadony.api.addressbook.recipient.RecipientEntity;
import com.yadony.api.addressbook.recipient.RecipientRepository;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipientInvitationServiceTest {

    @Mock RecipientInvitationRepository repository;
    @Mock RecipientRepository recipientRepository;
    @Mock UserRepository userRepository;
    @Mock FirebaseContactService firebaseContact;
    @Mock BlockVisibility blockVisibility;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    private RecipientInvitationService service;

    private static final String PHONE = "+221771234512";
    private final UserEntity inviter = user("uid-inviter", "Awa", "Diallo");
    private final UserEntity invitee = user("uid-invitee", "Fatou", "Sow");

    @BeforeEach
    void setUp() {
        service = new RecipientInvitationService(repository, recipientRepository, userRepository, firebaseContact,
                blockVisibility, notificationDispatcher, auditService, eventPublisher);
        lenient().when(userRepository.findByFirebaseUid("uid-inviter")).thenReturn(Optional.of(inviter));
        lenient().when(userRepository.findByFirebaseUid("uid-invitee")).thenReturn(Optional.of(invitee));
        lenient().when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
        lenient().when(repository.saveAndFlush(any())).thenAnswer(inv -> withId(inv.getArgument(0)));
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(recipientRepository.save(any())).thenAnswer(inv -> {
            RecipientEntity r = inv.getArgument(0);
            if (r.getId() == null) {
                ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            }
            return r;
        });
        lenient().when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    private static UserEntity user(String uid, String first, String last) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirebaseUid(uid);
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    private static RecipientInvitationEntity withId(RecipientInvitationEntity e) {
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        return e;
    }

    private RecipientInvitationEntity pendingFor(UUID inviteeId) {
        return withId(new RecipientInvitationEntity(inviter.getId(), inviteeId, InvitationChannel.PHONE,
                InvitationTargets.hash(PHONE), InvitationTargets.maskPhone(PHONE)));
    }

    // ── send ────────────────────────────────────────────────────────────────

    @Test
    void send_existingAccount_createsPendingWithInviteeAndPublishesEvent() {
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.of("uid-invitee"));

        InvitationSentResponse response = service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null));

        assertThat(response).isEqualTo(InvitationSentResponse.SENT);
        ArgumentCaptor<RecipientInvitationEntity> saved = ArgumentCaptor.forClass(RecipientInvitationEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getInviteeUserId()).isEqualTo(invitee.getId());
        assertThat(saved.getValue().getTargetHash()).isEqualTo(InvitationTargets.hash(PHONE));
        assertThat(saved.getValue().getMaskedTarget()).isEqualTo("+221 •• •• •• 12");
        verify(eventPublisher).publishEvent(any(RecipientInvitationCreatedEvent.class));
        verify(auditService).log(eq("RECIPIENT_INVITATION"), any(), eq("RECIPIENT_INVITATION_SENT"),
                eq(inviter.getId()), eq(Map.of("channel", "PHONE")));
    }

    @Test
    void send_noAccount_createsPendingWithoutInviteeNorEvent() {
        when(firebaseContact.findUidByEmail("fatou@gmail.com")).thenReturn(Optional.empty());

        assertThat(service.send("uid-inviter", new CreateRecipientInvitationRequest(null, " Fatou@Gmail.com ")))
                .isEqualTo(InvitationSentResponse.SENT);

        ArgumentCaptor<RecipientInvitationEntity> saved = ArgumentCaptor.forClass(RecipientInvitationEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getInviteeUserId()).isNull();
        assertThat(saved.getValue().getChannel()).isEqualTo(InvitationChannel.EMAIL);
        assertThat(saved.getValue().getMaskedTarget()).isEqualTo("f••••@gmail.com");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void send_self_createsNothingButLooksUp() {
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.of("uid-inviter"));

        assertThat(service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null)))
                .isEqualTo(InvitationSentResponse.SENT);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void send_duplicate_createsNothingButLooksUp() {
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.of("uid-invitee"));
        when(repository.existsByInviterUserIdAndTargetHashAndStatusIn(eq(inviter.getId()),
                eq(InvitationTargets.hash(PHONE)), anyCollection())).thenReturn(true);

        assertThat(service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null)))
                .isEqualTo(InvitationSentResponse.SENT);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void send_blocked_createsNothing() {
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.of("uid-invitee"));
        when(blockVisibility.isHidden(inviter.getId(), invitee.getId())).thenReturn(true);

        assertThat(service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null)))
                .isEqualTo(InvitationSentResponse.SENT);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void send_alreadyAcceptedByAnotherChannel_createsNothing() {
        when(firebaseContact.findUidByEmail("fatou@gmail.com")).thenReturn(Optional.of("uid-invitee"));
        when(repository.existsByInviterUserIdAndInviteeUserIdAndStatus(
                inviter.getId(), invitee.getId(), InvitationStatus.ACCEPTED)).thenReturn(true);

        assertThat(service.send("uid-inviter", new CreateRecipientInvitationRequest(null, "fatou@gmail.com")))
                .isEqualTo(InvitationSentResponse.SENT);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void send_raceOnUniqueIndex_stillAnswersSent() {
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("uq")).when(repository).saveAndFlush(any());

        assertThat(service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null)))
                .isEqualTo(InvitationSentResponse.SENT);
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void send_quotaReached_returns429() {
        when(repository.countByInviterUserIdAndCreatedAtAfter(eq(inviter.getId()), any())).thenReturn(20L);

        assertThatThrownBy(() -> service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getErrorCode()).isEqualTo("recipient-invitation-quota");
                });
    }

    @Test
    void send_invalidTargets_return422() {
        for (CreateRecipientInvitationRequest bad : List.of(
                new CreateRecipientInvitationRequest(null, null),
                new CreateRecipientInvitationRequest(PHONE, "a@b.co"),
                new CreateRecipientInvitationRequest("771234512", null),
                new CreateRecipientInvitationRequest(null, "pas-un-email"),
                new CreateRecipientInvitationRequest(" ", " "))) {
            assertThatThrownBy(() -> service.send("uid-inviter", bad))
                    .isInstanceOfSatisfying(YadonyBusinessException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }
        assertThatThrownBy(() -> service.send("uid-inviter", null)).isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void send_unknownUser_404() {
        assertThatThrownBy(() -> service.send("uid-ghost", new CreateRecipientInvitationRequest(PHONE, null)))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    // ── sent ────────────────────────────────────────────────────────────────

    @Test
    void sent_neverRevealsDecline() {
        RecipientInvitationEntity declined = pendingFor(invitee.getId());
        declined.respond(InvitationStatus.DECLINED, null);
        RecipientInvitationEntity accepted = pendingFor(invitee.getId());
        accepted.respond(InvitationStatus.ACCEPTED, null);
        when(repository.findByInviterUserIdAndStatusInOrderByCreatedAtDesc(eq(inviter.getId()), anyCollection()))
                .thenReturn(List.of(declined, accepted));

        var sent = service.sent("uid-inviter");

        assertThat(sent).extracting("status").containsExactly("PENDING", "ACCEPTED");
        assertThat(sent.get(0).maskedTarget()).isEqualTo("+221 •• •• •• 12");
        assertThat(sent.get(0).channel()).isEqualTo("PHONE");
    }

    // ── incoming + rattrapage ───────────────────────────────────────────────

    @Test
    void incoming_catchesUpByPhoneOrEmailHash_andListsWithInviterFirstName() {
        when(firebaseContact.getContact("uid-invitee"))
                .thenReturn(new FirebaseContactService.Contact(PHONE, "Fatou@Gmail.com"));
        RecipientInvitationEntity orphan = pendingFor(null);
        RecipientInvitationEntity selfSent = withId(new RecipientInvitationEntity(invitee.getId(), null,
                InvitationChannel.PHONE, InvitationTargets.hash(PHONE), "x"));
        UserEntity blocker = user("uid-b", "Bob", null);
        RecipientInvitationEntity fromBlocked = withId(new RecipientInvitationEntity(blocker.getId(), null,
                InvitationChannel.EMAIL, InvitationTargets.hash("fatou@gmail.com"), "x"));
        lenient().when(blockVisibility.isHidden(blocker.getId(), invitee.getId())).thenReturn(true);
        when(repository.findByInviteeUserIdIsNullAndStatusAndTargetHashIn(eq(InvitationStatus.PENDING),
                eq(Set.of(InvitationTargets.hash(PHONE), InvitationTargets.hash("fatou@gmail.com")))))
                .thenReturn(List.of(orphan, selfSent, fromBlocked));
        when(repository.findByInviteeUserIdAndStatusInOrderByCreatedAtDesc(eq(invitee.getId()), anyCollection()))
                .thenAnswer(inv -> List.of(orphan));
        when(userRepository.findAllById(any())).thenReturn(List.of(inviter));

        List<IncomingInvitationDto> incoming = service.incoming("uid-invitee");

        assertThat(orphan.getInviteeUserId()).isEqualTo(invitee.getId());
        assertThat(selfSent.getInviteeUserId()).isNull();
        assertThat(fromBlocked.getInviteeUserId()).isNull();
        assertThat(incoming).singleElement().satisfies(dto -> {
            assertThat(dto.inviterFirstName()).isEqualTo("Awa");
            assertThat(dto.status()).isEqualTo("PENDING");
        });
    }

    @Test
    void incoming_withoutContact_skipsCatchUp_andHidesBlockedInviters() {
        when(firebaseContact.getContact("uid-invitee")).thenReturn(FirebaseContactService.Contact.EMPTY);
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        when(repository.findByInviteeUserIdAndStatusInOrderByCreatedAtDesc(eq(invitee.getId()), anyCollection()))
                .thenReturn(List.of(invitation));
        when(blockVisibility.isHidden(invitee.getId(), inviter.getId())).thenReturn(true);

        assertThat(service.incoming("uid-invitee")).isEmpty();
        verify(repository, never()).findByInviteeUserIdIsNullAndStatusAndTargetHashIn(any(), any());
    }

    // ── accept ──────────────────────────────────────────────────────────────

    @Test
    void accept_createsAddressBookEntryLinksAndNotifiesInviter() {
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
        when(firebaseContact.getContact("uid-invitee")).thenReturn(new FirebaseContactService.Contact(PHONE, null));

        IncomingInvitationDto dto = service.accept(invitation.getId(), "uid-invitee");

        assertThat(dto.status()).isEqualTo("ACCEPTED");
        assertThat(dto.inviterFirstName()).isEqualTo("Awa");
        ArgumentCaptor<RecipientEntity> entry = ArgumentCaptor.forClass(RecipientEntity.class);
        verify(recipientRepository).save(entry.capture());
        assertThat(entry.getValue().getUserId()).isEqualTo(inviter.getId());
        assertThat(entry.getValue().getFullName()).isEqualTo("Fatou Sow");
        assertThat(entry.getValue().getPhoneE164()).isEqualTo(PHONE);
        assertThat(entry.getValue().getCountry()).isEqualTo("SN");
        assertThat(invitation.getRecipientId()).isEqualTo(entry.getValue().getId());
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(invitation.getRespondedAt()).isNotNull();
        verify(notificationDispatcher).notifyUser(inviter.getId(), "Destinataire ajouté",
                "Fatou a accepté : ses colis lui seront rattachés.",
                Map.of("type", "RECIPIENT_INVITATION_ACCEPTED", "invitationId", invitation.getId().toString()));
        verify(auditService).log(eq("RECIPIENT_INVITATION"), eq(invitation.getId()),
                eq("RECIPIENT_INVITATION_ACCEPTED"), eq(invitee.getId()), any());
    }

    @Test
    void accept_updatesExistingEntryWithSameNumber() {
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
        when(firebaseContact.getContact("uid-invitee")).thenReturn(new FirebaseContactService.Contact(PHONE, null));
        RecipientEntity other = new RecipientEntity();
        other.setPhoneE164("+221700000000");
        RecipientEntity noPhone = new RecipientEntity();
        RecipientEntity existing = new RecipientEntity();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setUserId(inviter.getId());
        existing.setFullName("Tata");
        existing.setPhoneE164("+221 77 123 45 12");
        existing.setCountry("SN");
        existing.setDefault(true);
        when(recipientRepository.findByUserIdOrderByUpdatedAtDesc(inviter.getId()))
                .thenReturn(List.of(other, noPhone, existing));

        service.accept(invitation.getId(), "uid-invitee");

        assertThat(existing.getFullName()).isEqualTo("Fatou Sow");
        assertThat(existing.getPhoneE164()).isEqualTo(PHONE);
        assertThat(existing.isDefault()).isTrue();
        assertThat(invitation.getRecipientId()).isEqualTo(existing.getId());
        verify(auditService, never()).log(eq("RECIPIENT"), any(), any(), any(), any());
    }

    @Test
    void accept_withoutNames_usesFallbackFullName() {
        invitee.setFirstName(null);
        invitee.setLastName(" ");
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
        when(firebaseContact.getContact("uid-invitee")).thenReturn(new FirebaseContactService.Contact(PHONE, null));

        service.accept(invitation.getId(), "uid-invitee");

        ArgumentCaptor<RecipientEntity> entry = ArgumentCaptor.forClass(RecipientEntity.class);
        verify(recipientRepository).save(entry.capture());
        assertThat(entry.getValue().getFullName()).isEqualTo("Yadony");
    }

    @Test
    void accept_withoutPhone_returns409() {
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
        when(firebaseContact.getContact("uid-invitee")).thenReturn(new FirebaseContactService.Contact(null, "f@x.com"));

        assertThatThrownBy(() -> service.accept(invitation.getId(), "uid-invitee"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("recipient-invitation-phone-required");
                });
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void accept_alreadyAccepted_isIdempotent() {
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        invitation.respond(InvitationStatus.ACCEPTED, null);
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));

        assertThat(service.accept(invitation.getId(), "uid-invitee").status()).isEqualTo("ACCEPTED");
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }

    @Test
    void accept_notTheInvitee_orBlocked_orDeclined_is404() {
        RecipientInvitationEntity forSomeoneElse = pendingFor(UUID.randomUUID());
        when(repository.findById(forSomeoneElse.getId())).thenReturn(Optional.of(forSomeoneElse));
        assertThatThrownBy(() -> service.accept(forSomeoneElse.getId(), "uid-invitee"))
                .isInstanceOf(YadonyNotFoundException.class);

        RecipientInvitationEntity declined = pendingFor(invitee.getId());
        declined.respond(InvitationStatus.DECLINED, null);
        when(repository.findById(declined.getId())).thenReturn(Optional.of(declined));
        assertThatThrownBy(() -> service.accept(declined.getId(), "uid-invitee"))
                .isInstanceOf(YadonyNotFoundException.class);

        RecipientInvitationEntity fromBlocked = pendingFor(invitee.getId());
        when(repository.findById(fromBlocked.getId())).thenReturn(Optional.of(fromBlocked));
        when(blockVisibility.isHidden(invitee.getId(), inviter.getId())).thenReturn(true);
        assertThatThrownBy(() -> service.accept(fromBlocked.getId(), "uid-invitee"))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    // ── decline / revoke ────────────────────────────────────────────────────

    @Test
    void decline_marksDeclinedWithoutNotifyingInviter() {
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));

        assertThat(service.decline(invitation.getId(), "uid-invitee").status()).isEqualTo("DECLINED");
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
        verify(auditService).log(eq("RECIPIENT_INVITATION"), eq(invitation.getId()),
                eq("RECIPIENT_INVITATION_DECLINED"), eq(invitee.getId()), any());
    }

    @Test
    void revoke_byInviter_onAnyLiveStatus() {
        RecipientInvitationEntity declined = pendingFor(invitee.getId());
        declined.respond(InvitationStatus.DECLINED, null);
        when(repository.findById(declined.getId())).thenReturn(Optional.of(declined));

        service.revoke(declined.getId(), "uid-inviter");

        assertThat(declined.getStatus()).isEqualTo(InvitationStatus.REVOKED);
        verify(auditService).log(eq("RECIPIENT_INVITATION"), eq(declined.getId()),
                eq("RECIPIENT_INVITATION_REVOKED"), eq(inviter.getId()), eq(Map.of("side", "INVITER")));
    }

    @Test
    void revoke_byInvitee_onlyWhenAccepted() {
        RecipientInvitationEntity accepted = pendingFor(invitee.getId());
        accepted.respond(InvitationStatus.ACCEPTED, null);
        accepted.setRecipientId(UUID.randomUUID());
        when(repository.findById(accepted.getId())).thenReturn(Optional.of(accepted));

        service.revoke(accepted.getId(), "uid-invitee");
        assertThat(accepted.getStatus()).isEqualTo(InvitationStatus.REVOKED);
        verify(auditService).log(any(), any(), eq("RECIPIENT_INVITATION_REVOKED"), eq(invitee.getId()),
                eq(Map.of("side", "INVITEE")));

        RecipientInvitationEntity pending = pendingFor(invitee.getId());
        when(repository.findById(pending.getId())).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> service.revoke(pending.getId(), "uid-invitee"))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void revoke_alreadyRevokedOrUnknown_is404() {
        RecipientInvitationEntity revoked = pendingFor(invitee.getId());
        revoked.respond(InvitationStatus.REVOKED, null);
        when(repository.findById(revoked.getId())).thenReturn(Optional.of(revoked));
        assertThatThrownBy(() -> service.revoke(revoked.getId(), "uid-inviter"))
                .isInstanceOf(YadonyNotFoundException.class);

        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revoke(unknown, "uid-inviter"))
                .isInstanceOf(YadonyNotFoundException.class);
    }
}
