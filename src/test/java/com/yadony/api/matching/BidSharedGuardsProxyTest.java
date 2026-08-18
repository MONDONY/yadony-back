package com.yadony.api.matching;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verrou sur les gardes partagées entre l'offre ferme et la négociation.
 *
 * <p>{@code BidService#assertCanBidOn} et {@code resolvePaymentMethodFor} sont
 * package-private et appelées depuis {@link BidNegotiationService} à travers le bean
 * Spring, donc à travers un proxy CGLIB. Si le proxy n'interceptait pas une méthode
 * de cette visibilité, le corps s'exécuterait sur l'instance proxy dont les champs
 * sont nuls : toute proposition de prix planterait en {@code NullPointerException}
 * au lieu d'appliquer les gardes. Ce test exerce le vrai bean du contexte pour que
 * cette régression soit impossible à introduire en silence.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Gardes partagées de BidService — appelées via le bean Spring")
class BidSharedGuardsProxyTest {

    @Autowired private BidService bidService;
    @Autowired private BidNegotiationService negotiationService;

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

    @Test
    @DisplayName("le service de négociation est bien câblé sur le service d'offres")
    void negotiationServiceIsWired() {
        assertThat(negotiationService).isNotNull();
    }

    @Test
    @DisplayName("assertCanBidOn traverse le proxy et applique les gardes avec ses vraies dépendances")
    void assertCanBidOn_isIntercepted_andUsesRealCollaborators() {
        UUID userId = UUID.randomUUID();
        UserEntity sender = new UserEntity();
        sender.setFirebaseUid("uid-proxy-guard");
        sender.setUsername("user-proxy-guard");
        sender.getRoles().add(Role.SENDER); // évite tout enregistrement du rôle en base
        setId(sender, userId);

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setCurrency("EUR");
        // Le trajet appartient au demandeur : la garde attendue est atteinte après
        // la résolution de devise, qui exige de vraies dépendances injectées.
        announcement.setTravelerId(userId);
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Dakar");
        announcement.setDepartureDate(LocalDate.now().plusDays(10));
        announcement.setAvailableKg(BigDecimal.valueOf(20));
        announcement.setPricePerKg(BigDecimal.valueOf(5));
        setId(announcement, UUID.randomUUID());

        assertThatThrownBy(() -> bidService.assertCanBidOn(sender, announcement, "CLOTHING"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("cannot-bid-own-announcement");
                });
    }
}
