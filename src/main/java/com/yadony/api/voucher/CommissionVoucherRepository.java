package com.yadony.api.voucher;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface CommissionVoucherRepository extends JpaRepository<CommissionVoucherEntity, UUID> {

    Optional<CommissionVoucherEntity> findBySourceInvitationId(UUID sourceInvitationId);

    // Scopé par userId : un même bid peut porter DEUX consommations distinctes (le bon
    // de l'expéditeur ET celui du voyageur), (bidId) seul les confondrait.
    Optional<CommissionVoucherEntity> findByConsumedOnBidIdAndUserId(UUID bidId, UUID userId);

    /** Le plus ancien bon encore disponible pour ce détenteur (FIFO — règle "un bon = une transaction"). */
    @Query("SELECT v FROM CommissionVoucherEntity v "
            + "WHERE v.userId = :userId AND v.consumedAt IS NULL AND v.expiresAt > :now "
            + "ORDER BY v.grantedAt ASC")
    java.util.List<CommissionVoucherEntity> findActiveByUserId(
            @Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /**
     * Consommation atomique : un seul UPDATE conditionnel, la base tranche.
     *
     * <p>Remplace le couple {@code findActive} + {@code SELECT ... FOR UPDATE} : le
     * verrou pessimiste etait bien pose, mais Hibernate rendait l'instance DEJA gérée
     * par le contexte de persistance sans rafraichir son etat depuis la base. Le test
     * {@code consumedAt != null} portait donc sur une version anterieure au commit d'un
     * thread concurrent, et le meme bon pouvait etre consomme deux fois (double tap,
     * reessai client). Ici la garde {@code consumed_at IS NULL} est evaluee par la base
     * au moment de l'ecriture : le perdant de la course voit simplement 0 ligne affectee.
     *
     * <p>{@code expires_at > :now} est repris dans la clause : la validite doit etre
     * verifiee par la meme instruction que la prise, sinon elle porte sur une lecture
     * anterieure.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CommissionVoucherEntity v "
            + "SET v.consumedAt = :now, v.consumedOnBidId = :reference "
            + "WHERE v.id = :id AND v.consumedAt IS NULL AND v.expiresAt > :now")
    int consumeIfAvailable(@Param("id") UUID id,
                           @Param("now") LocalDateTime now,
                           @Param("reference") UUID reference);
}
