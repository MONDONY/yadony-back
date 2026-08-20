package com.yadony.api.matching;

import com.yadony.api.auth.BlockService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidRequest;
import com.yadony.api.ratings.RatingRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BidService.createBid() — gardes Confidentialité v2 (blocage + filtre KYC)")
class BidCreateGuardTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RatingRepository ratingRepository;
    @Mock private CancellationRepository cancellationRepository;
    @Mock private BidGridItemRepository bidGridItemRepository;
    @Mock private AnnouncementPriceGridItemRepository annGridItemRepository;
    @Mock private BlockService blockService;
    @Mock private com.yadony.api.common.CommissionRateResolver commissionRateResolver;
    @Mock private com.yadony.api.promo.PromoService promoService;
    @Mock private com.yadony.api.common.StorageService storageService;
    @Mock private BidPhotoService bidPhotoService;
    @Mock private HttpServletRequest httpRequest;

    @InjectMocks private BidService bidService;

    private static final String SENDER_UID = "uid-sender-001";
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();

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

    private UserEntity buildSender() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(SENDER_UID);
        u.getRoles().add(Role.SENDER);
        setId(u, SENDER_ID);
        return u;
    }

    private UserEntity buildTraveler() {
        UserEntity u = new UserEntity();
        u.getRoles().add(Role.TRAVELER);
        setId(u, TRAVELER_ID);
        return u;
    }

    private AnnouncementEntity buildAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(TRAVELER_ID);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setAvailableKg(BigDecimal.valueOf(20));
        a.setTotalKg(BigDecimal.valueOf(20));
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setStatus(AnnouncementStatus.ACTIVE);
        setId(a, ANNOUNCEMENT_ID);
        return a;
    }

    private BidRequest buildRequest() {
        return new BidRequest(BigDecimal.valueOf(5), "Vêtements", "CLOTHING",
                "Aminata Diallo", "+221701234567", true, null, null, null, null, null, null);
    }

    @BeforeEach
    void setup() {
        lenient().when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        lenient().when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(cancellationRepository.findByBidId(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("blocage (un sens ou l'autre) → 404 NOT_FOUND, masque l'annonce")
    void createBid_refuseSiBlocageEntreLesDeux() {
        UserEntity sender = buildSender();
        sender.setKycStatus(KycStatus.VERIFIED);
        AnnouncementEntity announcement = buildAnnouncement();

        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
        when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(announcement));
        when(blockService.isBlockedEitherWay(SENDER_ID, TRAVELER_ID)).thenReturn(true);

        assertThatThrownBy(() -> bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID, buildRequest(), httpRequest))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                    assertThat(ex.getErrorCode()).isEqualTo("announcement-not-found");
                });
    }

    @Test
    @DisplayName("cible contactKycOnly + sender non vérifié → 403 FORBIDDEN")
    void createBid_refuseSiCibleKycOnlyEtSenderNonVerifie() {
        UserEntity sender = buildSender();
        sender.setKycStatus(KycStatus.PENDING); // != VERIFIED
        AnnouncementEntity announcement = buildAnnouncement();
        UserEntity traveler = buildTraveler();
        traveler.setContactKycOnly(true);

        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
        when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(announcement));
        when(blockService.isBlockedEitherWay(SENDER_ID, TRAVELER_ID)).thenReturn(false);
        when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

        assertThatThrownBy(() -> bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID, buildRequest(), httpRequest))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(403);
                    assertThat(ex.getErrorCode()).isEqualTo("contact-kyc-required");
                });
    }
}
