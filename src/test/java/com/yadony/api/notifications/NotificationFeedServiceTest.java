package com.yadony.api.notifications;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.notifications.dto.FeedItemDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // la résolution de l'utilisateur est stubbée pour tous, certains cas échouent avant
@DisplayName("Feed : agrégation à la lecture à partir de trois non-lues de même clé")
class NotificationFeedServiceTest {

    @Mock NotificationRepository repository;
    @Mock UserRepository userRepository;

    NotificationFeedService service;

    private final String uid = "firebase-uid";
    private final UUID userId = UUID.randomUUID();
    private final String annId = UUID.randomUUID().toString();
    private final LocalDateTime t0 = LocalDateTime.of(2026, 9, 3, 10, 0);

    @BeforeEach
    void setUp() {
        var notificationService = new NotificationService(repository, userRepository,
                new NotificationCapsPolicy(NotificationCapsPolicy.Mode.OFF));
        service = new NotificationFeedService(repository, notificationService);
        var user = new UserEntity();
        user.setFirebaseUid(uid);
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(user));
    }

    private NotificationEntity bid(int minutesAgo, boolean read) {
        var e = new NotificationEntity(userId, "BID_CREATED", "Nouvelle demande d'envoi",
                "Karim T., 12 kg, Paris vers Dakar.",
                Map.of("type", "BID_CREATED", "announcementId", annId, "bidId", UUID.randomUUID().toString()), false);
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(e, "createdAt", t0.minusMinutes(minutesAgo));
        if (read) e.markRead(t0);
        return e;
    }

    private NotificationEntity single(String type, int minutesAgo) {
        var e = new NotificationEntity(userId, type, "Titre", "Corps.",
                Map.of("type", type, "bidId", UUID.randomUUID().toString()), false);
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(e, "createdAt", t0.minusMinutes(minutesAgo));
        return e;
    }

    @Test
    void twoUnreadOfSameGroup_stayTwoLines() {
        var a = bid(1, false);
        var b = bid(2, false);
        when(repository.findByUserIdAndReadAtIsNullAndGroupKeyIsNotNullOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(a, b));
        when(repository.findFeed(eq(userId), eq(NotificationCategory.ANNONCE),
                eq(List.of(NotificationFeedService.NO_COLLAPSED_KEY)), any()))
                .thenReturn(new PageImpl<>(List.of(a, b), PageRequest.of(0, 30), 2));

        var page = service.feed(uid, 0, 30);

        assertThat(page.content()).hasSize(2).allMatch(i -> i.count() == 1);
        assertThat(page.totalElements()).isEqualTo(2);
    }

    @Test
    void threeUnreadOfSameGroup_becomeOneAggregateWithCountAndGroupTarget() {
        var a = bid(1, false);
        var b = bid(2, false);
        var c = bid(3, false);
        var other = single("PAYMENT_RELEASED", 5);
        String key = "bid:announcement:" + annId;
        when(repository.findByUserIdAndReadAtIsNullAndGroupKeyIsNotNullOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(a, b, c));
        when(repository.findFeed(eq(userId), eq(NotificationCategory.ANNONCE), argThat(k -> k.contains(key)), any()))
                .thenReturn(new PageImpl<>(List.of(other), PageRequest.of(0, 30), 1));

        var page = service.feed(uid, 0, 30);

        assertThat(page.content()).hasSize(2);
        FeedItemDTO agg = page.content().get(0);
        assertThat(agg.count()).isEqualTo(3);
        assertThat(agg.title()).isEqualTo("3 demandes d'envoi");
        assertThat(agg.body()).isEqualTo("Karim T., 12 kg, Paris vers Dakar.");
        assertThat(agg.id()).isEqualTo(a.getId());
        assertThat(agg.notificationIds()).containsExactly(a.getId(), b.getId(), c.getId());
        assertThat(agg.groupKey()).isEqualTo(key);
        assertThat(agg.deeplink()).isEqualTo("yadony://announcements/" + annId + "/bids");
        assertThat(agg.read()).isFalse();
        assertThat(page.content().get(1).id()).isEqualTo(other.getId());
        assertThat(page.totalElements()).isEqualTo(2);
    }

    @Test
    void readLineOfCollapsedGroup_isNotCountedInAggregate() {
        var a = bid(1, false);
        var b = bid(2, false);
        var c = bid(3, true);
        when(repository.findByUserIdAndReadAtIsNullAndGroupKeyIsNotNullOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(a, b));
        when(repository.findFeed(eq(userId), eq(NotificationCategory.ANNONCE), anyCollection(), any()))
                .thenReturn(new PageImpl<>(List.of(a, b, c), PageRequest.of(0, 30), 3));

        var page = service.feed(uid, 0, 30);

        assertThat(page.content()).hasSize(3).allMatch(i -> i.count() == 1);
    }

    @Test
    void aggregate_isPlacedOnThePageWhereItsLatestDateFalls() {
        var a = bid(10, false);
        var b = bid(11, false);
        var c = bid(12, false);
        var newer = single("PAYMENT_RELEASED", 1);
        var older = single("DELIVERY_CONFIRMED", 20);
        when(repository.findByUserIdAndReadAtIsNullAndGroupKeyIsNotNullOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(a, b, c));
        // Page 0 : « newer » seul ; l'agrégat (t-10) est plus ancien que le bas de la page (t-1) → page 1.
        when(repository.findFeed(eq(userId), eq(NotificationCategory.ANNONCE), anyCollection(), eq(PageRequest.of(0, 1))))
                .thenReturn(new PageImpl<>(List.of(newer), PageRequest.of(0, 1), 2));
        when(repository.findFeed(eq(userId), eq(NotificationCategory.ANNONCE), anyCollection(), eq(PageRequest.of(1, 1))))
                .thenReturn(new PageImpl<>(List.of(older), PageRequest.of(1, 1), 2));

        var page0 = service.feed(uid, 0, 1);
        var page1 = service.feed(uid, 1, 1);

        assertThat(page0.content()).extracting(FeedItemDTO::count).containsExactly(1);
        assertThat(page1.content()).extracting(FeedItemDTO::count).containsExactly(3, 1);
        assertThat(page1.content().get(0).createdAt()).isAfter(page1.content().get(1).createdAt());
    }

    @Test
    void markGroupRead_marksAllUnreadOfTheGroupAtOnce() {
        when(repository.markGroupRead(eq(userId), eq("bid:announcement:" + annId), any())).thenReturn(3);

        assertThat(service.markGroupRead(uid, "bid:announcement:" + annId)).isEqualTo(3);
    }

    @Test
    void markGroupRead_onLoneKey_readsThatNotification() {
        var e = single("PAYMENT_RELEASED", 1);
        when(repository.findById(e.getId())).thenReturn(Optional.of(e));

        int n = service.markGroupRead(uid, "notif:" + e.getId());

        assertThat(n).isEqualTo(1);
        assertThat(e.isRead()).isTrue();
        verify(repository, never()).markGroupRead(any(), any(), any());
    }

    @Test
    void markGroupRead_rejectsBlankOrMalformedKey() {
        assertThatThrownBy(() -> service.markGroupRead(uid, " "))
                .isInstanceOf(YadonyBusinessException.class);
        assertThatThrownBy(() -> service.markGroupRead(uid, "notif:pas-un-uuid"))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void announcementsSummary_carriesUnreadCountAndLatest() {
        var latest = new NotificationEntity(userId, "ADMIN_BROADCAST", "Maintenance ce soir", "Corps.",
                Map.of("type", "ADMIN_BROADCAST"), false);
        ReflectionTestUtils.setField(latest, "id", UUID.randomUUID());
        when(repository.countByUserIdAndCategoryAndReadAtIsNull(userId, NotificationCategory.ANNONCE)).thenReturn(2L);
        when(repository.findFirstByUserIdAndCategoryOrderByCreatedAtDesc(userId, NotificationCategory.ANNONCE))
                .thenReturn(Optional.of(latest));

        var summary = service.announcementsSummary(uid);

        assertThat(summary.unreadCount()).isEqualTo(2);
        assertThat(summary.latestId()).isEqualTo(latest.getId());
        assertThat(summary.latestTitle()).isEqualTo("Maintenance ce soir");
    }

    @Test
    void announcementsSummary_withoutAnyAnnouncement_isZeroAndEmpty() {
        when(repository.countByUserIdAndCategoryAndReadAtIsNull(userId, NotificationCategory.ANNONCE)).thenReturn(0L);
        when(repository.findFirstByUserIdAndCategoryOrderByCreatedAtDesc(userId, NotificationCategory.ANNONCE))
                .thenReturn(Optional.empty());

        var summary = service.announcementsSummary(uid);

        assertThat(summary.unreadCount()).isZero();
        assertThat(summary.latestId()).isNull();
        assertThat(summary.latestTitle()).isNull();
    }
}
