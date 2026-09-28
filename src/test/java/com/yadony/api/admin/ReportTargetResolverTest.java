package com.yadony.api.admin;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.messaging.FirestoreService;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportTargetResolverTest {

    @Mock UserRepository userRepo;
    @Mock AnnouncementRepository announcementRepo;
    @Mock PackageRequestRepository packageRequestRepo;
    @Mock BidRepository bidRepo;
    @Mock RatingRepository ratingRepo;
    @Mock ConversationRepository conversationRepo;
    @Mock FirestoreService firestoreService;

    private ReportTargetResolver resolver() {
        return new ReportTargetResolver(userRepo, announcementRepo, packageRequestRepo, bidRepo, ratingRepo,
                conversationRepo, firestoreService);
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private static UserEntity user(UUID id) {
        UserEntity u = withId(new UserEntity(), id);
        u.setFirstName("Moussa");
        u.setLastName("Traoré");
        return u;
    }

    private static ReportEntity report(ReportTargetType type, UUID targetId, UUID reporterId) {
        ReportEntity r = withId(new ReportEntity(), UUID.randomUUID());
        r.setTargetType(type);
        r.setTargetId(targetId);
        r.setReporterId(reporterId);
        r.setReason(ReportReason.OTHER);
        r.setStatus(ReportStatus.OPEN);
        return r;
    }

    // ---- BID : la partie adverse du signalant ----

    private BidEntity bid(UUID bidId, UUID senderId, UUID announcementId) {
        BidEntity b = withId(new BidEntity(), bidId);
        b.setSenderId(senderId);
        b.setAnnouncementId(announcementId);
        return b;
    }

    private AnnouncementEntity announcement(UUID id, UUID travelerId) {
        AnnouncementEntity a = withId(new AnnouncementEntity(), id);
        a.setTravelerId(travelerId);
        return a;
    }

    @Test
    void offre_signaleeParLExpediteur_auteurEstLeVoyageur() {
        UUID sender = UUID.randomUUID(), traveler = UUID.randomUUID(), bidId = UUID.randomUUID(), annId = UUID.randomUUID();
        when(bidRepo.findAllById(anyCollection())).thenReturn(List.of(bid(bidId, sender, annId)));
        when(announcementRepo.findAllById(anyCollection())).thenReturn(List.of(announcement(annId, traveler)));
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(user(traveler)));

        ResolvedReportTarget t = resolver().resolve(report(ReportTargetType.BID, bidId, sender));

        assertThat(t.targetFound()).isTrue();
        assertThat(t.author().getId()).isEqualTo(traveler);
    }

    @Test
    void offre_signaleeParLeVoyageur_auteurEstLExpediteur() {
        UUID sender = UUID.randomUUID(), traveler = UUID.randomUUID(), bidId = UUID.randomUUID(), annId = UUID.randomUUID();
        when(bidRepo.findAllById(anyCollection())).thenReturn(List.of(bid(bidId, sender, annId)));
        when(announcementRepo.findAllById(anyCollection())).thenReturn(List.of(announcement(annId, traveler)));
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(user(sender)));

        ResolvedReportTarget t = resolver().resolve(report(ReportTargetType.BID, bidId, traveler));

        assertThat(t.author().getId()).isEqualTo(sender);
    }

    @Test
    void offre_signaleeParUnTiers_auteurInconnu() {
        UUID bidId = UUID.randomUUID(), annId = UUID.randomUUID();
        when(bidRepo.findAllById(anyCollection())).thenReturn(List.of(bid(bidId, UUID.randomUUID(), annId)));
        when(announcementRepo.findAllById(anyCollection()))
                .thenReturn(List.of(announcement(annId, UUID.randomUUID())));

        ResolvedReportTarget t = resolver().resolve(report(ReportTargetType.BID, bidId, UUID.randomUUID()));

        assertThat(t.targetFound()).isTrue();
        assertThat(t.author()).isNull();
    }

    @Test
    void offre_introuvable() {
        ResolvedReportTarget t = resolver().resolve(report(ReportTargetType.BID, UUID.randomUUID(), UUID.randomUUID()));
        assertThat(t.targetFound()).isFalse();
        assertThat(t.author()).isNull();
    }

    @Test
    void offre_auteurSupprime_auteurNul() {
        UUID sender = UUID.randomUUID(), traveler = UUID.randomUUID(), bidId = UUID.randomUUID(), annId = UUID.randomUUID();
        when(bidRepo.findAllById(anyCollection())).thenReturn(List.of(bid(bidId, sender, annId)));
        when(announcementRepo.findAllById(anyCollection())).thenReturn(List.of(announcement(annId, traveler)));
        // @Where deleted_at IS NULL : un compte supprimé n'est pas relu.
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of());

        assertThat(resolver().resolve(report(ReportTargetType.BID, bidId, sender)).author()).isNull();
    }

    // ---- RATING ----

    @Test
    void avis_auteurEstLeNotantEtExclusionReportee() {
        UUID rater = UUID.randomUUID(), ratingId = UUID.randomUUID();
        RatingEntity rating = withId(new RatingEntity(), ratingId);
        rating.setRaterId(rater);
        rating.setExcludedFromAverage(true);
        when(ratingRepo.findAllById(anyCollection())).thenReturn(List.of(rating));
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(user(rater)));

        ResolvedReportTarget t = resolver().resolve(report(ReportTargetType.RATING, ratingId, UUID.randomUUID()));

        assertThat(t.targetFound()).isTrue();
        assertThat(t.alreadyModerated()).isTrue();
        assertThat(t.author().getId()).isEqualTo(rater);
    }

    @Test
    void avisAnonymeDuDestinataire_pasDAuteur() {
        UUID ratingId = UUID.randomUUID();
        RatingEntity rating = withId(new RatingEntity(), ratingId);
        rating.setTrackingToken("tok");
        when(ratingRepo.findAllById(anyCollection())).thenReturn(List.of(rating));

        ResolvedReportTarget t = resolver().resolve(report(ReportTargetType.RATING, ratingId, UUID.randomUUID()));

        assertThat(t.targetFound()).isTrue();
        assertThat(t.alreadyModerated()).isFalse();
        assertThat(t.author()).isNull();
    }

    // ---- MESSAGE ----

    private ConversationEntity conversation(UUID id, String fsId) {
        return withId(new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), fsId), id);
    }

    @Test
    void message_retrouve_auteurEtEtat() {
        UUID convId = UUID.randomUUID(), author = UUID.randomUUID();
        ReportEntity r = report(ReportTargetType.MESSAGE, convId, UUID.randomUUID());
        r.setTargetMessageId("msg_1");
        when(conversationRepo.findAllById(anyCollection())).thenReturn(List.of(conversation(convId, "conv_fs")));
        when(firestoreService.findMessage("conv_fs", "msg_1"))
                .thenReturn(Optional.of(new FirestoreService.MessageSnapshot(author.toString(), false)));
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(user(author)));

        ResolvedReportTarget t = resolver().resolve(r);

        assertThat(t.messageResolved()).isTrue();
        assertThat(t.conversation().getFirestoreConversationId()).isEqualTo("conv_fs");
        assertThat(t.messageId()).isEqualTo("msg_1");
        assertThat(t.alreadyModerated()).isFalse();
        assertThat(t.author().getId()).isEqualTo(author);
    }

    @Test
    void message_systeme_pasDAuteur() {
        UUID convId = UUID.randomUUID();
        ReportEntity r = report(ReportTargetType.MESSAGE, convId, UUID.randomUUID());
        r.setTargetMessageId("msg_1");
        when(conversationRepo.findAllById(anyCollection())).thenReturn(List.of(conversation(convId, "conv_fs")));
        when(firestoreService.findMessage("conv_fs", "msg_1"))
                .thenReturn(Optional.of(new FirestoreService.MessageSnapshot("SYSTEM", true)));

        ResolvedReportTarget t = resolver().resolve(r);

        assertThat(t.messageResolved()).isTrue();
        assertThat(t.alreadyModerated()).isTrue();
        assertThat(t.author()).isNull();
    }

    @Test
    void message_sansIdentifiant_introuvableSansAppelFirestore() {
        ResolvedReportTarget t = resolver().resolve(report(ReportTargetType.MESSAGE, UUID.randomUUID(), UUID.randomUUID()));

        assertThat(t.messageResolved()).isFalse();
        verifyNoInteractions(firestoreService);
    }

    @Test
    void message_conversationInconnue_introuvable() {
        ReportEntity r = report(ReportTargetType.MESSAGE, UUID.randomUUID(), UUID.randomUUID());
        r.setTargetMessageId("msg_1");

        assertThat(resolver().resolve(r).messageResolved()).isFalse();
        verify(firestoreService, never()).findMessage(anyString(), anyString());
    }

    @Test
    void message_absentDeFirestore_introuvable() {
        UUID convId = UUID.randomUUID();
        ReportEntity r = report(ReportTargetType.MESSAGE, convId, UUID.randomUUID());
        r.setTargetMessageId("msg_1");
        when(conversationRepo.findAllById(anyCollection())).thenReturn(List.of(conversation(convId, "conv_fs")));
        when(firestoreService.findMessage("conv_fs", "msg_1")).thenReturn(Optional.empty());

        assertThat(resolver().resolve(r)).isEqualTo(ResolvedReportTarget.none());
    }

    // ---- USER, ANNOUNCEMENT, PACKAGE_REQUEST, APP ----

    @Test
    void utilisateur_auteurEstLaCible() {
        UUID target = UUID.randomUUID();
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(user(target)));

        ResolvedReportTarget t = resolver().resolve(report(ReportTargetType.USER, target, UUID.randomUUID()));

        assertThat(t.targetFound()).isTrue();
        assertThat(t.author().getId()).isEqualTo(target);
    }

    @Test
    void annonce_auteurEstLeVoyageur() {
        UUID annId = UUID.randomUUID(), traveler = UUID.randomUUID();
        when(announcementRepo.findAllById(anyCollection())).thenReturn(List.of(announcement(annId, traveler)));
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(user(traveler)));

        ResolvedReportTarget t = resolver().resolve(report(ReportTargetType.ANNOUNCEMENT, annId, UUID.randomUUID()));

        assertThat(t.targetFound()).isTrue();
        assertThat(t.author().getId()).isEqualTo(traveler);
    }

    @Test
    void demandeDEnvoi_auteurEstLExpediteur() {
        UUID reqId = UUID.randomUUID(), sender = UUID.randomUUID();
        PackageRequestEntity pr = withId(new PackageRequestEntity(), reqId);
        pr.setSenderId(sender);
        when(packageRequestRepo.findAllById(anyCollection())).thenReturn(List.of(pr));
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(user(sender)));

        ResolvedReportTarget t = resolver().resolve(report(ReportTargetType.PACKAGE_REQUEST, reqId, UUID.randomUUID()));

        assertThat(t.targetFound()).isTrue();
        assertThat(t.author().getId()).isEqualTo(sender);
    }

    @Test
    void app_aucuneCibleNiRequete() {
        ResolvedReportTarget t = resolver().resolve(report(ReportTargetType.APP, null, UUID.randomUUID()));

        assertThat(t).isEqualTo(ResolvedReportTarget.none());
        verifyNoInteractions(userRepo, announcementRepo, packageRequestRepo, bidRepo, ratingRepo,
                conversationRepo, firestoreService);
    }

    @Test
    void lot_uneRequeteParTable() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var map = resolver().resolve(List.of(
                report(ReportTargetType.RATING, a, null),
                report(ReportTargetType.RATING, b, null)));

        assertThat(map).hasSize(2);
        verify(ratingRepo).findAllById(org.mockito.ArgumentMatchers.argThat(
                (java.util.Collection<UUID> ids) -> ids.containsAll(List.of(a, b))));
        verify(userRepo, never()).findAllById(any());
    }
}
