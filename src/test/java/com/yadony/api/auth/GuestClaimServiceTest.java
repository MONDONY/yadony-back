package com.yadony.api.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.favorites.FavoriteEntity;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.favorites.FavoriteTargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Le mecanisme de securite central : l'appelant est authentifie sur son VRAI compte et
 * presente dans le corps de la requete le token anonyme encore valide. Verifier ce token
 * prouve qu'il controlait cette session — un UID devine ne donne rien.
 *
 * <p>Amendement A14 : seuls les FAVORIS sont transferes. Les alertes corridor sont sorties
 * du perimetre invite (un invite n'a aucun role, et la creation d'alerte exige un role
 * precis selon la direction demandee), donc ni {@code AlertService} ni
 * {@code CorridorAlertRepository} n'apparaissent ici.
 */
@DisplayName("GuestClaimService — réclamation des données d'une session anonyme")
class GuestClaimServiceTest {

    private static final String CALLER_UID = "caller-uid";
    private static final String GUEST_UID = "guest-uid";
    private static final String PRESENTED_TOKEN = "presented-token";

    private final FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final FavoriteRepository favoriteRepository = mock(FavoriteRepository.class);
    private final AuditService auditService = mock(AuditService.class);

    // ── Etape 1 : le token presente doit etre verifiable ──────────────────────

    @Test
    @DisplayName("token présenté invalide ou expiré → 401, aucun transfert")
    void rejectsUnverifiableToken() throws Exception {
        when(firebaseAuth.verifyIdToken(anyString())).thenThrow(mock(FirebaseAuthException.class));

        assertThatThrownBy(() -> service().claim(CALLER_UID, PRESENTED_TOKEN))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("guest-claim-invalid-token");
                    assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.UNAUTHORIZED);
                });

        verifyNoInteractions(userRepository, favoriteRepository, auditService);
    }

    // ── Etape 2 : le token presente doit etre ANONYME ─────────────────────────

    @Test
    @DisplayName("token présenté non anonyme → refus, aucun transfert")
    void rejectsNonAnonymousToken() {
        // Un token de compte réel ne prouve rien ici : accepter reviendrait à
        // permettre de siphonner les favoris de n'importe quel utilisateur dont
        // on aurait intercepté un token.
        assertThatThrownBy(() -> serviceWithGuestToken("phone", "other-uid")
                .claim("caller-uid", "presented-token"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("guest-claim-not-anonymous"));

        verifyNoInteractions(userRepository, favoriteRepository, auditService);
    }

    // ── Etape 3 : le token presente ne peut pas etre celui de l'appelant ──────

    @Test
    @DisplayName("token présenté = celui de l'appelant → refus")
    void rejectsSelfClaim() {
        assertThatThrownBy(() -> serviceWithGuestToken("anonymous", "caller-uid")
                .claim("caller-uid", "presented-token"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("guest-claim-self"));

        verifyNoInteractions(userRepository, favoriteRepository, auditService);
    }

    // ── Etape 4 : ligne invitee absente → succes sans effet ───────────────────

    @Test
    @DisplayName("aucune ligne invitée → succès sans effet (idempotent)")
    void noGuestRowIsANoOp() {
        // Le cas courant : le visiteur n'a jamais rien mis en favori.
        // L'app appelle quand même l'endpoint, il ne doit pas échouer.
        // La materialisation paresseuse (Task 4) fait qu'une ligne `users` n'existe
        // que si l'invite a persiste quelque chose : la majorite des sessions
        // anonymes n'en ont aucune.
        when(userRepository.findByFirebaseUid(GUEST_UID)).thenReturn(Optional.empty());

        assertThatCode(() -> serviceWithGuestToken("anonymous", GUEST_UID)
                .claim(CALLER_UID, PRESENTED_TOKEN))
                .doesNotThrowAnyException();

        // Rien n'est lu ni ecrit au-dela de la recherche de la ligne invitee : ni la
        // ligne de l'appelant (donc aucun 404 possible), ni les favoris, ni l'audit.
        verify(userRepository).findByFirebaseUid(GUEST_UID);
        verify(userRepository, never()).findByFirebaseUid(CALLER_UID);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(favoriteRepository, auditService);
    }

    // ── Etape 5 : ligne de l'appelant absente → 404 ───────────────────────────

    @Test
    @DisplayName("ligne de l'appelant absente → 404, la ligne invitée reste intacte")
    void missingCallerRowIsRejected() {
        UserEntity guest = userWithId(GUEST_UID);
        when(userRepository.findByFirebaseUid(GUEST_UID)).thenReturn(Optional.of(guest));
        when(userRepository.findByFirebaseUid(CALLER_UID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceWithGuestToken("anonymous", GUEST_UID)
                .claim(CALLER_UID, PRESENTED_TOKEN))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("user-not-found");
                    assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });

        // Rien ne doit avoir bouge : ni les favoris, ni la suppression de la ligne invitee.
        assertThat(guest.getDeletedAt()).isNull();
        verify(userRepository, never()).save(any());
        verifyNoInteractions(favoriteRepository, auditService);
    }

    // ── Etape 6 : transfert des favoris, doublons ignores ─────────────────────

    @Test
    @DisplayName("favori déjà présent chez l'appelant → ignoré, le transfert aboutit")
    void duplicateFavoriteIsSkipped() {
        // Sans ce comportement, la contrainte d'unicité (user_id, target) ferait
        // échouer TOUT le transfert à cause d'un seul doublon : l'index unique partiel
        // ux_favorites_active (user_id, target_type, target_id) WHERE deleted_at IS NULL
        // refuserait la reassignation, et l'exception remonterait sur tout l'appel.
        UserEntity guest = userWithId(GUEST_UID);
        UserEntity caller = userWithId(CALLER_UID);
        when(userRepository.findByFirebaseUid(GUEST_UID)).thenReturn(Optional.of(guest));
        when(userRepository.findByFirebaseUid(CALLER_UID)).thenReturn(Optional.of(caller));

        UUID dejaFavori = UUID.randomUUID();
        UUID nouveau = UUID.randomUUID();
        FavoriteEntity doublon = new FavoriteEntity(guest.getId(), FavoriteTargetType.TRIP, dejaFavori);
        FavoriteEntity aTransferer =
                new FavoriteEntity(guest.getId(), FavoriteTargetType.PACKAGE_REQUEST, nouveau);
        when(favoriteRepository.findByUserIdOrderByCreatedAtDesc(guest.getId()))
                .thenReturn(List.of(doublon, aTransferer));
        when(favoriteRepository.existsByUserIdAndTargetTypeAndTargetId(
                caller.getId(), FavoriteTargetType.TRIP, dejaFavori)).thenReturn(true);
        when(favoriteRepository.existsByUserIdAndTargetTypeAndTargetId(
                caller.getId(), FavoriteTargetType.PACKAGE_REQUEST, nouveau)).thenReturn(false);

        serviceWithGuestToken("anonymous", GUEST_UID).claim(CALLER_UID, PRESENTED_TOKEN);

        // Un seul favori sauvegarde : celui qui n'existait pas encore chez l'appelant.
        ArgumentCaptor<FavoriteEntity> saved = ArgumentCaptor.forClass(FavoriteEntity.class);
        verify(favoriteRepository).save(saved.capture());
        assertThat(saved.getValue().getTargetId()).isEqualTo(nouveau);
        assertThat(saved.getValue().getUserId()).isEqualTo(caller.getId());

        // Le doublon reste sur la ligne invitee, qui va etre supprimee.
        assertThat(doublon.getUserId()).isEqualTo(guest.getId());

        // Le transfert aboutit malgre le doublon : ligne invitee supprimee et audit ecrit.
        assertThat(guest.getDeletedAt()).isNotNull();
        assertThat(auditPayload(caller.getId()))
                .containsEntry("favoritesTransferred", 1)
                .containsEntry("favoritesSkipped", 1);
    }

    // ── Etapes 6 a 8 : nominal ────────────────────────────────────────────────

    @Test
    @DisplayName("transfert nominal → favoris déplacés, ligne invitée supprimée")
    void transfersThenDeletesGuestRow() {
        UserEntity guest = userWithId(GUEST_UID);
        UserEntity caller = userWithId(CALLER_UID);
        when(userRepository.findByFirebaseUid(GUEST_UID)).thenReturn(Optional.of(guest));
        when(userRepository.findByFirebaseUid(CALLER_UID)).thenReturn(Optional.of(caller));

        UUID trajet = UUID.randomUUID();
        UUID colis = UUID.randomUUID();
        FavoriteEntity favTrajet = new FavoriteEntity(guest.getId(), FavoriteTargetType.TRIP, trajet);
        FavoriteEntity favColis =
                new FavoriteEntity(guest.getId(), FavoriteTargetType.PACKAGE_REQUEST, colis);
        when(favoriteRepository.findByUserIdOrderByCreatedAtDesc(guest.getId()))
                .thenReturn(List.of(favTrajet, favColis));
        when(favoriteRepository.existsByUserIdAndTargetTypeAndTargetId(
                eq(caller.getId()), any(), any())).thenReturn(false);

        serviceWithGuestToken("anonymous", GUEST_UID).claim(CALLER_UID, PRESENTED_TOKEN);

        // Les deux favoris changent de proprietaire, sans etre recrees (created_at conserve).
        assertThat(favTrajet.getUserId()).isEqualTo(caller.getId());
        assertThat(favColis.getUserId()).isEqualTo(caller.getId());
        verify(favoriteRepository).save(favTrajet);
        verify(favoriteRepository).save(favColis);

        // La ligne invitee disparait — soft delete, la regle du projet interdit les
        // suppressions physiques d'entites metier (et favorites.user_id reference
        // users(id) sans ON DELETE CASCADE : un DELETE physique echouerait des qu'un
        // doublon a ete laisse sur la ligne invitee).
        assertThat(guest.getDeletedAt()).isNotNull();
        verify(userRepository).save(guest);

        // Trace d'audit : sans l'UID invite, l'entree ne permet plus de relier les deux
        // sessions. La cle `guestUid` survit a AuditService.redact() (amendement A11) —
        // verrouille cote AuditServiceTest.
        assertThat(auditPayload(caller.getId()))
                .containsEntry("guestUid", GUEST_UID)
                .containsEntry("favoritesTransferred", 2)
                .containsEntry("favoritesSkipped", 0);
    }

    // ── Outillage ─────────────────────────────────────────────────────────────

    private GuestClaimService service() {
        return new GuestClaimService(firebaseAuth, userRepository, favoriteRepository, auditService);
    }

    private GuestClaimService serviceWithGuestToken(String provider, String uid) {
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getUid()).thenReturn(uid);
        when(token.getClaims()).thenReturn(Map.of("firebase", Map.of("sign_in_provider", provider)));
        try {
            when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);
        } catch (FirebaseAuthException e) {
            throw new AssertionError(e);
        }
        return service();
    }

    private static UserEntity userWithId(String firebaseUid) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditPayload(UUID callerId) {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("USER"), eq(callerId), eq("GUEST_DATA_CLAIMED"),
                eq(callerId), payload.capture());
        return payload.getValue();
    }
}
