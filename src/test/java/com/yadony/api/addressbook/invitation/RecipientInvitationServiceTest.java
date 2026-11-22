package com.yadony.api.addressbook.invitation;

import com.yadony.api.addressbook.invitation.dto.CreateRecipientInvitationRequest;
import com.yadony.api.addressbook.invitation.dto.IncomingInvitationDto;
import com.yadony.api.addressbook.invitation.dto.InvitationSentResponse;
import com.yadony.api.addressbook.recipient.RecipientEntity;
import com.yadony.api.addressbook.recipient.RecipientRepository;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.PhoneLookupService;
import com.yadony.api.auth.PhoneMatch;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipientInvitationServiceTest {

    @Mock RecipientInvitationRepository repository;
    @Mock RecipientRepository recipientRepository;
    @Mock UserRepository userRepository;
    @Mock FirebaseContactService firebaseContact;
    @Mock PhoneLookupService phoneLookup;
    @Mock BlockVisibility blockVisibility;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock UsernameLookupService usernameLookup;

    private RecipientInvitationService service;

    private static final String PHONE = "+221771234512";
    private final UserEntity inviter = user("uid-inviter", "Awa", "Diallo");
    private final UserEntity invitee = user("uid-invitee", "Fatou", "Sow");

    @BeforeEach
    void setUp() {
        service = new RecipientInvitationService(repository, recipientRepository, userRepository, firebaseContact,
                blockVisibility, notificationDispatcher, auditService, eventPublisher, phoneLookup, usernameLookup);
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
        when(phoneLookup.findByPhone(PHONE)).thenReturn(new PhoneMatch.Found(invitee, PhoneMatch.Trust.SMS));

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
        when(phoneLookup.findByPhone(PHONE)).thenReturn(new PhoneMatch.Found(inviter, PhoneMatch.Trust.SMS));

        assertThat(service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null)))
                .isEqualTo(InvitationSentResponse.SENT);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void send_duplicate_createsNothingButLooksUp() {
        when(phoneLookup.findByPhone(PHONE)).thenReturn(new PhoneMatch.Found(invitee, PhoneMatch.Trust.SMS));
        when(repository.existsByInviterUserIdAndTargetHashAndStatusIn(eq(inviter.getId()),
                eq(InvitationTargets.hash(PHONE)), anyCollection())).thenReturn(true);

        assertThat(service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null)))
                .isEqualTo(InvitationSentResponse.SENT);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void send_blocked_createsNothing() {
        when(phoneLookup.findByPhone(PHONE)).thenReturn(new PhoneMatch.Found(invitee, PhoneMatch.Trust.SMS));
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
    void invitation_ambiguous_returnsNeutralSent() {
        when(phoneLookup.findByPhone(PHONE)).thenReturn(new PhoneMatch.Ambiguous());

        InvitationSentResponse response = service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null));

        // Même issue qu'un numéro inconnu : invitation en attente sans invité, aucun push.
        assertThat(response).isEqualTo(InvitationSentResponse.SENT);
        ArgumentCaptor<RecipientInvitationEntity> saved = ArgumentCaptor.forClass(RecipientInvitationEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getInviteeUserId()).isNull();
        verify(eventPublisher, never()).publishEvent(any(RecipientInvitationCreatedEvent.class));
    }

    @Test
    void invitation_declaredNumber_returnsNeutralSentWithoutInvitee() {
        when(phoneLookup.findByPhone(PHONE))
                .thenReturn(new PhoneMatch.Found(invitee, PhoneMatch.Trust.DECLARED));

        assertThat(service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null)))
                .isEqualTo(InvitationSentResponse.SENT);
        ArgumentCaptor<RecipientInvitationEntity> saved = ArgumentCaptor.forClass(RecipientInvitationEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getInviteeUserId()).isNull();
        verify(eventPublisher, never()).publishEvent(any(RecipientInvitationCreatedEvent.class));
    }

    @Test
    void invitation_yadonyVerifiedNumber_resolvesInvitee() {
        when(phoneLookup.findByPhone(PHONE))
                .thenReturn(new PhoneMatch.Found(invitee, PhoneMatch.Trust.YADONY));

        service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null));

        ArgumentCaptor<RecipientInvitationEntity> saved = ArgumentCaptor.forClass(RecipientInvitationEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getInviteeUserId()).isEqualTo(invitee.getId());
    }

    @Test
    void send_raceOnUniqueIndex_stillAnswersSent() {
        when(phoneLookup.findByPhone(PHONE)).thenReturn(new PhoneMatch.None());
        doThrow(new DataIntegrityViolationException("uq")).when(repository).saveAndFlush(any());
        when(repository.existsByInviterUserIdAndTargetHashAndStatusIn(eq(inviter.getId()), any(), any()))
                .thenReturn(false, true);

        assertThat(service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null)))
                .isEqualTo(InvitationSentResponse.SENT);
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void send_integrityViolationWithoutLiveInvitation_isNotSwallowed() {
        when(phoneLookup.findByPhone(PHONE)).thenReturn(new PhoneMatch.None());
        doThrow(new DataIntegrityViolationException("chk")).when(repository).saveAndFlush(any());

        assertThatThrownBy(() -> service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(eventPublisher, never()).publishEvent(any());
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

    // ── send : canal USERNAME ───────────────────────────────────────────────

    private static CreateRecipientInvitationRequest byUsername(String username) {
        return new CreateRecipientInvitationRequest(null, null, null, username);
    }

    @Test
    void send_username_createsPendingWithInviteeAndPublishesEvent() {
        invitee.setUsername("fatou.sow");
        when(usernameLookup.resolveVisible(inviter, "Fatou.Sow")).thenReturn(Optional.of(invitee));

        assertThat(service.send("uid-inviter", byUsername("Fatou.Sow"))).isEqualTo(InvitationSentResponse.SENT);

        ArgumentCaptor<RecipientInvitationEntity> saved = ArgumentCaptor.forClass(RecipientInvitationEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getInviteeUserId()).isEqualTo(invitee.getId());
        assertThat(saved.getValue().getChannel()).isEqualTo(InvitationChannel.USERNAME);
        assertThat(saved.getValue().getTargetHash()).isEqualTo(InvitationTargets.hash("u:" + invitee.getId()));
        assertThat(saved.getValue().getMaskedTarget()).isEqualTo("@fatou.sow");
        verify(eventPublisher).publishEvent(any(RecipientInvitationCreatedEvent.class));
        verify(auditService).log(eq("RECIPIENT_INVITATION"), any(), eq("RECIPIENT_INVITATION_SENT"),
                eq(inviter.getId()), eq(Map.of("channel", "USERNAME")));
        verify(firebaseContact, never()).findUidByPhone(any());
        verify(phoneLookup, never()).findByPhone(any());
        verify(firebaseContact, never()).findUidByEmail(any());
    }

    @Test
    void send_unknownOrHiddenUsername_404_nothingWritten() {
        when(usernameLookup.resolveVisible(inviter, "inconnu")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send("uid-inviter", byUsername("inconnu")))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("user-not-found");
                });
        verify(repository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void send_username_countsLookupQuotaBeforeResolving() {
        when(usernameLookup.resolveVisible(inviter, "inconnu")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send("uid-inviter", byUsername("inconnu")))
                .isInstanceOf(YadonyBusinessException.class);

        var order = inOrder(usernameLookup);
        order.verify(usernameLookup).beginLookup(inviter.getId(), "inconnu");
        order.verify(usernameLookup).resolveVisible(inviter, "inconnu");
    }

    @Test
    void send_username_lookupQuotaExhausted_429_withoutResolving() {
        doThrow(YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "recipient-lookup-quota",
                "problem.recipient-lookup-quota")).when(usernameLookup).beginLookup(inviter.getId(), "fatou.sow");

        assertThatThrownBy(() -> service.send("uid-inviter", byUsername("fatou.sow")))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getErrorCode()).isEqualTo("recipient-lookup-quota");
                });
        verify(usernameLookup, never()).resolveVisible(any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    private RecipientInvitationEntity usernameInvitation() {
        return withId(new RecipientInvitationEntity(inviter.getId(), invitee.getId(), InvitationChannel.USERNAME,
                InvitationTargets.hash("u:" + invitee.getId()), "@fatou.sow"));
    }

    @Test
    void usernameInvitation_acceptDeclineRevoke_work() {
        RecipientInvitationEntity toAccept = usernameInvitation();
        when(repository.findById(toAccept.getId())).thenReturn(Optional.of(toAccept));
        when(firebaseContact.getContact("uid-invitee")).thenReturn(new FirebaseContactService.Contact(PHONE, null));
        assertThat(service.accept(toAccept.getId(), "uid-invitee").status()).isEqualTo("ACCEPTED");
        assertThat(toAccept.getRecipientId()).isNotNull();
        service.revoke(toAccept.getId(), "uid-inviter");
        assertThat(toAccept.getStatus()).isEqualTo(InvitationStatus.REVOKED);

        RecipientInvitationEntity toDecline = usernameInvitation();
        when(repository.findById(toDecline.getId())).thenReturn(Optional.of(toDecline));
        assertThat(service.decline(toDecline.getId(), "uid-invitee").status()).isEqualTo("DECLINED");
        verify(auditService).log(eq("RECIPIENT_INVITATION"), eq(toDecline.getId()),
                eq("RECIPIENT_INVITATION_DECLINED"), eq(invitee.getId()), any());
    }

    @Test
    void send_ownUsername_422() {
        when(usernameLookup.resolveVisible(inviter, "moi")).thenReturn(Optional.of(inviter));

        assertThatThrownBy(() -> service.send("uid-inviter", byUsername("moi")))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("recipient-invitation-self");
                });
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void send_username_liveInvitationAlreadyThere_createsNothing() {
        when(usernameLookup.resolveVisible(inviter, "fatou.sow")).thenReturn(Optional.of(invitee));
        when(repository.existsByInviterUserIdAndInviteeUserIdAndStatusIn(eq(inviter.getId()), eq(invitee.getId()),
                anySet())).thenReturn(true);

        assertThat(service.send("uid-inviter", byUsername("fatou.sow"))).isEqualTo(InvitationSentResponse.SENT);
        verify(repository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void send_username_raceOnUniqueIndex_stillAnswersSent() {
        when(usernameLookup.resolveVisible(inviter, "fatou.sow")).thenReturn(Optional.of(invitee));
        doThrow(new DataIntegrityViolationException("uq")).when(repository).saveAndFlush(any());
        when(repository.existsByInviterUserIdAndInviteeUserIdAndStatusIn(eq(inviter.getId()), eq(invitee.getId()), any()))
                .thenReturn(false, true);

        assertThat(service.send("uid-inviter", byUsername("fatou.sow"))).isEqualTo(InvitationSentResponse.SENT);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void send_username_integrityViolationWithoutLiveInvitation_isNotSwallowed() {
        when(usernameLookup.resolveVisible(inviter, "fatou.sow")).thenReturn(Optional.of(invitee));
        doThrow(new DataIntegrityViolationException("chk")).when(repository).saveAndFlush(any());

        assertThatThrownBy(() -> service.send("uid-inviter", byUsername("fatou.sow")))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void send_username_quotaReached_429_beforeAnyLookup() {
        when(repository.countByInviterUserIdAndCreatedAtAfter(eq(inviter.getId()), any())).thenReturn(20L);

        assertThatThrownBy(() -> service.send("uid-inviter", byUsername("fatou.sow")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(usernameLookup, never()).resolveVisible(any(), any());
    }

    @Test
    void send_usernameWithAnotherField_422() {
        for (CreateRecipientInvitationRequest bad : List.of(
                new CreateRecipientInvitationRequest(PHONE, null, null, "fatou.sow"),
                new CreateRecipientInvitationRequest(null, "a@b.co", null, "fatou.sow"),
                new CreateRecipientInvitationRequest(PHONE, "a@b.co", null, "fatou.sow"),
                new CreateRecipientInvitationRequest(null, null, null, " "))) {
            assertThatThrownBy(() -> service.send("uid-inviter", bad))
                    .isInstanceOfSatisfying(YadonyBusinessException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }
    }

    @Test
    void sent_acceptedUsernameInvitation_showsInviteeCurrentUsername() {
        invitee.setUsername("fatou.renamed");
        RecipientInvitationEntity byName = withId(new RecipientInvitationEntity(inviter.getId(), invitee.getId(),
                InvitationChannel.USERNAME, InvitationTargets.hash("u:" + invitee.getId()), "@fatou.sow"));
        byName.respond(InvitationStatus.ACCEPTED, OffsetDateTime.now(ZoneOffset.UTC));
        when(repository.findByInviterUserIdAndStatusInOrderByCreatedAtDesc(eq(inviter.getId()), anyCollection()))
                .thenReturn(List.of(byName, pendingFor(null)));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(invitee));

        var sent = service.sent("uid-inviter");

        assertThat(sent.get(0).channel()).isEqualTo("USERNAME");
        assertThat(sent.get(0).maskedTarget()).isEqualTo("@fatou.renamed");
        assertThat(sent.get(1).maskedTarget()).isEqualTo("+221 •• •• •• 12");
    }

    @Test
    void sent_pendingUsernameInvitation_renamedInvitee_keepsStoredLabel() {
        invitee.setUsername("fatou.renamed");
        RecipientInvitationEntity byName = withId(new RecipientInvitationEntity(inviter.getId(), invitee.getId(),
                InvitationChannel.USERNAME, InvitationTargets.hash("u:" + invitee.getId()), "@fatou.sow"));
        when(repository.findByInviterUserIdAndStatusInOrderByCreatedAtDesc(eq(inviter.getId()), anyCollection()))
                .thenReturn(List.of(byName));

        var sent = service.sent("uid-inviter");

        assertThat(sent.get(0).maskedTarget()).isEqualTo("@fatou.sow");
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void sent_acceptedUsernameInvitation_blockedInvitee_keepsStoredLabelNotNewHandle() {
        invitee.setUsername("fatou.renamed");
        RecipientInvitationEntity byName = withId(new RecipientInvitationEntity(inviter.getId(), invitee.getId(),
                InvitationChannel.USERNAME, InvitationTargets.hash("u:" + invitee.getId()), "@fatou.sow"));
        byName.respond(InvitationStatus.ACCEPTED, OffsetDateTime.now(ZoneOffset.UTC));
        when(repository.findByInviterUserIdAndStatusInOrderByCreatedAtDesc(eq(inviter.getId()), anyCollection()))
                .thenReturn(List.of(byName));
        when(blockVisibility.isHidden(inviter.getId(), invitee.getId())).thenReturn(true);

        var sent = service.sent("uid-inviter");

        assertThat(sent.get(0).maskedTarget()).isEqualTo("@fatou.sow");
        verify(userRepository, never()).findAllById(any());
    }

    // ── sent ────────────────────────────────────────────────────────────────

    @Test
    void sent_listsOnlyPendingAndAccepted_notDeclined() {
        RecipientInvitationEntity declined = pendingFor(invitee.getId());
        declined.respond(InvitationStatus.DECLINED, null);
        RecipientInvitationEntity accepted = pendingFor(invitee.getId());
        accepted.respond(InvitationStatus.ACCEPTED, null);
        RecipientInvitationEntity pending = pendingFor(invitee.getId());
        // Le dépôt filtre sur les statuts demandés : seuls PENDING et ACCEPTED doivent être réclamés.
        when(repository.findByInviterUserIdAndStatusInOrderByCreatedAtDesc(eq(inviter.getId()), anyCollection()))
                .thenAnswer(inv -> Stream.of(declined, pending, accepted)
                        .filter(i -> ((Collection<?>) inv.getArgument(1)).contains(i.getStatus())).toList());

        var sent = service.sent("uid-inviter");

        assertThat(sent).extracting("status").containsExactly("PENDING", "ACCEPTED");
        assertThat(sent.get(0).maskedTarget()).isEqualTo("+221 •• •• •• 12");
        assertThat(sent.get(0).channel()).isEqualTo("PHONE");
    }

    @Test
    void sent_returnsInviterChosenName() {
        // FLUTTER-7V : la liste n'affichait qu'un numéro masqué
        RecipientInvitationEntity named = pendingFor(invitee.getId());
        named.setLabel("Adama");
        RecipientInvitationEntity legacy = pendingFor(null);
        when(repository.findByInviterUserIdAndStatusInOrderByCreatedAtDesc(eq(inviter.getId()), anyCollection()))
                .thenReturn(List.of(named, legacy));

        var sent = service.sent("uid-inviter");

        assertThat(sent).extracting("name").containsExactly("Adama", null);
    }

    @Test
    void send_storesTrimmedName_andIgnoresBlankName() {
        when(phoneLookup.findByPhone(PHONE)).thenReturn(new PhoneMatch.None());
        var captor = org.mockito.ArgumentCaptor.forClass(RecipientInvitationEntity.class);

        service.send("uid-inviter", new CreateRecipientInvitationRequest(PHONE, null, "  Adama  "));
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getLabel()).isEqualTo("Adama");

        org.mockito.Mockito.clearInvocations(repository);
        service.send("uid-inviter", new CreateRecipientInvitationRequest(null, "adama@gmail.com", "   "));
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getLabel()).isNull();
    }

    @Test
    void send_nameTooLong_returns400() {
        assertThatThrownBy(() -> service.send("uid-inviter",
                new CreateRecipientInvitationRequest(PHONE, null, "x".repeat(101))))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("100");
        verify(repository, never()).saveAndFlush(any());
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

    // ── numéro « Vérifié par Yadony » (déclaré sans SMS, validé par un administrateur) ──

    @Test
    void accept_yadonyTrustedDeclaredPhone_succeeds() {
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
        FirebaseContactService.Contact contact = new FirebaseContactService.Contact(null, null, PHONE);
        when(firebaseContact.getContact("uid-invitee")).thenReturn(contact);
        when(phoneLookup.trustedDeclaredPhone(invitee, contact)).thenReturn(Optional.of(PHONE));

        IncomingInvitationDto dto = service.accept(invitation.getId(), "uid-invitee");

        assertThat(dto.status()).isEqualTo("ACCEPTED");
        ArgumentCaptor<RecipientEntity> entry = ArgumentCaptor.forClass(RecipientEntity.class);
        verify(recipientRepository).save(entry.capture());
        assertThat(entry.getValue().getPhoneE164()).isEqualTo(PHONE);
    }

    @Test
    void accept_plainDeclaredPhone_stillReturns409() {
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
        FirebaseContactService.Contact contact = new FirebaseContactService.Contact(null, null, PHONE);
        when(firebaseContact.getContact("uid-invitee")).thenReturn(contact);
        when(phoneLookup.trustedDeclaredPhone(invitee, contact)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(invitation.getId(), "uid-invitee"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("recipient-invitation-phone-required"));
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void accept_smsPhone_neverConsultsTheDeclaredNumber() {
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
        when(firebaseContact.getContact("uid-invitee")).thenReturn(new FirebaseContactService.Contact(PHONE, null));

        service.accept(invitation.getId(), "uid-invitee");

        verify(phoneLookup, never()).trustedDeclaredPhone(any(), any());
    }

    @Test
    void incoming_catchUp_yadonyTrustedDeclaredPhone_claimsTheInvitation() {
        FirebaseContactService.Contact contact = new FirebaseContactService.Contact(null, null, PHONE);
        when(firebaseContact.getContact("uid-invitee")).thenReturn(contact);
        when(phoneLookup.trustedDeclaredPhone(invitee, contact)).thenReturn(Optional.of(PHONE));
        RecipientInvitationEntity orphan = pendingFor(null);
        when(repository.findByInviteeUserIdIsNullAndStatusAndTargetHashIn(eq(InvitationStatus.PENDING),
                eq(Set.of(InvitationTargets.hash(PHONE))))).thenReturn(List.of(orphan));
        when(repository.findByInviteeUserIdAndStatusInOrderByCreatedAtDesc(eq(invitee.getId()), anyCollection()))
                .thenAnswer(inv -> List.of(orphan));
        when(userRepository.findAllById(any())).thenReturn(List.of(inviter));

        service.incoming("uid-invitee");

        assertThat(orphan.getInviteeUserId()).isEqualTo(invitee.getId());
    }

    @Test
    void incoming_catchUp_plainDeclaredPhone_claimsNothing() {
        FirebaseContactService.Contact contact = new FirebaseContactService.Contact(null, null, PHONE);
        when(firebaseContact.getContact("uid-invitee")).thenReturn(contact);
        when(phoneLookup.trustedDeclaredPhone(invitee, contact)).thenReturn(Optional.empty());

        service.incoming("uid-invitee");

        verify(repository, never()).findByInviteeUserIdIsNullAndStatusAndTargetHashIn(any(), any());
    }

    @Test
    void incoming_catchUp_trustedNumberContested_claimsNothing() {
        // Le vrai PhoneLookupService refuse un numéro disputé : ici le contrat est « vide => rien de réclamé ».
        FirebaseContactService.Contact contact = new FirebaseContactService.Contact(null, null, PHONE);
        when(firebaseContact.getContact("uid-invitee")).thenReturn(contact);
        when(phoneLookup.trustedDeclaredPhone(invitee, contact)).thenReturn(Optional.empty());
        RecipientInvitationEntity orphan = pendingFor(null);

        service.incoming("uid-invitee");

        assertThat(orphan.getInviteeUserId()).isNull();
        verify(repository, never()).save(any());
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
    void accept_linksEntryHoldingInvitedNumber_evenWhenAccountNumberDiffers() {
        // FLUTTER-7T : l'entrée « Adama » enregistrée avant l'invitation restait non liée
        // et une seconde entrée était créée avec le numéro du compte de l'invité.
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        invitation.setLabel("Adama");
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
        when(firebaseContact.getContact("uid-invitee"))
                .thenReturn(new FirebaseContactService.Contact("+33751101299", null));
        RecipientEntity saved = new RecipientEntity();
        ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
        saved.setUserId(inviter.getId());
        saved.setFullName("Adama D.");
        saved.setPhoneE164(PHONE);
        saved.setCountry("SN");
        when(recipientRepository.findByUserIdOrderByUpdatedAtDesc(inviter.getId())).thenReturn(List.of(saved));

        service.accept(invitation.getId(), "uid-invitee");

        assertThat(invitation.getRecipientId()).isEqualTo(saved.getId());
        assertThat(saved.getFullName()).isEqualTo("Adama");
        verify(recipientRepository, org.mockito.Mockito.times(1)).save(any());
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

    private RecipientEntity acceptWithPhone(String phone) {
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
        when(firebaseContact.getContact("uid-invitee")).thenReturn(new FirebaseContactService.Contact(phone, null));
        service.accept(invitation.getId(), "uid-invitee");
        ArgumentCaptor<RecipientEntity> entry = ArgumentCaptor.forClass(RecipientEntity.class);
        verify(recipientRepository).save(entry.capture());
        return entry.getValue();
    }

    @Test
    void accept_diasporaNumber_takesCountryFromCallingCode() {
        assertThat(acceptWithPhone("+33612345678").getCountry()).isEqualTo("FR");
    }

    @Test
    void accept_unknownCallingCode_fallsBackToInviterCountry() {
        inviter.setCountry("BE");
        invitee.setCountry("JP");
        assertThat(acceptWithPhone("+81312345678").getCountry()).isEqualTo("BE");
    }

    @Test
    void accept_unknownCallingCode_withoutInviterCountry_fallsBackToInviteeCountry() {
        invitee.setCountry("JP");
        assertThat(acceptWithPhone("+81312345678").getCountry()).isEqualTo("JP");
    }

    @Test
    void accept_unknownCallingCode_noKnownCountry_lastResortSN() {
        assertThat(acceptWithPhone("+81312345678").getCountry()).isEqualTo("SN");
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
    void decline_marksDeclinedAndNotifiesInviterOnceWithMaskedTarget() {
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));

        assertThat(service.decline(invitation.getId(), "uid-invitee").status()).isEqualTo("DECLINED");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationDispatcher).notifyUser(eq(inviter.getId()), eq("Invitation non acceptée"), body.capture(),
                eq(Map.of("type", "RECIPIENT_INVITATION_DECLINED", "invitationId", invitation.getId().toString())));
        assertThat(body.getValue()).isEqualTo(InvitationTargets.maskPhone(PHONE) + " n'a pas accepté votre invitation.")
                .doesNotContain(PHONE);
        verify(auditService).log(eq("RECIPIENT_INVITATION"), eq(invitation.getId()),
                eq("RECIPIENT_INVITATION_DECLINED"), eq(invitee.getId()), any());
    }

    @Test
    void decline_twice_notifiesOnlyOnce() {
        RecipientInvitationEntity invitation = pendingFor(invitee.getId());
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));

        service.decline(invitation.getId(), "uid-invitee");
        assertThatThrownBy(() -> service.decline(invitation.getId(), "uid-invitee"))
                .isInstanceOf(YadonyNotFoundException.class);

        verify(notificationDispatcher, times(1)).notifyUser(any(), any(), any(), any());
    }

    @Test
    void decline_usernameInvitation_renamedInvitee_usesStoredLabelNotCurrentUsername() {
        invitee.setUsername("fatou.neuf");
        RecipientInvitationEntity invitation = usernameInvitation();
        when(repository.findById(invitation.getId())).thenReturn(Optional.of(invitation));

        service.decline(invitation.getId(), "uid-invitee");

        verify(notificationDispatcher).notifyUser(eq(inviter.getId()), any(),
                eq("@fatou.sow n'a pas accepté votre invitation."), any());
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
