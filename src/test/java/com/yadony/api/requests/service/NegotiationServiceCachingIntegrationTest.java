package com.yadony.api.requests.service;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.dto.NegotiationThreadResponse;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie que le cache {@code negotiations-me} (cf. CacheConfig) évite
 * réellement un second aller-retour DB, pas seulement que l'annotation
 * {@code @Cacheable} est présente syntaxiquement.
 *
 * <p>Contexte : "/negotiations/me" est tiré à chaque changement d'onglet
 * (hub Activités) et s'est révélé être l'un des endpoints saturant le
 * rate-limit nginx en usage réel. TTL courte de 8 s, cf. le commentaire sur
 * {@link NegotiationService#listMine} pour le choix de ne pas évincer
 * manuellement (donnée bilatérale expéditeur/voyageur, une dizaine de
 * mutateurs).
 */
@SpringBootTest
@ActiveProfiles("test")
class NegotiationServiceCachingIntegrationTest {

    @Autowired private NegotiationService negotiationService;
    @Autowired private NegotiationThreadRepository threadRepository;
    @Autowired private PackageRequestRepository packageRequestRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void cleanDb() {
        threadRepository.deleteAll();
        packageRequestRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void listMine_secondCallWithinTtl_returnsCachedResultDespiteDbDeletion() {
        UserEntity traveler = persistUser("uid-nego-cache-traveler-" + UUID.randomUUID());
        UserEntity sender = persistUser("uid-nego-cache-sender-" + UUID.randomUUID());
        PackageRequestEntity request = persistPackageRequest(sender.getId());
        persistThread(request.getId(), traveler.getId());

        List<NegotiationThreadResponse> firstCall = negotiationService.listMine(traveler.getId(), false);
        assertThat(firstCall).hasSize(1);

        // Supprime en base DIRECTEMENT, en contournant le service : si le
        // cache fonctionne, le 2e appel doit renvoyer le même résultat que le
        // 1er malgré la suppression.
        threadRepository.deleteAll();

        List<NegotiationThreadResponse> secondCall = negotiationService.listMine(traveler.getId(), false);
        assertThat(secondCall).hasSize(1);
    }

    @Test
    void listMine_differentUsers_haveIndependentCacheEntries() {
        UserEntity traveler = persistUser("uid-nego-cache-traveler2-" + UUID.randomUUID());
        UserEntity sender = persistUser("uid-nego-cache-sender2-" + UUID.randomUUID());
        UserEntity uninvolved = persistUser("uid-nego-cache-outsider-" + UUID.randomUUID());
        PackageRequestEntity request = persistPackageRequest(sender.getId());
        persistThread(request.getId(), traveler.getId());

        List<NegotiationThreadResponse> forTraveler = negotiationService.listMine(traveler.getId(), false);
        List<NegotiationThreadResponse> forOutsider = negotiationService.listMine(uninvolved.getId(), false);

        // La clé de cache est #userId : un utilisateur non impliqué dans le
        // thread ne doit jamais voir les négociations d'un autre.
        assertThat(forTraveler).hasSize(1);
        assertThat(forOutsider).isEmpty();
    }

    private UserEntity persistUser(String firebaseUid) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(firebaseUid);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        roles.add(Role.SENDER);
        u.setRoles(roles);
        return userRepository.save(u);
    }

    private PackageRequestEntity persistPackageRequest(UUID senderId) {
        PackageRequestEntity e = new PackageRequestEntity();
        e.setSenderId(senderId);
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDesiredDate(LocalDate.now().plusDays(10));
        e.setDateToleranceDays((short) 2);
        e.setWeightKg(new BigDecimal("5.00"));
        e.setParcelSize(ParcelSize.SMALL);
        e.setTransportMode(TransportMode.PLANE);
        e.setContentCategory("vetements");
        e.setNegotiable(true);
        e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        e.setStatus(PackageRequestStatus.NEGOTIATING);
        return packageRequestRepository.save(e);
    }

    private NegotiationThreadEntity persistThread(UUID packageRequestId, UUID travelerId) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(packageRequestId);
        t.setTravelerId(travelerId);
        t.setTravelerTravelDate(LocalDate.now().plusDays(10));
        t.setTravelerAvailableKg(new BigDecimal("10.00"));
        t.setStatus(NegotiationThreadStatus.OPEN);
        t.setCurrentPriceEur(new BigDecimal("35.00"));
        t.setRoundsCount((short) 0);
        t.setLastActivityAt(LocalDateTime.now());
        return threadRepository.save(t);
    }
}
