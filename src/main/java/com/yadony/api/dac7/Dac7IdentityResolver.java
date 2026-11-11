package com.yadony.api.dac7;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.travelerstatus.TravelerStatus;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Identité d'un vendeur DAC7 sans RIEN redemander au voyageur, à partir de ce que Yadony détient déjà :
 * <ol>
 *   <li><b>pièce vérifiée</b> par le prestataire KYC ({@link Dac7VerifiedIdentityPort}, Didit aujourd'hui) : nom et
 *       date de naissance lus sur la pièce, la source la plus fiable ;</li>
 *   <li><b>profil</b> ({@code users}) : prénom et nom, date de naissance et adresse de résidence quand le voyageur les
 *       a saisis (l'app ne les demande plus : Stripe Connect les collecte dans son propre formulaire).</li>
 * </ol>
 * Nom et date de naissance : la pièce d'abord, le profil sinon. Adresse, dans l'ordre : celle du profil si elle est
 * complète (rue et ville) ; sinon celle de la pièce SEULEMENT si son pays est lisible ET égal au pays de résidence
 * (une adresse d'Abidjan sur la pièce d'un résident français n'est jamais déclarée en France) ; sinon inconnue.
 * Stripe Connect n'est pas relu (voir le rapport du lot 3b).
 *
 * <p>Prestataire injoignable : le profil seul est utilisé ; s'il ne suffit pas, {@link Dac7IdentityStatus#UNAVAILABLE}
 * (réessayable), jamais INCOMPLETE.
 *
 * <p>Professionnel ({@code PROFESSIONAL}, statut figé à la suppression compris) : entité ; aucune adresse de siège
 * n'existe dans les données, l'entité reste donc INCOMPLETE (revue humaine).
 *
 * <p>Rien n'est persisté. Les pages admin lisent un VERDICT gardé 6 heures en mémoire (jamais un verdict obtenu sur
 * panne), calculé au plus {@value #PARALLEL_LOOKUPS} à la fois ; l'export relit toujours la pièce en direct.
 */
@Component
public class Dac7IdentityResolver implements DisposableBean {

    static final int PARALLEL_LOOKUPS = 4;
    private static final long LOOKUP_TIMEOUT_SECONDS = 20;

    private final Dac7VerifiedIdentityPort verified;
    private final Cache<UUID, Dac7IdentityStatus> verdicts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(6)).maximumSize(20_000).build();
    private final ExecutorService lookups = Executors.newFixedThreadPool(PARALLEL_LOOKUPS, r -> {
        Thread thread = new Thread(r, "dac7-identity");
        thread.setDaemon(true);
        return thread;
    });

    public Dac7IdentityResolver(Dac7VerifiedIdentityPort verified) {
        this.verified = verified;
    }

    @Override
    public void destroy() {
        lookups.shutdownNow();
    }

    /** Verdict seul, en cache (pages admin). */
    public Dac7IdentityStatus status(UserEntity user) {
        Dac7IdentityStatus cached = verdicts.getIfPresent(user.getId());
        return cached != null ? cached : resolve(user).status();
    }

    /** Verdicts de plusieurs voyageurs (pages admin) : cache d'abord, puis au plus 4 lectures en parallèle. */
    public Map<UUID, Dac7IdentityStatus> statuses(Collection<UserEntity> users) {
        Map<UUID, Dac7IdentityStatus> result = new HashMap<>();
        Map<UUID, CompletableFuture<Dac7IdentityStatus>> pending = new HashMap<>();
        for (UserEntity user : users) {
            Dac7IdentityStatus cached = verdicts.getIfPresent(user.getId());
            if (cached != null) {
                result.put(user.getId(), cached);
            } else {
                pending.put(user.getId(), CompletableFuture.supplyAsync(() -> resolve(user).status(), lookups));
            }
        }
        pending.forEach((id, future) -> {
            try {
                result.put(id, future.get(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.put(id, Dac7IdentityStatus.UNAVAILABLE);
            } catch (Exception e) {
                future.cancel(true);
                result.put(id, Dac7IdentityStatus.UNAVAILABLE);
            }
        });
        return result;
    }

    /** Compte introuvable (supprimé : identité effacée par la finalisation). */
    public Dac7SellerIdentity resolveMissing(UUID userId) {
        return new Dac7SellerIdentity(userId, Dac7SellerIdentity.SellerType.INDIVIDUAL, Dac7IdentityStatus.INCOMPLETE,
                List.of("ACCOUNT_DELETED"), null, null, null, null, null, null);
    }

    /** Identité complète, relue en direct (export). Met à jour le verdict en cache, sauf sur panne. */
    public Dac7SellerIdentity resolve(UserEntity user) {
        Dac7SellerIdentity identity = user.getDeletedAt() != null ? resolveMissing(user.getId()) : compute(user, true);
        if (identity.status() == Dac7IdentityStatus.UNAVAILABLE) {
            verdicts.invalidate(user.getId());
        } else {
            verdicts.put(user.getId(), identity.status());
        }
        return identity;
    }

    /**
     * Verdict du profil seul, sans relire la pièce : pour les voyageurs NON déclarés (résidence inconnue ou hors
     * périmètre) et les listes qui n'ont besoin que des noms.
     */
    public Dac7IdentityStatus profileStatus(UserEntity user) {
        return user.getDeletedAt() != null ? Dac7IdentityStatus.INCOMPLETE : compute(user, false).status();
    }

    private Dac7SellerIdentity compute(UserEntity user, boolean readPiece) {
        TravelerStatus seller = user.getTaxSellerStatus() != null ? user.getTaxSellerStatus() : user.getTravelerStatus();
        String residence = Dac7ResidencyPolicy.residenceCountryOf(user);
        if (seller == TravelerStatus.PROFESSIONAL) {
            List<String> missing = new ArrayList<>();
            if (blank(user.getBusinessName())) missing.add("BUSINESS_NAME");
            if (blank(user.getBusinessRegistrationNumber())) missing.add("REGISTRATION_NUMBER");
            // Aucune adresse de siège n'est collectée (lot 2b) : jamais déclarée en silence.
            missing.add("BUSINESS_ADDRESS");
            return new Dac7SellerIdentity(user.getId(), Dac7SellerIdentity.SellerType.ENTITY,
                    Dac7IdentityStatus.INCOMPLETE, List.copyOf(missing), null, null, null, null,
                    trim(user.getBusinessName()), trim(user.getBusinessRegistrationNumber()));
        }
        Dac7VerifiedIdentityPort.Lookup lookup = readPiece ? verified.verifiedIdentity(user.getId())
                : Dac7VerifiedIdentityPort.Lookup.none();
        boolean unavailable = lookup == null || lookup.status() == Dac7VerifiedIdentityPort.Status.UNAVAILABLE;
        Dac7VerifiedIdentity piece = lookup != null && lookup.status() == Dac7VerifiedIdentityPort.Status.FOUND
                ? lookup.identity() : null;
        String first = piece != null && !blank(piece.firstName()) ? piece.firstName().strip() : trim(user.getFirstName());
        String last = piece != null && !blank(piece.lastName()) ? piece.lastName().strip() : trim(user.getLastName());
        LocalDate birth = piece != null && piece.birthDate() != null ? piece.birthDate() : user.getBirthDate();
        Dac7Address address = profileAddress(user, residence);
        if (address == null && piece != null && piece.address() != null && piece.address().usable()
                && residence != null && residence.equals(piece.address().countryCode())) {
            address = piece.address();
        }
        List<String> missing = new ArrayList<>();
        if (blank(first) || blank(last)) missing.add("NAME");
        if (birth == null) missing.add("BIRTH_DATE");
        if (address == null) missing.add("ADDRESS");
        Dac7IdentityStatus status = missing.isEmpty() ? Dac7IdentityStatus.COMPLETE
                : unavailable ? Dac7IdentityStatus.UNAVAILABLE : Dac7IdentityStatus.INCOMPLETE;
        return new Dac7SellerIdentity(user.getId(), Dac7SellerIdentity.SellerType.INDIVIDUAL, status,
                List.copyOf(missing), first, last, birth, address, null, null);
    }

    private static Dac7Address profileAddress(UserEntity user, String residence) {
        if (blank(user.getResidenceStreet()) || blank(user.getCity()) || residence == null) {
            return null;
        }
        return new Dac7Address(trim(user.getResidenceStreet()), trim(user.getResidenceLine2()),
                trim(user.getResidencePostalCode()), trim(user.getCity()), residence, null);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return blank(value) ? null : value.strip();
    }
}
