package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.BlockService;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidGridItemRequest;
import com.yadony.api.matching.dto.BidQuoteRequest;
import com.yadony.api.matching.dto.BidQuoteResponse;
import com.yadony.api.promo.PromoService;
import com.yadony.api.ratings.RatingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidQuoteServiceTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock RatingRepository ratingRepository;
    @Mock CancellationRepository cancellationRepository;
    @Mock BidGridItemRepository bidGridItemRepository;
    @Mock AnnouncementPriceGridItemRepository annGridItemRepository;
    @Mock BlockService blockService;
    @Mock CommissionRateResolver commissionRateResolver;
    @Mock PromoService promoService;

    @InjectMocks BidService bidService;

    private static final String SENDER_UID = "uid-sender";
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID ANN_ID = UUID.randomUUID();

    private UserEntity sender() {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", SENDER_ID);
        u.setFirebaseUid(SENDER_UID);
        return u;
    }

    private AnnouncementEntity announcement() {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", ANN_ID);
        a.setTravelerId(TRAVELER_ID);
        a.setPricePerKg(new BigDecimal("20.00"));
        a.setPricingMode(PricingMode.KG);
        return a;
    }

    private AnnouncementEntity mixedAnnouncement() {
        AnnouncementEntity a = announcement();
        a.setPricingMode(PricingMode.MIXED);
        return a;
    }

    private AnnouncementPriceGridItemEntity gridItem(UUID id, UUID announcementId,
                                                     String label, String unitPriceNet) {
        AnnouncementPriceGridItemEntity e = new AnnouncementPriceGridItemEntity();
        ReflectionTestUtils.setField(e, "id", id);
        e.setAnnouncementId(announcementId);
        e.setLabel(label);
        e.setUnitPriceNet(new BigDecimal(unitPriceNet));
        return e;
    }

    // ── Mode KG ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KG sans promo : total = net × (1 + globalRate)")
    void quote_kgNoPromo_usesGlobalRate() {
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement()));
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.12"));

        BidQuoteRequest req = new BidQuoteRequest(ANN_ID, new BigDecimal("5"), null, null);
        BidQuoteResponse resp = bidService.quote(SENDER_UID, req);

        assertThat(resp.netEur()).isEqualByComparingTo("100.00"); // 5 × 20
        assertThat(resp.gridNetEur()).isEqualByComparingTo("0.00");
        assertThat(resp.kgNetEur()).isEqualByComparingTo("100.00");
        assertThat(resp.rate()).isEqualByComparingTo("0.12");
        assertThat(resp.commissionEur()).isEqualByComparingTo("12.00");
        assertThat(resp.totalEur()).isEqualByComparingTo("112.00");
        assertThat(resp.promoApplied()).isFalse();
        assertThat(resp.promoLabel()).isNull();
        // Fixture sans devise explicite : le défaut d'entité, jamais un mélange.
        assertThat(resp.currency()).isEqualTo("EUR");
    }

    @Test
    void quote_carriesTheAnnouncementCurrency_evenWhenFieldsAreNamedEur() {
        AnnouncementEntity xof = announcement();
        xof.setCurrency("xof");
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(xof));
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.10"));

        BidQuoteResponse resp = bidService.quote(SENDER_UID, new BidQuoteRequest(ANN_ID, new BigDecimal("5"), null, null));

        assertThat(resp.currency()).isEqualTo("XOF");
        assertThat(resp.netEur()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("KG avec promo valide : commission affichée au taux de base, total reflète le taux final réduit")
    void quote_kgWithPromo_appliesPromoRate() {
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement()));
        // 0.06 = taux FINAL déjà réduit par le promo (résolu par CommissionRateResolver) ;
        // 0.12 = taux de base, toujours affiché sur la ligne "Commission Yadony".
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID, "PROMO6")).thenReturn(new BigDecimal("0.06"));
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.12"));

        BidQuoteRequest req = new BidQuoteRequest(ANN_ID, new BigDecimal("5"), "PROMO6", null);
        BidQuoteResponse resp = bidService.quote(SENDER_UID, req);

        assertThat(resp.netEur()).isEqualByComparingTo("100.00");
        assertThat(resp.rate()).isEqualByComparingTo("0.12");
        assertThat(resp.commissionEur()).isEqualByComparingTo("12.00");
        assertThat(resp.totalEur()).isEqualByComparingTo("106.00"); // 100 + 100×0.06 (taux final)
        assertThat(resp.promoApplied()).isTrue();
        assertThat(resp.promoLabel()).contains("PROMO6");
        assertThat(resp.promoLabel()).contains("6 % de réduction");
    }

    @Test
    @DisplayName("promo au même taux que le taux global : commission finale nulle (régression WELCOME05)")
    void quote_kgWithPromo_sameRateAsGlobal_zeroesFinalCommission() {
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement()));
        // CommissionRateResolver a déjà retranché le promo du taux de base (0.12 - 0.12 = 0).
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID, "WELCOME12")).thenReturn(BigDecimal.ZERO);
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.12"));

        BidQuoteRequest req = new BidQuoteRequest(ANN_ID, new BigDecimal("5"), "WELCOME12", null);
        BidQuoteResponse resp = bidService.quote(SENDER_UID, req);

        assertThat(resp.rate()).isEqualByComparingTo("0.12"); // ligne commission inchangée
        assertThat(resp.commissionEur()).isEqualByComparingTo("12.00");
        assertThat(resp.totalEur()).isEqualByComparingTo("100.00"); // commission finale nulle → total = net
        assertThat(resp.promoLabel()).contains("12 % de réduction");
    }

    @Test
    @DisplayName("promo invalide : YadonyBusinessException propagée (pas de fallback silencieux)")
    void quote_invalidPromo_throwsException() {
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement()));
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID, "EXPIRED"))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "promo-expired", "Promo Expired", "Ce code promo a expiré"));

        BidQuoteRequest req = new BidQuoteRequest(ANN_ID, new BigDecimal("5"), "EXPIRED", null);

        assertThatThrownBy(() -> bidService.quote(SENDER_UID, req))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-expired"));
    }

    @Test
    @DisplayName("annonce introuvable → 404")
    void quote_announcementNotFound_throws404() {
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(any())).thenReturn(Optional.empty());

        BidQuoteRequest req = new BidQuoteRequest(UUID.randomUUID(), new BigDecimal("5"), null, null);

        assertThatThrownBy(() -> bidService.quote(SENDER_UID, req))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── Mode GRID ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GRID seul sans promo : net = Σ(unitPriceNet × qté), kgNet = 0, promo reflété")
    void quote_gridOnly_noPromo() {
        UUID itemA = UUID.randomUUID();
        UUID itemB = UUID.randomUUID();
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(mixedAnnouncement()));
        when(annGridItemRepository.findById(itemA))
                .thenReturn(Optional.of(gridItem(itemA, ANN_ID, "Chaussures", "25.00")));
        when(annGridItemRepository.findById(itemB))
                .thenReturn(Optional.of(gridItem(itemB, ANN_ID, "Chapeau", "15.00")));
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.12"));

        // 2 × 25 + 1 × 15 = 65 ; pas de poids
        BidQuoteRequest req = new BidQuoteRequest(ANN_ID, null, null,
                List.of(new BidGridItemRequest(itemA, 2), new BidGridItemRequest(itemB, 1)));
        BidQuoteResponse resp = bidService.quote(SENDER_UID, req);

        assertThat(resp.gridNetEur()).isEqualByComparingTo("65.00");
        assertThat(resp.kgNetEur()).isEqualByComparingTo("0.00");
        assertThat(resp.netEur()).isEqualByComparingTo("65.00");
        assertThat(resp.commissionEur()).isEqualByComparingTo("7.80");  // 65 × 0.12
        assertThat(resp.totalEur()).isEqualByComparingTo("72.80");
        assertThat(resp.promoApplied()).isFalse();
    }

    @Test
    @DisplayName("GRID seul AVEC promo : l'aperçu reflète enfin le promo en mode grille")
    void quote_gridOnly_withPromo() {
        UUID itemA = UUID.randomUUID();
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(mixedAnnouncement()));
        when(annGridItemRepository.findById(itemA))
                .thenReturn(Optional.of(gridItem(itemA, ANN_ID, "Chaussures", "25.00")));
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID, "PROMO6")).thenReturn(new BigDecimal("0.06"));
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.12"));

        BidQuoteRequest req = new BidQuoteRequest(ANN_ID, null, "PROMO6",
                List.of(new BidGridItemRequest(itemA, 2))); // 2 × 25 = 50
        BidQuoteResponse resp = bidService.quote(SENDER_UID, req);

        assertThat(resp.netEur()).isEqualByComparingTo("50.00");
        assertThat(resp.rate()).isEqualByComparingTo("0.12");
        assertThat(resp.commissionEur()).isEqualByComparingTo("6.00");  // 50 × 0.12 (base, affiché)
        assertThat(resp.totalEur()).isEqualByComparingTo("53.00");  // 50 + 50×0.06 (taux final)
        assertThat(resp.promoApplied()).isTrue();
        assertThat(resp.promoLabel()).contains("PROMO6");
    }

    // ── Mode MIXED ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("MIXED : net = gridNet + kgNet, commission sur le total")
    void quote_mixed_sumsGridAndKg() {
        UUID itemA = UUID.randomUUID();
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(mixedAnnouncement()));
        when(annGridItemRepository.findById(itemA))
                .thenReturn(Optional.of(gridItem(itemA, ANN_ID, "Chaussures", "25.00")));
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.12"));

        // grid : 2 × 25 = 50 ; kg : 3 × 20 = 60 ; net = 110
        BidQuoteRequest req = new BidQuoteRequest(ANN_ID, new BigDecimal("3"), null,
                List.of(new BidGridItemRequest(itemA, 2)));
        BidQuoteResponse resp = bidService.quote(SENDER_UID, req);

        assertThat(resp.gridNetEur()).isEqualByComparingTo("50.00");
        assertThat(resp.kgNetEur()).isEqualByComparingTo("60.00");
        assertThat(resp.netEur()).isEqualByComparingTo("110.00");
        assertThat(resp.commissionEur()).isEqualByComparingTo("13.20"); // 110 × 0.12
        assertThat(resp.totalEur()).isEqualByComparingTo("123.20");
    }

    // ── Erreurs ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("article hors grille de l'annonce → 422 invalid-grid-item")
    void quote_gridItemFromOtherAnnouncement_throws422() {
        UUID itemA = UUID.randomUUID();
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(mixedAnnouncement()));
        // L'article appartient à une AUTRE annonce → le filtre le rejette.
        when(annGridItemRepository.findById(itemA))
                .thenReturn(Optional.of(gridItem(itemA, UUID.randomUUID(), "Pirate", "25.00")));

        BidQuoteRequest req = new BidQuoteRequest(ANN_ID, null, null,
                List.of(new BidGridItemRequest(itemA, 1)));

        assertThatThrownBy(() -> bidService.quote(SENDER_UID, req))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("invalid-grid-item");
                    assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
    }

    @Test
    @DisplayName("ni poids ni article → 422 bid-empty")
    void quote_empty_throws422() {
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(mixedAnnouncement()));

        BidQuoteRequest req = new BidQuoteRequest(ANN_ID, null, null, null);

        assertThatThrownBy(() -> bidService.quote(SENDER_UID, req))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("bid-empty");
                    assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
    }
}
