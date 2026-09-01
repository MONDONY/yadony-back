package com.yadony.api.subscriptions;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class TravelerSubscriptionRepositoryTest {

    @Autowired
    TravelerSubscriptionRepository repo;

    @Autowired
    UserRepository userRepository;

    @Test
    void findActiveBySenderIdAndTravelerId_returnsSavedSubscription() {
        UUID sender = UUID.randomUUID();
        UUID traveler = UUID.randomUUID();
        TravelerSubscriptionEntity sub = new TravelerSubscriptionEntity();
        sub.setSenderId(sender);
        sub.setTravelerId(traveler);
        repo.save(sub);

        assertThat(repo.findBySenderIdAndTravelerId(sender, traveler)).isPresent();
        assertThat(repo.existsBySenderIdAndTravelerId(sender, traveler)).isTrue();
    }

    @Test
    void findEnrichedBySenderId_fallsBackToPlaceholderName_whenTravelerHasNoFirstOrLastName() {
        UserEntity traveler = new UserEntity();
        traveler.setFirebaseUid("uid-" + UUID.randomUUID());
        // first_name / last_name left null on purpose — reproduces the "drissa"-style
        // seed account that has no name fields populated.
        traveler = userRepository.save(traveler);

        UUID sender = UUID.randomUUID();
        TravelerSubscriptionEntity sub = new TravelerSubscriptionEntity();
        sub.setSenderId(sender);
        sub.setTravelerId(traveler.getId());
        repo.save(sub);

        List<Object[]> rows = repo.findEnrichedBySenderId(sender);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).isEqualTo("Voyageur");
        // avatar_url pas renseigné sur ce voyageur → colonne null (index 2)
        assertThat(rows.get(0)[2]).isNull();
    }

    @Test
    void findEnrichedBySenderId_includesTravelerAvatarUrl() {
        UserEntity traveler = new UserEntity();
        traveler.setFirebaseUid("uid-" + UUID.randomUUID());
        traveler.setFirstName("Ibrahima");
        traveler.setAvatarUrl("avatars/ibrahima.jpg");
        traveler = userRepository.save(traveler);

        UUID sender = UUID.randomUUID();
        TravelerSubscriptionEntity sub = new TravelerSubscriptionEntity();
        sub.setSenderId(sender);
        sub.setTravelerId(traveler.getId());
        repo.save(sub);

        List<Object[]> rows = repo.findEnrichedBySenderId(sender);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[2]).isEqualTo("avatars/ibrahima.jpg");
    }

    @Test
    void markAllSeenBySenderId_resetsOnlyThatSendersFlags() {
        UUID sender = UUID.randomUUID();
        UUID autreSender = UUID.randomUUID();

        TravelerSubscriptionEntity avecNouveau = new TravelerSubscriptionEntity();
        avecNouveau.setSenderId(sender);
        avecNouveau.setTravelerId(UUID.randomUUID());
        avecNouveau.setHasNew(true);
        repo.save(avecNouveau);

        TravelerSubscriptionEntity dejaVu = new TravelerSubscriptionEntity();
        dejaVu.setSenderId(sender);
        dejaVu.setTravelerId(UUID.randomUUID());
        dejaVu.setHasNew(false);
        repo.save(dejaVu);

        TravelerSubscriptionEntity voisin = new TravelerSubscriptionEntity();
        voisin.setSenderId(autreSender);
        voisin.setTravelerId(UUID.randomUUID());
        voisin.setHasNew(true);
        repo.save(voisin);

        // Seule la ligne réellement marquée est réécrite : le compteur le prouve.
        assertThat(repo.markAllSeenBySenderId(sender)).isEqualTo(1);

        assertThat(repo.findAllBySenderId(sender))
            .allSatisfy(s -> assertThat(s.isHasNew()).isFalse());
        // L'abonnement d'un autre expéditeur garde sa pastille.
        assertThat(repo.findAllBySenderId(autreSender))
            .singleElement()
            .satisfies(s -> assertThat(s.isHasNew()).isTrue());
    }

    @Test
    void markAllSeenBySenderId_ignoresUnsubscribedRows() {
        UUID sender = UUID.randomUUID();
        TravelerSubscriptionEntity resilie = new TravelerSubscriptionEntity();
        resilie.setSenderId(sender);
        resilie.setTravelerId(UUID.randomUUID());
        resilie.setHasNew(true);
        resilie.setDeletedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        repo.save(resilie);

        // Le @Where de l'entité ne filtre pas les mises à jour en masse : c'est
        // la clause deletedAt de la requête qui protège la ligne résiliée.
        assertThat(repo.markAllSeenBySenderId(sender)).isZero();
    }
}
