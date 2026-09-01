package com.yadony.api.auth;

import com.yadony.api.auth.dto.BlockedUserDto;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.BidRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlockServiceTest {

    @Mock UserBlockJpaRepository blockRepo;
    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;

    BlockService service;

    UUID me = UUID.randomUUID();
    UUID other = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BlockService(blockRepo, userRepository, bidRepository);
    }

    @Test
    void block_creeLaRelation() {
        when(blockRepo.existsByBlockerIdAndBlockedId(me, other)).thenReturn(false);
        service.block(me, other);
        verify(blockRepo).save(argThat(b -> b.getBlockerId().equals(me) && b.getBlockedId().equals(other)));
    }

    @Test
    void block_refuseAutoBlocage() {
        assertThatThrownBy(() -> service.block(me, me)).isInstanceOf(YadonyBusinessException.class);
        verify(blockRepo, never()).save(any());
    }

    @Test
    void block_idempotent_siDejaBloque() {
        when(blockRepo.existsByBlockerIdAndBlockedId(me, other)).thenReturn(true);
        service.block(me, other);
        verify(blockRepo, never()).save(any());
    }

    /** Une transaction en cours n'empêche plus de bloquer : le harcèlement peut survenir
     *  précisément pendant l'acheminement. La coordination est préservée autrement, en
     *  gardant la contrepartie visible (voir isHidden_*). */
    @Test
    void block_autoriseMemeAvecTransactionActive() {
        when(blockRepo.existsByBlockerIdAndBlockedId(me, other)).thenReturn(false);

        service.block(me, other);

        verify(blockRepo).save(argThat(b -> b.getBlockerId().equals(me) && b.getBlockedId().equals(other)));
    }

    @Test
    void isHidden_faux_siAucunBlocage() {
        when(blockRepo.existsBetween(me, other)).thenReturn(false);
        assertThat(service.isHidden(me, other)).isFalse();
        verifyNoInteractions(bidRepository);
    }

    @Test
    void isHidden_vrai_siBloqueSansTransaction() {
        when(blockRepo.existsBetween(me, other)).thenReturn(true);
        when(bidRepository.hasActiveTransactionBetween(eq(me), eq(other), anyList())).thenReturn(false);
        assertThat(service.isHidden(me, other)).isTrue();
    }

    /** Régression I2, reformulée : ARRIVED reste dans ACTIVE_STATUSES. Le colis est arrivé
     *  mais pas encore retiré — c'est le moment où les deux parties coordonnent le retrait,
     *  donc le pire moment pour leur couper la visibilité mutuelle. */
    @Test
    void isHidden_faux_siTransactionActive_etArrivedEnFaitPartie() {
        when(blockRepo.existsBetween(me, other)).thenReturn(true);
        when(bidRepository.hasActiveTransactionBetween(eq(me), eq(other), anyList())).thenReturn(true);

        assertThat(service.isHidden(me, other)).isFalse();

        verify(bidRepository).hasActiveTransactionBetween(eq(me), eq(other),
                argThat(statuses -> statuses.contains(com.yadony.api.matching.BidStatus.ARRIVED)));
    }

    @Test
    void isHidden_faux_pourUnViewerAnonymeOuSoiMeme() {
        assertThat(service.isHidden(null, other)).isFalse();
        assertThat(service.isHidden(me, me)).isFalse();
        verifyNoInteractions(blockRepo, bidRepository);
    }

    /** 404 et non 403 : un 403 confirmerait l'existence de la ressource et rendrait le
     *  blocage détectable. */
    @Test
    void assertVisible_leve404_siMasque() {
        when(blockRepo.existsBetween(me, other)).thenReturn(true);
        when(bidRepository.hasActiveTransactionBetween(eq(me), eq(other), anyList())).thenReturn(false);

        assertThatThrownBy(() -> service.assertVisible(me, other))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void assertVisible_passe_siVisible() {
        when(blockRepo.existsBetween(me, other)).thenReturn(false);
        assertThatCode(() -> service.assertVisible(me, other)).doesNotThrowAnyException();
    }

    @Test
    void hiddenUserIdsFor_excluteLesContrepartiesEnTransaction() {
        UUID trading = UUID.randomUUID();
        when(blockRepo.findBlockedRelationIds(me)).thenReturn(List.of(other, trading));
        when(bidRepository.findActiveTransactionCounterparties(eq(me), anyList(), anyList()))
                .thenReturn(List.of(trading));

        assertThat(service.hiddenUserIdsFor(me)).containsExactly(other);
    }

    @Test
    void hiddenUserIdsFor_vide_siAucuneRelation() {
        when(blockRepo.findBlockedRelationIds(me)).thenReturn(List.of());
        assertThat(service.hiddenUserIdsFor(me)).isEmpty();
        verifyNoInteractions(bidRepository);
    }

    @Test
    void hiddenUserIdsFor_vide_pourUnViewerAnonyme() {
        assertThat(service.hiddenUserIdsFor(null)).isEmpty();
        verifyNoInteractions(blockRepo, bidRepository);
    }

    @Test
    void unblock_supprimeLaRelation() {
        when(blockRepo.deleteByBlockerIdAndBlockedId(me, other)).thenReturn(1);
        service.unblock(me, other);
        verify(blockRepo).deleteByBlockerIdAndBlockedId(me, other);
    }

    @Test
    void listBlocked_retourneLesBloques() {
        UserBlockEntity b = new UserBlockEntity();
        b.setBlockerId(me);
        b.setBlockedId(other);
        b.setCreatedAt(OffsetDateTime.now());
        UserEntity u = new UserEntity();
        setId(u, other);
        u.setFirstName("Mamadou");
        u.setLastName("Diallo");
        when(blockRepo.findByBlockerIdOrderByCreatedAtDesc(me)).thenReturn(List.of(b));
        when(userRepository.findAllById(List.of(other))).thenReturn(List.of(u));
        List<BlockedUserDto> result = service.listBlocked(me);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(other);
        assertThat(result.get(0).displayName()).isEqualTo("Mamadou D.");
    }

    @Test
    void isBlockedEitherWay_bidirectionnel() {
        when(blockRepo.existsBetween(me, other)).thenReturn(true);
        assertThat(service.isBlockedEitherWay(me, other)).isTrue();
    }

    /** {@link com.yadony.api.common.BaseEntity} has no public id setter; set it reflectively for the test. */
    private static void setId(UserEntity u, UUID id) {
        try {
            var field = Class.forName("com.yadony.api.common.BaseEntity").getDeclaredField("id");
            field.setAccessible(true);
            field.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
