package com.yadony.api.subscriptions;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.subscriptions.dto.SubscriptionStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock TravelerSubscriptionRepository repo;
    @Mock UserRepository userRepository;
    @Mock com.yadony.api.common.StorageService storageService;
    @Mock com.yadony.api.common.BlockVisibility blockVisibility;
    @InjectMocks SubscriptionService service;

    final String uid = "firebase-uid";
    final UUID senderId = UUID.randomUUID();
    final UUID travelerId = UUID.randomUUID();
    UserEntity sender;

    @BeforeEach
    void setup() {
        sender = new UserEntity();
        sender.setFirstName("Awa"); sender.setLastName("K");
        try { var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
              f.setAccessible(true); f.set(sender, senderId); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void subscribe_createsSubscription_whenNoneExists() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(new UserEntity()));
        when(repo.findBySenderIdAndTravelerIdIncludingDeleted(senderId, travelerId)).thenReturn(Optional.empty());

        service.subscribe(uid, travelerId);

        verify(repo).save(any(TravelerSubscriptionEntity.class));
    }

    @Test
    void subscribe_reactivatesSoftDeleted_whenExists() {
        TravelerSubscriptionEntity existing = new TravelerSubscriptionEntity();
        existing.setSenderId(senderId); existing.setTravelerId(travelerId);
        existing.setDeletedAt(java.time.LocalDateTime.now());
        existing.setHasNew(true);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(new UserEntity()));
        when(repo.findBySenderIdAndTravelerIdIncludingDeleted(senderId, travelerId)).thenReturn(Optional.of(existing));

        service.subscribe(uid, travelerId);

        assertThat(existing.getDeletedAt()).isNull();
        assertThat(existing.isHasNew()).isFalse();
        verify(repo).save(existing);
    }

    @Test
    void subscribe_unknownTraveler_throwsNotFound() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.subscribe(uid, travelerId))
            .isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void unsubscribe_softDeletesExisting() {
        TravelerSubscriptionEntity existing = new TravelerSubscriptionEntity();
        existing.setSenderId(senderId); existing.setTravelerId(travelerId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(repo.findBySenderIdAndTravelerId(senderId, travelerId)).thenReturn(Optional.of(existing));

        service.unsubscribe(uid, travelerId);

        assertThat(existing.getDeletedAt()).isNotNull();
        verify(repo).save(existing);
    }

    @Test
    void setPush_updatesFlag() {
        TravelerSubscriptionEntity existing = new TravelerSubscriptionEntity();
        existing.setSenderId(senderId); existing.setTravelerId(travelerId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(repo.findBySenderIdAndTravelerId(senderId, travelerId)).thenReturn(Optional.of(existing));

        service.setPush(uid, travelerId, true);

        assertThat(existing.isPushEnabled()).isTrue();
    }

    @Test
    void getStatus_returnsSubscribedAndPush() {
        TravelerSubscriptionEntity existing = new TravelerSubscriptionEntity();
        existing.setPushEnabled(true);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(repo.findBySenderIdAndTravelerId(senderId, travelerId)).thenReturn(Optional.of(existing));

        SubscriptionStatusResponse status = service.getStatus(uid, travelerId);

        assertThat(status.subscribed()).isTrue();
        assertThat(status.pushEnabled()).isTrue();
    }

    @Test
    void getStatus_notSubscribed_returnsFalse() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(repo.findBySenderIdAndTravelerId(senderId, travelerId)).thenReturn(Optional.empty());
        assertThat(service.getStatus(uid, travelerId).subscribed()).isFalse();
    }

    @Test
    void markSeen_resetsHasNew() {
        TravelerSubscriptionEntity existing = new TravelerSubscriptionEntity();
        existing.setHasNew(true);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(repo.findBySenderIdAndTravelerId(senderId, travelerId)).thenReturn(Optional.of(existing));

        service.markSeen(uid, travelerId);

        assertThat(existing.isHasNew()).isFalse();
    }

    @Test
    void getMySubscriptions_mapsProjection() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(storageService.avatarUrl("avatars/ibrahima.jpg")).thenReturn("https://cdn.yadony.app/signed/ibrahima.jpg");
        Object[] row = new Object[]{
            travelerId, "Ibrahima D", "avatars/ibrahima.jpg", true, new java.math.BigDecimal("4.8"), 2L,
            false, true, UUID.randomUUID(), "Paris", "Dakar",
            new java.math.BigDecimal("8.00"), "XOF",
            java.sql.Date.valueOf(java.time.LocalDate.of(2026, 9, 27)),
            java.sql.Timestamp.valueOf(java.time.LocalDateTime.now())
        };
        when(repo.findEnrichedBySenderId(senderId)).thenReturn(List.<Object[]>of(row));

        var list = service.getMySubscriptions(uid);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).travelerName()).isEqualTo("Ibrahima D");
        assertThat(list.get(0).avatarUrl()).isEqualTo("https://cdn.yadony.app/signed/ibrahima.jpg");
        assertThat(list.get(0).ongoingTripsCount()).isEqualTo(2L);
        assertThat(list.get(0).lastAnnouncement().arrivalCity()).isEqualTo("Dakar");
        // La devise vient de l'annonce : afficher un euro sur un prix publié en
        // XOF donnerait un montant faux de plusieurs centaines de fois.
        assertThat(list.get(0).lastAnnouncement().currency()).isEqualTo("XOF");
        // La date de départ décide si l'expéditeur peut confier son colis : la
        // carte l'affiche à la place de la date de publication.
        assertThat(list.get(0).lastAnnouncement().departureDate())
            .isEqualTo(java.time.LocalDate.of(2026, 9, 27));
    }

    @Test
    void getMySubscriptions_nullCurrency_fallsBackToEur() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(storageService.avatarUrl(null)).thenReturn(null);
        Object[] row = new Object[]{
            travelerId, "Ibrahima D", null, false, new java.math.BigDecimal("4.8"), 1L,
            false, false, UUID.randomUUID(), "Lyon", "Bamako",
            new java.math.BigDecimal("7.00"), null,
            java.time.LocalDate.of(2026, 10, 5),
            java.sql.Timestamp.valueOf(java.time.LocalDateTime.now())
        };
        when(repo.findEnrichedBySenderId(senderId)).thenReturn(List.<Object[]>of(row));

        var list = service.getMySubscriptions(uid);

        assertThat(list.get(0).lastAnnouncement().currency()).isEqualTo("EUR");
        // H2 rend un LocalDate là où PostgreSQL rend un java.sql.Date : les deux
        // formes doivent être acceptées par le mapping.
        assertThat(list.get(0).lastAnnouncement().departureDate())
            .isEqualTo(java.time.LocalDate.of(2026, 10, 5));
    }

    @Test
    void markAllSeen_delegatesToRepositoryWithSenderId() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));

        service.markAllSeen(uid);

        verify(repo).markAllSeenBySenderId(senderId);
    }

    @Test
    void getMySubscriptions_nullAvatarKey_mapsToNullUrl() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(storageService.avatarUrl(null)).thenReturn(null);
        Object[] row = new Object[]{
            travelerId, "Karim", null, false, new java.math.BigDecimal("4.5"), 0L,
            false, false, null, null, null, null, null, null, null
        };
        when(repo.findEnrichedBySenderId(senderId)).thenReturn(List.<Object[]>of(row));

        var list = service.getMySubscriptions(uid);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).avatarUrl()).isNull();
    }

    @Test
    void getMySubscribers_returnsSubscribersWithDisplayName() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        UUID subscriberUserId = UUID.randomUUID();
        TravelerSubscriptionEntity sub = new TravelerSubscriptionEntity();
        sub.setSenderId(subscriberUserId);
        sub.setTravelerId(senderId);
        when(repo.findAllByTravelerId(senderId)).thenReturn(List.of(sub));
        UserEntity subscriber = new UserEntity();
        subscriber.setFirstName("Fatou");
        subscriber.setLastName("Ndiaye");
        when(userRepository.findById(subscriberUserId)).thenReturn(Optional.of(subscriber));

        var result = service.getMySubscribers(uid);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).senderId()).isEqualTo(subscriberUserId);
        assertThat(result.get(0).displayName()).isEqualTo("Fatou N.");
    }

    @Test
    void getMySubscribers_fallsBackToGenericName_whenSenderMissing() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        UUID subscriberUserId = UUID.randomUUID();
        TravelerSubscriptionEntity sub = new TravelerSubscriptionEntity();
        sub.setSenderId(subscriberUserId);
        sub.setTravelerId(senderId);
        when(repo.findAllByTravelerId(senderId)).thenReturn(List.of(sub));
        when(userRepository.findById(subscriberUserId)).thenReturn(Optional.empty());

        var result = service.getMySubscribers(uid);

        // Compte introuvable (supprimé) : repli neutre unique, et non plus le rôle tenu dans
        // le fil, qui faisait changer de nom un même compte selon l'écran.
        assertThat(result.get(0).displayName())
                .isEqualTo(com.yadony.api.auth.UserEntity.UNKNOWN_DISPLAY_NAME);
    }

    // ---- Blocage : un compte masqué disparaît des deux listes et refuse tout nouvel abonnement ----

    private Object[] subscriptionRow(UUID traveler, String name) {
        return new Object[]{
            traveler, name, null, false, new java.math.BigDecimal("4.5"), 0L,
            false, false, null, null, null, null, null, null, null
        };
    }

    @Test
    void getMySubscriptions_omitsBlockedTravelers() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(storageService.avatarUrl(null)).thenReturn(null);
        UUID blockedTraveler = UUID.randomUUID();
        when(repo.findEnrichedBySenderId(senderId)).thenReturn(List.<Object[]>of(
            subscriptionRow(blockedTraveler, "Bloqué"),
            subscriptionRow(travelerId, "Visible")));
        when(blockVisibility.hiddenUserIdsFor(senderId)).thenReturn(java.util.Set.of(blockedTraveler));

        var list = service.getMySubscriptions(uid);

        // L'abonnement n'est pas résilié, seulement masqué : il réapparaît au déblocage.
        assertThat(list).extracting(r -> r.travelerId()).containsExactly(travelerId);
    }

    @Test
    void getMySubscribers_omitsBlockedSenders() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        UUID blockedSender = UUID.randomUUID();
        UUID visibleSender = UUID.randomUUID();
        TravelerSubscriptionEntity blocked = new TravelerSubscriptionEntity();
        blocked.setSenderId(blockedSender); blocked.setTravelerId(senderId);
        TravelerSubscriptionEntity visible = new TravelerSubscriptionEntity();
        visible.setSenderId(visibleSender); visible.setTravelerId(senderId);
        when(repo.findAllByTravelerId(senderId)).thenReturn(List.of(blocked, visible));
        when(blockVisibility.hiddenUserIdsFor(senderId)).thenReturn(java.util.Set.of(blockedSender));
        when(userRepository.findById(visibleSender)).thenReturn(Optional.of(new UserEntity()));

        var result = service.getMySubscribers(uid);

        assertThat(result).extracting(r -> r.senderId()).containsExactly(visibleSender);
        verify(userRepository, never()).findById(blockedSender);
    }

    @Test
    void subscribe_isRefusedSilently_whenTravelerHidden() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        doThrow(new com.yadony.api.common.YadonyBusinessException(
                org.springframework.http.HttpStatus.NOT_FOUND, "not-found", "Not Found", "Ressource introuvable"))
            .when(blockVisibility).assertVisible(senderId, travelerId);

        assertThatThrownBy(() -> service.subscribe(uid, travelerId))
            .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);

        verify(repo, never()).save(any());
    }

    @Test
    void getStatus_isRefusedSilently_whenTravelerHidden() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        doThrow(new com.yadony.api.common.YadonyBusinessException(
                org.springframework.http.HttpStatus.NOT_FOUND, "not-found", "Not Found", "Ressource introuvable"))
            .when(blockVisibility).assertVisible(senderId, travelerId);

        assertThatThrownBy(() -> service.getStatus(uid, travelerId))
            .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);
    }
}
