package com.yadony.api.export;

import com.yadony.api.addressbook.delivery.DeliveryAddressEntity;
import com.yadony.api.addressbook.delivery.DeliveryAddressRepository;
import com.yadony.api.addressbook.pickup.PickupAddressEntity;
import com.yadony.api.addressbook.pickup.PickupAddressRepository;
import com.yadony.api.addressbook.recipient.RecipientEntity;
import com.yadony.api.addressbook.recipient.RecipientRepository;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.export.dto.UserDataExportDto;
import com.yadony.api.favorites.FavoriteEntity;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.favorites.FavoriteTargetType;
import com.yadony.api.kyc.KycRepository;
import com.yadony.api.kyc.KycVerificationEntity;
import com.yadony.api.kyc.KycVerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserDataExportService")
class UserDataExportServiceTest {

    @Mock private RecipientRepository recipientRepository;
    @Mock private PickupAddressRepository pickupAddressRepository;
    @Mock private DeliveryAddressRepository deliveryAddressRepository;
    @Mock private FavoriteRepository favoriteRepository;
    @Mock private KycRepository kycRepository;
    @Mock private com.yadony.api.auth.FirebaseContactService firebaseContact;

    private UserDataExportService service() {
        // L'export RGPD lit les coordonnées dans Firebase, elles ne sont plus en base
        org.mockito.Mockito.lenient()
                .when(firebaseContact.getContact(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.yadony.api.auth.FirebaseContactService.Contact(
                        "+221771234567", "aissatou@example.com"));
        return new UserDataExportService(
                recipientRepository, pickupAddressRepository, deliveryAddressRepository,
                favoriteRepository, kycRepository, firebaseContact);
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UserEntity makeUser(UUID id) {
        UserEntity u = new UserEntity();
        setId(u, id);
        u.setFirstName("Aïssatou");
        u.setLastName("Ba");
        u.setFirebaseUid("uid-aissatou");
        u.setCity("Dakar");
        u.setCountry("SN");
        u.setResidenceStreet("10 avenue Bourguiba");
        u.setResidenceLine2("Villa 3");
        u.setResidencePostalCode("11000");
        u.setRoles(new HashSet<>(List.of(Role.SENDER)));
        return u;
    }

    @Test
    @DisplayName("agrège profil, KYC, destinataires, adresses et favoris de l'utilisateur")
    void aggregatesAllCategories() {
        UUID userId = UUID.randomUUID();
        UserEntity user = makeUser(userId);

        RecipientEntity recipient = new RecipientEntity();
        recipient.setUserId(userId);
        recipient.setFullName("Maman");
        recipient.setPhoneE164("+221771111111");
        recipient.setCity("Dakar");
        recipient.setCountry("SN");
        when(recipientRepository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(recipient));

        PickupAddressEntity pickup = new PickupAddressEntity();
        pickup.setUserId(userId);
        pickup.setLabel("Domicile");
        pickup.setStreet("1 rue de Paris");
        pickup.setCity("Paris");
        pickup.setCountry("FR");
        when(pickupAddressRepository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId))
                .thenReturn(List.of(pickup));

        DeliveryAddressEntity delivery = new DeliveryAddressEntity();
        delivery.setUserId(userId);
        delivery.setLabel("Bureau");
        delivery.setStreet("2 avenue Cheikh Anta Diop");
        delivery.setCity("Dakar");
        delivery.setCountry("SN");
        when(deliveryAddressRepository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId))
                .thenReturn(List.of(delivery));

        FavoriteEntity favorite = new FavoriteEntity(userId, FavoriteTargetType.TRIP, UUID.randomUUID());
        when(favoriteRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(favorite));

        KycVerificationEntity kyc = new KycVerificationEntity();
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kyc));

        UserDataExportDto export = service().export(user);

        assertThat(export.profile().id()).isEqualTo(userId);
        assertThat(export.profile().firstName()).isEqualTo("Aïssatou");
        assertThat(export.profile().roles()).containsExactly("SENDER");
        assertThat(export.profile().residenceStreet()).isEqualTo("10 avenue Bourguiba");
        assertThat(export.profile().residenceLine2()).isEqualTo("Villa 3");
        assertThat(export.profile().residencePostalCode()).isEqualTo("11000");
        assertThat(export.kyc().status()).isEqualTo("VERIFIED");
        assertThat(export.recipients()).hasSize(1);
        assertThat(export.recipients().get(0).fullName()).isEqualTo("Maman");
        assertThat(export.pickupAddresses()).hasSize(1);
        assertThat(export.pickupAddresses().get(0).label()).isEqualTo("Domicile");
        assertThat(export.deliveryAddresses()).hasSize(1);
        assertThat(export.deliveryAddresses().get(0).label()).isEqualTo("Bureau");
        assertThat(export.favorites()).hasSize(1);
        assertThat(export.favorites().get(0).targetType()).isEqualTo("TRIP");
        assertThat(export.generatedAt()).isNotNull();
    }

    @Test
    @DisplayName("kyc est null quand l'utilisateur n'a pas de vérification KYC")
    void kycNullWhenNoVerification() {
        UUID userId = UUID.randomUUID();
        UserEntity user = makeUser(userId);
        when(recipientRepository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
        when(pickupAddressRepository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId)).thenReturn(List.of());
        when(deliveryAddressRepository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId)).thenReturn(List.of());
        when(favoriteRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.empty());

        UserDataExportDto export = service().export(user);

        assertThat(export.kyc()).isNull();
        assertThat(export.recipients()).isEmpty();
    }
}
