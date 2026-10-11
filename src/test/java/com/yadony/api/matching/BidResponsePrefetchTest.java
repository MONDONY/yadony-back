package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.dto.BidPhotoResponse;
import com.yadony.api.matching.reception.BidRecipientLinkEntity;
import com.yadony.api.matching.reception.BidRecipientLinkRepository;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lectures groupées d'une liste de colis (YADONY-BACK-STAGING-W) : chaque donnée liée
 * revient au bon colis, l'ordre des photos est conservé, un colis sans donnée liée reçoit
 * les mêmes valeurs vides que les lectures à l'unité.
 */
@DisplayName("BidResponsePrefetch — lectures groupées des listes de colis")
class BidResponsePrefetchTest {

    private static final UUID BID_A = UUID.randomUUID();
    private static final UUID BID_B = UUID.randomUUID();
    private static final UUID BID_EMPTY = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID TRAVELER = UUID.randomUUID();

    @Nested
    @DisplayName("accesseurs")
    class Accessors {

        @Test
        @DisplayName("chaque donnée est rendue pour son colis ; un colis inconnu reçoit des valeurs vides")
        void returnsDataByBidAndEmptyDefaults() {
            BidRecipientLinkEntity link = new BidRecipientLinkEntity(BID_A, UUID.randomUUID());
            CancellationEntity cancellation = cancellation(BID_B, CancellationScope.DELIVERY);
            BidGridItemEntity item = gridItem(BID_A);
            BidPhotoResponse photo = new BidPhotoResponse(UUID.randomUUID(), "https://r2/a.jpg");
            BidResponsePrefetch prefetch = new BidResponsePrefetch(
                    Map.of(BID_A, link),
                    Set.of(BidResponsePrefetch.pairKey(BID_A, SENDER)),
                    Map.of(BID_B, List.of(cancellation)),
                    Map.of(BID_A, List.of(item)),
                    Map.of(BID_A, List.of(photo)));

            assertThat(prefetch.recipientLink(BID_A)).isSameAs(link);
            assertThat(prefetch.recipientLink(BID_B)).isNull();
            assertThat(prefetch.cancellations(BID_B)).containsExactly(cancellation);
            assertThat(prefetch.cancellations(BID_A)).isEmpty();
            assertThat(prefetch.gridItems(BID_A)).containsExactly(item);
            assertThat(prefetch.gridItems(BID_EMPTY)).isEmpty();
            assertThat(prefetch.photos(BID_A)).containsExactly(photo);
            assertThat(prefetch.photos(BID_EMPTY)).isEmpty();
        }

        @Test
        @DisplayName("note : le couple colis + auteur exact, jamais un autre colis ni un auteur absent")
        void hasRatedMatchesTheExactPair() {
            BidResponsePrefetch prefetch = new BidResponsePrefetch(Map.of(),
                    Set.of(BidResponsePrefetch.pairKey(BID_A, SENDER)), Map.of(), Map.of(), Map.of());

            assertThat(prefetch.hasRated(BID_A, SENDER)).isTrue();
            assertThat(prefetch.hasRated(BID_A, TRAVELER)).isFalse();
            assertThat(prefetch.hasRated(BID_B, SENDER)).isFalse();
            assertThat(prefetch.hasRated(BID_A, null)).isFalse();
        }

        @Test
        @DisplayName("taux de commission : résolu une fois par couple voyageur/expéditeur, null compris")
        void commissionRateIsResolvedOncePerCouple() {
            BidResponsePrefetch prefetch = new BidResponsePrefetch(Map.of(), Set.of(), Map.of(), Map.of(), Map.of());
            AtomicInteger calls = new AtomicInteger();
            UUID otherSender = UUID.randomUUID();

            BigDecimal first = prefetch.commissionRate(TRAVELER, SENDER, () -> {
                calls.incrementAndGet();
                return new BigDecimal("0.12");
            });
            BigDecimal again = prefetch.commissionRate(TRAVELER, SENDER, () -> {
                calls.incrementAndGet();
                return BigDecimal.ONE;
            });
            BigDecimal nullRate = prefetch.commissionRate(TRAVELER, otherSender, () -> {
                calls.incrementAndGet();
                return null;
            });
            BigDecimal nullAgain = prefetch.commissionRate(TRAVELER, otherSender, () -> {
                calls.incrementAndGet();
                return BigDecimal.TEN;
            });

            assertThat(first).isEqualByComparingTo("0.12");
            assertThat(again).isEqualByComparingTo("0.12");
            assertThat(nullRate).isNull();
            assertThat(nullAgain).isNull();
            assertThat(calls).hasValue(2);
        }

        @Test
        @DisplayName("lots IN (...) : identifiants distincts, découpés par CHUNK_SIZE ; liste vide sans requête")
        void loadInChunks() {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < BidResponsePrefetch.CHUNK_SIZE * 2 + 1; i++) {
                ids.add(UUID.randomUUID());
            }
            ids.add(ids.get(3));
            List<Integer> sizes = new ArrayList<>();

            List<UUID> loaded = BidResponsePrefetch.loadInChunks(ids, chunk -> {
                sizes.add(chunk.size());
                return List.copyOf(chunk);
            });

            assertThat(sizes).containsExactly(BidResponsePrefetch.CHUNK_SIZE, BidResponsePrefetch.CHUNK_SIZE, 1);
            assertThat(loaded).hasSize(BidResponsePrefetch.CHUNK_SIZE * 2 + 1).doesNotHaveDuplicates();
            assertThat(BidResponsePrefetch.loadInChunks(List.of(), chunk -> {
                throw new AssertionError("aucune requête attendue");
            })).isEmpty();
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("BidService.prefetchFor : association par colis")
    class PrefetchFor {

        @Mock BidRepository bidRepository;
        @Mock BidRecipientLinkRepository recipientLinkRepository;
        @Mock AnnouncementRepository announcementRepository;
        @Mock UserRepository userRepository;
        @Mock RatingRepository ratingRepository;
        @Mock CancellationRepository cancellationRepository;
        @Mock BidGridItemRepository bidGridItemRepository;
        @Mock BidPhotoService bidPhotoService;
        @InjectMocks BidService bidService;

        @Test
        @DisplayName("liens, notes, annulations, grille et photos rangés sous le bon colis ; colis nu vide")
        void associatesEachRowWithItsBid() {
            UUID annA = UUID.randomUUID();
            UUID annB = UUID.randomUUID();
            UUID recipientUser = UUID.randomUUID();
            List<BidEntity> bids = List.of(bid(BID_A, annA), bid(BID_B, annB), bid(BID_EMPTY, annA));

            AnnouncementEntity a = announcement(annA, TRAVELER);
            AnnouncementEntity b = announcement(annB, TRAVELER);
            when(announcementRepository.findAllById(anyCollection())).thenReturn(List.of(a, b));
            BidRecipientLinkEntity link = new BidRecipientLinkEntity(BID_B, recipientUser);
            when(recipientLinkRepository.findByBidIdIn(anyCollection())).thenReturn(List.of(link));
            when(userRepository.findAllById(anyCollection())).thenReturn(List.<UserEntity>of());
            when(ratingRepository.findByBidIdIn(anyCollection())).thenReturn(List.of(
                    rating(BID_A, SENDER), rating(BID_B, TRAVELER), rating(BID_B, null)));
            CancellationEntity handover = cancellation(BID_A, CancellationScope.HANDOVER);
            CancellationEntity delivery = cancellation(BID_A, CancellationScope.DELIVERY);
            when(cancellationRepository.findAllByBidIdIn(anyCollection())).thenReturn(List.of(handover, delivery));
            BidGridItemEntity item = gridItem(BID_B);
            when(bidGridItemRepository.findByBidIdIn(anyCollection())).thenReturn(List.of(item));
            BidPhotoResponse photo = new BidPhotoResponse(UUID.randomUUID(), "https://r2/b.jpg");
            when(bidPhotoService.activePhotosByBid(anyCollection())).thenReturn(Map.of(BID_B, List.of(photo)));

            BidResponsePrefetch prefetch = bidService.prefetchFor(bids);

            assertThat(prefetch.recipientLink(BID_B)).isSameAs(link);
            assertThat(prefetch.recipientLink(BID_A)).isNull();
            assertThat(prefetch.hasRated(BID_A, SENDER)).isTrue();
            assertThat(prefetch.hasRated(BID_B, TRAVELER)).isTrue();
            assertThat(prefetch.hasRated(BID_B, SENDER)).isFalse();
            assertThat(prefetch.cancellations(BID_A)).containsExactlyInAnyOrder(handover, delivery);
            assertThat(prefetch.cancellations(BID_B)).isEmpty();
            assertThat(prefetch.gridItems(BID_B)).containsExactly(item);
            assertThat(prefetch.photos(BID_B)).containsExactly(photo);
            for (UUID none : List.of(BID_EMPTY)) {
                assertThat(prefetch.recipientLink(none)).isNull();
                assertThat(prefetch.cancellations(none)).isEmpty();
                assertThat(prefetch.gridItems(none)).isEmpty();
                assertThat(prefetch.photos(none)).isEmpty();
                assertThat(prefetch.hasRated(none, SENDER)).isFalse();
            }

            // Trajets distincts une fois ; voyageur + destinataire inscrit chargés ensemble.
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> announcementIds = ArgumentCaptor.forClass(Collection.class);
            verify(announcementRepository).findAllById(announcementIds.capture());
            assertThat(announcementIds.getValue()).containsExactlyInAnyOrder(annA, annB);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> userIds = ArgumentCaptor.forClass(Collection.class);
            verify(userRepository).findAllById(userIds.capture());
            assertThat(userIds.getValue()).containsExactlyInAnyOrder(TRAVELER, recipientUser);
        }

        @Test
        @DisplayName("liste vide : aucune requête")
        void emptyListQueriesNothing() {
            when(bidPhotoService.activePhotosByBid(anyCollection())).thenReturn(Map.of());

            BidResponsePrefetch prefetch = bidService.prefetchFor(List.of());

            assertThat(prefetch.photos(BID_A)).isEmpty();
            verify(announcementRepository, never()).findAllById(any());
            verify(recipientLinkRepository, never()).findByBidIdIn(any());
            verify(ratingRepository, never()).findByBidIdIn(any());
            verify(cancellationRepository, never()).findAllByBidIdIn(any());
        }
    }

    @Nested
    @DisplayName("BidPhotoService.activePhotosByBid")
    class ActivePhotosByBid {

        @Test
        @DisplayName("photos rangées par colis et triées par position, colis sans photo absent")
        void groupsByBidAndKeepsPositionOrder() {
            BidPhotoRepository repository = mock(BidPhotoRepository.class);
            StorageService storage = mock(StorageService.class);
            when(storage.generatePresignedUrl(any(), any(Duration.class)))
                    .thenAnswer(inv -> "https://r2/" + inv.getArgument(0));
            // Ordre rendu par la base : par position, colis mélangés.
            when(repository.findByBidIdInAndStatusOrderByPositionAsc(anyCollection(), eq(BidPhotoStatus.ACTIVE)))
                    .thenReturn(List.of(
                            new BidPhotoEntity(BID_B, "b2.jpg", 2),
                            new BidPhotoEntity(BID_A, "a1.jpg", 1),
                            new BidPhotoEntity(BID_A, "a0.jpg", 0),
                            new BidPhotoEntity(BID_B, "b0.jpg", 0)));

            Map<UUID, List<BidPhotoResponse>> byBid = new BidPhotoService(repository, storage)
                    .activePhotosByBid(List.of(BID_A, BID_B, BID_EMPTY));

            assertThat(byBid.get(BID_A)).extracting(BidPhotoResponse::url)
                    .containsExactly("https://r2/a0.jpg", "https://r2/a1.jpg");
            assertThat(byBid.get(BID_B)).extracting(BidPhotoResponse::url)
                    .containsExactly("https://r2/b0.jpg", "https://r2/b2.jpg");
            assertThat(byBid).doesNotContainKey(BID_EMPTY);
        }
    }

    // ── Fabriques ────────────────────────────────────────────────────────────

    private static BidEntity bid(UUID id, UUID announcementId) {
        BidEntity b = new BidEntity();
        setId(b, id);
        b.setAnnouncementId(announcementId);
        b.setSenderId(SENDER);
        return b;
    }

    private static AnnouncementEntity announcement(UUID id, UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        setId(a, id);
        a.setTravelerId(travelerId);
        return a;
    }

    private static RatingEntity rating(UUID bidId, UUID raterId) {
        RatingEntity r = new RatingEntity();
        r.setBidId(bidId);
        r.setRaterId(raterId);
        return r;
    }

    private static CancellationEntity cancellation(UUID bidId, CancellationScope scope) {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(bidId);
        c.setScope(scope);
        return c;
    }

    private static BidGridItemEntity gridItem(UUID bidId) {
        BidGridItemEntity item = new BidGridItemEntity();
        item.setBidId(bidId);
        item.setQuantity(1);
        item.setUnitPriceNetSnapshot(BigDecimal.ONE);
        return item;
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
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
