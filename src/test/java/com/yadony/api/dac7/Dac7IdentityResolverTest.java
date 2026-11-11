package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.dac7.Dac7VerifiedIdentityPort.Lookup;
import com.yadony.api.travelerstatus.TravelerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Identité DAC7 : profil puis pièce vérifiée, panne distinguée, jamais rien redemandé")
class Dac7IdentityResolverTest {

    private final Dac7VerifiedIdentityPort port = mock(Dac7VerifiedIdentityPort.class);
    private final Dac7IdentityResolver resolver = new Dac7IdentityResolver(port);

    private static UserEntity user(String country) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirstName("Awa");
        u.setLastName("Diallo");
        u.setCountry(country);
        return u;
    }

    private static Lookup verified(LocalDate birth, Dac7Address address) {
        return Lookup.found(new Dac7VerifiedIdentity("AWA", "DIALLO", birth, address));
    }

    @Test
    @DisplayName("profil complet (date de naissance, rue et ville) : complet, la pièce vérifiée l'emporte pour le nom")
    void profilComplet() {
        UserEntity u = user("FR");
        u.setBirthDate(LocalDate.of(1990, 1, 2));
        u.setResidenceStreet("1 rue de Paris");
        u.setCity("Lyon");
        u.setResidencePostalCode("69001");
        when(port.verifiedIdentity(u.getId())).thenReturn(verified(null, null));

        Dac7SellerIdentity id = resolver.resolve(u);

        assertThat(id.status()).isEqualTo(Dac7IdentityStatus.COMPLETE);
        assertThat(id.missing()).isEmpty();
        assertThat(id.type()).isEqualTo(Dac7SellerIdentity.SellerType.INDIVIDUAL);
        assertThat(id.firstName()).isEqualTo("AWA");
        assertThat(id.birthDate()).isEqualTo(LocalDate.of(1990, 1, 2));
        assertThat(id.address().city()).isEqualTo("Lyon");
        assertThat(id.address().countryCode()).isEqualTo("FR");
    }

    @Test
    @DisplayName("profil sans date ni adresse : lues sur la pièce, adresse retenue seulement si son pays est celui de la résidence")
    void pieceVerifiee() {
        UserEntity u = user("FR");
        when(port.verifiedIdentity(u.getId())).thenReturn(verified(LocalDate.of(1985, 5, 6),
                new Dac7Address("3 avenue Foch", null, "75016", "Paris", "FR", null)));

        Dac7SellerIdentity id = resolver.resolve(u);

        assertThat(id.status()).isEqualTo(Dac7IdentityStatus.COMPLETE);
        assertThat(id.birthDate()).isEqualTo(LocalDate.of(1985, 5, 6));
        assertThat(id.address().street()).isEqualTo("3 avenue Foch");
    }

    @Test
    @DisplayName("adresse de la pièce sans pays lisible ou d'un autre pays : jamais attribuée à la résidence, adresse inconnue")
    void adresseDeLaPieceRefusee() {
        for (String country : new String[]{null, "CI"}) {
            UserEntity u = user("FR");
            when(port.verifiedIdentity(u.getId())).thenReturn(verified(LocalDate.of(1985, 5, 6),
                    new Dac7Address("Rue des Jardins", null, null, "Abidjan", country, null)));
            Dac7SellerIdentity id = resolver.resolve(u);
            assertThat(id.status()).isEqualTo(Dac7IdentityStatus.INCOMPLETE);
            assertThat(id.missing()).containsExactly("ADDRESS");
            assertThat(id.address()).isNull();
        }
    }

    @Test
    @DisplayName("aucune source : incomplet, avec la liste de ce qui manque (sans aucune valeur)")
    void aucuneSource() {
        UserEntity u = user("FR");
        u.setLastName(null);
        when(port.verifiedIdentity(any())).thenReturn(Lookup.none());

        Dac7SellerIdentity id = resolver.resolve(u);

        assertThat(id.status()).isEqualTo(Dac7IdentityStatus.INCOMPLETE);
        assertThat(id.missing()).containsExactly("NAME", "BIRTH_DATE", "ADDRESS");
        assertThat(id.toString()).doesNotContain("Awa");
    }

    @Test
    @DisplayName("prestataire en panne, profil insuffisant : UNAVAILABLE (réessayable), jamais mis en cache")
    void panne() {
        UserEntity u = user("FR");
        when(port.verifiedIdentity(u.getId())).thenReturn(Lookup.unavailable());

        assertThat(resolver.resolve(u).status()).isEqualTo(Dac7IdentityStatus.UNAVAILABLE);
        assertThat(resolver.status(u)).isEqualTo(Dac7IdentityStatus.UNAVAILABLE);
        verify(port, times(2)).verifiedIdentity(u.getId());
    }

    @Test
    @DisplayName("erreur permanente du prestataire (404, 403) : INCOMPLETE avec IDENTITY_SOURCE_UNREADABLE, jamais UNAVAILABLE")
    void illisible() {
        UserEntity u = user("FR");
        when(port.verifiedIdentity(u.getId())).thenReturn(Lookup.unreadable());
        Dac7SellerIdentity id = resolver.resolve(u);
        assertThat(id.status()).isEqualTo(Dac7IdentityStatus.INCOMPLETE);
        assertThat(id.missing()).containsExactly("BIRTH_DATE", "ADDRESS", "IDENTITY_SOURCE_UNREADABLE");
    }

    @Test
    @DisplayName("pages admin : budget total dépassé, NOT_CHECKED (jamais lu) sans attendre ; les lectures en file n'appellent pas le prestataire")
    void budgetTotal() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Dac7VerifiedIdentityPort slow = id -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Lookup.none();
        };
        Dac7IdentityResolver bounded = new Dac7IdentityResolver(slow, java.time.Duration.ofMillis(200));
        List<UserEntity> users = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) users.add(user("FR"));

        long start = System.nanoTime();
        Map<UUID, Dac7IdentityStatus> result = bounded.statuses(users);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Aucune lecture n'a abouti dans le budget : « non vérifié », jamais une panne du prestataire inventée.
        assertThat(result).hasSize(10);
        assertThat(result.values()).allMatch(v -> v == Dac7IdentityStatus.NOT_CHECKED);
        assertThat(elapsedMs).isLessThan(1_200);
        // Seules les 4 lectures déjà commencées ont appelé le prestataire ; les 6 en file sont sorties sans appel.
        try {
            Thread.sleep(1_800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(calls.get()).isLessThanOrEqualTo(Dac7IdentityResolver.PARALLEL_LOOKUPS);
        bounded.destroy();
    }

    @Test
    @DisplayName("au-delà de 40 lectures nouvelles : NOT_CHECKED sans appel ; une vraie panne du prestataire reste UNAVAILABLE")
    void auDelaDuPlafond() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        UserEntity down = user("FR");
        Dac7VerifiedIdentityPort fast = id -> {
            calls.incrementAndGet();
            return id.equals(down.getId()) ? Lookup.unavailable() : Lookup.none();
        };
        Dac7IdentityResolver bounded = new Dac7IdentityResolver(fast, java.time.Duration.ofSeconds(5));
        List<UserEntity> users = new java.util.ArrayList<>(List.of(down));
        for (int i = 0; i < 49; i++) users.add(user("FR"));

        Map<UUID, Dac7IdentityStatus> result = bounded.statuses(users);

        assertThat(result).hasSize(50).containsEntry(down.getId(), Dac7IdentityStatus.UNAVAILABLE);
        assertThat(result.values().stream().filter(v -> v == Dac7IdentityStatus.NOT_CHECKED).count())
                .isEqualTo(50 - Dac7IdentityResolver.MAX_LOOKUPS_PER_REQUEST);
        assertThat(calls.get()).isEqualTo(Dac7IdentityResolver.MAX_LOOKUPS_PER_REQUEST);
        // Passage suivant : les verdicts en cache servent, les 10 restants sont lus.
        Map<UUID, Dac7IdentityStatus> next = bounded.statuses(users);
        assertThat(next.values()).doesNotContain(Dac7IdentityStatus.NOT_CHECKED);
        bounded.destroy();
    }

    @Test
    @DisplayName("prestataire en panne mais profil complet : complet, le profil suffit")
    void panneProfilComplet() {
        UserEntity u = user("FR");
        u.setBirthDate(LocalDate.of(1990, 1, 2));
        u.setResidenceStreet("1 rue de Paris");
        u.setCity("Lyon");
        when(port.verifiedIdentity(u.getId())).thenReturn(Lookup.unavailable());
        assertThat(resolver.resolve(u).status()).isEqualTo(Dac7IdentityStatus.COMPLETE);
    }

    @Test
    @DisplayName("compte supprimé (introuvable) : incomplet, la pièce n'est jamais relue")
    void compteSupprime() {
        UUID id = UUID.randomUUID();
        Dac7SellerIdentity identity = resolver.resolveMissing(id);
        assertThat(identity.status()).isEqualTo(Dac7IdentityStatus.INCOMPLETE);
        assertThat(identity.missing()).containsExactly("ACCOUNT_DELETED");
        verify(port, never()).verifiedIdentity(any());
    }

    @Test
    @DisplayName("professionnel : entité, incomplète faute d'adresse du siège dans les données")
    void professionnel() {
        UserEntity u = user("FR");
        u.setTravelerStatus(TravelerStatus.PROFESSIONAL);
        u.setBusinessName("Diallo Express");
        u.setBusinessRegistrationNumber("12345678900011");
        u.setBusinessCountry("FR");

        Dac7SellerIdentity id = resolver.resolve(u);

        assertThat(id.type()).isEqualTo(Dac7SellerIdentity.SellerType.ENTITY);
        assertThat(id.status()).isEqualTo(Dac7IdentityStatus.INCOMPLETE);
        assertThat(id.missing()).containsExactly("BUSINESS_ADDRESS");
        verify(port, never()).verifiedIdentity(any());
    }

    @Test
    @DisplayName("pages admin : verdicts en cache, un seul appel par voyageur, plusieurs voyageurs en parallèle borné")
    void statutsEnCache() {
        UserEntity a = user("FR");
        UserEntity b = user("FR");
        when(port.verifiedIdentity(any())).thenReturn(Lookup.none());

        Map<UUID, Dac7IdentityStatus> first = resolver.statuses(List.of(a, b));
        Map<UUID, Dac7IdentityStatus> second = resolver.statuses(List.of(a, b));

        assertThat(first).containsEntry(a.getId(), Dac7IdentityStatus.INCOMPLETE)
                .containsEntry(b.getId(), Dac7IdentityStatus.INCOMPLETE);
        assertThat(second).isEqualTo(first);
        assertThat(resolver.status(a)).isEqualTo(Dac7IdentityStatus.INCOMPLETE);
        verify(port, times(1)).verifiedIdentity(a.getId());
        verify(port, times(1)).verifiedIdentity(b.getId());
        assertThat(resolver.profileStatus(a)).isEqualTo(Dac7IdentityStatus.INCOMPLETE);
        resolver.destroy();
    }
}
