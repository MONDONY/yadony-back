package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.matching.reception.BidRecipientLinkEntity;
import com.yadony.api.matching.reception.BidRecipientLinkRepository;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Listes de colis (« Mes colis » de l'expéditeur, colis du voyageur) : un nombre de
 * requêtes SQL indépendant du nombre de colis, et des réponses identiques à la
 * conversion colis par colis.
 *
 * <p>YADONY-BACK-STAGING-W (test de charge du 08/10) : {@code GET /bids/me} sans
 * pagination, sur un compte de 741 colis, tenait sa connexion JDBC jusqu'à 125 s, une
 * dizaine de requêtes par colis. Chaque colis porte ici tout ce qui en coûtait une : lien
 * destinataire, notes des deux parties, annulation, article de grille, photo.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Listes de colis : requêtes groupées, réponses inchangées")
class MyBidsQueryCountIntegrationTest {

    private static final Map<String, String> PAGES = new LinkedHashMap<>();

    static {
        PAGES.put("/bids/me", "$.length()");
        PAGES.put("/bids/me?page=0&size=50", "$.content.length()");
    }

    @Autowired MockMvc mockMvc;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired CacheManager cacheManager;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired BidService bidService;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired RatingRepository ratingRepository;
    @Autowired CancellationRepository cancellationRepository;
    @Autowired BidGridItemRepository bidGridItemRepository;
    @Autowired BidPhotoRepository bidPhotoRepository;
    @Autowired BidRecipientLinkRepository recipientLinkRepository;
    @MockitoSpyBean StorageService storageService;

    private Statistics statistics;
    private UserEntity sender;
    private UserEntity traveler;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        // URL présignée déterministe : la comparaison des réponses ne dépend pas de l'heure.
        doAnswer(inv -> "https://r2.test/" + inv.getArgument(0))
                .when(storageService).generatePresignedUrl(anyString(), any(Duration.class));
        sender = persistUser("Awa");
        traveler = persistUser("Moussa");
    }

    @Test
    @DisplayName("GET /bids/me, paginé ou non, et colis du voyageur : même nombre de requêtes pour 2 ou 6 colis")
    void requestCountDoesNotGrowWithTheList() throws Exception {
        for (int i = 0; i < 2; i++) {
            persistRichBid(i);
        }
        Map<String, Long> withTwo = measureAll(2);

        for (int i = 2; i < 6; i++) {
            persistRichBid(i);
        }
        Map<String, Long> withSix = measureAll(6);

        assertThat(withSix).as("requêtes par liste, 2 colis → 6 colis").isEqualTo(withTwo);
    }

    @Test
    @DisplayName("lectures groupées : chaque colis identique à sa conversion à l'unité")
    void prefetchedResponsesEqualOneByOneResponses() {
        for (int i = 0; i < 5; i++) {
            persistRichBid(i);
        }
        // Colis nu : ni lien, ni note, ni annulation, ni grille, ni photo.
        persistBid(9, BidStatus.PENDING);

        transactionTemplate.executeWithoutResult(tx -> {
            List<BidEntity> bids = bidRepository.findBySenderId(sender.getId()).stream()
                    .sorted(Comparator.comparing(BidEntity::getCreatedAt))
                    .toList();
            BidResponsePrefetch prefetch = bidService.prefetchFor(bids);
            for (BidEntity bid : bids) {
                BidResponse oneByOne = bidService.toResponse(bid, sender, sender.getId());
                BidResponse grouped = bidService.toResponse(bid, sender, sender.getId(), prefetch);
                assertThat(grouped).as("colis %s", bid.getId()).isEqualTo(oneByOne);
                BidResponse travelerOneByOne = bidService.toResponse(bid, sender);
                BidResponse travelerGrouped = bidService.toResponse(bid, sender, null, prefetch);
                assertThat(travelerGrouped).as("colis %s vu du voyageur", bid.getId()).isEqualTo(travelerOneByOne);
            }
        });
    }

    @Test
    @DisplayName("lots IN (...) : au-delà de CHUNK_SIZE identifiants, découpage sans doublon")
    void loadInChunksSplitsDistinctIds() {
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < BidResponsePrefetch.CHUNK_SIZE + 3; i++) {
            ids.add(UUID.randomUUID());
        }
        ids.add(ids.get(0));
        List<Integer> chunkSizes = new java.util.ArrayList<>();

        List<UUID> loaded = BidResponsePrefetch.loadInChunks(ids, chunk -> {
            chunkSizes.add(chunk.size());
            return chunk;
        });

        assertThat(chunkSizes).containsExactly(BidResponsePrefetch.CHUNK_SIZE, 3);
        assertThat(loaded).hasSize(BidResponsePrefetch.CHUNK_SIZE + 3).doesNotHaveDuplicates();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Long> measureAll(int expectedItems) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map.Entry<String, String> page : PAGES.entrySet()) {
            counts.put(page.getKey(), measure(page.getKey(), sender, page.getValue(), expectedItems));
        }
        counts.put("/travelers/me/bids", measure("/travelers/me/bids", traveler, "$.content.length()", expectedItems));
        return counts;
    }

    private long measure(String url, UserEntity caller, String lengthPath, int expectedItems) throws Exception {
        // Premier appel à blanc : caches applicatifs (réglages, taux de change…) chauds ;
        // puis les caches de ces listes vidés, pour mesurer une vraie lecture.
        mockMvc.perform(get(url).with(authentication(as(caller)))).andExpect(status().isOk());
        List.of("bids-me", "traveler-bids-me").forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
        statistics.clear();
        mockMvc.perform(get(url).with(authentication(as(caller))))
                .andExpect(status().isOk())
                .andExpect(jsonPath(lengthPath).value(expectedItems));
        return statistics.getPrepareStatementCount();
    }

    /** Colis accepté avec tout ce qui coûtait une requête par colis. */
    private void persistRichBid(int i) {
        BidEntity b = persistBid(i, BidStatus.ACCEPTED);

        UserEntity recipient = persistUser("Fatou" + i);
        recipientLinkRepository.save(new BidRecipientLinkEntity(b.getId(), recipient.getId()));

        for (UUID rater : List.of(sender.getId(), traveler.getId())) {
            RatingEntity r = new RatingEntity();
            r.setRaterId(rater);
            r.setRatedUserId(rater.equals(sender.getId()) ? traveler.getId() : sender.getId());
            r.setBidId(b.getId());
            r.setStars(5);
            ratingRepository.save(r);
        }

        CancellationEntity c = new CancellationEntity();
        c.setBidId(b.getId());
        c.setCancelledBy(sender.getId());
        c.setReason("SENDER_NO_SHOW");
        c.setRefundStatus("NONE");
        c.setRematchStatus("NONE");
        c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        c.setScope(CancellationScope.HANDOVER);
        c.setContestationDeadline(OffsetDateTime.now().plusDays(1).withNano(0));
        cancellationRepository.save(c);

        BidGridItemEntity item = new BidGridItemEntity();
        item.setBidId(b.getId());
        item.setAnnouncementGridItemId(UUID.randomUUID());
        item.setLabelSnapshot("Valise");
        item.setUnitPriceNetSnapshot(new BigDecimal("12.50"));
        item.setQuantity(2);
        bidGridItemRepository.save(item);

        bidPhotoRepository.save(new BidPhotoEntity(b.getId(), "bids/" + sender.getId() + "/p" + i + "_1.jpg", 1));
        bidPhotoRepository.save(new BidPhotoEntity(b.getId(), "bids/" + sender.getId() + "/p" + i + "_0.jpg", 0));
    }

    private BidEntity persistBid(int i, BidStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(7 + i));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a = announcementRepository.save(a);

        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(sender.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(status);
        b.setRecipientName("Fatou Diop");
        b.setRecipientPhone("+221 77 123 45 67");
        b.setTrackingNumber("DON-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        b.setTrackingToken(UUID.randomUUID().toString());
        b.setConfirmationCode("123456");
        return bidRepository.save(b);
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-bids-count-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }
}
