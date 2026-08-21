package com.yadony.api.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.auth.UserRecord;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.FirebaseSignInProvider;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.favorites.FavoriteEntity;
import com.yadony.api.favorites.FavoriteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Transfère les données d'une session visiteur vers le compte réel de la même personne.
 *
 * <p><b>Quand cela se produit.</b> Le visiteur anonyme finit par s'inscrire. L'app tente un
 * {@code linkWithCredential} pour promouvoir sa session, mais celui-ci échoue quand le numéro
 * appartient déjà à un compte existant. L'app bascule alors sur ce compte : l'utilisateur est
 * authentifié sur son vrai compte, et sa session anonyme, encore valide quelques minutes,
 * détient les favoris posés pendant sa visite. C'est ce que cette classe récupère.
 *
 * <p><b>Le mécanisme de sécurité.</b> L'appelant est authentifié sur son vrai compte et
 * présente dans le corps de la requête le token anonyme lui-même. Vérifier ce token auprès de
 * Firebase prouve qu'il contrôlait cette session : un UID deviné ou lu ailleurs ne donne rien.
 * Quatre refus complètent la preuve, dans cet ordre :
 * <ul>
 *   <li>un token dont le claim n'est <b>pas anonyme</b> est rejeté — l'accepter permettrait de
 *       siphonner les favoris de n'importe quel utilisateur dont on aurait intercepté un
 *       jeton ;</li>
 *   <li>un token qui est celui de l'appelant lui-même est rejeté — il n'y a rien à transférer,
 *       et le traiter reviendrait à supprimer la ligne de l'appelant ;</li>
 *   <li>un compte Firebase <b>déjà promu</b> est rejeté, même si le claim du jeton annonce
 *       encore {@code anonymous} : le claim est figé à l'émission alors que
 *       {@code linkWithCredential} conserve l'UID, si bien qu'un jeton d'avant la promotion
 *       désignerait un vrai compte pendant environ une heure ;</li>
 *   <li>une ligne {@code users} <b>porteuse de rôles</b> est rejetée : les lignes invitées
 *       naissent sans aucun rôle, celle-ci est donc un compte réel. Barrière indépendante de
 *       Firebase, qui tient même si l'appel réseau ci-dessus venait à être retiré.</li>
 * </ul>
 *
 * <p>Les deux derniers refus protègent un scénario précis : sans eux, présenter le jeton
 * pré-promotion d'un vrai compte depuis un <b>autre</b> compte ferait transférer ses favoris
 * puis <b>soft-deleter sa ligne {@code users}</b>. Le mécanisme n'est censé déplacer que les
 * favoris d'un visiteur, jamais détruire un compte.
 *
 * <p><b>Périmètre (amendement A14).</b> Seuls les favoris sont transférés. Les alertes
 * corridor sont sorties du périmètre invité : leur création exige un rôle précis selon la
 * direction demandée, et un invité n'en a aucun. Cette classe ne connaît donc rien du package
 * {@code alerts/}.
 */
@Service
public class GuestClaimService {

    private static final Logger log = LoggerFactory.getLogger(GuestClaimService.class);

    private final FirebaseAuth firebaseAuth;
    private final UserRepository userRepository;
    private final FavoriteRepository favoriteRepository;
    private final AuditService auditService;

    /**
     * {@code firebaseAuth} est {@link Nullable} : {@code FirebaseConfig} ne publie aucun
     * {@code FirebaseAuth} en test/CI (Firebase n'y est jamais initialisé). Sans cela, le
     * contexte Spring des tests d'intégration ne démarrerait plus.
     */
    public GuestClaimService(@Nullable FirebaseAuth firebaseAuth,
                             UserRepository userRepository,
                             FavoriteRepository favoriteRepository,
                             AuditService auditService) {
        this.firebaseAuth = firebaseAuth;
        this.userRepository = userRepository;
        this.favoriteRepository = favoriteRepository;
        this.auditService = auditService;
    }

    /**
     * Rattache au compte de {@code callerFirebaseUid} les favoris de la session anonyme dont
     * {@code guestIdToken} est le jeton.
     *
     * <p>Idempotent : appelé deux fois, le second appel ne trouve plus de ligne invitée et ne
     * fait rien. L'app peut donc l'appeler systématiquement après une bascule de compte, sans
     * savoir si le visiteur avait posé le moindre favori.
     *
     * @throws YadonyBusinessException 401 {@code guest-claim-invalid-token} si le jeton
     *         présenté n'est pas vérifiable ou si le compte qu'il désigne est introuvable ;
     *         422 {@code guest-claim-not-anonymous} si son claim n'est pas celui d'une session
     *         anonyme ; 422 {@code guest-claim-self} s'il est celui de l'appelant ;
     *         422 {@code guest-claim-promoted-account} si le compte Firebase désigné n'est
     *         plus anonyme ; 422 {@code guest-claim-has-roles} si sa ligne {@code users} porte
     *         des rôles ; 404 {@code user-not-found} si l'appelant n'a pas de ligne.
     */
    @Transactional
    public void claim(String callerFirebaseUid, String guestIdToken) {
        // 1. Le jeton présenté doit être vérifiable par Firebase. C'est toute la preuve de
        //    possession : sans elle, connaître un UID suffirait à réclamer ses favoris.
        FirebaseToken decoded = verifyGuestToken(guestIdToken);

        // 2. ... et il doit être ANONYME. Accepter un jeton de compte réel transformerait cet
        //    endpoint en aspirateur à favoris pour quiconque intercepte un jeton.
        if (!FirebaseSignInProvider.isAnonymous(decoded)) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "guest-claim-not-anonymous",
                    "Session non anonyme",
                    "Le jeton présenté n'est pas celui d'une session visiteur.");
        }

        String guestUid = decoded.getUid();

        // 3. ... et il ne peut pas être celui de l'appelant : il n'y aurait rien à transférer,
        //    et la suppression de « la ligne invitée » supprimerait son propre compte.
        if (guestUid.equals(callerFirebaseUid)) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "guest-claim-self",
                    "Session identique à l'appelant",
                    "Le jeton présenté est celui de la session appelante : il n'y a rien à transférer.");
        }

        // 3 bis. Barrière A — l'état COURANT du compte Firebase, pas le claim figé.
        //    `sign_in_provider` est gravé à l'émission du jeton, et `linkWithCredential`
        //    CONSERVE l'UID : un jeton émis avant la promotion reste valide environ une
        //    heure et continue d'annoncer `anonymous` alors que l'UID désigne désormais un
        //    vrai compte. Sans ce contrôle, présenter un tel jeton depuis un AUTRE compte
        //    ferait transférer les favoris de ce vrai compte, puis soft-deleter sa ligne
        //    `users`. On interroge donc Firebase : un compte encore anonyme n'a aucun
        //    fournisseur rattaché.
        requireStillAnonymousAccount(guestUid);

        // 4. Aucune ligne invitée : le cas de loin le plus fréquent, la matérialisation
        //    paresseuse ne créant une ligne qu'au premier favori. Succès sans effet.
        Optional<UserEntity> guestRow = userRepository.findByFirebaseUid(guestUid);
        if (guestRow.isEmpty()) {
            return;
        }
        UserEntity guest = guestRow.get();

        // 4 bis. Barrière B — seconde barrière, côté base et indépendante de Firebase.
        //    `GuestUserProvisioner` crée les lignes invitées SANS aucun rôle : une ligne
        //    porteuse du moindre rôle n'en est donc jamais une. Même garde que celle du
        //    provisioner sur la réactivation, et elle ne coûte rien.
        if (!guest.getRoles().isEmpty()) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "guest-claim-has-roles",
                    "Compte réel",
                    "La session désignée appartient à un compte inscrit, pas à un visiteur.");
        }

        // 5. La ligne de l'appelant, elle, doit exister : il est authentifié sur un vrai compte.
        UserEntity caller = userRepository.findByFirebaseUid(callerFirebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"));

        // 6. Transfert des favoris, doublons ignorés.
        TransferResult favorites = transferFavorites(guest.getId(), caller.getId());

        // 7. La ligne invitée disparaît. Soft delete : la règle du projet interdit les
        //    suppressions physiques d'entités métier, et `favorites.user_id` référence
        //    `users(id)` sans ON DELETE CASCADE — un DELETE physique échouerait dès qu'un
        //    doublon a été laissé en place à l'étape 6.
        guest.softDelete();
        userRepository.save(guest);

        // 8. Trace d'audit. `guestUid` survit à AuditService.redact() (denylist vérifiée) :
        //    sans lui, l'entrée ne permettrait plus de relier les deux sessions.
        auditService.log("USER", caller.getId(), "GUEST_DATA_CLAIMED", caller.getId(),
                auditPayload(guestUid, guest.getId(), favorites));

        log.info("Données de session invitée réclamées: callerId={} guestUserId={} favoris transférés={} ignorés={}",
                caller.getId(), guest.getId(), favorites.transferred(), favorites.discarded());
    }

    /**
     * Refuse si le compte Firebase désigné n'est <b>plus</b> anonyme.
     *
     * <p>Un compte anonyme n'a aucun fournisseur d'identité rattaché :
     * {@code getProviderData()} y est vide. Dès qu'un {@code linkWithCredential} a réussi,
     * le fournisseur (téléphone, Google, Apple…) y apparaît, alors même que les jetons émis
     * avant la promotion continuent d'annoncer {@code sign_in_provider = anonymous}.
     *
     * <p>Fermé par défaut : si le compte a disparu ou si Firebase ne répond pas, on refuse
     * plutôt que de supposer l'anonymat.
     */
    private void requireStillAnonymousAccount(String guestUid) {
        UserRecord record;
        try {
            record = firebaseAuth.getUser(guestUid);
        } catch (FirebaseAuthException | IllegalArgumentException e) {
            throw new YadonyBusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "guest-claim-invalid-token",
                    "Jeton visiteur invalide",
                    "Le jeton de la session visiteur est invalide ou a expiré.");
        }
        UserInfo[] providers = record.getProviderData();
        if (providers != null && providers.length > 0) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "guest-claim-promoted-account",
                    "Session déjà promue",
                    "Cette session visiteur est devenue un compte inscrit : ses données ne "
                            + "peuvent plus être réclamées.");
        }
    }

    private FirebaseToken verifyGuestToken(String guestIdToken) {
        if (firebaseAuth == null) {
            // Erreur d'environnement, pas d'usage : aucun client ne peut la provoquer.
            throw new IllegalStateException(
                    "FirebaseAuth indisponible dans cet environnement : impossible de vérifier "
                            + "le jeton de la session visiteur.");
        }
        try {
            return firebaseAuth.verifyIdToken(guestIdToken);
        } catch (FirebaseAuthException | IllegalArgumentException e) {
            // Jeton expiré, révoqué, malformé ou vide. Aucun détail renvoyé au client : le
            // message distinguerait « jeton d'une session qui existe » de « jeton inventé ».
            throw new YadonyBusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "guest-claim-invalid-token",
                    "Jeton visiteur invalide",
                    "Le jeton de la session visiteur est invalide ou a expiré.");
        }
    }

    /**
     * Réassigne les favoris de l'invité à l'appelant, en ignorant ceux que l'appelant possède
     * déjà.
     *
     * <p>Le contrôle d'existence n'est pas une optimisation : l'index unique partiel
     * {@code ux_favorites_active (user_id, target_type, target_id) WHERE deleted_at IS NULL}
     * ferait échouer tout le transfert sur un seul doublon. L'appelant garde le sien, plus
     * ancien.
     *
     * <p>Le doublon est <b>supprimé physiquement</b> et non laissé en place : attaché à une
     * ligne {@code users} soft-deletée, il deviendrait invisible à toute requête JPQL (filtre
     * {@code @Where}) et plus rien ne le purgerait jamais. Le hard delete est le régime assumé
     * des favoris dans ce dépôt (retrait d'un favori, migration {@code V172}), la règle
     * générale de soft delete ne s'y applique pas.
     */
    private TransferResult transferFavorites(UUID guestId, UUID callerId) {
        List<FavoriteEntity> guestFavorites = favoriteRepository.findByUserIdOrderByCreatedAtDesc(guestId);
        int transferred = 0;
        for (FavoriteEntity favorite : guestFavorites) {
            boolean alreadyOwned = favoriteRepository.existsByUserIdAndTargetTypeAndTargetId(
                    callerId, favorite.getTargetType(), favorite.getTargetId());
            if (alreadyOwned) {
                favoriteRepository.delete(favorite);
                continue;
            }
            favorite.reassignTo(callerId);
            favoriteRepository.save(favorite);
            transferred++;
        }
        return new TransferResult(transferred, guestFavorites.size() - transferred);
    }

    private static Map<String, Object> auditPayload(String guestUid, UUID guestUserId, TransferResult favorites) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("guestUid", guestUid);
        payload.put("guestUserId", guestUserId.toString());
        payload.put("favoritesTransferred", favorites.transferred());
        payload.put("favoritesDiscarded", favorites.discarded());
        return payload;
    }

    /** Favoris réellement déplacés, et favoris laissés parce que l'appelant les avait déjà. */
    private record TransferResult(int transferred, int discarded) {
    }
}
