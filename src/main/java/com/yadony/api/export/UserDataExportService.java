package com.yadony.api.export;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.addressbook.delivery.DeliveryAddressEntity;
import com.yadony.api.addressbook.delivery.DeliveryAddressRepository;
import com.yadony.api.addressbook.delivery.dto.DeliveryAddressDto;
import com.yadony.api.addressbook.pickup.PickupAddressEntity;
import com.yadony.api.addressbook.pickup.PickupAddressRepository;
import com.yadony.api.addressbook.pickup.dto.PickupAddressDto;
import com.yadony.api.addressbook.recipient.RecipientEntity;
import com.yadony.api.addressbook.recipient.RecipientRepository;
import com.yadony.api.addressbook.recipient.dto.RecipientDto;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.export.dto.UserDataExportDto;
import com.yadony.api.export.dto.UserDataExportDto.FavoriteExport;
import com.yadony.api.export.dto.UserDataExportDto.KycExport;
import com.yadony.api.export.dto.UserDataExportDto.ProfileExport;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.kyc.KycRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserDataExportService {

    private final RecipientRepository recipientRepository;
    private final PickupAddressRepository pickupAddressRepository;
    private final DeliveryAddressRepository deliveryAddressRepository;
    private final FavoriteRepository favoriteRepository;
    private final KycRepository kycRepository;
    private final FirebaseContactService firebaseContact;

    public UserDataExportService(RecipientRepository recipientRepository,
                                  PickupAddressRepository pickupAddressRepository,
                                  DeliveryAddressRepository deliveryAddressRepository,
                                  FavoriteRepository favoriteRepository,
                                  KycRepository kycRepository,
                                  FirebaseContactService firebaseContact) {
        this.recipientRepository = recipientRepository;
        this.pickupAddressRepository = pickupAddressRepository;
        this.deliveryAddressRepository = deliveryAddressRepository;
        this.favoriteRepository = favoriteRepository;
        this.kycRepository = kycRepository;
        this.firebaseContact = firebaseContact;
    }

    public UserDataExportDto export(UserEntity user) {
        // Coordonnées lues dans Firebase : l'export RGPD doit rester complet même
        // si la base Yadony ne les stocke plus.
        var contact = firebaseContact.getContact(user.getFirebaseUid());
        ProfileExport profile = new ProfileExport(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                contact.email(),
                contact.phoneNumber(),
                user.getBirthDate(),
                user.getCity(),
                user.getCountry(),
                user.getResidenceStreet(),
                user.getResidenceLine2(),
                user.getResidencePostalCode(),
                user.getBio(),
                user.getAvatarUrl(),
                user.getRoles().stream().map(Enum::name).collect(Collectors.toList()),
                user.getCreatedAt()
        );

        KycExport kyc = kycRepository.findByUserId(user.getId())
                .map(k -> new KycExport(k.getStatus().name(), k.getRejectionReason()))
                .orElse(null);

        List<RecipientDto> recipients = recipientRepository.findByUserIdOrderByUpdatedAtDesc(user.getId())
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());

        List<PickupAddressDto> pickupAddresses = pickupAddressRepository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(user.getId())
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());

        List<DeliveryAddressDto> deliveryAddresses = deliveryAddressRepository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(user.getId())
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());

        List<FavoriteExport> favorites = favoriteRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(f -> new FavoriteExport(f.getTargetType().name(), f.getTargetId(), f.getCreatedAt()))
                .collect(Collectors.toList());

        return new UserDataExportDto(
                profile,
                kyc,
                recipients,
                pickupAddresses,
                deliveryAddresses,
                favorites,
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    private RecipientDto toDto(RecipientEntity e) {
        return new RecipientDto(
                e.getId(),
                e.getFullName(),
                e.getRelationship(),
                e.getPhoneE164(),
                e.getWhatsappE164(),
                e.getStreet(),
                e.getCity(),
                e.getCountry(),
                e.getNotes(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.isDefault()
        );
    }

    private PickupAddressDto toDto(PickupAddressEntity e) {
        return new PickupAddressDto(
                e.getId(),
                e.getLabel(),
                e.getStreet(),
                e.getPostalCode(),
                e.getCity(),
                e.getCountry(),
                e.getFloorApartment(),
                e.getInstructions(),
                e.getLatitude(),
                e.getLongitude(),
                e.isDefault(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    private DeliveryAddressDto toDto(DeliveryAddressEntity e) {
        return new DeliveryAddressDto(
                e.getId(),
                e.getLabel(),
                e.getStreet(),
                e.getCity(),
                e.getCountry(),
                e.getInstructions(),
                e.getLatitude(),
                e.getLongitude(),
                e.isDefault(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
