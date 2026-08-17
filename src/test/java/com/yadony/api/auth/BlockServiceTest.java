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
        when(bidRepository.hasActiveTransactionBetween(eq(me), eq(other), anyList())).thenReturn(false);
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

    @Test
    void block_refuseSiTransactionActive() {
        when(blockRepo.existsByBlockerIdAndBlockedId(me, other)).thenReturn(false);
        when(bidRepository.hasActiveTransactionBetween(eq(me), eq(other), anyList())).thenReturn(true);
        assertThatThrownBy(() -> service.block(me, other))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus().value()).isEqualTo(409));
        verify(blockRepo, never()).save(any());
    }

    /** Régression I2 : ARRIVED doit figurer dans ACTIVE_STATUSES. Le colis est arrivé
     *  mais pas encore retiré — c'est exactement le moment où les deux parties
     *  coordonnent le retrait, donc le pire moment pour autoriser un blocage. */
    @Test
    void block_ACTIVE_STATUSES_inclutArrived() {
        when(blockRepo.existsByBlockerIdAndBlockedId(me, other)).thenReturn(false);
        when(bidRepository.hasActiveTransactionBetween(eq(me), eq(other), anyList())).thenReturn(false);

        service.block(me, other);

        verify(bidRepository).hasActiveTransactionBetween(eq(me), eq(other),
                argThat(statuses -> statuses.contains(com.yadony.api.matching.BidStatus.ARRIVED)));
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
