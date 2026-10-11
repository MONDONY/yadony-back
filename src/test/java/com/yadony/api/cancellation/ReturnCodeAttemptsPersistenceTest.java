package com.yadony.api.cancellation;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.CommissionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Régression : un code de retour faux est décompté en base malgré l'erreur renvoyée.
 *
 * <p>Le service passe par son proxy transactionnel, sans transaction de test autour : c'est
 * la seule façon de voir que l'exception annulait l'incrément (le test Mockito lisait
 * l'entité en mémoire).
 *
 * <p>{@code BidRepository.findByIdForUpdate} compile en {@code FOR NO KEY UPDATE}, refusé
 * par H2 : le dépôt est espionné et la lecture verrouillée délègue à {@code findById}
 * (cf. {@code RecipientChangeIntegrationTest}).
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({CancellationService.class, ReturnCodeAttemptsPersistenceTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("CancellationService.confirmReturn — essais ratés enregistrés malgré le refus")
class ReturnCodeAttemptsPersistenceTest {

    private static final String CODE = "482913";

    @TestConfiguration
    static class Config {
        @Bean
        CommissionProperties commissionProperties() {
            return new CommissionProperties(new BigDecimal("0.12"), new BigDecimal("1.00"), 24);
        }
    }

    @Autowired CancellationService service;
    @MockitoSpyBean BidRepository bidRepository;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;

    @MockitoBean AuditService auditService;
    @MockitoBean RematchService rematchService;
    @MockitoBean StorageService storageService;
    @MockitoBean DeliveryNoShowProcedureService deliveryNoShowProcedure;

    @BeforeEach
    void verrouSansH2() {
        doAnswer(inv -> bidRepository.findById(inv.getArgument(0)))
                .when(bidRepository).findByIdForUpdate(any());
    }

    @Test
    void unCodeFauxEstDecompteEnBase() {
        Fixture f = bidAwaitingReturn();

        assertThatThrownBy(() -> service.confirmReturn(f.travelerUid(), f.bidId(), "000000"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("code-incorrect");

        assertThat(bidRepository.findById(f.bidId()).orElseThrow().getReturnCodeAttempts())
                .isEqualTo(1);
    }

    @Test
    void apresTroisEssaisLeBonCodeEstRefuseEtLeColisNonRendu() {
        Fixture f = bidAwaitingReturn();

        for (int i = 0; i < 3; i++) {
            String wrong = String.format("%06d", i);
            assertThatThrownBy(() -> service.confirmReturn(f.travelerUid(), f.bidId(), wrong))
                    .isInstanceOf(YadonyBusinessException.class);
        }

        assertThatThrownBy(() -> service.confirmReturn(f.travelerUid(), f.bidId(), CODE))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("too-many-attempts");
        assertThat(bidRepository.findById(f.bidId()).orElseThrow().getReturnedAt()).isNull();
    }

    private Fixture bidAwaitingReturn() {
        UserEntity traveler = new UserEntity();
        traveler.setFirebaseUid("uid-" + UUID.randomUUID());
        traveler.setUsername("trav-" + UUID.randomUUID().toString().substring(0, 12));
        traveler.setRoles(new HashSet<>(Set.of(Role.TRAVELER)));
        traveler = userRepository.saveAndFlush(traveler);

        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDepartureDate(LocalDate.now().plusDays(2));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport Bamako-Sénou");
        a.setDeliveryLat(new BigDecimal("12.533579"));
        a.setDeliveryLng(new BigDecimal("-7.948969"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        a = announcementRepository.saveAndFlush(a);

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(a.getId());
        bid.setSenderId(UUID.randomUUID());
        bid.setWeightKg(new BigDecimal("5.0"));
        bid.setStatus(BidStatus.CANCELLED);
        bid.setReturnCode(CODE);
        bid.setReturnCodeExpiry(LocalDateTime.now().plusDays(1));
        bid = bidRepository.saveAndFlush(bid);
        return new Fixture(traveler.getFirebaseUid(), bid.getId());
    }

    private record Fixture(String travelerUid, UUID bidId) {}
}
