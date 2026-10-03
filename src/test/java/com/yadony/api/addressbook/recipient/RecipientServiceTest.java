package com.yadony.api.addressbook.recipient;

import com.yadony.api.addressbook.invitation.InvitationChannel;
import com.yadony.api.addressbook.invitation.InvitationStatus;
import com.yadony.api.addressbook.invitation.RecipientInvitationEntity;
import com.yadony.api.addressbook.invitation.RecipientInvitationRemovedEvent;
import com.yadony.api.addressbook.invitation.RecipientInvitationRepository;
import com.yadony.api.addressbook.recipient.dto.CreateRecipientRequest;
import com.yadony.api.addressbook.recipient.dto.RecipientDto;
import com.yadony.api.addressbook.recipient.dto.UpdateRecipientRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipientServiceTest {

    @Mock
    private RecipientRepository repository;

    @Mock
    private AuditService auditService;

    @Mock
    private RecipientInvitationRepository invitationRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RecipientService service;

    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new RecipientService(repository, auditService, invitationRepository, eventPublisher);
        userId = UUID.randomUUID();
    }

    private RecipientEntity buildEntity(UUID userId) {
        RecipientEntity e = new RecipientEntity();
        e.setUserId(userId);
        e.setFullName("Mamadou Diallo");
        e.setPhoneE164("+221701234567");
        e.setCity("Dakar");
        e.setCountry("SN");
        return e;
    }

    @Test
    void findAll_returnsRecipientsForUser() {
        RecipientEntity e1 = buildEntity(userId);
        RecipientEntity e2 = buildEntity(userId);
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(e1, e2));

        List<RecipientDto> result = service.findAll(userId);

        assertThat(result).hasSize(2);
    }

    @Test
    void create_mergesIntoExistingEntry_whenSamePhoneDigits() {
        // FLUTTER-7T : deux « Adama » identiques dans le carnet
        RecipientEntity existing = buildEntity(userId);
        existing.setPhoneE164("+33751101245");
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invitationRepository.findLinkedRecipientIds(userId)).thenReturn(java.util.Set.of());

        CreateRecipientRequest request = new CreateRecipientRequest(
                "Adama", null, "+33 7 51 10 12 45", null,
                null, "Dakar", "SN", null, false);

        RecipientDto result = service.create(userId, request);

        assertThat(result.fullName()).isEqualTo("Adama");
        verify(repository).save(existing);
        verify(repository, org.mockito.Mockito.times(1)).save(any());
        assertThat(existing.getFullName()).isEqualTo("Adama");
    }

    @Test
    void create_persistsAndReturnsDto() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateRecipientRequest request = new CreateRecipientRequest(
                "Fatou Diop", "Mère", "+221701234567", null,
                "Rue 12", "Dakar", "SN", null, false);

        RecipientDto result = service.create(userId, request);

        assertThat(result.fullName()).isEqualTo("Fatou Diop");
        assertThat(result.country()).isEqualTo("SN");
        verify(auditService).log(any(), any(), any(), any(), any());
    }

    @Test
    void create_withDefault_clearsPreviousDefault() {
        UUID userId = UUID.randomUUID();
        RecipientEntity previous = new RecipientEntity();
        previous.setUserId(userId);
        previous.setDefault(true);
        when(repository.findByUserIdAndIsDefaultTrue(userId)).thenReturn(Optional.of(previous));

        CreateRecipientRequest request = new CreateRecipientRequest(
                "Awa Diakité", "Mère", "+221771234567", null, null,
                "Dakar", "SN", null, true);

        RecipientDto dto = service.create(userId, request);

        assertThat(previous.isDefault()).isFalse();
        assertThat(dto.isDefault()).isTrue();
        // L'ancien défaut doit être écrit en base AVANT l'insertion du nouveau :
        // sinon l'index unique partiel lève une violation (YADONY-BACK-STAGING-E).
        InOrder order = inOrder(repository);
        order.verify(repository).saveAndFlush(previous);
        order.verify(repository).save(any(RecipientEntity.class));
    }

    @Test
    void create_withoutDefault_doesNotTouchPreviousDefault() {
        UUID userId = UUID.randomUUID();
        CreateRecipientRequest request = new CreateRecipientRequest(
                "Issa Koné", null, "+2250789012345", null, null,
                "Abidjan", "CI", null, false);

        RecipientDto dto = service.create(userId, request);

        verify(repository, never()).findByUserIdAndIsDefaultTrue(any());
        assertThat(dto.isDefault()).isFalse();
    }

    @Test
    void update_withValidOwnership_updatesFields() {
        UUID id = UUID.randomUUID();
        RecipientEntity entity = buildEntity(userId);

        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRecipientRequest request = new UpdateRecipientRequest(
                "Aminata Sow", "Soeur", "+2250101234567", null,
                null, "Abidjan", "CI", "Quartier Plateau", false);

        RecipientDto result = service.update(userId, id, request);

        assertThat(result.fullName()).isEqualTo("Aminata Sow");
        assertThat(result.country()).isEqualTo("CI");
        assertThat(result.notes()).isEqualTo("Quartier Plateau");
    }

    @Test
    void update_withWrongUserId_throwsNotFoundException() {
        UUID otherId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(otherId, id)).thenReturn(Optional.empty());

        UpdateRecipientRequest request = new UpdateRecipientRequest(
                "X", null, "+221701234567", null, null, "Dakar", "SN", null, false);

        assertThatThrownBy(() -> service.update(otherId, id, request))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void update_setDefault_clearsPreviousDefault() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        RecipientEntity entity = new RecipientEntity();
        entity.setUserId(userId);
        RecipientEntity previous = new RecipientEntity();
        previous.setDefault(true);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(entity));
        when(repository.findByUserIdAndIsDefaultTrue(userId)).thenReturn(Optional.of(previous));

        UpdateRecipientRequest request = new UpdateRecipientRequest(
                "Awa Diakité", "Mère", "+221771234567", null, null,
                "Dakar", "SN", null, true);

        RecipientDto dto = service.update(userId, id, request);

        assertThat(previous.isDefault()).isFalse();
        assertThat(dto.isDefault()).isTrue();
        InOrder order = inOrder(repository);
        order.verify(repository).saveAndFlush(previous);
        order.verify(repository).save(entity);
    }

    @Test
    void update_alreadyDefault_keepsDefaultWithoutClearing() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        RecipientEntity entity = new RecipientEntity();
        entity.setUserId(userId);
        entity.setDefault(true);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(entity));

        UpdateRecipientRequest request = new UpdateRecipientRequest(
                "Awa Diakité", "Mère", "+221771234567", null, null,
                "Dakar", "SN", null, true);

        service.update(userId, id, request);

        verify(repository, never()).findByUserIdAndIsDefaultTrue(any());
        assertThat(entity.isDefault()).isTrue();
    }

    @Test
    void delete_softDeletesAndLogsAudit() {
        UUID id = UUID.randomUUID();
        RecipientEntity entity = buildEntity(userId);

        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.delete(userId, id);

        assertThat(entity.getDeletedAt()).isNotNull();
        verify(auditService).log(any(), any(), any(), any(), any());
    }

    @Test
    void delete_revokesTheAcceptedInvitationLinkedToTheEntry() {
        // FLUTTER-8Z : sans révocation, l'expéditeur restait autorisé chez l'invité et
        // une nouvelle invitation vers la même personne n'était jamais envoyée.
        UUID id = UUID.randomUUID();
        RecipientEntity entity = buildEntity(userId);
        UUID inviteeId = UUID.randomUUID();
        RecipientInvitationEntity accepted = new RecipientInvitationEntity(
                userId, inviteeId, InvitationChannel.PHONE, "hash", "+221 •• •• 67");
        accepted.respond(InvitationStatus.ACCEPTED, OffsetDateTime.now());
        accepted.setRecipientId(id);

        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invitationRepository.findByInviterUserIdAndRecipientIdAndStatus(
                userId, id, InvitationStatus.ACCEPTED)).thenReturn(List.of(accepted));

        service.delete(userId, id);

        assertThat(accepted.getStatus()).isEqualTo(InvitationStatus.REVOKED);
        verify(invitationRepository).save(accepted);
        verify(auditService).log(eq("RECIPIENT_INVITATION"), any(),
                eq("RECIPIENT_INVITATION_REVOKED"),
                eq(userId), any());
        // L'invité est prévenu, après commit, par RecipientInvitationNotificationListener.
        verify(eventPublisher).publishEvent(new RecipientInvitationRemovedEvent(accepted.getId(), userId, inviteeId));
    }

    @Test
    void delete_withoutLinkedInvitation_touchesNoInvitation() {
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(buildEntity(userId)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invitationRepository.findByInviterUserIdAndRecipientIdAndStatus(
                userId, id, InvitationStatus.ACCEPTED)).thenReturn(List.of());

        service.delete(userId, id);

        verify(invitationRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void delete_withWrongUserId_throwsNotFoundException() {
        UUID otherId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(otherId, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(otherId, id))
                .isInstanceOf(YadonyNotFoundException.class);

        verify(repository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void findAll_flagsEntriesLinkedByAcceptedInvitation() {
        RecipientEntity linked = buildEntity(userId);
        RecipientEntity plain = buildEntity(userId);
        UUID linkedId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(linked, "id", linkedId);
        org.springframework.test.util.ReflectionTestUtils.setField(plain, "id", UUID.randomUUID());
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(linked, plain));
        when(invitationRepository.findLinkedRecipientIds(userId)).thenReturn(java.util.Set.of(linkedId));

        List<RecipientDto> result = service.findAll(userId);

        assertThat(result.get(0).linkedOnYadony()).isTrue();
        assertThat(result.get(1).linkedOnYadony()).isFalse();
    }

    @Test
    void update_linkedEntry_keepsLinkedFlag() {
        UUID id = UUID.randomUUID();
        RecipientEntity entity = buildEntity(userId);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(entity));
        when(invitationRepository.findLinkedRecipientIds(userId)).thenReturn(java.util.Set.of(id));

        RecipientDto dto = service.update(userId, id, new UpdateRecipientRequest(
                "Awa Diakité", null, "+221771234567", null, null, "Dakar", "SN", null, false));

        assertThat(dto.linkedOnYadony()).isTrue();
    }
}
