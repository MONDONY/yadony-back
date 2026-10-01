package com.yadony.api.addressbook.invitation;

import com.yadony.api.addressbook.invitation.dto.CreateRecipientInvitationRequest;
import com.yadony.api.addressbook.invitation.dto.IncomingInvitationDto;
import com.yadony.api.addressbook.invitation.dto.InvitationSentResponse;
import com.yadony.api.addressbook.invitation.dto.SentInvitationDto;
import com.yadony.api.addressbook.recipient.RecipientEntity;
import com.yadony.api.addressbook.recipient.RecipientRepository;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationTexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Invitations d'un expéditeur à ajouter un utilisateur Yadony à son carnet de destinataires.
 *
 * <p>Invariant central : l'envoi ne révèle JAMAIS si un compte existe. La réponse est la
 * même dans tous les cas (compte, pas de compte, doublon, soi-même, blocage), la recherche
 * Firebase a lieu à chaque fois, et le push à l'invité part en asynchrone. Seul le quota
 * (compté sur les invitations créées, indépendamment de l'existence du compte) peut refuser.
 */
@Service
public class RecipientInvitationService {

    private static final Logger log = LoggerFactory.getLogger(RecipientInvitationService.class);

    static final int DAILY_QUOTA = 20;

    private static final Set<InvitationStatus> LIVE =
            EnumSet.of(InvitationStatus.PENDING, InvitationStatus.ACCEPTED);
    private static final Set<InvitationStatus> SENT_VISIBLE =
            EnumSet.of(InvitationStatus.PENDING, InvitationStatus.ACCEPTED, InvitationStatus.DECLINED);

    private final RecipientInvitationRepository repository;
    private final RecipientRepository recipientRepository;
    private final UserRepository userRepository;
    private final FirebaseContactService firebaseContact;
    private final BlockVisibility blockVisibility;
    private final NotificationDispatcher notificationDispatcher;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public RecipientInvitationService(RecipientInvitationRepository repository,
                                      RecipientRepository recipientRepository,
                                      UserRepository userRepository,
                                      FirebaseContactService firebaseContact,
                                      BlockVisibility blockVisibility,
                                      NotificationDispatcher notificationDispatcher,
                                      AuditService auditService,
                                      ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.recipientRepository = recipientRepository;
        this.userRepository = userRepository;
        this.firebaseContact = firebaseContact;
        this.blockVisibility = blockVisibility;
        this.notificationDispatcher = notificationDispatcher;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    // ── Inviteur ────────────────────────────────────────────────────────────

    /**
     * Envoie une invitation. Volontairement hors transaction : l'insertion a la sienne, ce
     * qui permet d'absorber une course sur l'index unique sans faire échouer la réponse.
     */
    public InvitationSentResponse send(String firebaseUid, CreateRecipientInvitationRequest request) {
        UserEntity inviter = currentUser(firebaseUid);
        Target target = parse(request);

        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(24);
        if (repository.countByInviterUserIdAndCreatedAtAfter(inviter.getId(), since) >= DAILY_QUOTA) {
            throw new YadonyBusinessException(HttpStatus.TOO_MANY_REQUESTS, "recipient-invitation-quota",
                    "Recipient Invitation Quota",
                    "Vous avez envoyé trop d'invitations aujourd'hui. Réessayez demain.");
        }

        // Recherche faite dans tous les cas, avant tout court-circuit : la durée de la
        // réponse ne doit pas dépendre de l'existence d'un compte ni d'un doublon.
        Optional<String> foundUid = target.channel() == InvitationChannel.PHONE
                ? firebaseContact.findUidByPhone(target.value())
                : firebaseContact.findUidByEmail(target.value());

        if (foundUid.filter(uid -> uid.equals(inviter.getFirebaseUid())).isPresent()) {
            return InvitationSentResponse.SENT;
        }
        String hash = InvitationTargets.hash(target.value());
        if (repository.existsByInviterUserIdAndTargetHashAndStatusIn(inviter.getId(), hash, LIVE)) {
            return InvitationSentResponse.SENT;
        }
        UUID inviteeId = foundUid.flatMap(userRepository::findByFirebaseUid).map(UserEntity::getId).orElse(null);
        if (inviteeId != null && (inviteeId.equals(inviter.getId())
                || blockVisibility.isHidden(inviter.getId(), inviteeId)
                || repository.existsByInviterUserIdAndInviteeUserIdAndStatus(
                        inviter.getId(), inviteeId, InvitationStatus.ACCEPTED))) {
            return InvitationSentResponse.SENT;
        }

        RecipientInvitationEntity invitation = new RecipientInvitationEntity(
                inviter.getId(), inviteeId, target.channel(), hash, target.masked());
        try {
            invitation = repository.saveAndFlush(invitation);
        } catch (DataIntegrityViolationException race) {
            // Deux envois simultanés vers la même cible : l'autre a gagné, même réponse.
            return InvitationSentResponse.SENT;
        }
        auditService.log("RECIPIENT_INVITATION", invitation.getId(), "RECIPIENT_INVITATION_SENT",
                inviter.getId(), Map.of("channel", target.channel().name()));
        if (inviteeId != null) {
            eventPublisher.publishEvent(new RecipientInvitationCreatedEvent(
                    invitation.getId(), inviter.getId(), inviteeId));
        }
        return InvitationSentResponse.SENT;
    }

    /** Invitations envoyées et non retirées ; une invitation non acceptée reste « PENDING ». */
    @Transactional(readOnly = true)
    public List<SentInvitationDto> sent(String firebaseUid) {
        UserEntity inviter = currentUser(firebaseUid);
        return repository.findByInviterUserIdAndStatusInOrderByCreatedAtDesc(inviter.getId(), SENT_VISIBLE)
                .stream()
                .map(i -> new SentInvitationDto(i.getId(), i.getChannel().name(), i.getMaskedTarget(),
                        i.getStatus() == InvitationStatus.ACCEPTED ? "ACCEPTED" : "PENDING",
                        i.getCreatedAt()))
                .toList();
    }

    // ── Invité ──────────────────────────────────────────────────────────────

    /**
     * Invitations PENDING et ACCEPTED qui visent l'utilisateur, après rattrapage de celles
     * envoyées à son numéro ou à son email avant qu'il n'ait un compte.
     */
    @Transactional
    public List<IncomingInvitationDto> incoming(String firebaseUid) {
        UserEntity me = currentUser(firebaseUid);
        catchUp(me);
        List<RecipientInvitationEntity> invitations = repository
                .findByInviteeUserIdAndStatusInOrderByCreatedAtDesc(me.getId(), LIVE)
                .stream()
                .filter(i -> !blockVisibility.isHidden(me.getId(), i.getInviterUserId()))
                .toList();
        Map<UUID, String> firstNames = userRepository.findAllById(
                        invitations.stream().map(RecipientInvitationEntity::getInviterUserId).collect(Collectors.toSet()))
                .stream()
                .filter(u -> u.getFirstName() != null)
                .collect(Collectors.toMap(UserEntity::getId, UserEntity::getFirstName, (a, b) -> a));
        return invitations.stream()
                .map(i -> new IncomingInvitationDto(i.getId(), firstNames.get(i.getInviterUserId()),
                        i.getStatus().name(), i.getCreatedAt()))
                .toList();
    }

    /** Pose {@code invitee_user_id} sur les invitations sans compte dont l'empreinte est la sienne. */
    private void catchUp(UserEntity me) {
        FirebaseContactService.Contact contact = firebaseContact.getContact(me.getFirebaseUid());
        Set<String> hashes = Stream.concat(
                        InvitationTargets.phone(contact.phoneNumber()).stream(),
                        InvitationTargets.email(contact.email()).stream())
                .map(InvitationTargets::hash)
                .collect(Collectors.toCollection(HashSet::new));
        if (hashes.isEmpty()) {
            return;
        }
        for (RecipientInvitationEntity invitation
                : repository.findByInviteeUserIdIsNullAndStatusAndTargetHashIn(InvitationStatus.PENDING, hashes)) {
            if (invitation.getInviterUserId().equals(me.getId())
                    || blockVisibility.isHidden(invitation.getInviterUserId(), me.getId())) {
                continue;
            }
            invitation.setInviteeUserId(me.getId());
            repository.save(invitation);
        }
    }

    /**
     * Accepte : ajoute (ou met à jour) l'invité dans le carnet de l'inviteur, lie l'entrée et
     * prévient l'inviteur. Idempotent sur une invitation déjà acceptée.
     */
    @Transactional
    public IncomingInvitationDto accept(UUID invitationId, String firebaseUid) {
        UserEntity me = currentUser(firebaseUid);
        RecipientInvitationEntity invitation = incomingInvitation(invitationId, me);
        if (invitation.getStatus() == InvitationStatus.ACCEPTED) {
            return toIncoming(invitation);
        }
        requirePending(invitation);
        String phone = InvitationTargets.phone(firebaseContact.getContact(me.getFirebaseUid()).phoneNumber())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.CONFLICT,
                        "recipient-invitation-phone-required", "Recipient Invitation Phone Required",
                        "Ajoutez un numéro de téléphone à votre profil pour accepter cette invitation."));

        RecipientEntity entry = upsertAddressBookEntry(invitation.getInviterUserId(), me, phone);
        invitation.setRecipientId(entry.getId());
        invitation.respond(InvitationStatus.ACCEPTED, OffsetDateTime.now(ZoneOffset.UTC));
        repository.save(invitation);
        auditService.log("RECIPIENT_INVITATION", invitation.getId(), "RECIPIENT_INVITATION_ACCEPTED", me.getId(),
                Map.of("inviterUserId", invitation.getInviterUserId().toString(),
                        "recipientId", entry.getId().toString()));

        var text = NotificationTexts.recipientInvitationAccepted(
                notificationDispatcher.messagesFor(invitation.getInviterUserId()), me.getFirstName());
        notificationDispatcher.notifyUser(invitation.getInviterUserId(), text.title(), text.body(),
                Map.of("type", RecipientInvitationNotifications.ACCEPTED,
                        "invitationId", invitation.getId().toString()));
        log.info("Invitation {} acceptée, entrée de carnet {}", invitation.getId(), entry.getId());
        return toIncoming(invitation);
    }

    /** Refuse, sans rien dire à l'inviteur (il continue de voir « en attente »). */
    @Transactional
    public IncomingInvitationDto decline(UUID invitationId, String firebaseUid) {
        UserEntity me = currentUser(firebaseUid);
        RecipientInvitationEntity invitation = incomingInvitation(invitationId, me);
        requirePending(invitation);
        invitation.respond(InvitationStatus.DECLINED, OffsetDateTime.now(ZoneOffset.UTC));
        repository.save(invitation);
        auditService.log("RECIPIENT_INVITATION", invitation.getId(), "RECIPIENT_INVITATION_DECLINED", me.getId(),
                Map.of("inviterUserId", invitation.getInviterUserId().toString()));
        return toIncoming(invitation);
    }

    /**
     * Retire une invitation : l'inviteur (tout statut non retiré) ou l'invité (relation
     * acceptée). L'entrée du carnet reste, elle n'est simplement plus liée.
     */
    @Transactional
    public void revoke(UUID invitationId, String firebaseUid) {
        UserEntity me = currentUser(firebaseUid);
        RecipientInvitationEntity invitation = repository.findById(invitationId)
                .orElseThrow(() -> notFound(invitationId));
        String side;
        if (me.getId().equals(invitation.getInviterUserId()) && SENT_VISIBLE.contains(invitation.getStatus())) {
            side = "INVITER";
        } else if (me.getId().equals(invitation.getInviteeUserId())
                && invitation.getStatus() == InvitationStatus.ACCEPTED) {
            side = "INVITEE";
        } else {
            throw notFound(invitationId);
        }
        invitation.respond(InvitationStatus.REVOKED, OffsetDateTime.now(ZoneOffset.UTC));
        repository.save(invitation);
        auditService.log("RECIPIENT_INVITATION", invitation.getId(), "RECIPIENT_INVITATION_REVOKED", me.getId(),
                Map.of("side", side));
    }

    // ── Interne ─────────────────────────────────────────────────────────────

    private RecipientEntity upsertAddressBookEntry(UUID inviterId, UserEntity invitee, String phone) {
        String digits = phone.substring(1);
        RecipientEntity entry = recipientRepository.findByUserIdOrderByUpdatedAtDesc(inviterId).stream()
                .filter(r -> r.getPhoneE164() != null && digits.equals(r.getPhoneE164().replaceAll("\\D", "")))
                .findFirst()
                .orElse(null);
        boolean created = entry == null;
        if (created) {
            entry = new RecipientEntity();
            entry.setUserId(inviterId);
            entry.setCountry(countryFor(phone, inviterId, invitee));
            entry.setDefault(false);
        }
        entry.setFullName(fullName(invitee));
        entry.setPhoneE164(phone);
        entry = recipientRepository.save(entry);
        if (created) {
            auditService.log("RECIPIENT", entry.getId(), "RECIPIENT_CREATED", invitee.getId(),
                    Map.of("source", "INVITATION", "country", entry.getCountry()));
        }
        return entry;
    }

    /**
     * Pays de l'entrée : celui de l'indicatif ; inconnu, celui de l'inviteur puis celui de
     * l'invité. {@code recipients.country} est NOT NULL : SN (corridor principal, repli de
     * l'app) ne sert qu'en tout dernier recours, quand aucun pays n'est connu.
     */
    private String countryFor(String phone, UUID inviterId, UserEntity invitee) {
        return InvitationTargets.countryOf(phone)
                .or(() -> userRepository.findById(inviterId).map(UserEntity::getCountry).filter(c -> c != null && c.length() == 2))
                .or(() -> Optional.ofNullable(invitee.getCountry()).filter(c -> c.length() == 2))
                .orElse("SN");
    }

    private static String fullName(UserEntity user) {
        String joined = Stream.of(user.getFirstName(), user.getLastName())
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" "));
        return joined.isEmpty() ? "Yadony" : joined;
    }

    private RecipientInvitationEntity incomingInvitation(UUID invitationId, UserEntity me) {
        return repository.findById(invitationId)
                .filter(i -> me.getId().equals(i.getInviteeUserId()))
                .filter(i -> !blockVisibility.isHidden(me.getId(), i.getInviterUserId()))
                .orElseThrow(() -> notFound(invitationId));
    }

    private static void requirePending(RecipientInvitationEntity invitation) {
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw notFound(invitation.getId());
        }
    }

    private IncomingInvitationDto toIncoming(RecipientInvitationEntity invitation) {
        String firstName = userRepository.findById(invitation.getInviterUserId())
                .map(UserEntity::getFirstName).orElse(null);
        return new IncomingInvitationDto(invitation.getId(), firstName, invitation.getStatus().name(),
                invitation.getCreatedAt());
    }

    private static YadonyNotFoundException notFound(UUID invitationId) {
        return new YadonyNotFoundException("RecipientInvitation", invitationId);
    }

    private UserEntity currentUser(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyNotFoundException("Utilisateur introuvable"));
    }

    private static Target parse(CreateRecipientInvitationRequest request) {
        boolean hasPhone = request != null && request.phone() != null && !request.phone().isBlank();
        boolean hasEmail = request != null && request.email() != null && !request.email().isBlank();
        if (hasPhone == hasEmail) {
            throw invalid("Indiquez soit un numéro, soit un email.");
        }
        if (hasPhone) {
            String phone = InvitationTargets.phone(request.phone())
                    .orElseThrow(() -> invalid("Numéro invalide : format international attendu (+221…)."));
            return new Target(InvitationChannel.PHONE, phone, InvitationTargets.maskPhone(phone));
        }
        String email = InvitationTargets.email(request.email())
                .orElseThrow(() -> invalid("Adresse email invalide."));
        return new Target(InvitationChannel.EMAIL, email, InvitationTargets.maskEmail(email));
    }

    private static YadonyBusinessException invalid(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "recipient-invitation-invalid-target",
                "Invalid Recipient Invitation Target", detail);
    }

    private record Target(InvitationChannel channel, String value, String masked) {}
}
