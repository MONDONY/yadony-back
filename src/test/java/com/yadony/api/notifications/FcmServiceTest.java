package com.yadony.api.notifications;

import com.yadony.api.auth.UserDeviceEntity;
import com.yadony.api.auth.UserDeviceJpaRepository;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FcmServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserDeviceJpaRepository userDeviceRepository;
    @Mock NotificationPrefsService notificationPrefsService;
    @Mock NotificationRepository notificationRepository;
    @Mock PushThrottle pushThrottle;
    @Mock PushQuietHours pushQuietHours;
    @InjectMocks FcmService service;

    private static final UUID USER_ID = UUID.randomUUID();

    // Test 1: isAllowed() retourne false → sendToUser() retourne false sans appel à userRepository
    @Test
    void sendToUser_prefDisabled_returnsFalseWithoutCallingUserRepo() {
        when(notificationPrefsService.isAllowed(USER_ID, "BID_CREATED")).thenReturn(false);

        boolean result = service.sendToUser(USER_ID, "Titre", "Corps",
                Map.of("type", "BID_CREATED"));

        assertThat(result).isFalse();
        verifyNoInteractions(userRepository);
    }

    // Bruit (étape 2) : un type réservé au feed ne part jamais en push, même autorisé
    @Test
    void sendToUser_feedOnlyType_neverPushes() {
        boolean result = service.sendToUser(USER_ID, "Titre", "Corps",
                java.util.Map.of("type", "RECIPIENT_CONFIRMED"));

        org.assertj.core.api.Assertions.assertThat(result).isFalse();
        verifyNoInteractions(notificationPrefsService, userRepository, userDeviceRepository);
    }

    // Test 2: isAllowed() retourne true → la méthode continue et appelle userRepository.findById()
    @Test
    void sendToUser_prefEnabled_proceedsToUserRepository() {
        when(notificationPrefsService.isAllowed(USER_ID, "BID_CREATED")).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        boolean result = service.sendToUser(USER_ID, "Titre", "Corps",
                Map.of("type", "BID_CREATED"));

        assertThat(result).isFalse(); // false car user not found
        verify(userRepository).findById(USER_ID);
    }

    // Test 3: data est null → isAllowed() est appelé avec null comme type
    @Test
    void sendToUser_dataNullType_isAllowedCalledWithNull() {
        when(notificationPrefsService.isAllowed(USER_ID, null)).thenReturn(false);

        boolean result = service.sendToUser(USER_ID, "Titre", "Corps", null);

        assertThat(result).isFalse();
        verify(notificationPrefsService).isAllowed(USER_ID, null);
        verifyNoInteractions(userRepository);
    }

    // Test 4: data sans clé "type" → isAllowed() appelé avec null (get() retourne null si absent)
    @Test
    void sendToUser_dataWithoutTypeKey_isAllowedCalledWithNull() {
        when(notificationPrefsService.isAllowed(USER_ID, null)).thenReturn(false);

        boolean result = service.sendToUser(USER_ID, "Titre", "Corps",
                Map.of("other_key", "value"));

        assertThat(result).isFalse();
        verify(notificationPrefsService).isAllowed(USER_ID, null);
        verifyNoInteractions(userRepository);
    }

    // Test 5: type critique (PAYMENT_RELEASED) → isAllowed() est appelé (le service décide lui-même)
    @Test
    void sendToUser_criticalType_isAllowedIsCalled() {
        when(notificationPrefsService.isAllowed(USER_ID, "PAYMENT_RELEASED")).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        service.sendToUser(USER_ID, "Paiement libéré", "Votre paiement a été libéré",
                Map.of("type", "PAYMENT_RELEASED"));

        verify(notificationPrefsService).isAllowed(USER_ID, "PAYMENT_RELEASED");
        verify(userRepository).findById(USER_ID);
    }

    // Test 6: user sans token FCM → retourne false même si pref autorisée
    @Test
    void sendToUser_prefEnabled_noFcmToken_returnsFalse() {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setFcmToken(null);

        when(notificationPrefsService.isAllowed(USER_ID, "NEW_MESSAGE")).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        boolean result = service.sendToUser(USER_ID, "Message", "Vous avez un message",
                Map.of("type", "NEW_MESSAGE"));

        assertThat(result).isFalse();
        verify(userRepository).findById(USER_ID);
    }

    // Test 7 (régression) : deux appareils inscrits → les deux jetons reçoivent la push.
    // Avant, seul users.fcm_token était utilisé : le dernier appareil connecté rendait
    // muets tous les autres (un Android relancé coupait les notifications de l'iPhone).
    @Test
    void sendToUser_multipleDevices_sendsToEveryDeviceToken() throws Exception {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setFcmToken("token-android");

        when(notificationPrefsService.isAllowed(USER_ID, "NEW_MESSAGE")).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userDeviceRepository.findByUserIdOrderByLastSeenAtDesc(USER_ID))
                .thenReturn(List.of(device("token-android"), device("token-iphone")));

        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        when(messaging.send(any(Message.class))).thenReturn("msg-id");

        try (MockedStatic<FirebaseMessaging> fcmStatic = mockStatic(FirebaseMessaging.class)) {
            fcmStatic.when(FirebaseMessaging::getInstance).thenReturn(messaging);

            boolean result = service.sendToUser(USER_ID, "Message", "Vous avez un message",
                    Map.of("type", "NEW_MESSAGE"));

            assertThat(result).isTrue();
        }

        // Deux envois distincts, un par appareil — le jeton legacy est dédupliqué.
        verify(messaging, times(2)).send(any(Message.class));
    }

    // Test 8 : le jeton de users.fcm_token reste utilisé si aucun appareil n'est enregistré
    // (comptes antérieurs à user_devices).
    @Test
    void sendToUser_noDeviceRows_fallsBackToLegacyUserToken() throws Exception {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setFcmToken("token-legacy");

        when(notificationPrefsService.isAllowed(USER_ID, "NEW_MESSAGE")).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userDeviceRepository.findByUserIdOrderByLastSeenAtDesc(USER_ID)).thenReturn(List.of());

        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        when(messaging.send(any(Message.class))).thenReturn("msg-id");

        try (MockedStatic<FirebaseMessaging> fcmStatic = mockStatic(FirebaseMessaging.class)) {
            fcmStatic.when(FirebaseMessaging::getInstance).thenReturn(messaging);

            boolean result = service.sendToUser(USER_ID, "Message", "Vous avez un message",
                    Map.of("type", "NEW_MESSAGE"));

            assertThat(result).isTrue();
        }

        verify(messaging, times(1)).send(any(Message.class));
    }

    // Bruit (étape 3) : délai de calme du flux en cours → push retenu, sans même lire l'utilisateur
    @Test
    void sendToUser_heldByThrottle_returnsFalseWithoutCallingUserRepo() {
        when(notificationPrefsService.isAllowed(USER_ID, "NEW_MESSAGE")).thenReturn(true);
        Map<String, String> data = Map.of("type", "NEW_MESSAGE", "conversationId", "conv_abc");
        when(pushThrottle.hold(USER_ID, "NEW_MESSAGE", data)).thenReturn(true);

        assertThat(service.sendToUser(USER_ID, "Message", "Corps", data)).isFalse();
        verifyNoInteractions(userRepository);
    }

    // Bruit (étape 4) : heures calmes ou plafond du jour → push retenu, aucun envoi Firebase
    @Test
    void sendToUser_heldByQuietHours_sendsNothing() {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setFcmToken("token-android");
        when(notificationPrefsService.isAllowed(USER_ID, "BID_CREATED")).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(pushQuietHours.hold(user, "BID_CREATED")).thenReturn(true);

        assertThat(service.sendToUser(USER_ID, "Titre", "Corps", Map.of("type", "BID_CREATED"))).isFalse();
        verifyNoInteractions(userDeviceRepository);
    }

    /** Flux qui se répète : la clé de remplacement part pour Android, iOS et l'app. */
    @Test
    void sendToUser_repeatedStream_carriesTheCollapseKey() throws Exception {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setFcmToken("token-android");
        when(notificationPrefsService.isAllowed(USER_ID, "NEW_MESSAGE")).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userDeviceRepository.findByUserIdOrderByLastSeenAtDesc(USER_ID)).thenReturn(List.of());

        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        org.mockito.ArgumentCaptor<Message> sent = org.mockito.ArgumentCaptor.forClass(Message.class);
        when(messaging.send(sent.capture())).thenReturn("msg-id");

        try (MockedStatic<FirebaseMessaging> fcmStatic = mockStatic(FirebaseMessaging.class)) {
            fcmStatic.when(FirebaseMessaging::getInstance).thenReturn(messaging);
            service.sendToUser(USER_ID, "Message", "Corps",
                    Map.of("type", "NEW_MESSAGE", "conversationId", "conv_abc"));
        }

        Message message = sent.getValue();
        @SuppressWarnings("unchecked")
        Map<String, String> data = (Map<String, String>) ReflectionTestUtils.getField(message, "data");
        assertThat(data).containsEntry("collapseKey", "chat:conv_abc");
        Object android = ReflectionTestUtils.getField(message, "androidConfig");
        Object androidNotification = ReflectionTestUtils.getField(android, "notification");
        assertThat(ReflectionTestUtils.getField(androidNotification, "tag")).isEqualTo("chat:conv_abc");
        Object apns = ReflectionTestUtils.getField(message, "apnsConfig");
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) ReflectionTestUtils.getField(apns, "headers");
        assertThat(headers).containsEntry("apns-collapse-id", "chat:conv_abc");
    }

    // ── Relance de vérification d'identité ───────────────────────────────────
    // KYC_ACTION_REQUIRED porte le drapeau reminder=true : c'est une relance, réglable par
    // « Rappels et conseils » et comptée dans le plafond du jour. Sans drapeau, c'est une
    // décision : toujours envoyée, hors plafond. Vrais réglages et vraies heures calmes ici,
    // pour éprouver la chaîne réelle et pas seulement le type transmis aux bouchons.

    private static final Map<String, String> RELANCE =
            Map.of("type", "KYC_ACTION_REQUIRED", "reminder", "true", "reminderNumber", "1");
    private static final Map<String, String> VRAI_REFUS = Map.of("type", "KYC_ACTION_REQUIRED");

    @Test
    void fcm_relance_couperParPushRemindersTips() throws Exception {
        NotificationPrefsJpaRepository prefsRepository = mock(NotificationPrefsJpaRepository.class);
        NotificationPrefsEntity coupe = new NotificationPrefsEntity();
        coupe.setPushRemindersTips(false);
        when(prefsRepository.findById(USER_ID)).thenReturn(Optional.of(coupe));
        FcmService real = realChain(prefsRepository, new PushQuietHours(false));

        assertThat(real.sendToUser(USER_ID, "Votre vérification vous attend", "Corps", RELANCE)).isFalse();
        verifyNoInteractions(userRepository);

        // Réglage rallumé : la relance part, et le payload garde le type que l'app sait ouvrir.
        coupe.setPushRemindersTips(true);
        stubUserWithToken();
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        org.mockito.ArgumentCaptor<Message> sent = org.mockito.ArgumentCaptor.forClass(Message.class);
        when(messaging.send(sent.capture())).thenReturn("msg-id");
        try (MockedStatic<FirebaseMessaging> fcmStatic = mockStatic(FirebaseMessaging.class)) {
            fcmStatic.when(FirebaseMessaging::getInstance).thenReturn(messaging);
            assertThat(real.sendToUser(USER_ID, "Votre vérification vous attend", "Corps", RELANCE)).isTrue();
        }
        @SuppressWarnings("unchecked")
        Map<String, String> payload = (Map<String, String>) ReflectionTestUtils.getField(sent.getValue(), "data");
        assertThat(payload).containsEntry("type", "KYC_ACTION_REQUIRED").containsEntry("reminder", "true");
    }

    @Test
    void fcm_relance_compteDansLePlafond() throws Exception {
        // Bouchons : chaque garde reçoit le type effectif, une seule fois calculé.
        UserEntity user = stubUserWithToken();
        when(notificationPrefsService.isAllowed(USER_ID, "KYC_VERIFICATION_REMINDER")).thenReturn(true);
        when(pushQuietHours.hold(user, "KYC_VERIFICATION_REMINDER")).thenReturn(true);

        assertThat(service.sendToUser(USER_ID, "Titre", "Corps", RELANCE)).isFalse();
        verify(pushThrottle).hold(USER_ID, "KYC_VERIFICATION_REMINDER", RELANCE);
        verify(pushQuietHours).hold(user, "KYC_VERIFICATION_REMINDER");

        // Chaîne réelle, en pleine journée : au-delà de 10 push, la relance reste dans le feed.
        NotificationPrefsJpaRepository prefsRepository = mock(NotificationPrefsJpaRepository.class);
        FcmService real = realChain(prefsRepository, middayQuietHours());
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        when(messaging.send(any(Message.class))).thenReturn("msg-id");
        int sentCount = 0;
        try (MockedStatic<FirebaseMessaging> fcmStatic = mockStatic(FirebaseMessaging.class)) {
            fcmStatic.when(FirebaseMessaging::getInstance).thenReturn(messaging);
            for (int i = 0; i < PushQuietHours.DAILY_CAP + 1; i++) {
                if (real.sendToUser(USER_ID, "Titre", "Corps", RELANCE)) sentCount++;
            }
        }
        assertThat(sentCount).isEqualTo(PushQuietHours.DAILY_CAP);
    }

    @Test
    void fcm_vraiRefus_resteToujoursActif() throws Exception {
        // Tout coupé, plafond dépassé : un vrai refus de vérification part quand même.
        NotificationPrefsJpaRepository prefsRepository = mock(NotificationPrefsJpaRepository.class);
        NotificationPrefsEntity toutCoupe = new NotificationPrefsEntity();
        toutCoupe.setPushRemindersTips(false);
        toutCoupe.setPushActivityBids(false);
        toutCoupe.setPushMyTrips(false);
        toutCoupe.setPushPromo(false);
        lenient().when(prefsRepository.findById(USER_ID)).thenReturn(Optional.of(toutCoupe));
        stubUserWithToken();
        FcmService real = realChain(prefsRepository, middayQuietHours());
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        org.mockito.ArgumentCaptor<Message> sent = org.mockito.ArgumentCaptor.forClass(Message.class);
        when(messaging.send(sent.capture())).thenReturn("msg-id");
        int sentCount = 0;
        try (MockedStatic<FirebaseMessaging> fcmStatic = mockStatic(FirebaseMessaging.class)) {
            fcmStatic.when(FirebaseMessaging::getInstance).thenReturn(messaging);
            for (int i = 0; i < PushQuietHours.DAILY_CAP + 2; i++) {
                if (real.sendToUser(USER_ID, "Vérification à reprendre", "Corps", VRAI_REFUS)) sentCount++;
            }
        }
        assertThat(sentCount).isEqualTo(PushQuietHours.DAILY_CAP + 2);
        @SuppressWarnings("unchecked")
        Map<String, String> payload = (Map<String, String>) ReflectionTestUtils.getField(sent.getValue(), "data");
        assertThat(payload).containsEntry("type", "KYC_ACTION_REQUIRED").doesNotContainKey("reminder");
    }

    private FcmService realChain(NotificationPrefsJpaRepository prefsRepository, PushQuietHours quietHours) {
        return new FcmService(userRepository, userDeviceRepository,
                new NotificationPrefsService(prefsRepository, userRepository),
                notificationRepository, new PushThrottle(), quietHours);
    }

    /** Heures calmes actives, à midi à Paris (pays par défaut) : seul le plafond peut retenir. */
    private static PushQuietHours middayQuietHours() {
        return new PushQuietHours(true,
                java.time.Clock.fixed(java.time.Instant.parse("2026-11-10T11:00:00Z"), java.time.ZoneOffset.UTC));
    }

    private UserEntity stubUserWithToken() {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setFcmToken("token-android");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        lenient().when(userDeviceRepository.findByUserIdOrderByLastSeenAtDesc(USER_ID)).thenReturn(List.of());
        return user;
    }

    private static UserDeviceEntity device(String fcmToken) {
        UserDeviceEntity device = new UserDeviceEntity();
        ReflectionTestUtils.setField(device, "fcmToken", fcmToken);
        return device;
    }

    // ── Pastille d'icône iOS ─────────────────────────────────────────────────
    // Le champ APNs `badge` est ABSOLU : il écrit ce nombre sur l'icône, il ne
    // l'incrémente pas. Il valait 1 en dur, donc l'icône affichait 1 dès la première
    // notification et le gardait pour toujours, rien ne la remettant jamais à zéro.

    @Test
    void unreadBadge_rendLeNombreReelDeNonLues() {
        when(notificationRepository.countByUserIdAndReadAtIsNull(USER_ID)).thenReturn(4L);

        assertThat(service.unreadBadge(USER_ID)).isEqualTo(4);
    }

    @Test
    void unreadBadge_rendZeroQuandToutEstLu() {
        when(notificationRepository.countByUserIdAndReadAtIsNull(USER_ID)).thenReturn(0L);

        // Zéro efface la pastille côté iOS : c'est ce qui manquait.
        assertThat(service.unreadBadge(USER_ID)).isZero();
    }

    @Test
    void unreadBadge_plafonneA99() {
        when(notificationRepository.countByUserIdAndReadAtIsNull(USER_ID)).thenReturn(1_500L);

        assertThat(service.unreadBadge(USER_ID)).isEqualTo(99);
    }

    @Test
    void unreadBadge_compteurIndisponible_rendZeroSansLever() {
        // Une notification doit partir même si le compteur est injoignable.
        when(notificationRepository.countByUserIdAndReadAtIsNull(USER_ID))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("base injoignable"));

        assertThat(service.unreadBadge(USER_ID)).isZero();
    }
}
