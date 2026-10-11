package com.yadony.api.matching;

import com.yadony.api.matching.events.TripCapacityFreedEvent;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Écouteur JPA de {@link AnnouncementEntity} : détecte le passage {@code FULL → ACTIVE}
 * et publie {@link TripCapacityFreedEvent}.
 *
 * <p>Pourquoi ici et pas dans chaque service : la place se libère par une dizaine de
 * chemins répartis dans plusieurs packages ({@code releaseCapacity} des annulations
 * avant paiement, de l'annulation admin et du mobile money, refus d'un colis, report de
 * trajet, décision de report…). Les doter chacun d'une publication en aurait oublié un,
 * et le suivant aussi. Le statut lu en base est mémorisé au chargement ; au flush, on
 * compare au statut écrit. Un aller-retour {@code FULL → ACTIVE → FULL} dans la même
 * transaction ne publie donc rien, ce qui est voulu : aucune place n'est réellement
 * revenue.
 *
 * <p>Bean Spring : Hibernate l'instancie via le {@code SpringBeanContainer} que Spring
 * Boot configure, d'où l'injection par constructeur.
 */
@Component
public class AnnouncementStatusTransitionListener {

    private final ApplicationEventPublisher eventPublisher;

    public AnnouncementStatusTransitionListener(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @PostLoad
    @PostPersist
    void rememberPersistedStatus(AnnouncementEntity announcement) {
        announcement.markStatusPersisted();
    }

    @PostUpdate
    void onUpdate(AnnouncementEntity announcement) {
        if (announcement.getPersistedStatus() == AnnouncementStatus.FULL
                && announcement.getStatus() == AnnouncementStatus.ACTIVE) {
            eventPublisher.publishEvent(
                    new TripCapacityFreedEvent(announcement.getId(), announcement.getTravelerId()));
        }
        announcement.markStatusPersisted();
    }
}
