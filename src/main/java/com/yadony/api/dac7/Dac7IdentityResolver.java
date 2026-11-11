package com.yadony.api.dac7;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.travelerstatus.TravelerStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Identité d'un vendeur DAC7 sans RIEN redemander au voyageur, à partir de ce que Yadony détient déjà :
 * <ol>
 *   <li><b>pièce vérifiée</b> par le prestataire KYC ({@link Dac7VerifiedIdentityPort}, Didit aujourd'hui) : nom et
 *       date de naissance lus sur la pièce, la source la plus fiable ; l'adresse quand la pièce en porte une ;</li>
 *   <li><b>profil</b> ({@code users}) : prénom et nom, date de naissance et adresse de résidence quand le voyageur les
 *       a saisis (l'app ne les demande plus : Stripe Connect les collecte dans son propre formulaire).</li>
 * </ol>
 * Nom et date de naissance : la pièce d'abord, le profil sinon. Adresse : celle du profil si elle est complète (rue et
 * ville, la résidence déclarée la plus récente), sinon celle de la pièce. Jamais de mélange au sein d'une adresse.
 * Pays de l'adresse : celui lu sur la pièce s'il est lisible, sinon le pays de résidence du profil.
 *
 * <p>Professionnel ({@code PROFESSIONAL}, statut figé à la suppression compris) : entité, raison sociale et
 * immatriculation du profil ; aucune adresse de siège n'existe dans les données, l'entité reste donc INCOMPLETE (revue
 * humaine).
 *
 * <p>Rien n'est persisté. Seul le VERDICT ({@link #status}) est gardé en mémoire 10 minutes, pour que les pages admin
 * ne relisent pas la pièce de chaque voyageur à chaque affichage ; les valeurs, elles, sont relues à chaque export.
 */
@Component
public class Dac7IdentityResolver {

    private final Dac7VerifiedIdentityPort verified;
    private final Cache<UUID, Dac7IdentityStatus> verdicts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10)).maximumSize(20_000).build();

    public Dac7IdentityResolver(Dac7VerifiedIdentityPort verified) {
        this.verified = verified;
    }

    /** Verdict seul, en cache court (pages admin). */
    public Dac7IdentityStatus status(UserEntity user) {
        Dac7IdentityStatus cached = verdicts.getIfPresent(user.getId());
        return cached != null ? cached : resolve(user).status();
    }

    /** Compte introuvable (supprimé : identité effacée par la finalisation). */
    public Dac7SellerIdentity resolveMissing(UUID userId) {
        return new Dac7SellerIdentity(userId, Dac7SellerIdentity.SellerType.INDIVIDUAL, Dac7IdentityStatus.INCOMPLETE,
                List.of("ACCOUNT_DELETED"), null, null, null, null, null, null);
    }

    /** Identité complète, relue à chaque appel (export). Met à jour le verdict en cache. */
    public Dac7SellerIdentity resolve(UserEntity user) {
        Dac7SellerIdentity identity = user.getDeletedAt() != null ? resolveMissing(user.getId()) : compute(user, true);
        verdicts.put(user.getId(), identity.status());
        return identity;
    }

    /**
     * Verdict du profil seul, sans relire la pièce : pour les voyageurs NON déclarés (résidence inconnue ou hors
     * périmètre), qu'il serait inutile et coûteux d'interroger chez le prestataire.
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
        Optional<Dac7VerifiedIdentity> piece = readPiece ? verified.verifiedIdentity(user.getId()) : Optional.empty();
        String first = piece.map(Dac7VerifiedIdentity::firstName).filter(v -> !blank(v)).orElse(trim(user.getFirstName()));
        String last = piece.map(Dac7VerifiedIdentity::lastName).filter(v -> !blank(v)).orElse(trim(user.getLastName()));
        LocalDate birth = piece.map(Dac7VerifiedIdentity::birthDate).orElse(user.getBirthDate());
        Dac7Address address = profileAddress(user, residence);
        if (address == null) {
            address = piece.map(Dac7VerifiedIdentity::address)
                    .map(a -> a.countryCode() != null ? a
                            : new Dac7Address(a.street(), a.line2(), a.postCode(), a.city(), residence, a.free()))
                    .filter(Dac7Address::usable)
                    .orElse(null);
        }
        List<String> missing = new ArrayList<>();
        if (blank(first) || blank(last)) missing.add("NAME");
        if (birth == null) missing.add("BIRTH_DATE");
        if (address == null) missing.add("ADDRESS");
        return new Dac7SellerIdentity(user.getId(), Dac7SellerIdentity.SellerType.INDIVIDUAL,
                missing.isEmpty() ? Dac7IdentityStatus.COMPLETE : Dac7IdentityStatus.INCOMPLETE, List.copyOf(missing),
                first, last, birth, address, null, null);
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
