package com.yadony.api.addressbook.recipient;

import com.yadony.api.addressbook.invitation.RecipientInvitationRepository;
import com.yadony.api.addressbook.recipient.dto.CreateRecipientRequest;
import com.yadony.api.addressbook.recipient.dto.RecipientDto;
import com.yadony.api.addressbook.recipient.dto.UpdateRecipientRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RecipientService {

    private static final Logger log = LoggerFactory.getLogger(RecipientService.class);

    private final RecipientRepository repository;
    private final AuditService auditService;
    private final RecipientInvitationRepository invitationRepository;

    public RecipientService(RecipientRepository repository, AuditService auditService,
                            RecipientInvitationRepository invitationRepository) {
        this.repository = repository;
        this.auditService = auditService;
        this.invitationRepository = invitationRepository;
    }

    public List<RecipientDto> findAll(UUID userId) {
        Set<UUID> linked = invitationRepository.findLinkedRecipientIds(userId);
        return repository.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream()
                .map(e -> toDto(e, linked.contains(e.getId())))
                .collect(Collectors.toList());
    }

    @Transactional
    public RecipientDto create(UUID userId, CreateRecipientRequest request) {
        if (request.isDefault()) {
            clearCurrentDefault(userId);
        }

        RecipientEntity entity = new RecipientEntity();
        entity.setUserId(userId);
        entity.setFullName(request.fullName());
        entity.setRelationship(request.relationship());
        entity.setPhoneE164(request.phoneE164());
        entity.setWhatsappE164(request.whatsappE164());
        entity.setStreet(request.street());
        entity.setCity(request.city());
        entity.setCountry(request.country());
        entity.setNotes(request.notes());
        entity.setDefault(request.isDefault());

        repository.save(entity);

        auditService.log("RECIPIENT", entity.getId(), "RECIPIENT_CREATED", userId,
                Map.of("fullName", request.fullName(), "country", request.country()));

        log.info("Recipient created: id={} userId={}", entity.getId(), userId);
        return toDto(entity, false);
    }

    @Transactional
    public RecipientDto update(UUID userId, UUID id, UpdateRecipientRequest request) {
        RecipientEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new YadonyNotFoundException("Recipient", id));

        if (request.isDefault() && !entity.isDefault()) {
            clearCurrentDefault(userId);
        }

        entity.setFullName(request.fullName());
        entity.setRelationship(request.relationship());
        entity.setPhoneE164(request.phoneE164());
        entity.setWhatsappE164(request.whatsappE164());
        entity.setStreet(request.street());
        entity.setCity(request.city());
        entity.setCountry(request.country());
        entity.setNotes(request.notes());
        entity.setDefault(request.isDefault());

        repository.save(entity);

        auditService.log("RECIPIENT", entity.getId(), "RECIPIENT_UPDATED", userId,
                Map.of("fullName", request.fullName()));

        log.info("Recipient updated: id={} userId={}", id, userId);
        return toDto(entity, invitationRepository.findLinkedRecipientIds(userId).contains(id));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        RecipientEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new YadonyNotFoundException("Recipient", id));

        entity.softDelete();
        repository.save(entity);

        auditService.log("RECIPIENT", entity.getId(), "RECIPIENT_DELETED", userId,
                Map.of("id", id.toString()));

        log.info("Recipient soft-deleted: id={} userId={}", id, userId);
    }

    // saveAndFlush, pas save : au flush, Hibernate exécute les INSERT avant les
    // UPDATE. Le nouveau destinataire par défaut partait donc en base avant que
    // l'ancien ne perde le drapeau, et l'index unique partiel
    // idx_recipients_user_default (V168) levait une violation → 500 sur
    // POST /addressbook/recipients (Sentry YADONY-BACK-STAGING-E). H2, sans
    // index partiel, ne le voit pas : la garde est vérifiée par l'ordre des appels.
    private void clearCurrentDefault(UUID userId) {
        repository.findByUserIdAndIsDefaultTrue(userId).ifPresent(current -> {
            current.setDefault(false);
            repository.saveAndFlush(current);
        });
    }

    private RecipientDto toDto(RecipientEntity e, boolean linkedOnYadony) {
        return new RecipientDto(
                e.getId(),
                e.getFullName(),
                e.getRelationship(),
                e.getPhoneE164(),
                e.getWhatsappE164(),
                e.getStreet(),
                e.getCity(),
                e.getCountry(),
                e.getNotes(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.isDefault(),
                linkedOnYadony
        );
    }
}
