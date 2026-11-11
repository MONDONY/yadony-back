package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.travelerstatus.TravelerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Identité DAC7 : profil puis pièce vérifiée, jamais rien redemandé")
class Dac7IdentityResolverTest {

    private final Dac7VerifiedIdentityPort port = mock(Dac7VerifiedIdentityPort.class);
    private final Dac7IdentityResolver resolver = new Dac7IdentityResolver(port);

    private static UserEntity user(String country) {
        UserEntity u = new UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirstName("Awa");
        u.setLastName("Diallo");
        u.setCountry(country);
        return u;
    }

    private static Dac7VerifiedIdentity verified(LocalDate birth, Dac7Address address) {
        return new Dac7VerifiedIdentity("AWA", "DIALLO", birth, address);
    }

    @Test
    @DisplayName("profil complet (date de naissance, rue et ville) : complet, la pièce vérifiée l'emporte pour le nom")
    void profilComplet() {
        UserEntity u = user("FR");
        u.setBirthDate(LocalDate.of(1990, 1, 2));
        u.setResidenceStreet("1 rue de Paris");
        u.setCity("Lyon");
        u.setResidencePostalCode("69001");
        when(port.verifiedIdentity(u.getId())).thenReturn(Optional.of(verified(null, null)));

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
    @DisplayName("profil sans date de naissance ni adresse : lues sur la pièce vérifiée")
    void pieceVerifiee() {
        UserEntity u = user("FR");
        Dac7Address address = new Dac7Address("3 avenue Foch", null, "75016", "Paris", null, null);
        when(port.verifiedIdentity(u.getId())).thenReturn(Optional.of(verified(LocalDate.of(1985, 5, 6), address)));

        Dac7SellerIdentity id = resolver.resolve(u);

        assertThat(id.status()).isEqualTo(Dac7IdentityStatus.COMPLETE);
        assertThat(id.birthDate()).isEqualTo(LocalDate.of(1985, 5, 6));
        assertThat(id.address().street()).isEqualTo("3 avenue Foch");
        // Pays de l'adresse illisible sur la pièce : celui de la résidence déclarée.
        assertThat(id.address().countryCode()).isEqualTo("FR");
    }

    @Test
    @DisplayName("aucune source : incomplet, avec la liste de ce qui manque (sans aucune valeur)")
    void aucuneSource() {
        UserEntity u = user("FR");
        u.setLastName(null);
        when(port.verifiedIdentity(any())).thenReturn(Optional.empty());

        Dac7SellerIdentity id = resolver.resolve(u);

        assertThat(id.status()).isEqualTo(Dac7IdentityStatus.INCOMPLETE);
        assertThat(id.missing()).containsExactly("NAME", "BIRTH_DATE", "ADDRESS");
        assertThat(id.toString()).doesNotContain("Awa");
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
    @DisplayName("statut seul : mis en cache, la pièce n'est relue qu'une fois")
    void statutEnCache() {
        UserEntity u = user("FR");
        when(port.verifiedIdentity(u.getId())).thenReturn(Optional.empty());
        assertThat(resolver.status(u)).isEqualTo(Dac7IdentityStatus.INCOMPLETE);
        assertThat(resolver.status(u)).isEqualTo(Dac7IdentityStatus.INCOMPLETE);
        verify(port, times(1)).verifiedIdentity(u.getId());
    }
}
